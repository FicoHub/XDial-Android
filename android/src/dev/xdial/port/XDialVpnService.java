package dev.xdial.port;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import libbox.*;
import org.json.*;

public final class XDialVpnService extends VpnService {
  static final int IDLE = 0, CONNECTING = 1, CONNECTED = 2, RECOVERING = 3, DISCONNECTING = 4, FAILED = 5;
  static volatile int phase = IDLE;
  static volatile String state = "未连接", detail = "";
  static volatile boolean active = false;
  /** Raw core/platform error, shown only on the diagnostics page. */
  static volatile String safeError = "";
  static volatile long connectedAt = 0;
  static volatile String exitName = "";
  private static volatile XDialVpnService current;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private volatile libbox.Libbox_ core;
  private ParcelFileDescriptor tun;
  private ConnectivityManager connectivity;
  private ConnectivityManager.NetworkCallback networkCallback;
  private volatile Network underlay;
  private boolean foreground;
  private volatile CountDownLatch cancelCompletion;

  @Override
  public void onCreate() {
    super.onCreate();
    current = this;
    go.Seq.setContext(getApplicationContext());
    connectivity = getSystemService(ConnectivityManager.class);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    String action = intent == null ? "STOP" : intent.getAction();
    if ("STOP".equals(action)) {
      set(DISCONNECTING, "正在断开", "");
      cancelled.set(true);
      cancelCore();
      worker.execute(() -> finish(IDLE, "未连接", ""));
      return START_NOT_STICKY;
    }
    if (active) return START_NOT_STICKY;
    active = true;
    cancelled.set(false);
    safeError = "";
    connectedAt = 0;
    set(CONNECTING, "正在连接", "正在准备线路");
    showNotification();
    worker.execute(this::connect);
    return START_NOT_STICKY;
  }

  private void connect() {
    try {
      if (VpnService.prepare(this) != null) throw new Exception("需要重新允许 VPN 连接");
      String profile = ProfileStore.read(this);
      exitName = Profiles.exitName(profile);
      Libbox.generateConnectionPlan(profile);
      underlay = connectivity.getActiveNetwork();
      if (underlay == null) throw new Exception("没有可用网络");
      NetworkCapabilities cap = connectivity.getNetworkCapabilities(underlay);
      if (cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        throw new Exception("请先断开其他 Android VPN；系统每个用户仅允许一个 VPN");
      libbox.Libbox_ engine =
          Libbox.new_(
              new Callback() {
                public void onStatusChanged(String json) {
                  if (cancelled.get()) return;
                  try {
                    String s = new JSONObject(json).optString("status");
                    if (s.equals("reconnecting")) set(RECOVERING, "正在恢复", "线路中断，正在自动重连");
                    if (s.equals("connected") && phase == RECOVERING) set(CONNECTED, "已连接", "");
                    if (s.equals("failed")) {
                      set(FAILED, "连接中断", "线路已失效，请断开后重新连接");
                      showNotification();
                    }
                  } catch (Exception ignored) {
                  }
                }

                public void onError(long code, String message) {
                  if (message != null && !message.isEmpty()) safeError = message;
                }
              });
      core = engine;
      configureUnderlay(engine, underlay, connectivity);
      engine.setAndroidTunHost(() -> openTunnel());
      engine.setAndroidSocketProtector(
          (fd) -> {
            if (!protect((int) fd)) throw new Exception("保护核心 socket 失败");
            Network n = underlay;
            if (n == null) throw new Exception("基础网络已断开");
            try (ParcelFileDescriptor copy = ParcelFileDescriptor.fromFd((int) fd)) {
              n.bindSocket(copy.getFileDescriptor());
            }
          });
      if (cancelled.get()) throw new Exception("已取消");
      engine.androidStartProfile(profile, getFilesDir().getAbsolutePath());
      if (cancelled.get()) throw new Exception("已取消");
      connectedAt = System.currentTimeMillis();
      set(CONNECTED, "已连接", "");
      showNotification();
      networkCallback =
          new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
              worker.execute(() -> networkChanged(network));
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties props) {
              worker.execute(() -> networkChanged(network));
            }

            @Override
            public void onLost(Network network) {
              if (network.equals(underlay) && active)
                set(RECOVERING, "等待网络", "手机网络断开，恢复后自动继续");
            }
          };
      // Let Android choose the best underlying network. The ordinary default
      // callback can report our own VPN and conceal Wi-Fi/cellular changes.
      NetworkRequest request =
          new NetworkRequest.Builder()
              .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
              .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
              .build();
      if (Build.VERSION.SDK_INT >= 31) {
        connectivity.registerBestMatchingNetworkCallback(
            request, networkCallback, new Handler(Looper.getMainLooper()));
      } else {
        connectivity.requestNetwork(request, networkCallback);
      }
    } catch (Exception e) {
      if (!cancelled.get())
        safeError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      if (cancelled.get()) finish(IDLE, "未连接", "");
      else finish(FAILED, "连接失败", Errors.friendly(safeError));
    }
  }

  private static void set(int p, String s, String d) {
    phase = p;
    state = s;
    detail = d;
  }

  private void networkChanged(Network n) {
    if (cancelled.get() || core == null) return;
    NetworkCapabilities cap = connectivity.getNetworkCapabilities(n);
    if (cap == null || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return;
    try {
      underlay = n;
      configureUnderlay(core, n, connectivity);
      setUnderlyingNetworks(new Network[] {n});
      core.refreshDNS();
      set(CONNECTED, "已连接", "");
    } catch (Exception e) {
      set(FAILED, "网络切换失败", "请断开后重新连接");
    }
  }

  static void configureUnderlay(libbox.Libbox_ c, Network n, ConnectivityManager connectivity)
      throws Exception {
    LinkProperties props = connectivity.getLinkProperties(n);
    if (props == null || props.getInterfaceName() == null) throw new Exception("无法获取系统网络接口");
    NetworkInterface current = NetworkInterface.getByName(props.getInterfaceName());
    if (current == null) throw new Exception("系统网络接口不可用");
    JSONArray interfaces = new JSONArray();
    Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
    while (all.hasMoreElements()) {
      NetworkInterface ni = all.nextElement();
      if (ni.getIndex() > 0 && ni.isUp() && !ni.isLoopback()) {
        JSONArray addresses = new JSONArray();
        for (java.net.InterfaceAddress address : ni.getInterfaceAddresses()) {
          String host = address.getAddress().getHostAddress().split("%")[0];
          addresses.put(host + "/" + address.getNetworkPrefixLength());
        }
        interfaces.put(
            new JSONObject()
                .put("name", ni.getName())
                .put("index", ni.getIndex())
                .put("type", "other")
                .put("mtu", ni.getMTU())
                .put(
                    "flags",
                    1 | 32 | (ni.supportsMulticast() ? 16 : 0) | (ni.isPointToPoint() ? 8 : 2))
                .put("addresses", addresses));
      }
    }
    c.setNetworkInterfaces(interfaces.toString());
    c.setDefaultInterface(current.getName(), current.getIndex());
  }

  private synchronized long openTunnel() throws Exception {
    if (cancelled.get()) throw new Exception("已取消");
    Builder b =
        new Builder()
            .setSession("XDial Android · 非官方移植")
            .setMtu(1500)
            .addAddress("198.18.0.1", 15)
            .addAddress("fd00::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("198.18.0.2")
            .addDisallowedApplication(getPackageName())
            .setBlocking(false);
    b.setUnderlyingNetworks(new Network[] {underlay});
    tun = b.establish();
    if (tun == null) throw new Exception("VPN 授权已撤销");
    return tun.getFd();
  }

  private void showNotification() {
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("vpn", "XDial VPN", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, XDialVpnService.class).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification n =
        new Notification.Builder(this, "vpn")
            .setContentTitle("XDial · " + state)
            .setContentText(detail.isEmpty() ? (exitName.isEmpty() ? "点按打开 XDial" : "出口：" + exitName) : detail)
            .setSmallIcon(dev.xdial.port.R.drawable.xdial_icon)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "断开", stop).build())
            .build();
    startForeground(901, n);
    foreground = true;
  }

  private synchronized void cancelCore() {
    libbox.Libbox_ c = core;
    if (c == null || cancelCompletion != null) return;
    CountDownLatch completion = new CountDownLatch(1);
    cancelCompletion = completion;
    new Thread(
            () -> {
              try {
                c.stop();
              } catch (Exception ignored) {
              } finally {
                completion.countDown();
              }
            },
            "xdial-cancel")
        .start();
  }

  private void cleanup() {
    CountDownLatch completion = cancelCompletion;
    if (completion != null) {
      try {
        completion.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    if (networkCallback != null) {
      try {
        connectivity.unregisterNetworkCallback(networkCallback);
      } catch (Exception ignored) {
      }
      networkCallback = null;
    }
    libbox.Libbox_ c = core;
    core = null;
    if (c != null)
      try {
        c.stop();
      } catch (Exception ignored) {
      }
    synchronized (this) {
      if (tun != null)
        try {
          tun.close();
        } catch (Exception ignored) {
        }
      tun = null;
    }
    if (foreground) {
      stopForeground(STOP_FOREGROUND_REMOVE);
      foreground = false;
    }
  }

  private void finish(int next, String title, String message) {
    cleanup();
    if (current == this) {
      active = false;
      connectedAt = 0;
      set(next, title, message);
    }
    stopSelf();
  }

  @Override
  public void onRevoke() {
    cancelled.set(true);
    cancelCore();
    worker.execute(() -> finish(IDLE, "未连接", "VPN 已被系统或其他应用关闭"));
  }

  @Override
  public void onDestroy() {
    cancelled.set(true);
    worker.execute(
        () -> {
          cleanup();
          if (current == this && active) {
            active = false;
            connectedAt = 0;
            set(IDLE, "未连接", "");
          }
        });
    worker.shutdown();
    super.onDestroy();
  }
}

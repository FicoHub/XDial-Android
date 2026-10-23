package dev.xdial.port;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import libbox.*;
import org.json.*;

/**
 * Keeps the user's connection up until they turn it off: survives screen-off and network changes,
 * retries transient failures with back-off, supports Android's always-on VPN and sticky restarts,
 * and switches to a Wi-Fi-bound scenario (profile {@code match_ssids}) while on that network.
 */
public final class XDialVpnService extends VpnService {
  static final int IDLE = 0, CONNECTING = 1, CONNECTED = 2, RECOVERING = 3, DISCONNECTING = 4, FAILED = 5;
  private static final long[] BACKOFF_SECONDS = {5, 10, 30, 60, 120, 300};
  static volatile int phase = IDLE;
  static volatile String state = "未连接", detail = "";
  static volatile boolean active = false;
  /** Raw core/platform error, shown only on the diagnostics page. */
  static volatile String safeError = "";
  static volatile long connectedAt = 0;
  /** Headline of the running scenario: {title, protocol, split}. */
  static volatile String exitName = "";
  static volatile String[] headline = {"", "", ""};
  /** Scenario actually running and whether Wi-Fi picked it, plus the Wi-Fi that did. */
  static volatile String runningScenario = "";
  static volatile int runningRules = 0;
  static volatile String autoSsid = "";
  static volatile boolean alwaysOn = false, lockdown = false;
  /** Wi-Fi name as Android reports it to us; "" when not on Wi-Fi, null-safe for the UI. */
  static volatile String wifi = "";
  private static volatile XDialVpnService current;

  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private volatile libbox.Libbox_ core;
  private ParcelFileDescriptor tun;
  private ConnectivityManager connectivity;
  private ConnectivityManager.NetworkCallback networkCallback;
  private volatile Network underlay;
  private volatile String ssid;
  /** Id of the scenario the running connection uses (Wi-Fi may have picked it). */
  static volatile String runningScenarioId = "";
  private boolean foreground;
  private volatile CountDownLatch cancelCompletion;
  private int attempt;
  private boolean waitingRetry;
  private final Runnable retry =
      () ->
          worker.execute(
              () -> {
                if (!cancelled.get() && active && waitingRetry) connect();
              });

  static boolean wantOn(Context c) {
    return c.getSharedPreferences("xdial", MODE_PRIVATE).getBoolean("want_on", false);
  }

  static void setWantOn(Context c, boolean on) {
    c.getSharedPreferences("xdial", MODE_PRIVATE).edit().putBoolean("want_on", on).apply();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    current = this;
    go.Seq.setContext(getApplicationContext());
    connectivity = getSystemService(ConnectivityManager.class);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    String action = intent == null ? null : intent.getAction();
    if ("STOP".equals(action)) {
      setWantOn(this, false);
      KeepAliveJob.cancel(this);
      set(DISCONNECTING, "正在断开", "");
      cancelled.set(true);
      main.removeCallbacks(retry);
      cancelCore();
      worker.execute(() -> finish(IDLE, "未连接", ""));
      return START_NOT_STICKY;
    }
    // START from the app, "android.net.VpnService" from always-on, or a sticky restart.
    if ("REFRESH".equals(action)) {
      if (active) worker.execute(this::refreshWifi);
      else if (!wantOn(this)) stopSelf();
      return START_STICKY;
    }
    if (intent == null && !wantOn(this)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    setWantOn(this, true);
    KeepAliveJob.schedule(this);
    if (active) return START_STICKY;
    active = true;
    cancelled.set(false);
    safeError = "";
    connectedAt = 0;
    attempt = 0;
    set(CONNECTING, "正在连接", "正在准备线路");
    showNotification();
    readAlwaysOn();
    watchNetwork();
    worker.execute(this::connect);
    return START_STICKY;
  }

  // ------------------------------------------------------------------ connection

  private void connect() {
    waitingRetry = false;
    try {
      if (VpnService.prepare(this) != null) throw new Fatal("需要重新允许 VPN 连接");
      JSONObject stored = new JSONObject(ProfileStore.read(this));
      if (ssid == null) ssid = wifiName(null);
      wifi = ssid == null ? "" : ssid;
      String scenarioId = Profiles.effectiveScenarioId(stored, ssid);
      android.util.Log.i("XDial", "connect wifi=" + ssid + " scenario=" + scenarioId);
      boolean auto = !scenarioId.equals(stored.optString("active_scenario_id"));
      stored.put("active_scenario_id", scenarioId);
      String profile = stored.toString();
      try {
        Libbox.generateConnectionPlan(profile);
      } catch (Exception e) {
        throw new Fatal(e.getMessage());
      }
      headline = Profiles.headline(stored);
      exitName = headline[0];
      JSONObject sc = Profiles.activeScenario(stored);
      runningScenario = sc == null ? "" : sc.optString("name");
      runningRules = Profiles.bindingCount(sc);
      autoSsid = auto && ssid != null ? ssid : "";
      runningScenarioId = scenarioId;

      underlay = bestUnderlay();
      if (underlay == null) throw new Exception("没有可用网络");
      libbox.Libbox_ engine =
          Libbox.new_(
              new Callback() {
                public void onStatusChanged(String json) {
                  if (cancelled.get()) return;
                  try {
                    String s = new JSONObject(json).optString("status");
                    if (s.equals("reconnecting")) set(RECOVERING, "正在恢复", "线路中断，正在自动重连");
                    if (s.equals("connected") && phase == RECOVERING) set(CONNECTED, "已连接", "");
                    if (s.equals("failed")) worker.execute(() -> lineFailed());
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
      if (cancelled.get()) throw new Fatal("已取消");
      engine.androidStartProfile(profile, getFilesDir().getAbsolutePath());
      if (cancelled.get()) throw new Fatal("已取消");
      attempt = 0;
      safeError = "";
      readAlwaysOn();
      connectedAt = System.currentTimeMillis();
      set(CONNECTED, "已连接", "");
      showNotification();
    } catch (Exception e) {
      if (cancelled.get()) {
        finish(IDLE, "未连接", "");
        return;
      }
      safeError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      stopCore();
      if (!(e instanceof Fatal) && Errors.retryable(safeError)) scheduleRetry();
      else finish(FAILED, "连接失败", Errors.friendly(safeError));
    }
  }

  /** Errors that retrying cannot fix (credentials, certificates, configuration). */
  private static final class Fatal extends Exception {
    Fatal(String m) {
      super(m);
    }
  }

  /** The running core reported that its line is gone: rebuild it rather than sit broken. */
  private void lineFailed() {
    if (cancelled.get() || !active || waitingRetry) return;
    if (safeError.isEmpty()) safeError = "线路中断";
    stopCore();
    scheduleRetry();
  }

  private void scheduleRetry() {
    long delay = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
    attempt++;
    waitingRetry = true;
    connectedAt = 0;
    set(RECOVERING, "正在重连", Errors.friendly(safeError) + delay + " 秒后自动重试");
    showNotification();
    main.removeCallbacks(retry);
    main.postDelayed(retry, delay * 1000);
  }

  private void retryNow() {
    if (!waitingRetry || cancelled.get()) return;
    main.removeCallbacks(retry);
    connect();
  }

  // ------------------------------------------------------------------ network & Wi-Fi

  private Network bestUnderlay() {
    Network n = underlay;
    NetworkCapabilities cap = n == null ? null : connectivity.getNetworkCapabilities(n);
    if (cap != null && !cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return n;
    n = connectivity.getActiveNetwork();
    cap = n == null ? null : connectivity.getNetworkCapabilities(n);
    if (cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null;
    return n;
  }

  /** Let Android choose the best non-VPN network and tell us about changes, including Wi-Fi name. */
  private void watchNetwork() {
    if (networkCallback != null) return;
    networkCallback =
        Build.VERSION.SDK_INT >= 31
            ? new ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
              @Override
              public void onAvailable(Network network) {
                worker.execute(() -> networkChanged(network, ssid, true));
              }

              @Override
              public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                String name = wifiName(caps);
                worker.execute(() -> networkChanged(network, name, false));
              }

              @Override
              public void onLinkPropertiesChanged(Network network, LinkProperties props) {
                worker.execute(() -> networkChanged(network, ssid, true));
              }

              @Override
              public void onLost(Network network) {
                lost(network);
              }
            }
            : new ConnectivityManager.NetworkCallback() {
              @Override
              public void onAvailable(Network network) {
                worker.execute(() -> networkChanged(network, wifiName(null), true));
              }

              @Override
              public void onLinkPropertiesChanged(Network network, LinkProperties props) {
                worker.execute(() -> networkChanged(network, wifiName(null), true));
              }

              @Override
              public void onLost(Network network) {
                lost(network);
              }
            };
    NetworkRequest request =
        new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build();
    if (Build.VERSION.SDK_INT >= 31) connectivity.registerBestMatchingNetworkCallback(request, networkCallback, main);
    else connectivity.requestNetwork(request, networkCallback);
  }

  private void lost(Network network) {
    if (network.equals(underlay) && active && phase == CONNECTED)
      set(RECOVERING, "等待网络", "手机网络断开，恢复后自动继续");
  }

  /** Wi-Fi name if Android lets us read it (needs location permission), else null. */
  private String wifiName(NetworkCapabilities caps) {
    String raw = null;
    if (caps != null) {
      if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null;
      if (Build.VERSION.SDK_INT >= 31 && caps.getTransportInfo() instanceof WifiInfo)
        raw = ((WifiInfo) caps.getTransportInfo()).getSSID();
    }
    if (raw == null) {
      try {
        Network n = connectivity.getActiveNetwork();
        NetworkCapabilities c = n == null ? null : connectivity.getNetworkCapabilities(n);
        if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) c = underlay == null ? null : connectivity.getNetworkCapabilities(underlay);
        if (c == null || !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null;
        @SuppressWarnings("deprecation")
        WifiInfo info = getApplicationContext().getSystemService(WifiManager.class).getConnectionInfo();
        raw = info == null ? null : info.getSSID();
      } catch (Exception e) {
        return null;
      }
    }
    String clean = Profiles.cleanSsid(raw);
    android.util.Log.i("XDial", "wifi name raw=" + raw + " clean=" + clean);
    return clean;
  }

  /**
   * Reacts to a different network, changed addresses or a different Wi-Fi. Capability updates
   * that change none of these (signal strength, bandwidth) are ignored so they never reset
   * connections or trigger retries.
   */
  private void networkChanged(Network n, String name, boolean linkChanged) {
    if (cancelled.get() || !active) return;
    NetworkCapabilities cap = connectivity.getNetworkCapabilities(n);
    if (cap == null || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return;
    boolean wifiChanged = !Objects.equals(name, ssid);
    boolean networkChanged = !n.equals(underlay);
    android.util.Log.i("XDial", "network event wifi=" + name + " changed=" + wifiChanged + "/" + networkChanged + "/" + linkChanged);
    if (!wifiChanged && !networkChanged && !linkChanged) return;
    ssid = name;
    wifi = name == null ? "" : name;
    underlay = n;
    if (waitingRetry) {
      retryNow();
      return;
    }
    if (core == null) return;
    if (wifiChanged && scenarioWouldChange()) {
      set(RECOVERING, "正在切换场景", name == null ? "离开了自动切换的 Wi-Fi" : "已连接 Wi-Fi「" + name + "」");
      stopCore();
      connect();
      return;
    }
    try {
      configureUnderlay(core, n, connectivity);
      setUnderlyingNetworks(new Network[] {n});
      core.refreshDNS();
      if (phase == RECOVERING) set(CONNECTED, "已连接", "");
    } catch (Exception e) {
      safeError = e.getMessage() == null ? "网络切换失败" : e.getMessage();
      stopCore();
      scheduleRetry();
    }
  }

  /** Android's always-on setting can change while we run; remember it for the 保持常连 page. */
  private void readAlwaysOn() {
    if (Build.VERSION.SDK_INT < 29) return;
    alwaysOn = isAlwaysOn();
    lockdown = isLockdownEnabled();
    getSharedPreferences("xdial", MODE_PRIVATE)
        .edit()
        .putBoolean("always_on", alwaysOn)
        .putBoolean("lockdown", lockdown)
        .apply();
  }

  /**
   * Location permission may have just been granted, or the Wi-Fi list was edited: read the Wi-Fi
   * name again and switch scenario when the rules now pick a different one.
   */
  private void refreshWifi() {
    readAlwaysOn();
    Network n = underlay;
    String name = wifiName(null);
    if (n != null && !Objects.equals(name, ssid)) {
      networkChanged(n, name, false);
    } else if (core != null && !cancelled.get() && scenarioWouldChange()) {
      set(RECOVERING, "正在切换场景", "Wi-Fi 规则已更新");
      stopCore();
      connect();
    }
  }

  private boolean scenarioWouldChange() {
    try {
      JSONObject p = new JSONObject(ProfileStore.read(this));
      return !Profiles.effectiveScenarioId(p, ssid).equals(runningScenarioId);
    } catch (Exception e) {
      return false;
    }
  }

  // ------------------------------------------------------------------ platform plumbing

  private static void set(int p, String s, String d) {
    phase = p;
    state = s;
    detail = d;
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
            .setSession("XDial")
            .setMtu(1500)
            .addAddress("198.18.0.1", 15)
            .addAddress("fd00::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("198.18.0.2")
            .addDisallowedApplication(getPackageName())
            .setBlocking(false);
    b.setUnderlyingNetworks(new Network[] {underlay});
    if (tun != null)
      try {
        tun.close();
      } catch (Exception ignored) {
      }
    tun = b.establish();
    if (tun == null) throw new Fatal("VPN 授权已撤销");
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
    String text = detail.isEmpty() ? (exitName.isEmpty() ? "点按打开 XDial" : exitName) : detail;
    if (detail.isEmpty() && !autoSsid.isEmpty()) text += " · Wi-Fi「" + autoSsid + "」";
    Notification n =
        new Notification.Builder(this, "vpn")
            .setContentTitle("XDial · " + state)
            .setContentText(text)
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

  /** Stops the engine and closes the ingress; the service and its network watch stay. */
  private void stopCore() {
    CountDownLatch completion = cancelCompletion;
    if (completion != null) {
      try {
        completion.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
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
  }

  private void cleanup() {
    main.removeCallbacks(retry);
    waitingRetry = false;
    if (networkCallback != null) {
      try {
        connectivity.unregisterNetworkCallback(networkCallback);
      } catch (Exception ignored) {
      }
      networkCallback = null;
    }
    stopCore();
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
      autoSsid = "";
      runningScenarioId = "";
      set(next, title, message);
    }
    stopSelf();
  }

  @Override
  public void onRevoke() {
    setWantOn(this, false);
    KeepAliveJob.cancel(this);
    cancelled.set(true);
    main.removeCallbacks(retry);
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

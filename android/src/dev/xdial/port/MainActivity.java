package dev.xdial.port;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import libbox.Libbox;
import org.json.*;

public final class MainActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private LinearLayout page;
  private TextView status, subtitle, summary;
  private Button connect;
  private Spinner scenarios;
  private String profile = ProfileStore.DEFAULT;
  private boolean binding = false;
  private boolean busy = false;
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          status.setText(XDialVpnService.state);
          subtitle.setText(XDialVpnService.detail);
          connect.setText(XDialVpnService.active ? "断开连接" : "连接当前场景");
          handler.postDelayed(this, 600);
        }
      };

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    go.Seq.setContext(getApplicationContext());
    try {
      profile = ProfileStore.read(this);
    } catch (Exception e) {
      error("无法解密配置，请重新导入。不会覆盖原文件。");
    }
    build();
  }

  @Override
  protected void onResume() {
    super.onResume();
    handler.post(refresh);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density);
  }

  TextView label(String value, int size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setPadding(0, dp(8), 0, dp(8));
    return t;
  }

  Button button(String title, Runnable action) {
    Button b = new Button(this);
    b.setText(title);
    b.setAllCaps(false);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  void build() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(Color.rgb(244, 246, 243));
    page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(24), dp(34), dp(24), dp(28));
    scroll.addView(page);
    setContentView(scroll);
    TextView brand = label("XDial", 38, 0xff152f35);
    brand.setTypeface(null, Typeface.BOLD);
    page.addView(brand);
    page.addView(label("ANDROID  /  源码移植测试版", 12, 0xff547077));
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(20), dp(20), dp(20), dp(20));
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(Color.WHITE);
    bg.setCornerRadius(dp(18));
    card.setBackground(bg);
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
    cp.setMargins(0, dp(22), 0, dp(20));
    page.addView(card, cp);
    status = label(XDialVpnService.state, 26, 0xff167d8d);
    status.setTypeface(null, Typeface.BOLD);
    card.addView(status);
    subtitle = label(XDialVpnService.detail, 14, 0xff547077);
    card.addView(subtitle);
    connect =
        button(
            "连接当前场景",
            () -> {
              if (XDialVpnService.active) stopVPN();
              else startVPN();
            });
    card.addView(connect);
    page.addView(label("当前场景", 16, 0xff152f35));
    scenarios = new Spinner(this);
    page.addView(scenarios);
    summary = label("", 14, 0xff547077);
    page.addView(summary);
    updateProfile();
    scenarios.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(android.widget.AdapterView<?> p) {}

          public void onItemSelected(
              android.widget.AdapterView<?> p, View v, int position, long id) {
            if (binding) return;
            try {
              JSONObject obj = new JSONObject(profile);
              String selected =
                  obj.getJSONArray("scenarios").getJSONObject(position).getString("id");
              if (selected.equals(obj.optString("active_scenario_id"))) return;
              if (!editable()) {
                updateProfile();
                return;
              }
              obj.put("active_scenario_id", selected);
              saveProfile(obj.toString());
            } catch (Exception e) {
              error(e.getMessage());
            }
          }
        });
    page.addView(button("新建线路与场景", this::quickProfile));
    page.addView(
        button(
            "从文件导入配置",
            () -> {
              if (editable()) {
                Intent i =
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, 20);
              }
            }));
    page.addView(button("编辑 / 粘贴配置", this::editProfile));
    page.addView(
        button(
            "连接错误详情",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("本机诊断")
                    .setMessage(
                        XDialVpnService.safeError.isEmpty()
                            ? "尚无错误记录。服务器地址等信息可能出现在错误中，请勿公开含私密信息的截图。"
                            : XDialVpnService.safeError)
                    .setPositiveButton("关闭", null)
                    .show()));
    page.addView(button("使用说明与开源许可", this::about));
    page.addView(
        label("0.9.1-port.1 · 非上游官方发行\nGo 核心与 sing-box 负责转发；本应用不提供 VPN 账号或节点。", 12, 0xff6b7c80));
  }

  void updateProfile() {
    try {
      JSONObject p = new JSONObject(profile);
      JSONArray a = p.getJSONArray("scenarios");
      ArrayList<String> names = new ArrayList<>();
      int selected = 0;
      for (int i = 0; i < a.length(); i++) {
        JSONObject s = a.getJSONObject(i);
        names.add(s.optString("name", s.getString("id")));
        if (s.getString("id").equals(p.optString("active_scenario_id"))) selected = i;
      }
      binding = true;
      scenarios.setAdapter(
          new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
      scenarios.setSelection(selected);
      handler.post(() -> binding = false);
      summary.setText(
          p.getJSONArray("lines").length()
              + " 条线路 · "
              + p.getJSONArray("rule_sets").length()
              + " 组规则\n配置加密保存在本机，关闭系统备份。");
    } catch (Exception e) {
      error("配置格式无效");
    }
  }

  boolean editable() {
    if (XDialVpnService.active) {
      error("请先断开，再修改或切换配置。");
      return false;
    }
    if (busy) {
      error("正在处理配置，请稍候。");
      return false;
    }
    return true;
  }

  void startVPN() {
    if (busy) return;
    if (loginCore != null) {
      busy = true;
      libbox.Libbox_ c = loginCore;
      loginCore = null;
      worker.execute(
          () -> {
            try {
              c.stop();
            } catch (Exception ignored) {
            }
            runOnUiThread(
                () -> {
                  busy = false;
                  startVPN();
                });
          });
      return;
    }
    try {
      Libbox.generateConnectionPlan(profile);
      Intent request = android.net.VpnService.prepare(this);
      if (request != null) startActivityForResult(request, 10);
      else launchVPN();
    } catch (Exception e) {
      error(e.getMessage());
    }
  }

  void launchVPN() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
      requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 30);
    XDialVpnService.safeError = "";
    startForegroundService(new Intent(this, XDialVpnService.class).setAction("START"));
  }

  void stopVPN() {
    startService(new Intent(this, XDialVpnService.class).setAction("STOP"));
  }

  void saveProfile(String value) throws Exception {
    Libbox.generateConnectionPlan(value);
    JSONObject p = new JSONObject(value);
    if (p.optJSONArray("import_warnings") != null && p.getJSONArray("import_warnings").length() > 0)
      throw new Exception("配置含无法移植的规则；请检查源配置，不会静默丢弃规则。");
    JSONArray lines = p.optJSONArray("lines");
    if (lines != null)
      for (int i = 0; i < lines.length(); i++)
        if (!lines.getJSONObject(i).optString("tailscale_auth_key").isEmpty())
          throw new Exception("不保存 Tailscale Auth Key；请用下方 Tailscale 登录功能注册身份。");
    ProfileStore.save(this, value);
    profile = value;
    updateProfile();
  }

  void importText(String text) {
    busy = true;
    subtitle.setText("正在解析配置…");
    worker.execute(
        () -> {
          try {
            String value = Libbox.importProfile(text, "auto", "android-import", false);
            runOnUiThread(
                () -> {
                  busy = false;
                  try {
                    saveProfile(value);
                    Toast.makeText(this, "配置已导入", Toast.LENGTH_SHORT).show();
                  } catch (Exception e) {
                    error(e.getMessage());
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  busy = false;
                  error(e.getMessage());
                });
          }
        });
  }

  void editProfile() {
    if (!editable()) return;
    EditText editor = new EditText(this);
    editor.setText(profile);
    editor.setTextSize(12);
    editor.setTypeface(Typeface.MONOSPACE);
    editor.setGravity(Gravity.TOP);
    editor.setMinLines(12);
    editor.setMaxLines(18);
    editor.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    editor.setSelectAllOnFocus(false);
    new AlertDialog.Builder(this)
        .setTitle("配置 JSON / Clash / Surge")
        .setMessage("可粘贴完整配置。线路凭据只存于本机；应用进程规则暂不支持。")
        .setView(editor)
        .setPositiveButton("校验并保存", (d, w) -> importText(editor.getText().toString()))
        .setNegativeButton("取消", null)
        .show();
  }

  EditText field(LinearLayout box, String hint, boolean secret) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setSingleLine();
    if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    box.addView(e);
    return e;
  }

  void quickProfile() {
    if (!editable()) return;
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(20), 0, dp(20), 0);
    Spinner type = new Spinner(this);
    String[] types = {"直连", "AnyConnect", "Shadowsocks", "Trojan", "VMess", "AnyTLS", "Tailscale"};
    type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, types));
    form.addView(type);
    EditText host = field(form, "服务器（AnyConnect 可含端口）", false),
        port = field(form, "代理端口，默认 443", false),
        user = field(form, "用户名 / VMess UUID / SS 加密方法", false),
        pass = field(form, "密码（Tailscale 无需填写）", true),
        sni = field(form, "TLS SNI / Tailscale Exit Node IP（可空）", false);
    CheckBox magic = new CheckBox(this);
    magic.setText("Tailscale：启用 MagicDNS 与节点路由");
    form.addView(magic);
    ScrollView sv = new ScrollView(this);
    sv.addView(form);
    new AlertDialog.Builder(this)
        .setTitle("新建配置")
        .setMessage("保存会替换当前配置。更多线路、分流规则可从文件导入或编辑。TLS 验证默认开启。")
        .setView(sv)
        .setPositiveButton(
            "保存",
            (d, w) -> {
              try {
                String[] raw = {
                  "direct", "vpn", "shadowsocks", "trojan", "vmess", "anytls", "tailscale"
                };
                String t = raw[type.getSelectedItemPosition()];
                JSONObject p = new JSONObject(ProfileStore.DEFAULT),
                    line =
                        new JSONObject()
                            .put("id", "primary")
                            .put("name", types[type.getSelectedItemPosition()])
                            .put("type", t)
                            .put("enabled", true);
                String h = host.getText().toString().trim(),
                    u = user.getText().toString().trim(),
                    pw = pass.getText().toString(),
                    sn = sni.getText().toString().trim();
                int po =
                    port.getText().length() == 0
                        ? 443
                        : Integer.parseInt(port.getText().toString());
                switch (t) {
                  case "vpn":
                    line.put("vpn_server", h).put("vpn_username", u).put("vpn_password", pw);
                    break;
                  case "shadowsocks":
                    line.put("ss_server", h)
                        .put("ss_port", po)
                        .put("ss_method", u.isEmpty() ? "aes-128-gcm" : u)
                        .put("ss_password", pw);
                    break;
                  case "trojan":
                    line.put("trojan_server", h)
                        .put("trojan_port", po)
                        .put("trojan_password", pw)
                        .put("trojan_sni", sn);
                    break;
                  case "vmess":
                    line.put("vmess_server", h).put("vmess_port", po).put("vmess_uuid", u);
                    break;
                  case "anytls":
                    line.put("anytls_server", h)
                        .put("anytls_port", po)
                        .put("anytls_password", pw)
                        .put("anytls_sni", sn);
                    break;
                  case "tailscale":
                    line.put("tailscale_exit_node", sn)
                        .put("tailscale_magic_dns", magic.isChecked());
                    p.put(
                        "tailscale",
                        new JSONObject()
                            .put(
                                "hostname",
                                "xdial-android-"
                                    + android.provider.Settings.Secure.getString(
                                            getContentResolver(),
                                            android.provider.Settings.Secure.ANDROID_ID)
                                        .substring(0, 6)));
                    break;
                }
                if (!t.equals("direct")) {
                  p.getJSONArray("lines").put(line);
                  p.getJSONArray("scenarios").getJSONObject(0).put("default_line_id", "primary");
                }
                saveProfile(p.toString());
                if (t.equals("tailscale")) tailscaleLogin();
              } catch (Exception e) {
                error(e.getMessage());
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private libbox.Libbox_ loginCore;

  void tailscaleLogin() {
    if (!editable()) return;
    busy = true;
    worker.execute(
        () -> {
          try {
            JSONObject p = new JSONObject(profile);
            JSONArray lines = p.getJSONArray("lines");
            String lineID = null;
            for (int i = 0; i < lines.length(); i++)
              if (lines.getJSONObject(i).optString("type").equals("tailscale")) {
                lineID = lines.getJSONObject(i).getString("id");
                break;
              }
            if (lineID == null) throw new Exception("请先新建或导入 Tailscale 线路");
            String cfg =
                Libbox.generateTailscaleSetupConfig(
                    profile, lineID, getFilesDir().getAbsolutePath());
            JSONObject options = new JSONObject(cfg);
            String tag = options.getJSONArray("endpoints").getJSONObject(0).getString("tag");
            loginCore = Libbox.new_(null);
            android.net.ConnectivityManager cm =
                getSystemService(android.net.ConnectivityManager.class);
            Network network = cm.getActiveNetwork();
            if (network == null) throw new Exception("没有可用网络");
            XDialVpnService.configureUnderlay(loginCore, network, cm);
            loginCore.setAndroidSocketProtector(
                fd -> {
                  try (ParcelFileDescriptor copy = ParcelFileDescriptor.fromFd((int) fd)) {
                    network.bindSocket(copy.getFileDescriptor());
                  }
                });
            loginCore.startStandalone(cfg);
            String result = loginCore.beginTailscaleLogin(tag);
            JSONObject state = new JSONObject(result);
            String url = state.optString("auth_url");
            runOnUiThread(
                () -> {
                  busy = false;
                  new AlertDialog.Builder(this)
                      .setTitle("Tailscale 登录")
                      .setMessage(
                          url.isEmpty() ? "身份已注册或无需登录。关闭后即可连接。" : "浏览器完成授权后返回，点“完成”。登录会话最多保留 3 分钟。")
                      .setPositiveButton("完成", (d, w) -> stopLogin())
                      .setNeutralButton(
                          "打开登录页",
                          (d, w) -> {
                            if (url.startsWith("https://")) {
                              startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                              Toast.makeText(this, "授权后返回，稍候连接", Toast.LENGTH_LONG).show();
                            }
                          })
                      .setNegativeButton("取消", (d, w) -> stopLogin())
                      .show();
                });
            handler.postDelayed(this::stopLogin, 180000);
          } catch (Exception e) {
            stopLogin();
            runOnUiThread(
                () -> {
                  busy = false;
                  error(e.getMessage());
                });
          }
        });
  }

  void stopLogin() {
    libbox.Libbox_ c = loginCore;
    loginCore = null;
    if (c != null)
      worker.execute(
          () -> {
            try {
              c.stop();
            } catch (Exception ignored) {
            }
          });
  }

  void about() {
    new AlertDialog.Builder(this)
        .setTitle("XDial Android · 使用说明")
        .setMessage(
            "基于上游 v0.9.1（9672ecc）源码构建，非官方发行。\n\n"
                + "① 新建线路或导入配置\n"
                + "② 选择场景并连接\n"
                + "③ 首次连接允许系统 VPN 请求\n\n"
                + "Android 每个用户只允许一个 VPN，本应用自身流量绕过 VPN 防止循环。配置变化需先断开。暂不支持常驻 VPN、macOS"
                + " 应用进程规则、无中断场景切换。\n\n"
                + "沿用上游移动端 DNS：默认 1.1.1.1；AnyConnect 企业 DNS 和显式 MagicDNS 由原核心处理。这不同于 macOS DNS"
                + " 路径。\n\n"
                + "XDial 源码 MIT；包含 sing-box（GPLv3）及其他开源组件，详见随附许可文件。卸载会删除本机配置、密钥和身份。")
        .setPositiveButton("关闭", null)
        .setNeutralButton("Tailscale 登录", (d, w) -> tailscaleLogin())
        .setNegativeButton("开源许可", (d, w) -> licenses())
        .show();
  }

  void licenses() {
    try (InputStream in = getAssets().open("THIRD_PARTY_NOTICES.txt")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
      String s = out.toString("UTF-8");
      TextView v = label(s, 12, Color.DKGRAY);
      v.setTextIsSelectable(true);
      ScrollView sc = new ScrollView(this);
      sc.addView(v);
      new AlertDialog.Builder(this)
          .setTitle("开源许可")
          .setView(sc)
          .setPositiveButton("关闭", null)
          .show();
    } catch (Exception e) {
      error("请查看随附开源许可文件");
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 10) {
      if (result == RESULT_OK) launchVPN();
      else error("未允许 VPN 连接，未接管系统网络。");
    }
    if (request == 20 && result == RESULT_OK && data != null) {
      try (InputStream in = getContentResolver().openInputStream(data.getData());
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
          if (out.size() + n > 2 * 1024 * 1024) throw new Exception("配置文件不能超过 2 MB");
          out.write(b, 0, n);
        }
        importText(out.toString("UTF-8"));
      } catch (Exception e) {
        error(e.getMessage());
      }
    }
  }

  void error(String s) {
    new AlertDialog.Builder(this)
        .setTitle("XDial")
        .setMessage(s == null ? "操作失败，请检查配置" : s)
        .setPositiveButton("知道了", null)
        .show();
  }
}

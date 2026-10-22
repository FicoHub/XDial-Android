package dev.xdial.port;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import libbox.Libbox;
import org.json.*;

public final class MainActivity extends Activity {
  static final String VERSION = "0.9.1-port.2";
  private static final int REQ_VPN = 10, REQ_IMPORT = 20, REQ_EXPORT = 21;
  private static final String[] SS_METHODS = {
    "aes-128-gcm",
    "aes-256-gcm",
    "chacha20-ietf-poly1305",
    "2022-blake3-aes-128-gcm",
    "2022-blake3-aes-256-gcm",
    "2022-blake3-chacha20-poly1305"
  };
  private static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
  private static final int URI = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
  private static final int EMAIL =
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
  private static final int NUMBER = InputType.TYPE_CLASS_NUMBER;
  private static final int SECRET = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ArrayDeque<Runnable> back = new ArrayDeque<>();
  private Ui ui;
  private FrameLayout root;
  private Runnable current;
  private String profile = ProfileStore.DEFAULT;
  private boolean busy = false;
  private boolean reconnectPending = false;
  private String exportText;
  private libbox.Libbox_ loginCore;
  private String summaryOf, summaryScenario = "", summaryExit = "", statusSignature;

  // Live views of the home status card; null on every other page.
  private View statusDot;
  private TextView statusTitle, statusDetail, statusError, connectButton;

  private final Runnable tick =
      new Runnable() {
        public void run() {
          updateStatus();
          handler.postDelayed(this, 500);
        }
      };

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    go.Seq.setContext(getApplicationContext());
    ui = new Ui(this);
    root = ui.root();
    setContentView(root);
    ui.styleWindow();
    loadProfile();
    current = this::home;
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (!busy && back.isEmpty()) {
      String before = profile;
      loadProfile();
      if (!before.equals(profile)) render();
    }
    handler.post(tick);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(tick);
    super.onPause();
  }

  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    boolean wasDark = ui.dark;
    Ui next = new Ui(this);
    if (next.dark != wasDark) {
      ui = next;
      ui.styleWindow();
      root.setBackgroundColor(ui.bg);
      render();
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  public void onBackPressed() {
    if (!back.isEmpty()) goBack();
    else super.onBackPressed();
  }

  // ---------------------------------------------------------------- navigation

  void show(Runnable page) {
    hideKeyboard();
    back.push(current);
    current = page;
    render();
  }

  void goBack() {
    hideKeyboard();
    if (back.isEmpty()) return;
    current = back.pop();
    render();
  }

  void goHome() {
    hideKeyboard();
    back.clear();
    current = this::home;
    render();
  }

  void render() {
    statusTitle = null;
    statusSignature = null;
    root.removeAllViews();
    current.run();
    root.requestApplyInsets();
    updateStatus();
  }

  void hideKeyboard() {
    View f = getCurrentFocus();
    if (f != null)
      getSystemService(InputMethodManager.class).hideSoftInputFromWindow(f.getWindowToken(), 0);
  }

  // ---------------------------------------------------------------- profile

  void loadProfile() {
    try {
      profile = ProfileStore.read(this);
    } catch (Exception e) {
      profile = ProfileStore.DEFAULT;
      handler.post(() -> alert("无法读取已保存的配置", "可能是系统重置了加密密钥。请重新导入配置；原文件不会被覆盖，直到你保存新配置。"));
    }
  }

  JSONObject profileObj() {
    try {
      return new JSONObject(profile);
    } catch (JSONException e) {
      try {
        return new JSONObject(ProfileStore.DEFAULT);
      } catch (JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }

  void saveProfile(String value) throws Exception {
    JSONObject p = new JSONObject(value);
    JSONArray warnings = p.optJSONArray("import_warnings");
    if (warnings != null && warnings.length() > 0)
      throw new Exception("配置里有 Android 不支持的规则（如 macOS 应用规则），请在电脑端去掉后再导入。");
    JSONArray lines = p.optJSONArray("lines");
    if (lines != null)
      for (int i = 0; i < lines.length(); i++)
        if (!lines.getJSONObject(i).optString("tailscale_auth_key").isEmpty())
          throw new Exception("不保存 Tailscale Auth Key。请在 Tailscale 线路里点「登录 Tailscale」。");
    Libbox.generateConnectionPlan(value);
    ProfileStore.save(this, value);
    profile = value;
  }

  /** Saves, returns home and offers to reconnect when the change cannot apply live. */
  boolean commit(JSONObject p) {
    try {
      saveProfile(p.toString());
    } catch (Exception e) {
      alert("无法保存", Errors.friendly(e.getMessage()));
      return false;
    }
    goHome();
    if (XDialVpnService.active) {
      new AlertDialog.Builder(this)
          .setTitle("已保存")
          .setMessage("重新连接后生效。")
          .setPositiveButton("立即重新连接", (d, w) -> reconnect())
          .setNegativeButton("稍后", null)
          .show();
    } else {
      toast("已保存");
    }
    return true;
  }

  // ---------------------------------------------------------------- home

  void home() {
    LinearLayout page = ui.page(root);
    JSONObject p = profileObj();
    JSONObject scenario = Profiles.activeScenario(p);
    ui.topBar(page, "XDial", null, null, null);
    statusCard(page);

    JSONArray scenarios = p.optJSONArray("scenarios");
    LinearLayout s = ui.section(page, "当前场景");
    if (scenarios != null && scenarios.length() > 1)
      ui.row(s, null, "场景", null, scenario == null ? "" : scenario.optString("name"), this::pickScenario);
    ui.row(s, null, "默认出口", null, Profiles.exitName(p), this::pickExit);
    int bindings = Profiles.bindingCount(scenario);
    if (bindings > 0) ui.row(s, null, "分流规则", null, bindings + " 条", null);
    ui.footer(page, bindings > 0 ? "命中分流规则的流量按规则走，其余走默认出口。" : "所有流量都走默认出口。");

    LinearLayout l = ui.section(page, "线路");
    JSONArray lines = p.optJSONArray("lines");
    int shown = 0, total = 0;
    String exitId = scenario == null ? "" : scenario.optString("default_line_id");
    if (lines != null)
      for (int i = 0; i < lines.length(); i++) {
        JSONObject line = lines.optJSONObject(i);
        if (line == null || line.optString("type").equals("direct")) continue;
        total++;
        if (shown >= 8) continue;
        shown++;
        lineRow(l, line, exitId);
      }
    if (total > shown)
      ui.row(l, null, "全部 " + total + " 条线路", null, null, () -> show(this::allLines));
    ui.action(l, "＋ 添加线路", ui.accent, this::addLine);
    if (total == 0) ui.footer(page, "还没有线路。添加一条，或在下方导入已有配置。");

    LinearLayout c = ui.section(page, "配置");
    ui.row(c, null, "导入配置文件", null, null, this::importFile);
    ui.row(c, null, "粘贴配置文本", null, null, () -> show(this::pastePage));
    ui.row(c, null, "导出配置", "不含密码和密钥", null, this::exportFile);
    ui.footer(page, "支持 XDial、Clash、Surge 配置。导入会替换当前全部线路和场景。");

    LinearLayout m = ui.section(page, "更多");
    ui.row(m, null, "连接诊断", null, null, () -> show(this::diagnosticsPage));
    ui.row(m, null, "编辑完整配置", "高级：直接修改 JSON", null, () -> show(this::jsonPage));
    ui.row(m, null, "关于与开源许可", null, null, () -> show(this::aboutPage));
    ui.action(m, "重置全部配置", ui.red, this::resetProfile);
    ui.footer(page, "版本 " + VERSION + " · 非官方 Android 移植");
  }

  void lineRow(LinearLayout card, JSONObject line, String exitId) {
    String id = line.optString("id");
    String type = line.optString("type");
    ui.row(
        card,
        ui.badge(Profiles.badge(type), badgeColor(type)),
        line.optString("name", id),
        Profiles.summary(line),
        id.equals(exitId) ? "默认" : null,
        () -> show(() -> lineEditor(id, null)));
  }

  int badgeColor(String type) {
    switch (type) {
      case "vpn":
        return 0xff2f6fdf;
      case "tailscale":
        return 0xff3b3f46;
      case "shadowsocks":
        return 0xff7a5af5;
      case "trojan":
        return 0xffd9534f;
      case "vmess":
        return 0xff1f9d8a;
      case "anytls":
        return 0xffe08a00;
      default:
        return 0xff8a8f98;
    }
  }

  void allLines() {
    LinearLayout page = ui.page(root);
    ui.topBar(page, "全部线路", this::goBack, null, null);
    JSONObject p = profileObj();
    JSONObject scenario = Profiles.activeScenario(p);
    String exitId = scenario == null ? "" : scenario.optString("default_line_id");
    LinearLayout l = ui.section(page, null);
    JSONArray lines = p.optJSONArray("lines");
    if (lines != null)
      for (int i = 0; i < lines.length(); i++) {
        JSONObject line = lines.optJSONObject(i);
        if (line != null && !line.optString("type").equals("direct")) lineRow(l, line, exitId);
      }
    ui.action(l, "＋ 添加线路", ui.accent, this::addLine);
  }

  void statusCard(LinearLayout page) {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(ui.rounded(ui.card, 18));
    card.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(20));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = ui.dp(12);
    page.addView(card, lp);

    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    statusDot = new View(this);
    LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ui.dp(12), ui.dp(12));
    dp.rightMargin = ui.dp(10);
    head.addView(statusDot, dp);
    statusTitle = ui.bold(ui.text("", 24, ui.text));
    head.addView(statusTitle);
    card.addView(head);

    statusDetail = ui.text("", 15, ui.secondary);
    statusDetail.setPadding(0, ui.dp(6), 0, 0);
    card.addView(statusDetail);

    statusError = ui.text("", 14, ui.red);
    statusError.setPadding(0, ui.dp(10), 0, 0);
    statusError.setOnClickListener(v -> show(this::diagnosticsPage));
    card.addView(statusError);

    View gap = new View(this);
    card.addView(gap, new LinearLayout.LayoutParams(1, ui.dp(16)));
    connectButton = ui.button(card, "连接", ui.fill, this::onConnectTapped);
  }

  void updateStatus() {
    int ph = XDialVpnService.phase;
    boolean active = XDialVpnService.active;
    if (reconnectPending && !active && ph != XDialVpnService.DISCONNECTING) {
      reconnectPending = false;
      startVPN();
      return;
    }
    if (statusTitle == null) return;
    int dot, button;
    String title = XDialVpnService.state, action;
    boolean enabled = !busy;
    switch (ph) {
      case XDialVpnService.CONNECTING:
        dot = ui.orange;
        title = "正在连接…";
        action = "取消";
        button = ui.secondary;
        break;
      case XDialVpnService.CONNECTED:
        dot = ui.green;
        title = "已连接";
        action = "断开";
        button = ui.red;
        break;
      case XDialVpnService.RECOVERING:
        dot = ui.orange;
        action = "断开";
        button = ui.red;
        break;
      case XDialVpnService.DISCONNECTING:
        dot = ui.secondary;
        title = "正在断开…";
        action = "正在断开…";
        button = ui.secondary;
        enabled = false;
        break;
      case XDialVpnService.FAILED:
        dot = ui.red;
        action = active ? "断开" : "重新连接";
        button = active ? ui.red : ui.fill;
        break;
      default:
        dot = ui.secondary;
        title = "未连接";
        action = "连接";
        button = ui.fill;
    }
    if (reconnectPending) {
      title = "正在重新连接…";
      enabled = false;
    }
    String error = ph == XDialVpnService.FAILED ? XDialVpnService.detail : "";
    if (ph == XDialVpnService.IDLE && !XDialVpnService.detail.isEmpty()) error = XDialVpnService.detail;

    if (summaryOf != profile) {
      JSONObject p = profileObj();
      JSONObject scenario = Profiles.activeScenario(p);
      JSONArray scenarios = p.optJSONArray("scenarios");
      summaryScenario =
          scenario != null && scenarios != null && scenarios.length() > 1
              ? scenario.optString("name") + " · "
              : "";
      summaryExit = Profiles.exitName(p);
      summaryOf = profile;
    }
    String exit = active && !XDialVpnService.exitName.isEmpty() ? XDialVpnService.exitName : summaryExit;
    StringBuilder detail = new StringBuilder(summaryScenario);
    detail.append("出口：").append(exit);
    long since = XDialVpnService.connectedAt;
    if (ph == XDialVpnService.CONNECTED && since > 0)
      detail.append("\n连接于 ")
          .append(new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(since)));
    else if ((ph == XDialVpnService.RECOVERING || ph == XDialVpnService.CONNECTING)
        && !XDialVpnService.detail.isEmpty()) detail.append("\n").append(XDialVpnService.detail);
    // Touch views only on change: a steady UI saves battery and lets the system go idle.
    String signature = title + '|' + detail + '|' + error + '|' + action + '|' + button + '|' + enabled + '|' + dot + '|' + ph;
    if (signature.equals(statusSignature) && statusTitle.getText().length() > 0) return;
    statusSignature = signature;
    GradientDrawable d = new GradientDrawable();
    d.setShape(GradientDrawable.OVAL);
    d.setColor(dot);
    statusDot.setBackground(d);
    statusTitle.setText(title);
    statusDetail.setText(detail.toString());
    statusError.setText(error.isEmpty() ? "" : error + "  查看详情 ›");
    statusError.setTextColor(ph == XDialVpnService.FAILED ? ui.red : ui.secondary);
    statusError.setVisibility(error.isEmpty() ? View.GONE : View.VISIBLE);

    connectButton.setText(action);
    connectButton.setBackground(ui.ripple(button, 12));
    connectButton.setEnabled(enabled);
    connectButton.setAlpha(enabled ? 1f : 0.5f);
  }

  static String duration(long ms) {
    long s = Math.max(0, ms / 1000), h = s / 3600, m = s % 3600 / 60;
    return h > 0
        ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s % 60)
        : String.format(Locale.ROOT, "%d:%02d", m, s % 60);
  }

  void onConnectTapped() {
    if (busy) return;
    if (XDialVpnService.active) stopVPN();
    else startVPN();
  }

  void pickScenario() {
    JSONObject p = profileObj();
    JSONArray a = p.optJSONArray("scenarios");
    if (a == null) return;
    String[] names = new String[a.length()];
    int checked = 0;
    String active = p.optString("active_scenario_id");
    for (int i = 0; i < a.length(); i++) {
      names[i] = a.optJSONObject(i).optString("name");
      if (a.optJSONObject(i).optString("id").equals(active)) checked = i;
    }
    new AlertDialog.Builder(this)
        .setTitle("选择场景")
        .setSingleChoiceItems(
            names,
            checked,
            (d, which) -> {
              d.dismiss();
              try {
                p.put("active_scenario_id", a.getJSONObject(which).getString("id"));
                commit(p);
              } catch (JSONException e) {
                alert("无法切换场景", e.getMessage());
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  void pickExit() {
    JSONObject p = profileObj();
    JSONObject scenario = Profiles.activeScenario(p);
    if (scenario == null) return;
    JSONArray lines = p.optJSONArray("lines");
    ArrayList<String> ids = new ArrayList<>(), names = new ArrayList<>();
    ids.add("direct");
    names.add("直连（不经过代理）");
    if (lines != null)
      for (int i = 0; i < lines.length(); i++) {
        JSONObject l = lines.optJSONObject(i);
        if (l == null || l.optString("type").equals("direct")) continue;
        ids.add(l.optString("id"));
        names.add(l.optString("name") + "（" + Profiles.typeLabel(l.optString("type")) + "）");
      }
    int checked =
        scenario.optString("default_subscription_id").isEmpty()
            ? Math.max(0, ids.indexOf(scenario.optString("default_line_id", "direct")))
            : -1;
    new AlertDialog.Builder(this)
        .setTitle("默认出口")
        .setSingleChoiceItems(
            names.toArray(new String[0]),
            checked,
            (d, which) -> {
              d.dismiss();
              try {
                scenario.put("default_line_id", ids.get(which));
                scenario.remove("default_subscription_id");
                commit(p);
              } catch (JSONException e) {
                alert("无法修改出口", e.getMessage());
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  // ---------------------------------------------------------------- VPN

  void startVPN() {
    if (busy) return;
    if (loginCore != null) {
      stopLogin();
      handler.postDelayed(this::startVPN, 600);
      return;
    }
    try {
      Libbox.generateConnectionPlan(profile);
    } catch (Exception e) {
      alert("配置还不完整", Errors.friendly(e.getMessage()));
      return;
    }
    Intent request = android.net.VpnService.prepare(this);
    if (request != null) {
      try {
        startActivityForResult(request, REQ_VPN);
      } catch (ActivityNotFoundException e) {
        alert("无法开启 VPN", "系统不允许本应用创建 VPN。请检查是否有其他「始终开启」的 VPN。");
      }
    } else launchVPN();
  }

  void launchVPN() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
      requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 30);
    XDialVpnService.safeError = "";
    XDialVpnService.detail = "";
    startForegroundService(new Intent(this, XDialVpnService.class).setAction("START"));
    updateStatus();
  }

  void stopVPN() {
    startService(new Intent(this, XDialVpnService.class).setAction("STOP"));
    updateStatus();
  }

  void reconnect() {
    if (!XDialVpnService.active) {
      startVPN();
      return;
    }
    reconnectPending = true;
    stopVPN();
  }

  // ---------------------------------------------------------------- lines

  void addLine() {
    String[] labels = {
      "AnyConnect　公司 VPN（Cisco / ocserv）",
      "Shadowsocks",
      "Trojan",
      "VMess",
      "AnyTLS",
      "Tailscale"
    };
    new AlertDialog.Builder(this)
        .setTitle("选择线路类型")
        .setItems(labels, (d, which) -> show(() -> lineEditor(null, Profiles.TYPES[which])))
        .setNegativeButton("取消", null)
        .show();
  }

  /** Type-specific form. Only the fields a person needs are shown; rare ones sit under 高级. */
  void lineEditor(String id, String newType) {
    JSONObject p = profileObj();
    JSONObject existing = id == null ? null : Profiles.line(p, id);
    if (id != null && existing == null) {
      goBack();
      return;
    }
    JSONObject l;
    try {
      l = existing == null ? new JSONObject() : new JSONObject(existing.toString());
    } catch (JSONException e) {
      l = new JSONObject();
    }
    String type = existing == null ? newType : existing.optString("type");
    boolean group = type.equals("selector") || type.equals("urltest");
    LinearLayout page = ui.page(root);
    final Runnable[] save = new Runnable[1];
    ui.topBar(
        page, id == null ? "添加线路" : "编辑线路", this::goBack, group ? null : "保存", () -> save[0].run());

    LinearLayout basic = ui.section(page, null);
    ui.row(basic, ui.badge(Profiles.badge(type), badgeColor(type)), "类型", null, Profiles.typeLabel(type), null);
    EditText name = ui.input(basic, "名称", l.optString("name"), defaultName(type), TEXT);

    EditText server = null, port = null, user = null, secret = null, sni = null, exitNode = null;
    Switch insecure = null, magic = null;
    final String[] method = {l.optString("ss_method", SS_METHODS[0])};
    String note = null;
    LinearLayout s;
    switch (type) {
      case "vpn":
        s = ui.section(page, "服务器");
        server = ui.input(s, "地址", l.optString("vpn_server"), "vpn.example.com:8443", URI);
        user = ui.input(s, "用户名", l.optString("vpn_username"), "必填", EMAIL);
        secret = ui.input(s, "密码", l.optString("vpn_password"), "必填", SECRET);
        note = "填写 AnyConnect 或 ocserv 服务器。端口不是 443 时要带上，例如 vpn.example.com:8443。";
        break;
      case "shadowsocks":
        s = ui.section(page, "服务器");
        server = ui.input(s, "地址", l.optString("ss_server"), "域名或 IP", URI);
        port = ui.input(s, "端口", portText(l.optInt("ss_port")), "8388", NUMBER);
        Ui.Row m = ui.row(s, null, "加密方式", null, method[0], null);
        m.view.setOnClickListener(
            v ->
                new AlertDialog.Builder(this)
                    .setTitle("加密方式")
                    .setSingleChoiceItems(
                        SS_METHODS,
                        Arrays.asList(SS_METHODS).indexOf(method[0]),
                        (d, w) -> {
                          method[0] = SS_METHODS[w];
                          m.value.setText(method[0]);
                          d.dismiss();
                        })
                    .show());
        secret = ui.input(s, "密码", l.optString("ss_password"), "必填", SECRET);
        break;
      case "trojan":
        s = ui.section(page, "服务器");
        server = ui.input(s, "地址", l.optString("trojan_server"), "域名或 IP", URI);
        port = ui.input(s, "端口", portText(l.optInt("trojan_port")), "443", NUMBER);
        secret = ui.input(s, "密码", l.optString("trojan_password"), "必填", SECRET);
        break;
      case "vmess":
        s = ui.section(page, "服务器");
        server = ui.input(s, "地址", l.optString("vmess_server"), "域名或 IP", URI);
        port = ui.input(s, "端口", portText(l.optInt("vmess_port")), "443", NUMBER);
        user = ui.input(s, "UUID", l.optString("vmess_uuid"), "xxxxxxxx-xxxx-…", TEXT);
        break;
      case "anytls":
        s = ui.section(page, "服务器");
        server = ui.input(s, "地址", l.optString("anytls_server"), "域名或 IP", URI);
        port = ui.input(s, "端口", portText(l.optInt("anytls_port")), "443", NUMBER);
        secret = ui.input(s, "密码", l.optString("anytls_password"), "必填", SECRET);
        break;
      case "tailscale":
        s = ui.section(page, "Tailscale");
        exitNode = ui.input(s, "出口节点", l.optString("tailscale_exit_node"), "可留空，填 Tailscale IP", URI);
        magic = ui.toggle(s, "MagicDNS", "访问 Tailnet 内设备的名称", l.optBoolean("tailscale_magic_dns"));
        note = "出口节点留空时，只有访问 Tailnet 内设备的流量走 Tailscale。";
        break;
      default:
        s = ui.section(page, "线路组");
        JSONArray members = l.optJSONArray("group_members");
        ui.row(s, null, "包含线路", null, (members == null ? 0 : members.length()) + " 条", null);
        note = "线路组来自导入的配置。需要修改时，请在电脑端改好后重新导入。";
    }
    if (note != null) ui.footer(page, note);

    if (type.equals("trojan") || type.equals("anytls") || type.equals("vpn")) {
      LinearLayout adv = ui.section(page, "高级");
      if (!type.equals("vpn"))
        sni = ui.input(adv, "SNI", l.optString(type + "_sni"), "默认与服务器地址相同", URI);
      insecure = ui.toggle(adv, "跳过证书验证", null, l.optBoolean("allow_insecure"));
      ui.footer(page, "仅在服务器使用自签证书时开启，会降低安全性。");
    }

    JSONObject scenario = Profiles.activeScenario(p);
    String currentExit = scenario == null ? "direct" : scenario.optString("default_line_id", "direct");
    boolean wasExit = id != null && id.equals(currentExit);
    LinearLayout use = ui.section(page, null);
    Switch makeDefault =
        ui.toggle(use, "设为默认出口", null, wasExit || (id == null && currentExit.equals("direct")));

    if (type.equals("tailscale") && id != null) {
      LinearLayout t = ui.section(page, null);
      ui.action(t, "登录 Tailscale", ui.accent, () -> tailscaleLogin(id));
      ui.footer(page, "首次使用需要登录一次。XDial 的登录与手机上的 Tailscale App 相互独立。");
    }

    final EditText fName = name, fServer = server, fPort = port, fUser = user, fSecret = secret;
    final EditText fSni = sni, fExit = exitNode;
    final Switch fInsecure = insecure, fMagic = magic;
    final JSONObject line = l;
    final String lineId = id == null ? Profiles.newId(p, type) : id;
    final java.util.concurrent.Callable<JSONObject> build =
        () -> {
          String n = fName.getText().toString().trim();
          line.put("id", lineId);
          line.put("name", n.isEmpty() ? defaultName(type) : n);
          line.put("type", type);
          line.put("enabled", true);
          String host = fServer == null ? "" : fServer.getText().toString().trim();
          int po = 0;
          if (fPort != null) {
            String raw = fPort.getText().toString().trim();
            po = raw.isEmpty() ? defaultPort(type) : parsePort(raw);
          }
          if (fServer != null && host.isEmpty()) throw new Exception("请填写服务器地址。");
          if (fSecret != null && fSecret.getText().length() == 0) throw new Exception("请填写密码。");
          switch (type) {
            case "vpn":
              if (fUser.getText().toString().trim().isEmpty()) throw new Exception("请填写用户名。");
              line.put("vpn_server", host)
                  .put("vpn_username", fUser.getText().toString().trim())
                  .put("vpn_password", fSecret.getText().toString());
              break;
            case "shadowsocks":
              line.put("ss_server", host)
                  .put("ss_port", po)
                  .put("ss_method", method[0])
                  .put("ss_password", fSecret.getText().toString());
              break;
            case "trojan":
              line.put("trojan_server", host)
                  .put("trojan_port", po)
                  .put("trojan_password", fSecret.getText().toString())
                  .put("trojan_sni", fSni.getText().toString().trim());
              break;
            case "vmess":
              String uuid = fUser.getText().toString().trim();
              if (!uuid.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new Exception("UUID 格式不正确，应类似 1b2c3d4e-0000-1111-2222-333344445555。");
              line.put("vmess_server", host).put("vmess_port", po).put("vmess_uuid", uuid);
              break;
            case "anytls":
              line.put("anytls_server", host)
                  .put("anytls_port", po)
                  .put("anytls_password", fSecret.getText().toString())
                  .put("anytls_sni", fSni.getText().toString().trim());
              break;
            case "tailscale":
              line.put("tailscale_exit_node", fExit.getText().toString().trim())
                  .put("tailscale_magic_dns", fMagic.isChecked());
              break;
          }
          if (fInsecure != null) line.put("allow_insecure", fInsecure.isChecked());
          return line;
        };

    if (type.equals("shadowsocks") || type.equals("trojan") || type.equals("vmess") || type.equals("anytls")) {
      LinearLayout t = ui.section(page, null);
      final TextView[] result = new TextView[1];
      result[0] =
          ui.action(
              t,
              "测试连接",
              ui.accent,
              () -> {
                try {
                  testLine(build.call(), result[0]);
                } catch (Exception e) {
                  alert("无法测试", e.getMessage());
                }
              });
    }

    if (id != null) {
      LinearLayout del = ui.section(page, null);
      ui.action(del, "删除线路", ui.red, () -> deleteLine(id));
    }

    save[0] =
        () -> {
          try {
            JSONObject built = build.call();
            JSONObject next = profileObj();
            JSONArray lines = next.getJSONArray("lines");
            boolean replaced = false;
            for (int i = 0; i < lines.length(); i++)
              if (lines.getJSONObject(i).optString("id").equals(lineId)) {
                lines.put(i, built);
                replaced = true;
              }
            if (!replaced) lines.put(built);
            if (type.equals("tailscale") && next.optJSONObject("tailscale") == null)
              next.put("tailscale", new JSONObject().put("hostname", tailscaleHostname()));
            JSONObject sc = Profiles.activeScenario(next);
            if (sc != null) {
              if (makeDefault.isChecked()) {
                sc.put("default_line_id", lineId);
                sc.remove("default_subscription_id");
              } else if (sc.optString("default_line_id").equals(lineId)) {
                sc.put("default_line_id", "direct");
              }
            }
            if (commit(next) && id == null && type.equals("tailscale")) tailscaleLogin(lineId);
          } catch (Exception e) {
            alert("请检查", e.getMessage());
          }
        };
  }

  static String portText(int port) {
    return port > 0 ? String.valueOf(port) : "";
  }

  static int defaultPort(String type) {
    return type.equals("shadowsocks") ? 8388 : 443;
  }

  static int parsePort(String raw) throws Exception {
    try {
      int p = Integer.parseInt(raw);
      if (p >= 1 && p <= 65535) return p;
    } catch (NumberFormatException ignored) {
    }
    throw new Exception("端口应为 1–65535 之间的数字。");
  }

  static String defaultName(String type) {
    return type.equals("vpn") ? "公司 VPN" : Profiles.typeLabel(type);
  }

  String tailscaleHostname() {
    String device =
        android.provider.Settings.Secure.getString(
            getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
    return "xdial-android-" + (device == null ? "device" : device.substring(0, Math.min(6, device.length())));
  }

  void testLine(JSONObject line, TextView label) {
    label.setText("正在测试…");
    label.setTextColor(ui.secondary);
    String json = line.toString();
    worker.execute(
        () -> {
          String result;
          boolean ok;
          try {
            String r = Libbox.probeStandaloneLineLatency(json, 8000);
            long ms = new JSONObject(r).optLong("milliseconds", -1);
            result = ms >= 0 ? "可用 · " + ms + " ms" : "可用";
            ok = true;
          } catch (Exception e) {
            result = Errors.friendly(e.getMessage());
            ok = false;
          }
          String text = result;
          boolean success = ok;
          runOnUiThread(
              () -> {
                label.setText((success ? "✓ " : "✕ ") + text + "　重新测试");
                label.setTextColor(success ? ui.green : ui.red);
              });
        });
  }

  void deleteLine(String id) {
    JSONObject p = profileObj();
    int refs = Profiles.references(p, id);
    if (refs > 0) {
      alert("无法删除", "有 " + refs + " 处分流规则或线路组在使用这条线路。请在电脑端修改配置后重新导入。");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("删除这条线路？")
        .setMessage("如果它是默认出口，默认出口会改为直连。")
        .setPositiveButton(
            "删除",
            (d, w) -> {
              try {
                JSONArray lines = p.getJSONArray("lines");
                for (int i = lines.length() - 1; i >= 0; i--)
                  if (lines.getJSONObject(i).optString("id").equals(id)) lines.remove(i);
                JSONArray scenarios = p.optJSONArray("scenarios");
                if (scenarios != null)
                  for (int i = 0; i < scenarios.length(); i++) {
                    JSONObject s = scenarios.getJSONObject(i);
                    if (s.optString("default_line_id").equals(id)) s.put("default_line_id", "direct");
                  }
                commit(p);
              } catch (JSONException e) {
                alert("无法删除", e.getMessage());
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  // ---------------------------------------------------------------- import / export

  void importFile() {
    if (busy) return;
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
    try {
      startActivityForResult(i, REQ_IMPORT);
    } catch (ActivityNotFoundException e) {
      alert("无法打开文件", "没有找到可用的文件选择器。可以改用「粘贴配置文本」。");
    }
  }

  void exportFile() {
    try {
      exportText = Libbox.exportProfile(profile);
    } catch (Exception e) {
      alert("无法导出", Errors.friendly(e.getMessage()));
      return;
    }
    Intent i =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType("application/json")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, "XDial-配置.json");
    try {
      startActivityForResult(i, REQ_EXPORT);
    } catch (ActivityNotFoundException e) {
      alert("无法导出", "没有找到可用的文件管理器。");
    }
  }

  void pastePage() {
    LinearLayout page = ui.page(root);
    final EditText[] box = new EditText[1];
    ui.topBar(
        page,
        "粘贴配置",
        this::goBack,
        "导入",
        () -> {
          String t = box[0].getText().toString();
          if (t.trim().isEmpty()) alert("请先粘贴配置", "支持 XDial JSON、Clash YAML 和 Surge 配置文本。");
          else confirmImport(t);
        });
    box[0] = editor(page, "", "在这里粘贴 XDial、Clash 或 Surge 配置");
    ui.footer(page, "导入会替换当前全部线路和场景。线路密码等信息只保存在本机。");
  }

  void jsonPage() {
    LinearLayout page = ui.page(root);
    final EditText[] box = new EditText[1];
    ui.topBar(
        page,
        "完整配置",
        this::goBack,
        "保存",
        () -> {
          try {
            JSONObject p = new JSONObject(box[0].getText().toString());
            commit(p);
          } catch (JSONException e) {
            alert("JSON 格式错误", e.getMessage());
          }
        });
    String pretty;
    try {
      pretty = new JSONObject(profile).toString(2);
    } catch (JSONException e) {
      pretty = profile;
    }
    box[0] = editor(page, pretty, "");
    ui.footer(page, "包含密码，请勿截图分享。保存前会检查格式，出错不会覆盖原配置。");
  }

  EditText editor(LinearLayout page, String value, String hint) {
    LinearLayout card = ui.section(page, null);
    EditText e = new EditText(this);
    e.setText(value);
    e.setHint(hint);
    e.setTextSize(13);
    e.setTextColor(ui.text);
    e.setHintTextColor(ui.secondary);
    e.setTypeface(Typeface.MONOSPACE);
    e.setGravity(Gravity.TOP | Gravity.START);
    e.setMinLines(14);
    e.setBackground(null);
    e.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
    e.setHorizontallyScrolling(false);
    e.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    card.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  void confirmImport(String text) {
    new AlertDialog.Builder(this)
        .setTitle("导入并替换当前配置？")
        .setMessage("当前全部线路、场景和规则会被替换。")
        .setPositiveButton("导入", (d, w) -> importText(text))
        .setNegativeButton("取消", null)
        .show();
  }

  void importText(String text) {
    busy = true;
    toast("正在解析配置…");
    worker.execute(
        () -> {
          try {
            String value = Libbox.importProfile(text, "auto", "android-import", false);
            runOnUiThread(
                () -> {
                  busy = false;
                  try {
                    JSONObject p = new JSONObject(value);
                    commit(p);
                  } catch (Exception e) {
                    alert("导入失败", Errors.friendly(e.getMessage()));
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  busy = false;
                  alert("导入失败", "无法识别这份配置：" + Errors.friendly(e.getMessage()));
                });
          }
        });
  }

  void resetProfile() {
    new AlertDialog.Builder(this)
        .setTitle("重置全部配置？")
        .setMessage("会删除所有线路、场景和规则，恢复为只有直连的初始状态。")
        .setPositiveButton(
            "重置",
            (d, w) -> {
              try {
                commit(new JSONObject(ProfileStore.DEFAULT));
              } catch (JSONException e) {
                alert("无法重置", e.getMessage());
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  // ---------------------------------------------------------------- info pages

  void diagnosticsPage() {
    LinearLayout page = ui.page(root);
    ui.topBar(page, "连接诊断", this::goBack, null, null);
    JSONObject p = profileObj();
    LinearLayout s = ui.section(page, null);
    ui.row(s, null, "状态", null, XDialVpnService.state, null);
    ui.row(s, null, "默认出口", null, Profiles.exitName(p), null);
    long since = XDialVpnService.connectedAt;
    if (since > 0) ui.row(s, null, "已连接", null, duration(System.currentTimeMillis() - since), null);
    ui.row(s, null, "版本", null, VERSION, null);
    String raw = XDialVpnService.safeError;
    LinearLayout e = ui.section(page, "最近一次错误");
    if (raw.isEmpty()) ui.row(e, null, "无", null, null, null);
    else {
      ui.row(e, null, Errors.friendly(raw), null, null, null);
      TextView detail = ui.text(raw, 12, ui.secondary);
      detail.setTypeface(Typeface.MONOSPACE);
      detail.setTextIsSelectable(true);
      detail.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12));
      e.addView(detail);
      LinearLayout c = ui.section(page, null);
      ui.action(
          c,
          "复制错误信息",
          ui.accent,
          () -> {
            getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("XDial", raw));
            toast("已复制");
          });
    }
    ui.footer(page, "错误信息可能包含服务器地址，分享前请检查。");
  }

  void aboutPage() {
    LinearLayout page = ui.page(root);
    ui.topBar(page, "关于", this::goBack, null, null);
    LinearLayout s = ui.section(page, null);
    ui.row(s, null, "版本", null, VERSION, null);
    ui.row(s, null, "源码基线", null, "XDial v0.9.1", null);
    ui.footer(
        page,
        "这是 XDial 的非官方 Android 移植测试版，不是上游官方发行。\n\n"
            + "· Android 同一时间只能开一个 VPN。\n"
            + "· 修改配置或切换场景后，需要重新连接才生效。\n"
            + "· 暂不支持 macOS 应用分流规则和「始终开启的 VPN」。\n"
            + "· 卸载会删除本机保存的配置和登录状态。");
    LinearLayout l = ui.section(page, null);
    ui.row(l, null, "开源许可", "XDial MIT · sing-box GPLv3 等", null, () -> show(this::licensePage));
  }

  void licensePage() {
    LinearLayout page = ui.page(root);
    ui.topBar(page, "开源许可", this::goBack, null, null);
    LinearLayout card = ui.section(page, null);
    StringBuilder all = new StringBuilder();
    for (String name : new String[] {"XDial-MIT-LICENSE.txt", "THIRD_PARTY_NOTICES.txt"}) {
      try (InputStream in = getAssets().open(name)) {
        all.append(readAll(in, 8 * 1024 * 1024)).append("\n\n");
      } catch (Exception e) {
        all.append(name).append("\n\n");
      }
    }
    TextView t = ui.text(all.toString(), 11, ui.secondary);
    t.setTypeface(Typeface.MONOSPACE);
    t.setTextIsSelectable(true);
    t.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
    card.addView(t);
  }

  // ---------------------------------------------------------------- Tailscale

  void tailscaleLogin(String lineID) {
    if (busy) return;
    if (XDialVpnService.active) {
      alert("请先断开", "登录 Tailscale 需要先断开当前连接。");
      return;
    }
    busy = true;
    toast("正在准备 Tailscale 登录…");
    worker.execute(
        () -> {
          try {
            String cfg =
                Libbox.generateTailscaleSetupConfig(profile, lineID, getFilesDir().getAbsolutePath());
            JSONObject options = new JSONObject(cfg);
            String tag = options.getJSONArray("endpoints").getJSONObject(0).getString("tag");
            loginCore = Libbox.new_(null);
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network network = cm.getActiveNetwork();
            if (network == null) throw new Exception("当前没有可用网络");
            XDialVpnService.configureUnderlay(loginCore, network, cm);
            loginCore.setAndroidSocketProtector(
                fd -> {
                  try (ParcelFileDescriptor copy = ParcelFileDescriptor.fromFd((int) fd)) {
                    network.bindSocket(copy.getFileDescriptor());
                  }
                });
            loginCore.startStandalone(cfg);
            String url = new JSONObject(loginCore.beginTailscaleLogin(tag)).optString("auth_url");
            runOnUiThread(
                () -> {
                  busy = false;
                  if (!url.startsWith("https://")) {
                    stopLogin();
                    alert("Tailscale 已登录", "可以直接连接了。");
                    return;
                  }
                  new AlertDialog.Builder(this)
                      .setTitle("登录 Tailscale")
                      .setMessage("在浏览器里完成登录后回到这里，点「完成」。")
                      .setPositiveButton("完成", (d, w) -> stopLogin())
                      .setNeutralButton(
                          "打开登录页",
                          (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))))
                      .setNegativeButton("取消", (d, w) -> stopLogin())
                      .setCancelable(false)
                      .show();
                });
            handler.postDelayed(this::stopLogin, 180000);
          } catch (Exception e) {
            stopLogin();
            runOnUiThread(
                () -> {
                  busy = false;
                  alert("无法登录 Tailscale", Errors.friendly(e.getMessage()));
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

  // ---------------------------------------------------------------- results

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == REQ_VPN) {
      if (result == RESULT_OK) launchVPN();
      else {
        reconnectPending = false;
        alert("未开启 VPN", "需要在系统弹窗里点「允许」，XDial 才能接管网络。");
      }
    }
    if (request == REQ_IMPORT && result == RESULT_OK && data != null) {
      try (InputStream in = getContentResolver().openInputStream(data.getData())) {
        confirmImport(readAll(in, 2 * 1024 * 1024));
      } catch (Exception e) {
        alert("无法读取文件", e.getMessage());
      }
    }
    if (request == REQ_EXPORT && result == RESULT_OK && data != null && exportText != null) {
      try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
        out.write(exportText.getBytes(StandardCharsets.UTF_8));
        toast("已导出");
      } catch (Exception e) {
        alert("导出失败", e.getMessage());
      }
      exportText = null;
    }
  }

  static String readAll(InputStream in, int limit) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > limit) throw new Exception("文件太大（上限 " + limit / 1024 / 1024 + " MB）");
      out.write(b, 0, n);
    }
    return out.toString("UTF-8");
  }

  void alert(String title, String message) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message == null || message.isEmpty() ? "操作失败，请重试。" : message)
        .setPositiveButton("好", null)
        .show();
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
  }
}

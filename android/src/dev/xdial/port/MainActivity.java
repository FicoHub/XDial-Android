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
  static final String VERSION = "0.9.1-port.3";
  private static final int REQ_VPN = 10, REQ_IMPORT = 20, REQ_EXPORT = 21;
  private static final String[] SS_METHODS = {
    "aes-128-gcm",
    "aes-256-gcm",
    "chacha20-ietf-poly1305",
    "2022-blake3-aes-128-gcm",
    "2022-blake3-aes-256-gcm",
    "2022-blake3-chacha20-poly1305"
  };
  private static final String[] TYPE_LABELS = {
    "AnyConnect", "Shadowsocks", "Trojan", "VMess", "AnyTLS", "Tailscale"
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
  private String summaryOf, summaryScenario = "", summaryExit = "", summaryProtocol = "", statusSignature;
  private int summaryRules;

  // Live views of the home hero card; null on every other page.
  private FrameLayout heroCard;
  private View heroDeco, heroDot;
  private LinearLayout heroPill, heroStats;
  private TextView heroPillText, heroTitle, heroSub, statTime, statSince, statProto;
  private Toggle heroSwitch;

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
    Ui next = new Ui(this);
    if (next.dark != ui.dark) {
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

  /** Swap the current page without adding a back step (e.g. changing a new line's type). */
  void replace(Runnable page) {
    hideKeyboard();
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
    heroCard = null;
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
    LinearLayout page = ui.page(root, false).column;
    JSONObject p = profileObj();
    JSONObject scenario = Profiles.activeScenario(p);
    ImageView more = ui.header(page, "XDial");
    more.setOnClickListener(v -> overflow(more));
    hero(page);

    LinearLayout tiles = new LinearLayout(this);
    ui.tile(
        tiles,
        R.drawable.ic_layers,
        "场景",
        scenario == null ? "默认场景" : scenario.optString("name"),
        this::pickScenario);
    ui.tile(tiles, R.drawable.ic_globe, "默认出口", Profiles.exitName(p), this::pickExit);
    page.addView(tiles, ui.margins(-1, -2, 0, 10, 0, 0));

    ui.sectionHeader(page, "线路", "＋ 添加", this::addLine);
    JSONArray lines = p.optJSONArray("lines");
    String exitId = exitLineId(scenario);
    int shown = 0, total = 0;
    if (lines != null)
      for (int i = 0; i < lines.length(); i++) {
        JSONObject line = lines.optJSONObject(i);
        if (line == null || line.optString("type").equals("direct")) continue;
        total++;
        if (shown >= 8) continue;
        shown++;
        lineCard(page, line, exitId);
      }
    if (total > shown) ui.rowCard(page, "全部 " + total + " 条线路", null, 0, () -> show(this::allLines));
    if (total == 0) {
      LinearLayout empty = ui.card(page, 22, 0);
      empty.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
      empty.addView(ui.bold(ui.text("还没有线路", 16, ui.text)));
      TextView t = ui.text("添加一条线路，或导入电脑上的配置。", 13.5f, ui.secondary);
      t.setPadding(0, ui.dp(4), 0, 0);
      empty.addView(t);
      ui.softButton(empty, "＋ 添加线路", 14, this::addLine);
    }

    LinearLayout acts = new LinearLayout(this);
    ui.pill(acts, "导入配置", this::importMenu);
    ui.pill(acts, "导出", this::exportFile);
    ui.pill(acts, "诊断", () -> show(this::diagnosticsPage));
    page.addView(acts, ui.margins(-1, -2, 0, 16, 0, 0));
    TextView ver = ui.text("版本 " + VERSION + " · 非官方 Android 移植", 12, ui.chevron);
    ver.setGravity(Gravity.CENTER);
    ver.setPadding(0, ui.dp(18), 0, 0);
    page.addView(ver);
  }

  static String exitLineId(JSONObject scenario) {
    if (scenario == null || !scenario.optString("default_subscription_id").isEmpty()) return "";
    return scenario.optString("default_line_id", "direct");
  }

  void lineCard(LinearLayout page, JSONObject line, String exitId) {
    String id = line.optString("id");
    String type = line.optString("type");
    ui.lineCard(
        page,
        Profiles.badge(type),
        badgeColor(type),
        line.optString("name", id),
        Profiles.summary(line),
        id.equals(exitId),
        () -> show(() -> lineEditor(id, null)),
        id.equals(exitId) ? null : () -> setExit(id));
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

  void overflow(View anchor) {
    PopupMenu m = new PopupMenu(this, anchor);
    m.getMenu().add(0, 1, 0, "粘贴配置文本");
    m.getMenu().add(0, 2, 1, "编辑完整配置");
    m.getMenu().add(0, 3, 2, "关于与开源许可");
    m.getMenu().add(0, 4, 3, "重置全部配置");
    m.setOnMenuItemClickListener(
        item -> {
          switch (item.getItemId()) {
            case 1:
              show(this::pastePage);
              break;
            case 2:
              show(this::jsonPage);
              break;
            case 3:
              show(this::aboutPage);
              break;
            case 4:
              resetProfile();
              break;
          }
          return true;
        });
    m.show();
  }

  void importMenu() {
    new AlertDialog.Builder(this)
        .setTitle("导入配置")
        .setItems(
            new String[] {"从文件选择", "粘贴配置文本"},
            (d, w) -> {
              if (w == 0) importFile();
              else show(this::pastePage);
            })
        .setNegativeButton("取消", null)
        .show();
  }

  // ---------------------------------------------------------------- hero card

  void hero(LinearLayout page) {
    FrameLayout card = new FrameLayout(this);
    card.setClipToOutline(true);
    heroDeco = new View(this);
    heroDeco.setBackground(ui.oval(0x14ffffff));
    FrameLayout.LayoutParams dl =
        new FrameLayout.LayoutParams(ui.dp(220), ui.dp(220), Gravity.TOP | Gravity.END);
    dl.topMargin = -ui.dp(70);
    dl.rightMargin = -ui.dp(60);
    card.addView(heroDeco, dl);

    LinearLayout inner = new LinearLayout(this);
    inner.setOrientation(LinearLayout.VERTICAL);
    inner.setPadding(ui.dp(20), ui.dp(18), ui.dp(18), ui.dp(20));
    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    heroPill = new LinearLayout(this);
    heroPill.setGravity(Gravity.CENTER_VERTICAL);
    heroPill.setPadding(ui.dp(11), ui.dp(6), ui.dp(12), ui.dp(6));
    heroDot = new View(this);
    heroPill.addView(heroDot, ui.margins(ui.dp(8), ui.dp(8), 0, 0, 7, 0));
    heroPillText = ui.bold(ui.text("", 13, ui.text));
    heroPill.addView(heroPillText);
    top.addView(heroPill);
    top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    heroSwitch = new Toggle(this, 0x59ffffff, 0x26000000);
    heroSwitch.setOnClickListener(v -> onConnectTapped());
    top.addView(heroSwitch, new LinearLayout.LayoutParams(ui.dp(66), ui.dp(38)));
    inner.addView(top);

    heroTitle = ui.bold(ui.text("", 30, ui.text));
    heroTitle.setSingleLine(true);
    heroTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    heroTitle.setPadding(0, ui.dp(16), 0, ui.dp(2));
    inner.addView(heroTitle);
    heroSub = ui.text("", 14, ui.secondary);
    heroSub.setLineSpacing(0, 1.15f);
    inner.addView(heroSub);

    heroStats = new LinearLayout(this);
    heroStats.setPadding(0, ui.dp(16), 0, 0);
    statTime = stat(heroStats, "时长");
    statSince = stat(heroStats, "连接于");
    statProto = stat(heroStats, "协议");
    inner.addView(heroStats);
    card.addView(inner, new FrameLayout.LayoutParams(-1, -2));
    card.setOnClickListener(
        v -> {
          if (XDialVpnService.phase == XDialVpnService.FAILED || !XDialVpnService.safeError.isEmpty())
            show(this::diagnosticsPage);
        });
    heroCard = card;
    page.addView(card, ui.margins(-1, -2, 0, 4, 0, 0));
  }

  TextView stat(LinearLayout row, String label) {
    LinearLayout col = new LinearLayout(this);
    col.setOrientation(LinearLayout.VERTICAL);
    TextView l = ui.text(label, 12.5f, 0xffffffff);
    l.setAlpha(0.8f);
    col.addView(l);
    TextView v = ui.bold(ui.text("", 16, 0xffffffff));
    v.setFontFeatureSettings("tnum");
    v.setPadding(0, ui.dp(2), 0, 0);
    col.addView(v);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.rightMargin = ui.dp(22);
    row.addView(col, lp);
    return v;
  }

  void updateStatus() {
    int ph = XDialVpnService.phase;
    boolean active = XDialVpnService.active;
    if (reconnectPending && !active && ph != XDialVpnService.DISCONNECTING) {
      reconnectPending = false;
      startVPN();
      return;
    }
    if (heroCard == null) return;
    if (summaryOf != profile) {
      JSONObject p = profileObj();
      JSONObject scenario = Profiles.activeScenario(p);
      summaryScenario = scenario == null ? "默认场景" : scenario.optString("name");
      summaryExit = Profiles.exitName(p);
      summaryRules = Profiles.bindingCount(scenario);
      JSONObject line = Profiles.line(p, exitLineId(scenario));
      summaryProtocol =
          scenario != null && !scenario.optString("default_subscription_id").isEmpty()
              ? "订阅"
              : line == null ? "直连" : Profiles.typeLabel(line.optString("type"));
      summaryOf = profile;
    }
    String exit = active && !XDialVpnService.exitName.isEmpty() ? XDialVpnService.exitName : summaryExit;
    String rules = summaryRules > 0 ? summaryRules + " 条分流规则" : "全部流量走这里";
    String detail = XDialVpnService.detail;

    // mode: 0 idle (tone), 1 live (gradient), 2 failed (red)
    int mode;
    String pill, sub, contentDesc;
    int dotColor;
    boolean on, enabled = !busy;
    switch (ph) {
      case XDialVpnService.CONNECTING:
        mode = 1;
        pill = "正在连接…";
        dotColor = 0xffffd38a;
        sub = detail.isEmpty() ? "正在准备线路" : detail;
        on = true;
        break;
      case XDialVpnService.CONNECTED:
        mode = 1;
        pill = "已连接";
        dotColor = 0xff7cf2b0;
        sub = summaryScenario + " · " + rules;
        on = true;
        break;
      case XDialVpnService.RECOVERING:
        mode = 1;
        pill = XDialVpnService.state;
        dotColor = 0xffffd38a;
        sub = detail.isEmpty() ? "正在恢复连接" : detail;
        on = true;
        break;
      case XDialVpnService.DISCONNECTING:
        mode = 0;
        pill = "正在断开…";
        dotColor = ui.secondary;
        sub = summaryScenario;
        on = false;
        enabled = false;
        break;
      case XDialVpnService.FAILED:
        mode = 2;
        pill = active ? "连接中断" : "连接失败";
        dotColor = ui.red;
        sub = (detail.isEmpty() ? Errors.friendly(XDialVpnService.safeError) : detail) + " 点此查看详情";
        on = active;
        break;
      default:
        mode = 0;
        pill = "未连接";
        dotColor = ui.secondary;
        sub = detail.isEmpty() ? summaryScenario + " · 打开右上角开关即可连接" : detail;
        on = false;
    }
    if (reconnectPending) {
      mode = 1;
      pill = "正在重新连接…";
      dotColor = 0xffffd38a;
      sub = "应用新的配置";
      on = true;
      enabled = false;
    }
    contentDesc = on ? "断开" : "连接";
    long since = XDialVpnService.connectedAt;
    boolean stats = ph == XDialVpnService.CONNECTED && since > 0 && !reconnectPending;
    String time = stats ? minutes(System.currentTimeMillis() - since) : "";
    String sinceText =
        stats ? new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(since)) : "";

    // Touch views only on change: a steady UI saves battery and lets the system go idle.
    String signature =
        mode + "|" + pill + "|" + sub + "|" + exit + "|" + on + "|" + enabled + "|" + time + "|"
            + sinceText + "|" + summaryProtocol + "|" + dotColor;
    if (signature.equals(statusSignature)) return;
    statusSignature = signature;

    boolean live = mode == 1;
    if (live) {
      GradientDrawable g =
          new GradientDrawable(
              GradientDrawable.Orientation.TL_BR, new int[] {ui.heroStart, ui.heroEnd});
      g.setCornerRadius(ui.dp(30));
      heroCard.setBackground(g);
    } else {
      heroCard.setBackground(ui.rounded(mode == 2 ? ui.redSoft : ui.tone, 30));
    }
    heroDeco.setVisibility(live ? View.VISIBLE : View.GONE);
    int fg = live ? 0xffffffff : ui.text;
    heroTitle.setText(exit);
    heroTitle.setTextColor(fg);
    heroSub.setText(sub);
    heroSub.setTextColor(live ? 0xc8ffffff : mode == 2 ? ui.red : ui.secondary);
    heroPillText.setText(pill);
    heroPillText.setTextColor(live ? 0xffffffff : mode == 2 ? ui.red : ui.text);
    heroPill.setBackground(
        ui.rounded(live ? 0x2effffff : mode == 2 ? (ui.red & 0x00ffffff) | 0x1a000000 : (ui.dark ? 0x14ffffff : 0x0f000000), 999));
    heroDot.setBackground(ui.oval(dotColor));
    heroStats.setVisibility(stats ? View.VISIBLE : View.GONE);
    statTime.setText(time);
    statSince.setText(sinceText);
    statProto.setText(summaryProtocol);
    heroSwitch.setColors(
        live ? 0x59ffffff : ui.accent, live ? 0x26000000 : (ui.dark ? 0x26ffffff : 0x26000000));
    if (heroSwitch.isChecked() != on) heroSwitch.setChecked(on, true);
    heroSwitch.setEnabled(enabled);
    heroSwitch.setAlpha(enabled ? 1f : 0.5f);
    heroSwitch.setContentDescription(contentDesc);
  }

  /** Coarse elapsed time; it changes at most once a minute so the screen stays idle. */
  static String minutes(long ms) {
    long m = Math.max(0, ms / 60000);
    if (m < 1) return "<1 分钟";
    if (m < 60) return m + " 分钟";
    return (m / 60) + " 小时 " + (m % 60) + " 分";
  }

  static String duration(long ms) {
    long s = Math.max(0, ms / 1000), h = s / 3600, m = s % 3600 / 60;
    return h > 0
        ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s % 60)
        : String.format(Locale.ROOT, "%d:%02d", m, s % 60);
  }

  void onConnectTapped() {
    if (busy || reconnectPending) return;
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
      JSONObject s = a.optJSONObject(i);
      int rules = Profiles.bindingCount(s);
      names[i] = s.optString("name") + (rules > 0 ? "（" + rules + " 条规则）" : "");
      if (s.optString("id").equals(active)) checked = i;
    }
    new AlertDialog.Builder(this)
        .setTitle("选择场景")
        .setSingleChoiceItems(
            names,
            checked,
            (d, which) -> {
              d.dismiss();
              try {
                if (a.getJSONObject(which).getString("id").equals(active)) return;
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
    String exitId = exitLineId(scenario);
    int checked = exitId.isEmpty() ? -1 : Math.max(0, ids.indexOf(exitId));
    new AlertDialog.Builder(this)
        .setTitle("默认出口")
        .setSingleChoiceItems(
            names.toArray(new String[0]),
            checked,
            (d, which) -> {
              d.dismiss();
              if (which != checked) setExit(ids.get(which));
            })
        .setNegativeButton("取消", null)
        .show();
  }

  void setExit(String lineId) {
    JSONObject p = profileObj();
    JSONObject scenario = Profiles.activeScenario(p);
    if (scenario == null) return;
    try {
      scenario.put("default_line_id", lineId);
      scenario.remove("default_subscription_id");
      commit(p);
    } catch (JSONException e) {
      alert("无法修改出口", e.getMessage());
    }
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
    show(() -> lineEditor(null, "vpn"));
  }

  /** Type-specific form. Only the fields a person needs are shown; rare ones sit under 高级选项. */
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
    Ui.Page pg = ui.page(root, true);
    LinearLayout page = pg.column;
    ui.nav(page, id == null ? "添加线路" : "编辑线路", this::goBack);
    int typeIndex = Arrays.asList(Profiles.TYPES).indexOf(type);
    if (id == null)
      ui.chips(page, TYPE_LABELS, typeIndex, i -> replace(() -> lineEditor(null, Profiles.TYPES[i])));
    else ui.chips(page, new String[] {Profiles.typeLabel(type)}, 0, null);

    EditText name = ui.field(page, "名称", l.optString("name"), defaultName(type), TEXT);
    EditText server = null, port = null, user = null, secret = null, sni = null, exitNode = null;
    Toggle insecure = null, magic = null;
    final String[] method = {l.optString("ss_method", SS_METHODS[0])};
    switch (type) {
      case "vpn":
        server = ui.field(page, "服务器地址", l.optString("vpn_server"), "vpn.example.com:8443", URI);
        ui.note(page, "端口不是 443 时要带上");
        user = ui.field(page, "用户名", l.optString("vpn_username"), "必填", EMAIL);
        secret = ui.field(page, "密码", l.optString("vpn_password"), "必填", SECRET);
        break;
      case "shadowsocks":
        server = ui.field(page, "服务器地址", l.optString("ss_server"), "域名或 IP", URI);
        port = ui.field(page, "端口", portText(l.optInt("ss_port")), "8388", NUMBER);
        final TextView[] m = new TextView[1];
        m[0] =
            ui.pickField(
                page,
                "加密方式",
                method[0],
                () ->
                    new AlertDialog.Builder(this)
                        .setTitle("加密方式")
                        .setSingleChoiceItems(
                            SS_METHODS,
                            Arrays.asList(SS_METHODS).indexOf(method[0]),
                            (d, w) -> {
                              method[0] = SS_METHODS[w];
                              m[0].setText(method[0]);
                              d.dismiss();
                            })
                        .show());
        secret = ui.field(page, "密码", l.optString("ss_password"), "必填", SECRET);
        break;
      case "trojan":
        server = ui.field(page, "服务器地址", l.optString("trojan_server"), "域名或 IP", URI);
        port = ui.field(page, "端口", portText(l.optInt("trojan_port")), "443", NUMBER);
        secret = ui.field(page, "密码", l.optString("trojan_password"), "必填", SECRET);
        break;
      case "vmess":
        server = ui.field(page, "服务器地址", l.optString("vmess_server"), "域名或 IP", URI);
        port = ui.field(page, "端口", portText(l.optInt("vmess_port")), "443", NUMBER);
        user = ui.field(page, "UUID", l.optString("vmess_uuid"), "xxxxxxxx-xxxx-…", TEXT);
        break;
      case "anytls":
        server = ui.field(page, "服务器地址", l.optString("anytls_server"), "域名或 IP", URI);
        port = ui.field(page, "端口", portText(l.optInt("anytls_port")), "443", NUMBER);
        secret = ui.field(page, "密码", l.optString("anytls_password"), "必填", SECRET);
        break;
      case "tailscale":
        exitNode =
            ui.field(page, "出口节点", l.optString("tailscale_exit_node"), "可留空，填 Tailscale IP", URI);
        ui.note(page, "留空时，只有访问 Tailnet 内设备的流量走 Tailscale。");
        magic =
            ui.toggleCard(
                page, "MagicDNS", "用名称访问 Tailnet 内的设备", l.optBoolean("tailscale_magic_dns"), 12);
        break;
      default:
        JSONArray members = l.optJSONArray("group_members");
        ui.rowCard(page, "包含线路", (members == null ? 0 : members.length()) + " 条", 12, null);
        ui.note(page, "线路组来自导入的配置。需要修改时，请在电脑端改好后重新导入。");
    }

    JSONObject scenario = Profiles.activeScenario(p);
    String currentExit = exitLineId(scenario);
    boolean wasExit = id != null && id.equals(currentExit);
    Toggle makeDefault =
        ui.toggleCard(
            page, "设为默认出口", null, wasExit || (id == null && currentExit.equals("direct")), 16);

    if (type.equals("trojan") || type.equals("anytls") || type.equals("vpn")) {
      LinearLayout adv = new LinearLayout(this);
      adv.setOrientation(LinearLayout.VERTICAL);
      if (!type.equals("vpn"))
        sni = ui.field(adv, "SNI", l.optString(type + "_sni"), "默认与服务器地址相同", URI);
      insecure = ui.toggleCard(adv, "跳过证书验证", "仅在服务器使用自签证书时开启", l.optBoolean("allow_insecure"), 10);
      boolean open = l.optBoolean("allow_insecure") || !l.optString(type + "_sni").isEmpty();
      Ui.Row row =
          ui.rowCard(page, "高级选项", null, 8, null);
      row.title.setTextColor(ui.secondary);
      row.view.addView(ui.chevron());
      adv.setVisibility(open ? View.VISIBLE : View.GONE);
      row.view.setVisibility(open ? View.GONE : View.VISIBLE);
      ui.clickable(
          row.view,
          ui.rounded(ui.card, 20),
          20,
          () -> {
            row.view.setVisibility(View.GONE);
            adv.setVisibility(View.VISIBLE);
          });
      page.addView(adv);
    }

    final EditText fName = name, fServer = server, fPort = port, fUser = user, fSecret = secret;
    final EditText fSni = sni, fExit = exitNode;
    final Toggle fInsecure = insecure, fMagic = magic;
    final JSONObject line = l;
    final String lineId = id == null ? Profiles.newId(p, type) : id;
    final Callable<JSONObject> build =
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
      final TextView[] result = new TextView[1];
      ui.softButton(
          page,
          "测试连接",
          16,
          () -> {
            try {
              testLine(build.call(), result[0]);
            } catch (Exception e) {
              alert("无法测试", e.getMessage());
            }
          });
      result[0] = ui.note(page, "不用连接 VPN，直接检查服务器是否可用。");
    }
    if (type.equals("tailscale") && id != null) {
      ui.softButton(page, "登录 Tailscale", 16, () -> tailscaleLogin(id));
      ui.note(page, "首次使用需要登录一次。XDial 的登录与手机上的 Tailscale App 相互独立。");
    }

    Runnable save =
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
    if (id != null) ui.barButton(pg.bar, "删除", false, 0.55f, () -> deleteLine(id));
    if (!group) ui.barButton(pg.bar, "保存", true, 1f, save);
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
                label.setText((success ? "✓ " : "✕ ") + text);
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

  void allLines() {
    LinearLayout page = ui.page(root, false).column;
    ui.nav(page, "全部线路", this::goBack);
    JSONObject p = profileObj();
    String exitId = exitLineId(Profiles.activeScenario(p));
    View gap = new View(this);
    page.addView(gap, new LinearLayout.LayoutParams(1, ui.dp(6)));
    JSONArray lines = p.optJSONArray("lines");
    if (lines != null)
      for (int i = 0; i < lines.length(); i++) {
        JSONObject line = lines.optJSONObject(i);
        if (line != null && !line.optString("type").equals("direct")) lineCard(page, line, exitId);
      }
    ui.softButton(page, "＋ 添加线路", 8, this::addLine);
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
    Ui.Page pg = ui.page(root, true);
    ui.nav(pg.column, "粘贴配置", this::goBack);
    EditText box = editor(pg.column, "", "在这里粘贴 XDial、Clash 或 Surge 配置");
    ui.note(pg.column, "导入会替换当前全部线路和场景。线路密码等信息只保存在本机。");
    ui.barButton(
        pg.bar,
        "导入",
        true,
        1f,
        () -> {
          String t = box.getText().toString();
          if (t.trim().isEmpty()) alert("请先粘贴配置", "支持 XDial JSON、Clash YAML 和 Surge 配置文本。");
          else confirmImport(t);
        });
  }

  void jsonPage() {
    Ui.Page pg = ui.page(root, true);
    ui.nav(pg.column, "完整配置", this::goBack);
    String pretty;
    try {
      pretty = new JSONObject(profile).toString(2);
    } catch (JSONException e) {
      pretty = profile;
    }
    EditText box = editor(pg.column, pretty, "");
    ui.note(pg.column, "包含密码，请勿截图分享。保存前会检查格式，出错不会覆盖原配置。");
    ui.barButton(
        pg.bar,
        "保存",
        true,
        1f,
        () -> {
          try {
            commit(new JSONObject(box.getText().toString()));
          } catch (JSONException e) {
            alert("JSON 格式错误", e.getMessage());
          }
        });
  }

  EditText editor(LinearLayout page, String value, String hint) {
    EditText e = new EditText(this);
    e.setText(value);
    e.setHint(hint);
    e.setTextSize(13);
    e.setTextColor(ui.text);
    e.setHintTextColor(ui.chevron);
    e.setTypeface(Typeface.MONOSPACE);
    e.setGravity(Gravity.TOP | Gravity.START);
    e.setMinLines(14);
    e.setBackground(ui.stroked(ui.card, ui.line, 1.5f, 16));
    e.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
    e.setHorizontallyScrolling(false);
    e.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    page.addView(e, ui.margins(-1, -2, 0, 10, 0, 0));
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
                    commit(new JSONObject(value));
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
    LinearLayout page = ui.page(root, false).column;
    ui.nav(page, "连接诊断", this::goBack);
    JSONObject p = profileObj();
    LinearLayout c = ui.card(page, 20, 8);
    ui.info(c, "状态", XDialVpnService.state);
    ui.info(c, "默认出口", Profiles.exitName(p));
    long since = XDialVpnService.connectedAt;
    if (since > 0) ui.info(c, "已连接", duration(System.currentTimeMillis() - since));
    ui.info(c, "版本", VERSION);
    String raw = XDialVpnService.safeError;
    ui.sectionHeader(page, "最近一次错误", null, null);
    LinearLayout e = ui.card(page, 20, 0);
    e.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
    if (raw.isEmpty()) e.addView(ui.text("无", 15, ui.secondary));
    else {
      e.addView(ui.bold(ui.text(Errors.friendly(raw), 15.5f, ui.red)));
      TextView detail = ui.text(raw, 12, ui.secondary);
      detail.setTypeface(Typeface.MONOSPACE);
      detail.setTextIsSelectable(true);
      detail.setPadding(0, ui.dp(8), 0, 0);
      e.addView(detail);
      ui.softButton(
          page,
          "复制错误信息",
          12,
          () -> {
            getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("XDial", raw));
            toast("已复制");
          });
    }
    ui.note(page, "错误信息可能包含服务器地址，分享前请检查。");
  }

  void aboutPage() {
    LinearLayout page = ui.page(root, false).column;
    ui.nav(page, "关于", this::goBack);
    LinearLayout c = ui.card(page, 20, 8);
    ui.info(c, "版本", VERSION);
    ui.info(c, "源码基线", "XDial v0.9.1");
    LinearLayout t = ui.card(page, 20, 10);
    t.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
    TextView body =
        ui.text(
            "这是 XDial 的非官方 Android 移植测试版，不是上游官方发行。\n\n"
                + "· Android 同一时间只能开一个 VPN。\n"
                + "· 修改配置或切换场景后，需要重新连接才生效。\n"
                + "· 暂不支持 macOS 应用分流规则和「始终开启的 VPN」。\n"
                + "· 卸载会删除本机保存的配置和登录状态。",
            14,
            ui.secondary);
    body.setLineSpacing(0, 1.25f);
    t.addView(body);
    ui.rowCard(page, "开源许可", "MIT · GPLv3 等", 10, () -> show(this::licensePage));
  }

  void licensePage() {
    LinearLayout page = ui.page(root, false).column;
    ui.nav(page, "开源许可", this::goBack);
    LinearLayout card = ui.card(page, 20, 8);
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

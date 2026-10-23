package dev.xdial.port;

import static dev.xdial.port.Ui.DANGER;
import static dev.xdial.port.Ui.PLAIN;
import static dev.xdial.port.Ui.PRIMARY;
import static dev.xdial.port.Ui.btn;
import static dev.xdial.port.Ui.stay;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.text.InputType;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
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
  static final String VERSION = "0.9.1-port.6";
  private static final int REQ_VPN = 10, REQ_IMPORT = 20, REQ_EXPORT = 21, REQ_LOC = 40, REQ_BG = 41;
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
  private final Runnable homePage = this::home;
  private Ui ui;
  private FrameLayout root, host;
  private Runnable current;
  private String profile = ProfileStore.DEFAULT;
  private boolean busy = false;
  private boolean reconnectPending = false;
  private String exportText;
  private libbox.Libbox_ loginCore;
  private String summaryOf, summaryScenarioId, statusSignature;
  private String summaryScenario = "", summaryTitle = "", summaryProtocol = "", summaryRoute = "";
  private boolean summaryAllDirect;
  private boolean keepAliveVisible, shownAlwaysOn;
  private String shownAuto = "";

  // Live views of the hero card; null when it is not on screen.
  private FrameLayout heroCard;
  private View heroDeco, heroDot;
  private LinearLayout heroPill, heroStats;
  private TextView heroPillText, heroTitle, heroSub, heroLink, statTime, statSince, statThird, statThirdLabel;
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
    current = homePage;
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
    if (keepAliveVisible) render();
    // The user left it on but it is not running (e.g. the process died): reconnect now.
    if (!busy && KeepAliveJob.revive(this)) toast("正在恢复连接…");
    handler.post(tick);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(tick);
    super.onPause();
  }

  /** Dark mode, folding and unfolding arrive here; the activity is not recreated. */
  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    Ui next = new Ui(this);
    if (next.dark != ui.dark || next.wide != ui.wide) {
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

  /**
   * Opens a page from the home status area. On wide screens the right pane shows one page at a
   * time, so this replaces whatever it held instead of stacking pages.
   */
  void open(Runnable page) {
    if (!ui.wide) {
      show(page);
      return;
    }
    hideKeyboard();
    back.clear();
    back.push(homePage);
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
    current = homePage;
    render();
  }

  /**
   * Phones show one page at a time. Screens at least 600dp wide (an unfolded Fold) keep the
   * status pane on the left and show lines or the open page on the right.
   */
  void render() {
    heroCard = null;
    keepAliveVisible = false;
    statusSignature = null;
    root.removeAllViews();
    if (ui.wide) {
      LinearLayout split = new LinearLayout(this);
      FrameLayout left = new FrameLayout(this), right = new FrameLayout(this);
      split.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
      View divider = new View(this);
      divider.setBackgroundColor(ui.line);
      split.addView(divider, new LinearLayout.LayoutParams(ui.hair(), -1));
      split.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
      root.addView(split, new FrameLayout.LayoutParams(-1, -1));
      host = left;
      homeSide();
      host = right;
      if (current == homePage) linesPane();
      else current.run();
    } else {
      host = root;
      current.run();
    }
    root.requestApplyInsets();
    updateStatus();
  }

  void hideKeyboard() {
    View f = getCurrentFocus();
    if (f != null)
      getSystemService(InputMethodManager.class).hideSoftInputFromWindow(f.getWindowToken(), 0);
  }

  /** Main page action: a pinned bottom bar on phones, a button in the title row on wide screens. */
  void action(Ui.Page pg, LinearLayout nav, String title, int kind, float weight, Runnable run) {
    if (pg.bar != null) ui.barButton(pg.bar, title, kind, weight, run);
    else ui.navButton(nav, title, kind, run);
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
      ui.dialog(
          "已保存",
          "修改要重新连接后才生效，现在重新连接吗？",
          true,
          btn("稍后", PLAIN, null),
          btn("重新连接", PRIMARY, this::reconnect));
    } else {
      toast("已保存");
    }
    return true;
  }

  /** The scenario on screen: the one actually running (Wi-Fi may have picked it), else the chosen one. */
  JSONObject shownScenario(JSONObject p) {
    if (XDialVpnService.active && !XDialVpnService.runningScenarioId.isEmpty()) {
      JSONObject s = Profiles.scenario(p, XDialVpnService.runningScenarioId);
      if (s != null) return s;
    }
    return Profiles.activeScenario(p);
  }

  // ---------------------------------------------------------------- home

  void home() {
    LinearLayout page = ui.page(host, false).column;
    JSONObject p = profileObj();
    homeTop(page, p);
    ui.sectionHeader(page, "线路", "＋ 添加", this::addLine);
    linesList(page, p, 8);
    homeFooter(page);
  }

  /** Left pane on wide screens: status, scenario, stay-connected and actions. */
  void homeSide() {
    LinearLayout page = ui.page(host, false).column;
    homeTop(page, profileObj());
    homeFooter(page);
  }

  /** Right pane root on wide screens: lines and how the scenario on screen routes traffic. */
  void linesPane() {
    LinearLayout page = ui.page(host, false).column;
    JSONObject p = profileObj();
    ui.paneHeader(page, "线路", "＋ 添加", this::addLine);
    linesList(page, p, Integer.MAX_VALUE);
    JSONObject sc = shownScenario(p);
    if (sc == null) return;
    ui.groupHeader(page, "「" + sc.optString("name") + "」场景怎么走");
    LinearLayout g = ui.group(page, 0);
    JSONArray b = sc.optJSONArray("bindings");
    int n = b == null ? 0 : b.length();
    for (int i = 0; i < Math.min(n, 12); i++) {
      JSONObject binding = b.optJSONObject(i);
      if (binding == null) continue;
      String rule = binding.optString("rule_set_id");
      String target =
          !binding.optString("subscription_id").isEmpty()
              ? "订阅"
              : Profiles.lineName(p, binding.optString("line_id"));
      ui.kv(g, Profiles.ruleName(p, rule), Profiles.ruleSummary(Profiles.ruleSet(p, rule)), target);
    }
    if (n > 12) ui.kv(g, "还有 " + (n - 12) + " 条规则", null, "");
    ui.kv(g, n == 0 ? "全部流量" : "其余流量", null, Profiles.exitName(p, sc));
    JSONArray ssids = sc.optJSONArray("match_ssids");
    JSONObject chosen = Profiles.activeScenario(p);
    if (ssids != null && ssids.length() > 0 && chosen != null && !chosen.optString("id").equals(sc.optString("id")))
      ui.note(page, "连着「" + ssids.optString(0) + "」时自动使用这个场景，离开后回到「" + chosen.optString("name") + "」。");
    else
      ui.note(page, n == 0 ? "全部流量走默认出口。" : "命中分流规则的走对应线路，其余走默认出口。");
  }

  void homeTop(LinearLayout page, JSONObject p) {
    ImageView more = ui.header(page, "XDial");
    more.setOnClickListener(v -> overflow(more));
    hero(page);
    JSONObject shown = shownScenario(p);
    boolean auto = XDialVpnService.active && !XDialVpnService.autoSsid.isEmpty();
    shownAuto = auto ? XDialVpnService.runningScenario : "";
    LinearLayout tiles = new LinearLayout(this);
    ui.tile(
        tiles,
        R.drawable.ic_layers,
        auto ? "场景 · 按 Wi-Fi 自动" : "场景",
        shown == null ? "默认场景" : shown.optString("name"),
        this::pickScenario);
    ui.tile(tiles, R.drawable.ic_globe, "默认出口", Profiles.exitName(p, shown), this::pickExit);
    page.addView(tiles, ui.margins(-1, -2, 0, 10, 0, 0));
    int[] keep = keepAliveScore(p);
    boolean ok = keep[0] >= keep[1];
    Ui.Row row =
        ui.rowCard(
            page,
            "保持常连",
            ok ? "✓ 已设置好" : "● 还差 " + (keep[1] - keep[0]) + " 项",
            10,
            () -> open(this::keepAlivePage));
    row.value.setTextColor(ok ? ui.green : ui.orange);
  }

  void linesList(LinearLayout page, JSONObject p, int limit) {
    JSONObject scenario = shownScenario(p);
    JSONArray lines = p.optJSONArray("lines");
    String exitId = exitLineId(scenario);
    int shown = 0, total = 0;
    if (lines != null)
      for (int i = 0; i < lines.length(); i++) {
        JSONObject line = lines.optJSONObject(i);
        if (line == null || line.optString("type").equals("direct")) continue;
        total++;
        if (shown >= limit) continue;
        shown++;
        lineCard(page, p, scenario, line, exitId);
      }
    if (total > shown) ui.rowCard(page, "全部 " + total + " 条线路", null, 0, () -> show(this::allLines));
    if (total == 0) {
      LinearLayout empty = ui.card(page, 22, 0);
      empty.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
      empty.addView(ui.bold(ui.text("还没有线路", 16, ui.text)));
      TextView t = ui.text("添加一条线路，或导入公司提供的配置。", 13.5f, ui.secondary);
      t.setPadding(0, ui.dp(4), 0, 0);
      empty.addView(t);
      ui.softButton(empty, "＋ 添加线路", 14, this::addLine);
    }
  }

  void homeFooter(LinearLayout page) {
    LinearLayout acts = new LinearLayout(this);
    ui.pill(acts, "导入配置", this::importMenu);
    ui.pill(acts, "导出", this::exportFile);
    ui.pill(acts, "诊断", () -> open(this::diagnosticsPage));
    page.addView(acts, ui.margins(-1, -2, 0, 16, 0, 0));
    TextView ver = ui.text(VERSION + " · 非官方 Android 移植", 12, ui.chevron);
    ver.setGravity(Gravity.CENTER);
    ver.setPadding(0, ui.dp(18), 0, 0);
    page.addView(ver);
  }

  static String exitLineId(JSONObject scenario) {
    if (scenario == null || !scenario.optString("default_subscription_id").isEmpty()) return "";
    return scenario.optString("default_line_id", "direct");
  }

  /** The tag says what the line does in the scenario on screen: default exit or which rule. */
  void lineCard(LinearLayout page, JSONObject p, JSONObject scenario, JSONObject line, String exitId) {
    String id = line.optString("id");
    String type = line.optString("type");
    String tag = null;
    if (id.equals(exitId)) tag = "默认出口";
    else {
      List<String> rules = Profiles.rulesUsing(p, scenario, id);
      if (rules.size() == 1) tag = rules.get(0);
      else if (rules.size() > 1) tag = rules.size() + " 条规则";
    }
    ui.lineCard(
        page,
        Profiles.badge(type),
        badgeColor(type),
        line.optString("name", id),
        Profiles.summary(line),
        tag,
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

  void overflow(View anchor) {
    PopupMenu m = new PopupMenu(this, anchor);
    m.getMenu().add(0, 1, 0, "粘贴配置文本");
    m.getMenu().add(0, 2, 1, "编辑完整配置");
    m.getMenu().add(0, 3, 2, "关于与开源许可");
    SpannableString reset = new SpannableString("重置全部配置");
    reset.setSpan(new ForegroundColorSpan(ui.red), 0, reset.length(), 0);
    m.getMenu().add(0, 4, 3, reset);
    m.setOnMenuItemClickListener(
        item -> {
          switch (item.getItemId()) {
            case 1:
              open(this::pastePage);
              break;
            case 2:
              open(this::jsonPage);
              break;
            case 3:
              open(this::aboutPage);
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
    Ui.Sheet s = ui.sheet("导入配置", "支持 XDial、Clash、Surge。导入会替换当前全部线路和场景。");
    s.option(R.drawable.ic_file, "从文件选择", "选择手机里的配置文件", null, this::importFile);
    s.option(R.drawable.ic_clip, "粘贴配置文本", "从剪贴板或手动粘贴", null, () -> open(this::pastePage));
    s.show();
  }

  // ---------------------------------------------------------------- hero card

  void hero(LinearLayout page) {
    FrameLayout card = new FrameLayout(this);
    card.setClipToOutline(true);
    heroDeco = new View(this);
    heroDeco.setBackground(ui.oval(0x14ffffff));
    FrameLayout.LayoutParams dl =
        new FrameLayout.LayoutParams(ui.dp(236), ui.dp(236), Gravity.TOP | Gravity.END);
    dl.topMargin = -ui.dp(84);
    dl.rightMargin = -ui.dp(72);
    card.addView(heroDeco, dl);

    LinearLayout inner = new LinearLayout(this);
    inner.setOrientation(LinearLayout.VERTICAL);
    inner.setPadding(ui.dp(20), ui.dp(18), ui.dp(18), ui.dp(19));
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
    top.addView(heroSwitch, new LinearLayout.LayoutParams(ui.dp(60), ui.dp(35)));
    inner.addView(top);

    heroTitle = ui.bold(ui.text("", 29, ui.text));
    heroTitle.setSingleLine(true);
    heroTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    heroTitle.setPadding(0, ui.dp(16), 0, ui.dp(3));
    inner.addView(heroTitle);
    heroSub = ui.text("", 14, ui.secondary);
    heroSub.setLineSpacing(0, 1.2f);
    inner.addView(heroSub);
    heroLink = ui.bold(ui.text("查看详情 ›", 13.5f, ui.red));
    heroLink.setPadding(0, ui.dp(10), 0, 0);
    heroLink.setVisibility(View.GONE);
    inner.addView(heroLink);

    heroStats = new LinearLayout(this);
    heroStats.setPadding(0, ui.dp(16), 0, 0);
    statTime = stat(heroStats, "时长")[1];
    statSince = stat(heroStats, "连接于")[1];
    TextView[] third = stat(heroStats, "协议");
    statThirdLabel = third[0];
    statThird = third[1];
    inner.addView(heroStats);
    card.addView(inner, new FrameLayout.LayoutParams(-1, -2));
    card.setOnClickListener(
        v -> {
          if (XDialVpnService.phase == XDialVpnService.FAILED || !XDialVpnService.safeError.isEmpty())
            open(this::diagnosticsPage);
        });
    heroCard = card;
    page.addView(card, ui.margins(-1, -2, 0, 4, 0, 0));
  }

  TextView[] stat(LinearLayout row, String label) {
    LinearLayout col = new LinearLayout(this);
    col.setOrientation(LinearLayout.VERTICAL);
    TextView l = ui.text(label, 12.5f, 0xffffffff);
    l.setAlpha(0.75f);
    col.addView(l);
    TextView v = ui.bold(ui.text("", 16, 0xffffffff));
    v.setFontFeatureSettings("tnum");
    v.setSingleLine(true);
    v.setPadding(0, ui.dp(3), 0, 0);
    col.addView(v);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.rightMargin = ui.dp(24);
    row.addView(col, lp);
    return new TextView[] {l, v};
  }

  void updateStatus() {
    if (keepAliveVisible && shownAlwaysOn != alwaysOnKnown()) {
      render();
      return;
    }
    int ph = XDialVpnService.phase;
    boolean active = XDialVpnService.active;
    if (reconnectPending && !active && ph != XDialVpnService.DISCONNECTING) {
      reconnectPending = false;
      startVPN();
      return;
    }
    if (heroCard == null) return;
    String auto = active && !XDialVpnService.autoSsid.isEmpty() ? XDialVpnService.runningScenario : "";
    if (!auto.equals(shownAuto) && back.isEmpty()) {
      render(); // Wi-Fi switched the scenario: refresh the tiles and tags too
      return;
    }
    String scenarioId = active ? XDialVpnService.runningScenarioId : "";
    if (summaryOf != profile || !scenarioId.equals(summaryScenarioId)) {
      JSONObject p = profileObj();
      JSONObject sc = scenarioId.isEmpty() ? null : Profiles.scenario(p, scenarioId);
      if (sc == null) sc = Profiles.activeScenario(p);
      String[] head = Profiles.headline(p, sc);
      summaryScenario = sc == null ? "默认场景" : sc.optString("name");
      summaryTitle = head[0];
      summaryProtocol = head[1];
      summaryAllDirect = head[2].isEmpty() && "直连".equals(head[1]);
      summaryRoute = Profiles.routeText(p, sc);
      summaryOf = profile;
      summaryScenarioId = scenarioId;
    }
    String detail = XDialVpnService.detail;

    // mode: 0 idle (tone), 1 live (gradient), 2 failed (red), 3 working on it (tone, amber)
    int mode;
    String pill, sub;
    boolean on, enabled = !busy;
    switch (ph) {
      case XDialVpnService.CONNECTING:
        mode = 3;
        pill = "正在连接…";
        sub = detail.isEmpty() ? "正在准备线路" : detail;
        on = true;
        break;
      case XDialVpnService.CONNECTED:
        mode = 1;
        pill = "已连接";
        sub =
            auto.isEmpty()
                ? summaryScenario + " · " + summaryRoute
                : "在 Wi-Fi「" + XDialVpnService.autoSsid + "」，已自动切到「" + auto + "」";
        on = true;
        break;
      case XDialVpnService.RECOVERING:
        mode = 3;
        pill = XDialVpnService.state;
        sub = detail.isEmpty() ? "正在恢复连接" : detail;
        on = true;
        break;
      case XDialVpnService.DISCONNECTING:
        mode = 0;
        pill = "正在断开…";
        sub = summaryScenario;
        on = false;
        enabled = false;
        break;
      case XDialVpnService.FAILED:
        mode = 2;
        pill = active ? "连接中断" : "连接失败";
        sub = detail.isEmpty() ? Errors.friendly(XDialVpnService.safeError) : detail;
        if (Errors.isCredential(XDialVpnService.safeError)) sub += "为避免账号被锁，不会自动重试。";
        on = active;
        break;
      default:
        mode = 0;
        pill = "未连接";
        sub = detail.isEmpty() ? summaryScenario + " · 打开右上角开关即可连接" : detail;
        on = false;
    }
    if (reconnectPending) {
      mode = 3;
      pill = "正在重新连接…";
      sub = "应用新的配置";
      on = true;
      enabled = false;
    }
    String title = summaryAllDirect ? "全部直连" : summaryTitle;
    long since = XDialVpnService.connectedAt;
    boolean stats = mode == 1 && since > 0;
    String time = stats ? minutes(System.currentTimeMillis() - since) : "";
    String sinceText =
        stats ? new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(since)) : "";
    boolean showScenario = !auto.isEmpty() || summaryAllDirect;
    String thirdLabel = showScenario ? "场景" : "协议";
    String third = showScenario ? summaryScenario : summaryProtocol;

    // Touch views only on change: a steady UI saves battery and lets the system go idle.
    String signature =
        mode + "|" + pill + "|" + sub + "|" + title + "|" + on + "|" + enabled + "|" + time + "|"
            + sinceText + "|" + thirdLabel + "|" + third;
    if (signature.equals(statusSignature)) return;
    statusSignature = signature;

    boolean live = mode == 1;
    if (live) {
      GradientDrawable g =
          new GradientDrawable(
              GradientDrawable.Orientation.TL_BR, new int[] {ui.heroStart, ui.heroEnd});
      g.setCornerRadius(ui.dp(28));
      heroCard.setBackground(g);
    } else {
      heroCard.setBackground(ui.rounded(mode == 2 ? ui.redSoft : ui.tone, 28));
    }
    heroDeco.setVisibility(live ? View.VISIBLE : View.GONE);
    heroTitle.setText(title);
    heroTitle.setTextColor(live ? 0xffffffff : ui.text);
    heroSub.setText(sub);
    heroSub.setTextColor(live ? 0xd0ffffff : mode == 2 ? ui.red : ui.secondary);
    heroLink.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
    heroPillText.setText(pill);
    int pillFg, pillBg, dot;
    switch (mode) {
      case 1:
        pillFg = 0xffffffff;
        pillBg = 0x2effffff;
        dot = 0xff7cf2b0;
        break;
      case 2:
        pillFg = ui.red;
        pillBg = (ui.red & 0x00ffffff) | 0x1a000000;
        dot = ui.red;
        break;
      case 3:
        pillFg = ui.orange;
        pillBg = ui.orangeSoft;
        dot = ui.orange;
        break;
      default:
        pillFg = ui.text;
        pillBg = ui.dark ? 0x12ffffff : 0x0f000000;
        dot = ui.chevron;
    }
    heroPillText.setTextColor(pillFg);
    heroPill.setBackground(ui.rounded(pillBg, 999));
    heroDot.setBackground(ui.oval(dot));
    heroStats.setVisibility(stats ? View.VISIBLE : View.GONE);
    statTime.setText(time);
    statSince.setText(sinceText);
    statThirdLabel.setText(thirdLabel);
    statThird.setText(third);
    heroSwitch.setColors(
        live ? 0x59ffffff : ui.accent, live ? 0x26000000 : (ui.dark ? 0x26ffffff : 0x26000000));
    if (heroSwitch.isChecked() != on) heroSwitch.setChecked(on, true);
    heroSwitch.setEnabled(enabled);
    heroSwitch.setAlpha(enabled ? 1f : 0.5f);
    heroSwitch.setContentDescription(on ? "断开" : "连接");
  }

  /** Coarse elapsed time; it changes at most once a minute so the screen stays idle. */
  static String minutes(long ms) {
    long m = Math.max(0, ms / 60000);
    if (m < 1) return "<1 分钟";
    if (m < 60) return m + " 分钟";
    return (m / 60) + " 小时 " + (m % 60) + " 分";
  }

  void onConnectTapped() {
    if (busy || reconnectPending) return;
    if (XDialVpnService.active) stopVPN();
    else startVPN();
  }

  void pickScenario() {
    JSONObject p = profileObj();
    JSONArray a = p.optJSONArray("scenarios");
    if (a == null || a.length() == 0) return;
    String active = p.optString("active_scenario_id");
    boolean anyWifi = false;
    Ui.Sheet s = ui.sheet("场景", null);
    for (int i = 0; i < a.length(); i++) {
      JSONObject sc = a.optJSONObject(i);
      if (sc == null) continue;
      String id = sc.optString("id");
      String desc = Profiles.routeText(p, sc);
      JSONArray ssids = sc.optJSONArray("match_ssids");
      if (ssids != null && ssids.length() > 0) {
        anyWifi = true;
        desc += " · 连上「" + ssids.optString(0) + "」" + (ssids.length() > 1 ? "等" : "") + "时自动使用";
      }
      s.option(
          0,
          sc.optString("name"),
          desc,
          id.equals(active),
          () -> {
            if (id.equals(active)) return;
            try {
              JSONObject next = profileObj();
              next.put("active_scenario_id", id);
              commit(next);
            } catch (JSONException e) {
              alert("无法切换场景", e.getMessage());
            }
          });
    }
    if (anyWifi) s.note("连着绑定的 Wi-Fi 时自动用对应场景，离开后回到这里选的场景。");
    s.show();
  }

  void pickExit() {
    JSONObject p = profileObj();
    JSONObject scenario = shownScenario(p);
    if (scenario == null) return;
    String scenarioId = scenario.optString("id");
    String exitId = exitLineId(scenario);
    Ui.Sheet s = ui.sheet("默认出口", "「" + scenario.optString("name") + "」里没命中分流规则的流量走这里");
    s.option(0, "直连", "不经过任何线路", exitId.equals("direct"), () -> setExit(scenarioId, "direct"));
    JSONArray lines = p.optJSONArray("lines");
    if (lines != null)
      for (int i = 0; i < lines.length(); i++) {
        JSONObject l = lines.optJSONObject(i);
        if (l == null || l.optString("type").equals("direct")) continue;
        String id = l.optString("id");
        s.option(0, l.optString("name", id), Profiles.summary(l), id.equals(exitId), () -> setExit(scenarioId, id));
      }
    s.show();
  }

  void setExit(String scenarioId, String lineId) {
    JSONObject p = profileObj();
    JSONObject scenario = Profiles.scenario(p, scenarioId);
    if (scenario == null || lineId.equals(exitLineId(scenario))) return;
    try {
      scenario.put("default_line_id", lineId);
      scenario.remove("default_subscription_id");
      commit(p);
    } catch (JSONException e) {
      alert("无法修改出口", e.getMessage());
    }
  }

  // ---------------------------------------------------------------- stay connected

  SharedPreferences prefs() {
    return getSharedPreferences("xdial", MODE_PRIVATE);
  }

  boolean alwaysOnKnown() {
    return XDialVpnService.active ? XDialVpnService.alwaysOn : prefs().getBoolean("always_on", false);
  }

  boolean lockdownKnown() {
    return XDialVpnService.active ? XDialVpnService.lockdown : prefs().getBoolean("lockdown", false);
  }

  boolean batteryUnrestricted() {
    return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
  }

  boolean granted(String permission) {
    return checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
  }

  boolean locationOn() {
    android.location.LocationManager lm = getSystemService(android.location.LocationManager.class);
    return lm == null || Build.VERSION.SDK_INT < 28 || lm.isLocationEnabled();
  }

  /** Wi-Fi names are readable in the background: location permission (always) and location on. */
  boolean wifiReadable() {
    boolean fine = granted("android.permission.ACCESS_FINE_LOCATION");
    boolean bg = Build.VERSION.SDK_INT < 29 || granted("android.permission.ACCESS_BACKGROUND_LOCATION");
    return fine && bg && locationOn();
  }

  /** {done, total} for the 保持常连 checklist. */
  int[] keepAliveScore(JSONObject p) {
    boolean wifi = !Profiles.ssidRules(p).isEmpty();
    int total = wifi ? 3 : 2;
    int done = (alwaysOnKnown() ? 1 : 0) + (batteryUnrestricted() ? 1 : 0) + (wifi && wifiReadable() ? 1 : 0);
    return new int[] {done, total};
  }

  /** Current Wi-Fi name, or null when not on Wi-Fi or the name cannot be read. */
  @SuppressWarnings("deprecation")
  String currentWifi() {
    if (XDialVpnService.active && !XDialVpnService.wifi.isEmpty()) return XDialVpnService.wifi;
    try {
      WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
      android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
      return info == null ? null : Profiles.cleanSsid(info.getSSID());
    } catch (Exception e) {
      return null;
    }
  }

  void keepAlivePage() {
    keepAliveVisible = true;
    shownAlwaysOn = alwaysOnKnown();
    if (XDialVpnService.active)
      startService(new Intent(this, XDialVpnService.class).setAction("REFRESH"));
    LinearLayout page = ui.page(host, false).column;
    ui.nav(page, "保持常连", this::goBack);
    JSONObject p = profileObj();
    int[] score = keepAliveScore(p);
    ui.summary(
        page,
        score[0],
        score[1],
        score[0] >= score[1] ? "已全部设置好" : "还差 " + (score[1] - score[0]) + " 项",
        "设置好后，熄屏、换网络、重启都会自动连回");

    ui.groupHeader(page, "系统设置");
    LinearLayout g = ui.group(page, 0);
    boolean always = alwaysOnKnown();
    ui.item(
        g,
        R.drawable.ic_key,
        always ? ui.green : ui.accent,
        always ? ui.greenSoft : ui.accentSoft,
        "始终开启的 VPN",
        always || XDialVpnService.active ? "重启或被系统清理后自动连回" : "连接后才能确认是否已开启",
        always ? ui.done("已开启") : ui.go("去设置"),
        () -> openSettings(new Intent(android.provider.Settings.ACTION_VPN_SETTINGS)));
    boolean battery = batteryUnrestricted();
    ui.item(
        g,
        R.drawable.ic_battery,
        battery ? ui.green : ui.accent,
        battery ? ui.greenSoft : ui.accentSoft,
        "电池不限制",
        "防止熄屏后被省电策略关掉",
        battery ? ui.done("已允许") : ui.go("去允许"),
        this::requestBattery);
    if (!Profiles.ssidRules(p).isEmpty()) {
      boolean readable = wifiReadable();
      String why =
          readable
              ? "按 Wi-Fi 切换场景时需要"
              : !granted("android.permission.ACCESS_FINE_LOCATION")
                  ? "需要位置权限；XDial 只读 Wi-Fi 名称"
                  : !locationOn() ? "需要打开系统定位" : "位置权限需要选「始终允许」";
      ui.item(
          g,
          R.drawable.ic_wifi,
          readable ? ui.green : ui.accent,
          readable ? ui.greenSoft : ui.accentSoft,
          "读取 Wi-Fi 名称",
          why,
          readable ? ui.done("已授权") : ui.go("去授权"),
          this::requestWifiAccess);
    }
    if (lockdownKnown()) {
      TextView warn = ui.note(page, "系统已打开「屏蔽未使用 VPN 的连接」：VPN 断开时手机会完全没网，建议关掉。");
      warn.setTextColor(ui.red);
    } else ui.note(page, "别打开系统的「屏蔽未使用 VPN 的连接」，断线时会完全没网。");
    if (Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("samsung"))
      ui.note(page, "三星手机：还要在电池设置里把 XDial 移出「休眠应用」。");

    JSONArray scenarios = p.optJSONArray("scenarios");
    if (scenarios == null || scenarios.length() < 2) return;
    ui.groupHeader(page, "按 Wi-Fi 自动切换场景");
    LinearLayout w = ui.group(page, 0);
    String here = currentWifi();
    for (int i = 0; i < scenarios.length(); i++) {
      JSONObject sc = scenarios.optJSONObject(i);
      JSONArray ssids = sc == null ? null : sc.optJSONArray("match_ssids");
      if (ssids == null) continue;
      String route = Profiles.routeText(p, sc);
      for (int j = 0; j < ssids.length(); j++) {
        String name = ssids.optString(j);
        if (name.isEmpty()) continue;
        boolean now = name.equals(here);
        ui.item(
            w,
            R.drawable.ic_wifi,
            now ? ui.accent : ui.secondary,
            now ? ui.accentSoft : ui.tone,
            name,
            "切到「" + sc.optString("name") + "」，" + route,
            ui.cluster(now ? ui.tag("当前", ui.green, ui.greenSoft) : null, ui.chevron()),
            () -> editWifi(name));
      }
    }
    LinearLayout add =
        ui.item(w, R.drawable.ic_plus, ui.accent, ui.accentSoft, "添加 Wi-Fi", null, null, () -> editWifi(null));
    ((TextView) ((LinearLayout) add.getChildAt(1)).getChildAt(0)).setTextColor(ui.accent);
    JSONObject chosen = Profiles.activeScenario(p);
    ui.note(
        page,
        Profiles.ssidRules(p).isEmpty()
            ? "例如在公司 Wi-Fi 下自动全部直连，离开后自动切回。"
            : "离开这些 Wi-Fi 后，回到「" + (chosen == null ? "默认场景" : chosen.optString("name")) + "」。");
  }

  /** Add (name null) or change a Wi-Fi that switches the scenario. */
  void editWifi(String name) {
    JSONObject p = profileObj();
    JSONArray scenarios = p.optJSONArray("scenarios");
    if (scenarios == null || scenarios.length() == 0) return;
    String[] labels = new String[scenarios.length()];
    String[] ids = new String[scenarios.length()];
    String active = p.optString("active_scenario_id");
    int selected = -1, fallback = -1;
    for (int i = 0; i < scenarios.length(); i++) {
      JSONObject sc = scenarios.optJSONObject(i);
      labels[i] = sc == null ? "" : sc.optString("name");
      ids[i] = sc == null ? "" : sc.optString("id");
      JSONArray ssids = sc == null ? null : sc.optJSONArray("match_ssids");
      boolean bound = ssids != null && ssids.length() > 0;
      if (name != null && bound)
        for (int j = 0; j < ssids.length(); j++) if (name.equals(ssids.optString(j))) selected = i;
      if (bound && fallback < 0) fallback = i;
    }
    if (selected < 0) selected = fallback;
    if (selected < 0) for (int i = 0; i < ids.length && selected < 0; i++) if (!ids[i].equals(active)) selected = i;

    Ui.Sheet s = ui.sheet(name == null ? "添加 Wi-Fi" : "修改 Wi-Fi", "连上这个 Wi-Fi 时自动切换场景");
    LinearLayout f = s.form();
    EditText field = ui.field(f, "Wi-Fi 名称", name == null ? "" : name, "和手机里显示的一致", TEXT);
    String here = currentWifi();
    if (here != null && !here.equals(name))
      ui.chips(
          f,
          new String[] {"填入当前 Wi-Fi：" + here},
          0,
          i -> {
            field.setText(here);
            field.setSelection(here.length());
          });
    ui.label(f, "连上时切到");
    int[] pick = ui.choice(f, labels, selected);
    s.note("名称要和手机 Wi-Fi 列表里显示的完全一致，区分大小写。");
    Ui.Btn save =
        stay(
            name == null ? "添加" : "保存",
            PRIMARY,
            () -> {
              String value = field.getText().toString().trim();
              if (value.isEmpty()) {
                toast("请填写 Wi-Fi 名称");
                return;
              }
              s.dialog.dismiss();
              hideKeyboard();
              saveWifi(name, value, ids[pick[0]]);
            });
    if (name == null) s.buttons(btn("取消", PLAIN, null), save);
    else s.buttons(btn("删除", DANGER, () -> saveWifi(name, null, null)), save);
    s.show();
  }

  /** Moves a Wi-Fi name to a scenario (or removes it) and lets a running connection follow. */
  void saveWifi(String old, String name, String scenarioId) {
    JSONObject p = profileObj();
    JSONArray scenarios = p.optJSONArray("scenarios");
    try {
      for (int i = 0; scenarios != null && i < scenarios.length(); i++) {
        JSONObject sc = scenarios.getJSONObject(i);
        JSONArray before = sc.optJSONArray("match_ssids");
        JSONArray after = new JSONArray();
        if (before != null)
          for (int j = 0; j < before.length(); j++) {
            String n = before.optString(j);
            if (!n.isEmpty() && !n.equals(old) && !n.equals(name)) after.put(n);
          }
        if (name != null && sc.optString("id").equals(scenarioId)) after.put(name);
        if (after.length() == 0) sc.remove("match_ssids");
        else sc.put("match_ssids", after);
      }
      saveProfile(p.toString());
    } catch (Exception e) {
      alert("无法保存", Errors.friendly(e.getMessage()));
      return;
    }
    if (XDialVpnService.active)
      startService(new Intent(this, XDialVpnService.class).setAction("REFRESH"));
    render();
    toast(name == null ? "已删除" : "已保存");
    if (name != null && !wifiReadable()) requestWifiAccess();
  }

  void openSettings(Intent i) {
    try {
      startActivity(i);
    } catch (ActivityNotFoundException e) {
      alert("无法打开设置", "请在系统设置里手动查找。");
    }
  }

  @SuppressWarnings("BatteryLife")
  void requestBattery() {
    if (batteryUnrestricted()) {
      openSettings(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
      return;
    }
    Intent i =
        new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + getPackageName()));
    try {
      startActivity(i);
    } catch (ActivityNotFoundException e) {
      openSettings(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }
  }

  void requestWifiAccess() {
    if (!granted("android.permission.ACCESS_FINE_LOCATION")) {
      ui.dialog(
          "读取 Wi-Fi 名称",
          "Android 规定读取 Wi-Fi 名称需要位置权限。XDial 只用它判断是否连着公司 Wi-Fi，不使用定位。\n\n下一步请保留「精确」，选择「使用时允许」。",
          true,
          btn("取消", PLAIN, null),
          btn(
              "继续",
              PRIMARY,
              () ->
                  requestPermissions(
                      new String[] {
                        "android.permission.ACCESS_FINE_LOCATION",
                        "android.permission.ACCESS_COARSE_LOCATION"
                      },
                      REQ_LOC)));
    } else if (Build.VERSION.SDK_INT >= 29
        && !granted("android.permission.ACCESS_BACKGROUND_LOCATION")) {
      ui.dialog(
          "还差一步",
          "XDial 在后台也要知道连的是哪个 Wi-Fi。下一页请选择「始终允许」。",
          true,
          btn("取消", PLAIN, null),
          btn(
              "继续",
              PRIMARY,
              () ->
                  requestPermissions(
                      new String[] {"android.permission.ACCESS_BACKGROUND_LOCATION"}, REQ_BG)));
    } else if (!locationOn()) {
      ui.dialog(
          "请打开定位",
          "系统关闭定位时，任何应用都读不到 Wi-Fi 名称。",
          true,
          btn("取消", PLAIN, null),
          btn(
              "去打开",
              PRIMARY,
              () ->
                  openSettings(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))));
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == REQ_LOC || request == REQ_BG) {
      if (XDialVpnService.active)
        startService(new Intent(this, XDialVpnService.class).setAction("REFRESH"));
      if (keepAliveVisible) render();
      if (request == REQ_LOC && granted("android.permission.ACCESS_FINE_LOCATION") && !wifiReadable())
        requestWifiAccess();
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
    if (ui.wide) open(() -> lineEditor(null, "vpn"));
    else show(() -> lineEditor(null, "vpn"));
  }

  static String typeNote(String type) {
    switch (type) {
      case "vpn":
        return "兼容 Cisco AnyConnect 与 ocserv 服务器";
      case "tailscale":
        return "通过你的 Tailnet 访问设备或出口节点";
      case "selector":
      case "urltest":
        return "来自导入的配置，请在电脑端修改";
      default:
        return "不用先连接，就能测试这条线路";
    }
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
    Ui.Page pg = ui.page(host, !ui.wide);
    LinearLayout page = pg.column;
    LinearLayout nav = ui.nav(page, id == null ? "添加线路" : "编辑线路", this::goBack);
    int typeIndex = Arrays.asList(Profiles.TYPES).indexOf(type);
    if (id == null)
      ui.chips(page, TYPE_LABELS, typeIndex, i -> replace(() -> lineEditor(null, Profiles.TYPES[i])));
    else ui.typeHeader(page, Profiles.badge(type), badgeColor(type), Profiles.typeLabel(type), typeNote(type));

    EditText name = ui.field(page, "名称", l.optString("name"), defaultName(type), TEXT);
    EditText server = null, port = null, user = null, secret = null, sni = null, exitNode = null;
    Toggle insecure = null, magic = null;
    final String[] method = {l.optString("ss_method", SS_METHODS[0])};
    String prefix = type.equals("shadowsocks") ? "ss" : type;
    switch (type) {
      case "vpn":
        server = ui.field(page, "服务器地址", l.optString("vpn_server"), "vpn.example.com:8443", URI);
        ui.note(page, "端口不是 443 时要带上");
        user = ui.field(page, "用户名", l.optString("vpn_username"), "必填", EMAIL);
        secret = ui.field(page, "密码", l.optString("vpn_password"), "必填", SECRET);
        break;
      case "shadowsocks":
      case "trojan":
      case "vmess":
      case "anytls":
        LinearLayout row = ui.fieldRow(page);
        server = ui.field(row, "服务器地址", l.optString(prefix + "_server"), "域名或 IP", URI);
        port =
            ui.field(row, "端口", portText(l.optInt(prefix + "_port")), String.valueOf(defaultPort(type)), NUMBER);
        ui.weight(port, 0.42f);
        if (type.equals("shadowsocks")) {
          final TextView[] m = new TextView[1];
          m[0] = ui.pickField(page, "加密方式", method[0], () -> pickMethod(method, m[0]));
        }
        if (type.equals("vmess")) user = ui.field(page, "UUID", l.optString("vmess_uuid"), "xxxxxxxx-xxxx-…", TEXT);
        else secret = ui.field(page, "密码", l.optString(prefix + "_password"), "必填", SECRET);
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
            page,
            "设为默认出口",
            "没命中分流规则的流量走这条",
            wasExit || (id == null && currentExit.equals("direct")),
            16);

    if (type.equals("trojan") || type.equals("anytls") || type.equals("vpn")) {
      LinearLayout adv = new LinearLayout(this);
      adv.setOrientation(LinearLayout.VERTICAL);
      if (!type.equals("vpn"))
        sni = ui.field(adv, "SNI", l.optString(type + "_sni"), "默认与服务器地址相同", URI);
      insecure = ui.toggleCard(adv, "跳过证书验证", "仅在服务器使用自签证书时开启", l.optBoolean("allow_insecure"), 10);
      boolean open = l.optBoolean("allow_insecure") || !l.optString(type + "_sni").isEmpty();
      Ui.Row row =
          ui.rowCard(
              page,
              "高级选项",
              type.equals("vpn") ? "跳过证书验证 · 关" : "SNI、证书验证",
              10,
              () -> {});
      adv.setVisibility(open ? View.VISIBLE : View.GONE);
      row.view.setVisibility(open ? View.GONE : View.VISIBLE);
      row.view.setOnClickListener(
          v -> {
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
      result[0].setGravity(Gravity.CENTER);
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
    if (id != null) action(pg, nav, "删除", DANGER, 0.55f, () -> deleteLine(id));
    if (!group) action(pg, nav, "保存", PRIMARY, 1f, save);
  }

  void pickMethod(String[] method, TextView label) {
    Ui.Sheet s = ui.sheet("加密方式", null);
    for (String m : SS_METHODS)
      s.option(
          0,
          m,
          null,
          m.equals(method[0]),
          () -> {
            method[0] = m;
            label.setText(m);
          });
    s.show();
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
            result = ms >= 0 ? "可用 · 延迟 " + ms + " ms" : "可用";
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
    JSONObject l = Profiles.line(p, id);
    ui.dialog(
        "删除「" + (l == null ? id : l.optString("name", id)) + "」？",
        "删除后不能恢复。如果它是默认出口，会改为直连。",
        true,
        btn("取消", PLAIN, null),
        btn(
            "删除",
            DANGER,
            () -> {
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
            }));
  }

  void allLines() {
    LinearLayout page = ui.page(host, false).column;
    ui.nav(page, "全部线路", this::goBack);
    JSONObject p = profileObj();
    JSONObject scenario = shownScenario(p);
    String exitId = exitLineId(scenario);
    View gap = new View(this);
    page.addView(gap, new LinearLayout.LayoutParams(1, ui.dp(6)));
    JSONArray lines = p.optJSONArray("lines");
    if (lines != null)
      for (int i = 0; i < lines.length(); i++) {
        JSONObject line = lines.optJSONObject(i);
        if (line != null && !line.optString("type").equals("direct"))
          lineCard(page, p, scenario, line, exitId);
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
    Ui.Page pg = ui.page(host, !ui.wide);
    LinearLayout nav = ui.nav(pg.column, "粘贴配置", this::goBack);
    EditText box = editor(pg.column, "", "把配置文本粘贴到这里\n支持 XDial、Clash、Surge");
    ui.chips(
        pg.column,
        new String[] {"从剪贴板粘贴"},
        0,
        i -> {
          ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
          CharSequence t = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
          if (t == null || t.length() == 0) toast("剪贴板是空的");
          else box.setText(t);
        });
    ui.note(pg.column, "导入会替换当前全部线路和场景。");
    action(
        pg,
        nav,
        "导入",
        PRIMARY,
        1f,
        () -> {
          String t = box.getText().toString();
          if (t.trim().isEmpty()) alert("请先粘贴配置", "支持 XDial JSON、Clash YAML 和 Surge 配置文本。");
          else confirmImport(t);
        });
  }

  void jsonPage() {
    Ui.Page pg = ui.page(host, !ui.wide);
    LinearLayout nav = ui.nav(pg.column, "完整配置", this::goBack);
    String pretty;
    try {
      pretty = new JSONObject(profile).toString(2);
    } catch (JSONException e) {
      pretty = profile;
    }
    EditText box = editor(pg.column, pretty, "");
    ui.note(pg.column, "包含密码，请勿截图分享。保存前会检查格式，出错不会覆盖原配置。");
    action(
        pg,
        nav,
        "保存",
        PRIMARY,
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
    JSONObject p = profileObj();
    JSONArray scenarios = p.optJSONArray("scenarios");
    int lines = Profiles.lineCount(p), sc = scenarios == null ? 0 : scenarios.length();
    ui.dialog(
        "导入并替换当前配置？",
        "当前的 " + lines + " 条线路和 " + sc + " 个场景会被替换。配置里没有的密码，导入后到线路里填写。",
        true,
        btn("取消", PLAIN, null),
        btn("导入", PRIMARY, () -> importText(text)));
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
    ui.dialog(
        "重置全部配置？",
        "会删除所有线路、场景和规则，恢复为只有直连的初始状态。",
        true,
        btn("取消", PLAIN, null),
        btn(
            "重置",
            DANGER,
            () -> {
              try {
                commit(new JSONObject(ProfileStore.DEFAULT));
              } catch (JSONException e) {
                alert("无法重置", e.getMessage());
              }
            }));
  }

  // ---------------------------------------------------------------- info pages

  /** What the phone is connected to underneath the VPN. */
  @SuppressWarnings("deprecation")
  String networkLabel() {
    ConnectivityManager cm = getSystemService(ConnectivityManager.class);
    boolean wifi = false, cell = false, other = false;
    try {
      for (Network n : cm.getAllNetworks()) {
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        if (c == null
            || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
        else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true;
        else other = true;
      }
    } catch (Exception ignored) {
    }
    if (wifi) {
      String name = currentWifi();
      return name != null ? "Wi-Fi「" + name + "」" : wifiReadable() ? "Wi-Fi" : "Wi-Fi（名称需要位置权限）";
    }
    if (cell) return "移动数据";
    return other ? "有线或其他网络" : "没有网络";
  }

  void diagnosticsPage() {
    Ui.Page pg = ui.page(host, !ui.wide);
    LinearLayout page = pg.column;
    LinearLayout nav = ui.nav(page, "连接诊断", this::goBack);
    JSONObject p = profileObj();
    boolean active = XDialVpnService.active;
    String state = XDialVpnService.state;
    String scenario = active ? XDialVpnService.runningScenario : summaryScenario;
    String network = networkLabel();
    String[] head = XDialVpnService.headline;
    String lineText =
        active && head != null && head.length == 3 && !head[0].isEmpty()
            ? head[0] + (head[1].isEmpty() ? "" : " · " + head[1])
            : Profiles.exitName(p);
    long since = XDialVpnService.connectedAt;
    String sinceText =
        since > 0 ? "今天 " + new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(since)) : "—";
    int[] keep = keepAliveScore(p);
    String keepText = keep[0] >= keep[1] ? "已设置好" : "还差 " + (keep[1] - keep[0]) + " 项";

    ui.groupHeader(page, "当前状态");
    LinearLayout g = ui.group(page, 0);
    TextView st = ui.kv(g, "状态", null, "● " + state);
    st.setTextColor(
        XDialVpnService.phase == XDialVpnService.CONNECTED
            ? ui.green
            : XDialVpnService.phase == XDialVpnService.FAILED ? ui.red : ui.secondary);
    ui.kv(g, "运行场景", null, scenario.isEmpty() ? "—" : scenario);
    ui.kv(g, "当前网络", null, network);
    ui.kv(g, "线路", null, lineText);
    if (since > 0) ui.kv(g, "连接于", null, sinceText);
    ui.kv(g, "保持常连", null, keepText);
    ui.kv(g, "版本", null, VERSION);

    String raw = XDialVpnService.safeError;
    ui.groupHeader(page, "最近一次错误");
    LinearLayout e = ui.card(page, 20, 0);
    e.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
    if (raw.isEmpty()) e.addView(ui.text("无", 15, ui.secondary));
    else {
      e.addView(ui.bold(ui.text(Errors.friendly(raw), 15.5f, ui.red)));
      TextView detail = ui.text(raw, 12, ui.text);
      detail.setTypeface(Typeface.MONOSPACE);
      detail.setTextIsSelectable(true);
      detail.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
      detail.setBackground(ui.rounded(ui.tone, 12));
      e.addView(detail, ui.margins(-1, -2, 0, 10, 0, 0));
    }
    ui.note(page, "错误信息可能包含服务器地址，分享前请检查。");
    String report =
        "XDial " + VERSION + "\n状态：" + state + "\n运行场景：" + scenario + "\n当前网络：" + network
            + "\n线路：" + lineText + "\n连接于：" + sinceText + "\n保持常连：" + keepText
            + "\n错误：" + (raw.isEmpty() ? "无" : raw);
    action(
        pg,
        nav,
        "复制诊断信息",
        PRIMARY,
        1f,
        () -> {
          getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("XDial", report));
          toast("已复制");
        });
  }

  void aboutPage() {
    LinearLayout page = ui.page(host, false).column;
    ui.nav(page, "关于", this::goBack);
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.xdial_icon);
    icon.setClipToOutline(true);
    icon.setBackground(ui.rounded(0xff167d8d, 24));
    LinearLayout.LayoutParams il = ui.margins(ui.dp(78), ui.dp(78), 0, 22, 0, 12);
    il.gravity = Gravity.CENTER_HORIZONTAL;
    page.addView(icon, il);
    TextView name = ui.bold(ui.text("XDial", 22, ui.text));
    name.setGravity(Gravity.CENTER);
    page.addView(name);
    TextView ver = ui.text(VERSION + " · 非官方 Android 移植", 13, ui.secondary);
    ver.setGravity(Gravity.CENTER);
    ver.setPadding(0, ui.dp(4), 0, 0);
    page.addView(ver);
    LinearLayout g = ui.group(page, 24);
    ui.item(g, 0, 0, 0, "开源许可", null, ui.chevron(), () -> show(this::licensePage));
    ui.kv(g, "基于", null, "XDial v0.9.1 源码");
    ui.kv(g, "包名", null, getPackageName());
    ui.kv(g, "系统要求", null, "Android 8.0 及以上");
    ui.note(page, "不含任何 VPN 账号、节点或订阅。配置经 Android Keystore 加密，只保存在这台手机上；卸载会一并删除。Android 同一时间只能开一个 VPN。");
  }

  void licensePage() {
    LinearLayout page = ui.page(host, false).column;
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
                  ui.dialog(
                      "登录 Tailscale",
                      "在浏览器里完成登录后回到这里，点「完成」。",
                      false,
                      btn("取消", PLAIN, this::stopLogin),
                      stay(
                          "打开登录页",
                          PLAIN,
                          () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))),
                      btn("完成", PRIMARY, this::stopLogin));
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
    if (isFinishing()) return;
    ui.dialog(
        title,
        message == null || message.isEmpty() ? "操作失败，请重试。" : message,
        true,
        btn("好", PLAIN, null));
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
  }
}

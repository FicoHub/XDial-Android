package dev.xdial.port;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * "Color block card" building blocks: tonal surfaces, a state-colored hero card, grouped lists,
 * outlined floating-label fields, bottom sheets and dialogs. Colors follow the system dark mode.
 * Screens at least 600dp wide (an unfolded Fold) use two panes; see {@link #wide}.
 */
final class Ui {
  static final int PRIMARY = 0, DANGER = 1, PLAIN = 2;
  private static final Object SHEET = new Object();

  final Activity a;
  final boolean dark, wide;
  final int bg, card, tone, text, secondary, line, chevron, accent, accentSoft, onFill, sheet;
  final int green, greenSoft, red, redSoft, orange, orangeSoft;
  final int heroStart, heroEnd;

  Ui(Activity a) {
    this.a = a;
    Configuration c = a.getResources().getConfiguration();
    dark = (c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    wide = c.screenWidthDp >= 600;
    bg = dark ? 0xff0f1114 : 0xfff5f6fa;
    card = dark ? 0xff1a1d22 : 0xffffffff;
    tone = dark ? 0xff23282f : 0xffe9edf3;
    text = dark ? 0xffeef1f5 : 0xff141820;
    secondary = dark ? 0xff9aa2ae : 0xff646c79;
    line = dark ? 0xff2a2f37 : 0xffe5e8ee;
    chevron = dark ? 0xff626a75 : 0xffa0a7b2;
    accent = dark ? 0xff5fcfdd : 0xff0f6e7e;
    accentSoft = dark ? 0xff163a40 : 0xffd8eef1;
    onFill = dark ? 0xff052a30 : 0xffffffff;
    sheet = dark ? 0xff1d2126 : 0xffffffff;
    green = dark ? 0xff4ad38a : 0xff14915a;
    greenSoft = dark ? 0xff16342a : 0xffddf2e7;
    red = dark ? 0xffff8a8c : 0xffc93b3f;
    redSoft = dark ? 0xff3b1d1f : 0xfffbe4e4;
    orange = dark ? 0xfff0b45a : 0xffa86400;
    orangeSoft = dark ? 0xff3a2c14 : 0xfffbefd8;
    heroStart = dark ? 0xff1a95a7 : 0xff13889a;
    heroEnd = dark ? 0xff0c4a60 : 0xff0b5e78;
  }

  int dp(float n) {
    return (int) (n * a.getResources().getDisplayMetrics().density + 0.5f);
  }

  int hair() {
    return Math.max(1, dp(0.7f));
  }

  // ------------------------------------------------------------------ window & pages

  /** Edge-to-edge root that pads itself for the status bar, navigation bar and keyboard. */
  FrameLayout root() {
    FrameLayout root = new FrameLayout(a);
    root.setBackgroundColor(bg);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int top, bottom, left, right;
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            top = bars.top;
            left = bars.left;
            right = bars.right;
            bottom = Math.max(bars.bottom, ime.bottom);
          } else {
            top = insets.getSystemWindowInsetTop();
            left = insets.getSystemWindowInsetLeft();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
          }
          v.setPadding(left, top, right, bottom);
          return insets;
        });
    return root;
  }

  @SuppressWarnings("deprecation")
  void styleWindow() {
    Window w = a.getWindow();
    w.setStatusBarColor(Color.TRANSPARENT);
    w.setNavigationBarColor(Color.TRANSPARENT);
    w.setBackgroundDrawable(new ColorDrawable(bg));
    if (Build.VERSION.SDK_INT >= 30) {
      w.setDecorFitsSystemWindows(false);
      WindowInsetsController c = w.getInsetsController();
      if (c != null) {
        int light =
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        c.setSystemBarsAppearance(dark ? 0 : light, light);
      }
    } else {
      int flags =
          View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
      if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
      w.getDecorView().setSystemUiVisibility(flags);
    }
  }

  /** A scrollable column (capped at a readable width) and, optionally, a pinned bottom bar. */
  static final class Page {
    LinearLayout column;
    LinearLayout bar;
  }

  Page page(FrameLayout root, boolean withBar) {
    Page p = new Page();
    ScrollView scroll = new ScrollView(a);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    FrameLayout center = new FrameLayout(a);
    scroll.addView(center, new FrameLayout.LayoutParams(-1, -2));
    LinearLayout column = new LinearLayout(a);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(14), dp(4), dp(14), dp(withBar ? 104 : 28));
    center.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
    p.column = column;
    if (withBar) {
      LinearLayout bar = new LinearLayout(a);
      bar.setOrientation(LinearLayout.HORIZONTAL);
      bar.setPadding(dp(16), dp(18), dp(16), dp(16));
      GradientDrawable fade =
          new GradientDrawable(
              GradientDrawable.Orientation.BOTTOM_TOP, new int[] {bg, bg, bg & 0x00ffffff});
      bar.setBackground(fade);
      root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
      p.bar = bar;
    }
    // Tablets and large panes: keep the column at a phone-like width.
    Object old = root.getTag();
    if (old instanceof View.OnLayoutChangeListener)
      root.removeOnLayoutChangeListener((View.OnLayoutChangeListener) old);
    View.OnLayoutChangeListener fit =
        (v, l, t, r, b, ol, ot, or, ob) -> fitWidth(r - l, column, p.bar);
    root.addOnLayoutChangeListener(fit);
    root.setTag(fit);
    fitWidth(root.getWidth(), column, p.bar);
    return p;
  }

  private void fitWidth(int width, View column, View bar) {
    if (width <= 0) return;
    int want = width > dp(640) ? dp(640) : -1;
    for (View view : new View[] {column, bar}) {
      if (view == null) continue;
      FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
      if (lp.width != want) {
        lp.width = want;
        lp.gravity = view == column ? Gravity.CENTER_HORIZONTAL : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        view.post(() -> view.setLayoutParams(lp));
      }
    }
  }

  // ------------------------------------------------------------------ primitives

  TextView text(String value, float sp, int color) {
    TextView t = new TextView(a);
    t.setText(value);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setIncludeFontPadding(true);
    return t;
  }

  TextView bold(TextView t) {
    t.setTypeface(Typeface.DEFAULT_BOLD);
    return t;
  }

  TextView medium(TextView t) {
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  ImageView icon(int res, int color, int sizeDp) {
    ImageView i = new ImageView(a);
    i.setImageResource(res);
    i.setImageTintList(ColorStateList.valueOf(color));
    i.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
    return i;
  }

  /** An icon centered on a filled shape (circle when radius is negative). */
  FrameLayout iconBox(int res, int fg, int fill, int boxDp, int iconDp, float radius) {
    FrameLayout box = new FrameLayout(a);
    box.setBackground(radius < 0 ? oval(fill) : rounded(fill, radius));
    ImageView i = icon(res, fg, iconDp);
    box.addView(i, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
    box.setLayoutParams(new LinearLayout.LayoutParams(dp(boxDp), dp(boxDp)));
    return box;
  }

  GradientDrawable rounded(int color, float radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  GradientDrawable stroked(int fill, int stroke, float width, float radius) {
    GradientDrawable d = rounded(fill, radius);
    d.setStroke(Math.max(1, dp(width)), stroke);
    return d;
  }

  GradientDrawable oval(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setShape(GradientDrawable.OVAL);
    d.setColor(color);
    return d;
  }

  Drawable ripple(Drawable content, float radius) {
    int pressed = dark ? 0x33ffffff : 0x1f000000;
    return new RippleDrawable(ColorStateList.valueOf(pressed), content, rounded(0xffffffff, radius));
  }

  void clickable(View v, Drawable background, float radius, Runnable onClick) {
    v.setBackground(ripple(background, radius));
    v.setOnClickListener(x -> onClick.run());
  }

  LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
    lp.setMargins(dp(l), dp(t), dp(r), dp(b));
    return lp;
  }

  View chevron() {
    ImageView c = icon(R.drawable.ic_chev, chevron, 18);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(18));
    lp.leftMargin = dp(6);
    c.setLayoutParams(lp);
    return c;
  }

  // ------------------------------------------------------------------ page chrome

  /** Home header: large title and an overflow button. */
  ImageView header(LinearLayout page, String title) {
    LinearLayout row = new LinearLayout(a);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(8), dp(14), 0, dp(8));
    TextView t = bold(text(title, 26, text));
    row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView more = icon(R.drawable.ic_more, secondary, 24);
    more.setPadding(dp(10), dp(10), dp(10), dp(10));
    more.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
    more.setContentDescription("更多");
    more.setBackground(ripple(null, 22));
    row.addView(more);
    page.addView(row);
    return more;
  }

  /** Pane header used by the right pane on wide screens: title with an accent action. */
  void paneHeader(LinearLayout page, String title, String action, Runnable onAction) {
    LinearLayout row = new LinearLayout(a);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(8), dp(18), dp(4), dp(12));
    row.addView(bold(text(title, 21, text)), new LinearLayout.LayoutParams(0, -2, 1));
    TextView act = bold(text(action, 14.5f, accent));
    act.setPadding(dp(8), dp(6), dp(8), dp(6));
    clickable(act, null, 12, onAction);
    row.addView(act);
    page.addView(row);
  }

  /** Sub-page header: back arrow and title. Returns the row so actions can sit on the right. */
  LinearLayout nav(LinearLayout page, String title, Runnable back) {
    LinearLayout row = new LinearLayout(a);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(8), 0, dp(6));
    ImageView b = icon(R.drawable.ic_back, text, 24);
    b.setPadding(dp(10), dp(10), dp(10), dp(10));
    b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
    b.setContentDescription("返回");
    b.setBackground(ripple(null, 22));
    b.setOnClickListener(v -> back.run());
    row.addView(b);
    TextView t = bold(text(title, 21, text));
    t.setPadding(dp(6), 0, 0, 0);
    t.setSingleLine(true);
    t.setEllipsize(TextUtils.TruncateAt.END);
    row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    page.addView(row);
    return row;
  }

  /** Compact action in a page header (wide screens put 保存 / 删除 here instead of a bottom bar). */
  TextView navButton(LinearLayout row, String title, int kind, Runnable onClick) {
    TextView b = bold(text(title, 14.5f, kind == PRIMARY ? onFill : kind == DANGER ? red : accent));
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(16), 0, dp(16), 0);
    b.setMinWidth(dp(56));
    clickable(b, kind == PRIMARY ? rounded(accent, 999) : null, 999, onClick);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
    lp.leftMargin = dp(6);
    row.addView(b, lp);
    return b;
  }

  /** Section title with an optional accent action on the right. */
  TextView sectionHeader(LinearLayout page, String title, String action, Runnable onAction) {
    LinearLayout row = new LinearLayout(a);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(8), dp(20), dp(4), dp(8));
    row.addView(bold(text(title, 15, text)), new LinearLayout.LayoutParams(0, -2, 1));
    TextView act = null;
    if (action != null) {
      act = bold(text(action, 14, accent));
      act.setPadding(dp(8), dp(6), dp(8), dp(6));
      clickable(act, null, 12, onAction);
      row.addView(act);
    }
    page.addView(row);
    return act;
  }

  /** Small grey title above a grouped list. */
  void groupHeader(LinearLayout page, String title) {
    TextView t = medium(text(title, 13, secondary));
    t.setPadding(dp(10), dp(22), dp(10), dp(8));
    page.addView(t);
  }

  TextView note(ViewGroup parent, String value) {
    TextView n = text(value, 12.5f, secondary);
    n.setLineSpacing(0, 1.25f);
    n.setPadding(dp(10), dp(8), dp(10), 0);
    parent.addView(n);
    return n;
  }

  // ------------------------------------------------------------------ cards

  LinearLayout card(LinearLayout parent, float radius, int topMargin) {
    LinearLayout c = new LinearLayout(a);
    c.setOrientation(LinearLayout.VERTICAL);
    c.setBackground(rounded(card, radius));
    parent.addView(c, margins(-1, -2, 0, topMargin, 0, 0));
    return c;
  }

  /** Rounded card holding rows separated by hairlines (phone-settings style). */
  LinearLayout group(LinearLayout parent, int topMargin) {
    LinearLayout g = card(parent, 20, topMargin);
    g.setClipToOutline(true);
    return g;
  }

  private void divider(LinearLayout group, int inset) {
    if (group.getChildCount() == 0) return;
    View d = new View(a);
    d.setBackgroundColor(line);
    group.addView(d, margins(-1, hair(), inset, 0, 0, 0));
  }

  private Drawable rowRipple() {
    int pressed = dark ? 0x33ffffff : 0x1f000000;
    return new RippleDrawable(ColorStateList.valueOf(pressed), null, new ColorDrawable(0xffffffff));
  }

  /** Grouped-list row: optional icon tile, title, subtitle and a view on the right. */
  LinearLayout item(
      LinearLayout group,
      int iconRes,
      int iconFg,
      int iconBg,
      String title,
      String subtitle,
      View right,
      Runnable onClick) {
    boolean withIcon = iconRes != 0;
    divider(group, withIcon ? 64 : 16);
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setMinimumHeight(dp(58));
    r.setPadding(dp(16), dp(11), dp(14), dp(11));
    if (withIcon) r.addView(iconBox(iconRes, iconFg, iconBg, 36, 19, 11));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(withIcon ? dp(12) : 0, 0, dp(6), 0);
    TextView t = medium(text(title, 15.5f, text));
    texts.addView(t);
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView s = text(subtitle, 12.5f, secondary);
      s.setLineSpacing(0, 1.15f);
      s.setPadding(0, dp(2), 0, 0);
      texts.addView(s);
    }
    r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    if (right != null) r.addView(right);
    if (onClick != null) {
      r.setBackground(rowRipple());
      r.setOnClickListener(v -> onClick.run());
    }
    group.addView(r);
    return r;
  }

  /** Label / value row inside a group; the subtitle is optional. */
  TextView kv(LinearLayout group, String label, String subtitle, String value) {
    divider(group, 16);
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setMinimumHeight(dp(52));
    r.setPadding(dp(16), dp(10), dp(16), dp(10));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.addView(text(label, 15.5f, text));
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView s = text(subtitle, 12.5f, secondary);
      s.setPadding(0, dp(2), 0, 0);
      texts.addView(s);
    }
    r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    TextView v = text(value, 14.5f, secondary);
    v.setGravity(Gravity.END);
    v.setMaxWidth(dp(220));
    v.setPadding(dp(10), 0, 0, 0);
    r.addView(v);
    group.addView(r);
    return v;
  }

  /** "去设置 ›" in the accent color. */
  View go(String label) {
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.addView(bold(text(label, 14, accent)));
    ImageView c = icon(R.drawable.ic_chev, accent, 16);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(16), dp(16));
    lp.leftMargin = dp(1);
    r.addView(c, lp);
    return r;
  }

  /** "✓ 已开启" in green. */
  View done(String label) {
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    ImageView c = icon(R.drawable.ic_check, green, 16);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(15), dp(15));
    lp.rightMargin = dp(5);
    r.addView(c, lp);
    r.addView(bold(text(label, 13.5f, green)));
    return r;
  }

  TextView tag(String label, int fg, int fill) {
    TextView t = bold(text(label, 12, fg));
    t.setSingleLine(true);
    t.setEllipsize(TextUtils.TruncateAt.END);
    t.setMaxWidth(dp(120));
    t.setPadding(dp(9), dp(3), dp(9), dp(3));
    t.setBackground(rounded(fill, 999));
    return t;
  }

  /** Wraps views into one horizontal right-side cluster. */
  View cluster(View... views) {
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    for (View v : views) if (v != null) r.addView(v);
    return r;
  }

  /** Progress summary: a ring (done of total) beside a title and a line of text. */
  void summary(LinearLayout parent, int done, int total, String title, String subtitle) {
    LinearLayout c = new LinearLayout(a);
    c.setGravity(Gravity.CENTER_VERTICAL);
    c.setPadding(dp(16), dp(16), dp(16), dp(16));
    c.setBackground(rounded(card, 22));
    FrameLayout ring = new FrameLayout(a);
    boolean complete = done >= total;
    ring.addView(
        new Ring(a, complete ? 1f : done / (float) Math.max(1, total), complete ? green : accent, tone, dp(6)),
        new FrameLayout.LayoutParams(-1, -1));
    if (complete) {
      ImageView i = icon(R.drawable.ic_check, green, 22);
      ring.addView(i, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
    } else {
      TextView n = bold(text(done + "/" + total, 14, text));
      ring.addView(n, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    }
    c.addView(ring, new LinearLayout.LayoutParams(dp(54), dp(54)));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(dp(14), 0, 0, 0);
    texts.addView(bold(text(title, 17, text)));
    TextView s = text(subtitle, 13, secondary);
    s.setPadding(0, dp(3), 0, 0);
    texts.addView(s);
    c.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    parent.addView(c, margins(-1, -2, 0, 6, 0, 0));
  }

  static final class Ring extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float progress, stroke;
    private final int color, track;

    Ring(Context c, float progress, int color, int track, float stroke) {
      super(c);
      this.progress = progress;
      this.color = color;
      this.track = track;
      this.stroke = stroke;
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(stroke);
      paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float h = stroke / 2f;
      box.set(h, h, getWidth() - h, getHeight() - h);
      paint.setColor(track);
      canvas.drawArc(box, 0, 360, false, paint);
      paint.setColor(color);
      if (progress > 0) canvas.drawArc(box, -90, Math.max(1f, 360 * progress), false, paint);
    }
  }

  /** Compact tile: icon in a soft circle beside a small label and a bold value. */
  TextView tile(LinearLayout row, int iconRes, String label, String value, Runnable onClick) {
    LinearLayout t = new LinearLayout(a);
    t.setGravity(Gravity.CENTER_VERTICAL);
    t.setPadding(dp(14), dp(14), dp(12), dp(14));
    t.setMinimumHeight(dp(72));
    clickable(t, rounded(card, 22), 22, onClick);
    t.addView(iconBox(iconRes, accent, accentSoft, 38, 20, -1));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(dp(12), 0, 0, 0);
    TextView l = text(label, 12.5f, secondary);
    l.setSingleLine(true);
    l.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(l);
    TextView v = bold(text(value, 16.5f, text));
    v.setSingleLine(true);
    v.setEllipsize(TextUtils.TruncateAt.END);
    v.setPadding(0, dp(2), 0, 0);
    texts.addView(v);
    t.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    t.setContentDescription(label + "：" + value);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    if (row.getChildCount() > 0) lp.leftMargin = dp(10);
    row.addView(t, lp);
    return v;
  }

  /** Line list item: round badge, title, subtitle and a tag saying what the line is used for. */
  void lineCard(
      LinearLayout page,
      String badge,
      int badgeColor,
      String title,
      String subtitle,
      String tagText,
      Runnable onOpen) {
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(dp(14), dp(12), dp(12), dp(12));
    clickable(r, rounded(card, 20), 20, onOpen);
    TextView b = bold(text(badge, badge.length() > 1 ? 13 : 16, 0xffffffff));
    b.setGravity(Gravity.CENTER);
    b.setBackground(oval(badgeColor));
    r.addView(b, new LinearLayout.LayoutParams(dp(40), dp(40)));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(dp(12), 0, dp(8), 0);
    TextView t = bold(text(title, 15.5f, text));
    t.setSingleLine(true);
    t.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(t);
    TextView s = text(subtitle, 12.5f, secondary);
    s.setSingleLine(true);
    s.setEllipsize(TextUtils.TruncateAt.END);
    s.setPadding(0, dp(2), 0, 0);
    texts.addView(s);
    r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    if (tagText != null) r.addView(tag(tagText, accent, accentSoft));
    r.addView(chevron());
    page.addView(r, margins(-1, -2, 0, 0, 0, 8));
  }

  /** Card row with a title, optional value and chevron. */
  Row rowCard(LinearLayout parent, String title, String value, int topMargin, Runnable onClick) {
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setMinimumHeight(dp(56));
    r.setPadding(dp(18), dp(12), dp(14), dp(12));
    if (onClick != null) clickable(r, rounded(card, 20), 20, onClick);
    else r.setBackground(rounded(card, 20));
    TextView t = medium(text(title, 15.5f, text));
    r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    TextView v = text(value == null ? "" : value, 14, secondary);
    v.setSingleLine(true);
    v.setEllipsize(TextUtils.TruncateAt.END);
    v.setMaxWidth(dp(220));
    r.addView(v);
    if (onClick != null) r.addView(chevron());
    parent.addView(r, margins(-1, -2, 0, topMargin, 0, 0));
    return new Row(r, t, v);
  }

  /** Toggle card ("设为默认出口"); tapping anywhere flips the switch. */
  Toggle toggleCard(LinearLayout parent, String title, String subtitle, boolean on, int topMargin) {
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setMinimumHeight(dp(58));
    r.setPadding(dp(16), dp(12), dp(16), dp(12));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.addView(medium(text(title, 15.5f, text)));
    if (subtitle != null) {
      TextView s = text(subtitle, 12.5f, secondary);
      s.setPadding(0, dp(2), dp(8), 0);
      texts.addView(s);
    }
    r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    Toggle t = new Toggle(a, accent, dark ? 0xff3a4049 : 0xffcdd3dc);
    t.setChecked(on, false);
    t.setContentDescription(title);
    t.setOnClickListener(v -> t.toggle());
    r.addView(t, new LinearLayout.LayoutParams(dp(52), dp(30)));
    clickable(r, rounded(card, 18), 18, t::toggle);
    parent.addView(r, margins(-1, -2, 0, topMargin, 0, 0));
    return t;
  }

  /** Header card for an existing line: its type cannot change, so it is shown, not picked. */
  void typeHeader(LinearLayout parent, String badge, int badgeColor, String title, String subtitle) {
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(dp(14), dp(12), dp(14), dp(12));
    r.setBackground(rounded(card, 18));
    TextView b = bold(text(badge, 12, 0xffffffff));
    b.setGravity(Gravity.CENTER);
    b.setBackground(oval(badgeColor));
    r.addView(b, new LinearLayout.LayoutParams(dp(36), dp(36)));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(dp(12), 0, 0, 0);
    texts.addView(bold(text(title, 15.5f, text)));
    TextView s = text(subtitle, 12.5f, secondary);
    s.setPadding(0, dp(2), 0, 0);
    texts.addView(s);
    r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    parent.addView(r, margins(-1, -2, 0, 4, 0, 4));
  }

  private boolean inSheet(View v) {
    for (int i = 0; v != null && i < 6; i++) {
      if (v.getTag() == SHEET) return true;
      v = v.getParent() instanceof View ? (View) v.getParent() : null;
    }
    return false;
  }

  /** Horizontal row for fields that sit side by side (server and port). */
  LinearLayout fieldRow(LinearLayout parent) {
    LinearLayout row = new LinearLayout(a);
    row.setOrientation(LinearLayout.HORIZONTAL);
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    return row;
  }

  /** Width share of a field placed in a {@link #fieldRow}. */
  void weight(EditText field, float weight) {
    View holder = (View) field.getParent().getParent();
    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) holder.getLayoutParams();
    lp.weight = weight;
    holder.setLayoutParams(lp);
  }

  /** Outlined text field with its label sitting on the top border. */
  EditText field(ViewGroup parent, String label, String value, String hint, int kind) {
    FrameLayout holder = new FrameLayout(a);
    holder.setPadding(0, dp(8), 0, 0);
    LinearLayout box = new LinearLayout(a);
    box.setGravity(Gravity.CENTER_VERTICAL);
    box.setPadding(dp(16), dp(6), dp(12), dp(4));
    box.setMinimumHeight(dp(56));
    // Outlined field on the surface color, so the label can sit on the border cleanly.
    int surface = inSheet(parent) ? sheet : bg;
    box.setBackground(stroked(surface, line, 1.5f, 14));
    EditText e = new EditText(a);
    e.setText(value);
    e.setHint(hint);
    e.setHintTextColor(chevron);
    e.setTextColor(text);
    e.setTextSize(16);
    e.setSingleLine(true);
    e.setBackground(null);
    e.setPadding(0, dp(8), 0, dp(6));
    e.setInputType(kind);
    box.addView(e, new LinearLayout.LayoutParams(0, -2, 1));
    if ((kind & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
      TextView eye = bold(text("显示", 14, accent));
      eye.setPadding(dp(10), dp(8), dp(6), dp(8));
      eye.setOnClickListener(
          v -> {
            boolean hidden =
                (e.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD)
                    == InputType.TYPE_TEXT_VARIATION_PASSWORD;
            int sel = e.getSelectionEnd();
            e.setInputType(
                hidden
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            e.setSelection(Math.max(0, sel));
            eye.setText(hidden ? "隐藏" : "显示");
          });
      box.addView(eye);
    }
    holder.addView(box, new FrameLayout.LayoutParams(-1, -2));
    TextView l = medium(text(label, 12, secondary));
    l.setPadding(dp(5), 0, dp(5), 0);
    l.setBackgroundColor(surface);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    lp.leftMargin = dp(11);
    holder.addView(l, lp);
    e.setOnFocusChangeListener(
        (v, focus) -> {
          box.setBackground(stroked(surface, focus ? accent : line, focus ? 2f : 1.5f, 14));
          l.setTextColor(focus ? accent : secondary);
        });
    box.setOnClickListener(v -> e.requestFocus());
    boolean inRow =
        parent instanceof LinearLayout
            && ((LinearLayout) parent).getOrientation() == LinearLayout.HORIZONTAL;
    if (inRow) {
      LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -2, 1);
      rl.topMargin = dp(10);
      if (parent.getChildCount() > 0) rl.leftMargin = dp(10);
      parent.addView(holder, rl);
    } else parent.addView(holder, margins(-1, -2, 0, 10, 0, 0));
    return e;
  }

  /** Read-only field that opens a picker, styled like {@link #field}. */
  TextView pickField(ViewGroup parent, String label, String value, Runnable onClick) {
    FrameLayout holder = new FrameLayout(a);
    holder.setPadding(0, dp(8), 0, 0);
    LinearLayout box = new LinearLayout(a);
    box.setGravity(Gravity.CENTER_VERTICAL);
    box.setPadding(dp(16), dp(6), dp(14), dp(4));
    box.setMinimumHeight(dp(56));
    int surface = inSheet(parent) ? sheet : bg;
    clickable(box, stroked(surface, line, 1.5f, 14), 14, onClick);
    TextView v = text(value, 16, text);
    v.setSingleLine(true);
    v.setEllipsize(TextUtils.TruncateAt.END);
    box.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
    box.addView(icon(R.drawable.ic_down, secondary, 18));
    holder.addView(box, new FrameLayout.LayoutParams(-1, -2));
    TextView l = medium(text(label, 12, secondary));
    l.setPadding(dp(5), 0, dp(5), 0);
    l.setBackgroundColor(surface);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    lp.leftMargin = dp(11);
    holder.addView(l, lp);
    parent.addView(holder, margins(-1, -2, 0, 10, 0, 0));
    return v;
  }

  /** Horizontal chips; returns the container so callers can hide it. */
  HorizontalScrollView chips(LinearLayout parent, String[] labels, int selected, OnPick onPick) {
    HorizontalScrollView scroll = new HorizontalScrollView(a);
    scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout row = new LinearLayout(a);
    row.setPadding(dp(2), dp(4), dp(2), dp(4));
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      boolean on = i == selected;
      TextView c = bold(text(labels[i], 13.5f, on ? accent : secondary));
      c.setPadding(dp(14), dp(9), dp(14), dp(9));
      if (onPick != null) clickable(c, rounded(on ? accentSoft : tone, 10), 10, () -> onPick.pick(index));
      else c.setBackground(rounded(on ? accentSoft : tone, 10));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.rightMargin = dp(8);
      row.addView(c, lp);
    }
    scroll.addView(row);
    parent.addView(scroll, margins(-1, -2, 0, 6, 0, 2));
    return scroll;
  }

  interface OnPick {
    void pick(int index);
  }

  /** Single choice: a segmented control for up to three options, chips beyond that. */
  int[] choice(LinearLayout parent, String[] labels, int selected) {
    int[] pick = {Math.max(0, selected)};
    if (labels.length > 3) {
      HorizontalScrollView[] holder = new HorizontalScrollView[1];
      OnPick[] again = new OnPick[1];
      again[0] =
          i -> {
            pick[0] = i;
            int at = parent.indexOfChild(holder[0]);
            parent.removeView(holder[0]);
            holder[0] = chips(parent, labels, i, again[0]);
            parent.removeView(holder[0]);
            parent.addView(holder[0], at);
          };
      holder[0] = chips(parent, labels, pick[0], again[0]);
      return pick;
    }
    LinearLayout seg = new LinearLayout(a);
    seg.setPadding(dp(4), dp(4), dp(4), dp(4));
    seg.setBackground(rounded(tone, 14));
    TextView[] items = new TextView[labels.length];
    Runnable paint =
        () -> {
          for (int i = 0; i < items.length; i++) {
            boolean on = i == pick[0];
            items[i].setTextColor(on ? accent : secondary);
            items[i].setBackground(ripple(on ? rounded(card, 10) : null, 10));
          }
        };
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      TextView t = bold(text(labels[i], 14.5f, secondary));
      t.setGravity(Gravity.CENTER);
      t.setSingleLine(true);
      t.setEllipsize(TextUtils.TruncateAt.END);
      t.setPadding(dp(6), dp(10), dp(6), dp(10));
      t.setOnClickListener(
          v -> {
            pick[0] = index;
            paint.run();
          });
      items[i] = t;
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      if (i > 0) lp.leftMargin = dp(4);
      seg.addView(t, lp);
    }
    paint.run();
    parent.addView(seg, new LinearLayout.LayoutParams(-1, -2));
    return pick;
  }

  /** Small grey label above a control. */
  void label(LinearLayout parent, String value) {
    TextView t = medium(text(value, 12.5f, secondary));
    t.setPadding(dp(4), dp(18), dp(4), dp(8));
    parent.addView(t);
  }

  /** Outlined pill button, used for the home footer actions. */
  TextView pill(LinearLayout row, String title, Runnable onClick) {
    TextView b = bold(text(title, 13.5f, text));
    b.setGravity(Gravity.CENTER);
    b.setPadding(0, dp(12), 0, dp(12));
    clickable(b, stroked(Color.TRANSPARENT, line, 1.5f, 999), 999, onClick);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    if (row.getChildCount() > 0) lp.leftMargin = dp(8);
    row.addView(b, lp);
    return b;
  }

  /** Filled pill button for the pinned bar, sheets or inline actions. */
  TextView barButton(LinearLayout bar, String title, int kind, float weight, Runnable onClick) {
    int fg = kind == PRIMARY ? onFill : kind == DANGER ? red : text;
    int fill = kind == PRIMARY ? accent : kind == DANGER ? redSoft : tone;
    TextView b = bold(text(title, 15.5f, fg));
    b.setGravity(Gravity.CENTER);
    b.setSingleLine(true);
    b.setPadding(dp(8), dp(15), dp(8), dp(15));
    clickable(b, rounded(fill, 999), 999, onClick);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
    if (bar.getChildCount() > 0) lp.leftMargin = dp(10);
    bar.addView(b, lp);
    return b;
  }

  /** Tonal full-width action, e.g. "测试连接". */
  TextView softButton(LinearLayout parent, String title, int topMargin, Runnable onClick) {
    TextView b = bold(text(title, 15, accent));
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(14), dp(15), dp(14), dp(15));
    clickable(b, rounded(accentSoft, 16), 16, onClick);
    parent.addView(b, margins(-1, -2, 0, topMargin, 0, 0));
    return b;
  }

  // ------------------------------------------------------------------ sheets & dialogs

  static final class Btn {
    final String label;
    final int kind;
    final Runnable run;
    final boolean keepOpen;

    Btn(String label, int kind, Runnable run, boolean keepOpen) {
      this.label = label;
      this.kind = kind;
      this.run = run;
      this.keepOpen = keepOpen;
    }
  }

  static Btn btn(String label, int kind, Runnable run) {
    return new Btn(label, kind, run, false);
  }

  /** A button that leaves the dialog open; the action dismisses it when it succeeds. */
  static Btn stay(String label, int kind, Runnable run) {
    return new Btn(label, kind, run, true);
  }

  /** Bottom sheet on phones, a centered card on wide screens. */
  final class Sheet {
    final Dialog dialog;
    final LinearLayout box, body;
    private LinearLayout form;

    Sheet(String title, String subtitle) {
      dialog = new Dialog(a);
      dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
      box = new LinearLayout(a);
      box.setOrientation(LinearLayout.VERTICAL);
      box.setTag(SHEET);
      int base = dp(wide ? 20 : 22);
      if (wide) {
        box.setBackground(rounded(sheet, 28));
        box.setPadding(0, dp(22), 0, base);
      } else {
        GradientDrawable g = new GradientDrawable();
        g.setColor(sheet);
        float r = dp(28);
        g.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        box.setBackground(g);
        box.setPadding(0, dp(10), 0, base);
        View handle = new View(a);
        handle.setBackground(rounded(dark ? 0xff3a4049 : line, 2));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(36), dp(4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = dp(14);
        box.addView(handle, hl);
      }
      TextView t = bold(text(title, 19, text));
      t.setPadding(dp(24), dp(2), dp(24), dp(4));
      box.addView(t);
      if (subtitle != null) {
        TextView s = text(subtitle, 13, secondary);
        s.setLineSpacing(0, 1.2f);
        s.setPadding(dp(24), 0, dp(24), dp(8));
        box.addView(s);
      }
      ScrollView scroll = new ScrollView(a);
      scroll.setVerticalScrollBarEnabled(false);
      body = new LinearLayout(a);
      body.setOrientation(LinearLayout.VERTICAL);
      scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
      box.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
      dialog.setContentView(box);
      Window w = dialog.getWindow();
      w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      w.setDimAmount(dark ? 0.56f : 0.36f);
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      if (wide) {
        w.setLayout(Math.min(dp(440), a.getResources().getDisplayMetrics().widthPixels - dp(48)), -2);
        w.setGravity(Gravity.CENTER);
      } else {
        w.setLayout(-1, -2);
        w.setGravity(Gravity.BOTTOM);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        if (Build.VERSION.SDK_INT >= 30) {
          // Reach the bottom edge behind the navigation bar; the padding below keeps content clear.
          WindowManager.LayoutParams attrs = w.getAttributes();
          attrs.setFitInsetsTypes(0);
          w.setAttributes(attrs);
          w.setDecorFitsSystemWindows(false);
          w.setNavigationBarColor(Color.TRANSPARENT);
          box.setOnApplyWindowInsetsListener(
              (v, insets) -> {
                int nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                v.setPadding(0, v.getPaddingTop(), 0, base + Math.max(nav, ime));
                return insets;
              });
        }
      }
    }

    /** Padded area for fields, chips and notes. */
    LinearLayout form() {
      if (form == null) {
        form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        body.addView(form, new LinearLayout.LayoutParams(-1, -2));
      }
      return form;
    }

    /** Full-width choice row; a null selection shows a chevron instead of a radio. */
    void option(int iconRes, String title, String subtitle, Boolean selected, Runnable onPick) {
      form = null;
      LinearLayout r = new LinearLayout(a);
      r.setGravity(Gravity.CENTER_VERTICAL);
      r.setMinimumHeight(dp(56));
      r.setPadding(dp(24), dp(12), dp(22), dp(12));
      r.setBackground(rowRipple());
      if (iconRes != 0) r.addView(iconBox(iconRes, accent, accentSoft, 40, 20, 12));
      LinearLayout texts = new LinearLayout(a);
      texts.setOrientation(LinearLayout.VERTICAL);
      texts.setPadding(iconRes != 0 ? dp(14) : 0, 0, dp(12), 0);
      texts.addView(medium(text(title, 16, text)));
      if (subtitle != null && !subtitle.isEmpty()) {
        TextView s = text(subtitle, 12.5f, secondary);
        s.setLineSpacing(0, 1.15f);
        s.setPadding(0, dp(3), 0, 0);
        texts.addView(s);
      }
      r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
      if (selected == null) r.addView(chevron());
      else r.addView(radio(selected), new LinearLayout.LayoutParams(dp(22), dp(22)));
      r.setContentDescription(title);
      r.setOnClickListener(
          v -> {
            dialog.dismiss();
            onPick.run();
          });
      body.addView(r);
    }

    void note(String value) {
      TextView n = text(value, 12.5f, secondary);
      n.setLineSpacing(0, 1.25f);
      n.setPadding(dp(24), dp(8), dp(24), 0);
      if (form != null) n.setPadding(dp(4), dp(10), dp(4), 0);
      (form != null ? form : body).addView(n);
    }

    void buttons(Btn... btns) {
      LinearLayout row = new LinearLayout(a);
      row.setPadding(dp(20), dp(20), dp(20), 0);
      for (Btn b : btns)
        barButton(
            row,
            b.label,
            b.kind,
            b.kind == PRIMARY ? 1f : 0.8f,
            () -> {
              if (!b.keepOpen) dialog.dismiss();
              if (b.run != null) b.run.run();
            });
      body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    void show() {
      dialog.show();
    }
  }

  Sheet sheet(String title, String subtitle) {
    return new Sheet(title, subtitle);
  }

  private View radio(boolean on) {
    FrameLayout f = new FrameLayout(a);
    GradientDrawable ring = new GradientDrawable();
    ring.setShape(GradientDrawable.OVAL);
    ring.setStroke(dp(2), on ? accent : chevron);
    f.setBackground(ring);
    if (on) {
      View dot = new View(a);
      dot.setBackground(oval(accent));
      f.addView(dot, new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER));
    }
    return f;
  }

  /** Centered message dialog with right-aligned buttons. */
  Dialog dialog(String title, CharSequence message, boolean cancelable, Btn... btns) {
    Dialog d = new Dialog(a);
    d.requestWindowFeature(Window.FEATURE_NO_TITLE);
    d.setCancelable(cancelable);
    LinearLayout box = new LinearLayout(a);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(24), dp(24), dp(20), dp(16));
    box.setBackground(rounded(sheet, 28));
    TextView t = bold(text(title, 19, text));
    t.setLineSpacing(0, 1.15f);
    box.addView(t);
    if (message != null && message.length() > 0) {
      TextView m = text("", 14.5f, secondary);
      m.setText(message);
      m.setLineSpacing(0, 1.3f);
      m.setPadding(0, dp(10), dp(4), 0);
      box.addView(m);
    }
    LinearLayout row = new LinearLayout(a);
    row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(20), 0, 0);
    for (Btn b : btns) {
      boolean fill = b.kind == PRIMARY;
      TextView v = bold(text(b.label, 14.5f, fill ? onFill : b.kind == DANGER ? red : accent));
      v.setGravity(Gravity.CENTER);
      v.setPadding(dp(fill ? 20 : 14), 0, dp(fill ? 20 : 14), 0);
      clickable(
          v,
          fill ? rounded(accent, 999) : null,
          999,
          () -> {
            if (!b.keepOpen) d.dismiss();
            if (b.run != null) b.run.run();
          });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
      lp.leftMargin = dp(4);
      row.addView(v, lp);
    }
    box.addView(row);
    d.setContentView(box);
    Window w = d.getWindow();
    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    w.setDimAmount(dark ? 0.56f : 0.36f);
    w.setLayout(Math.min(dp(wide ? 380 : 340), a.getResources().getDisplayMetrics().widthPixels - dp(48)), -2);
    d.show();
    return d;
  }

  static final class Row {
    final LinearLayout view;
    final TextView title, value;

    Row(LinearLayout view, TextView title, TextView value) {
      this.view = view;
      this.title = title;
      this.value = value;
    }
  }
}

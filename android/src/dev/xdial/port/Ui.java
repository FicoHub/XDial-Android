package dev.xdial.port;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
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
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * "Color block card" building blocks: tonal surfaces, a state-colored hero card, large tiles,
 * outlined floating-label fields and a pinned action bar. Colors follow the system dark mode.
 */
final class Ui {
  final Activity a;
  final boolean dark;
  final int bg, card, tone, text, secondary, line, chevron, accent, accentSoft, onFill;
  final int green, red, redSoft, orange;
  final int heroStart, heroEnd;

  Ui(Activity a) {
    this.a = a;
    dark =
        (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    bg = dark ? 0xff0f1114 : 0xfff5f6fa;
    card = dark ? 0xff1a1d22 : 0xffffffff;
    tone = dark ? 0xff22272e : 0xffe7ecf4;
    text = dark ? 0xffeef1f5 : 0xff141820;
    secondary = dark ? 0xff99a1ad : 0xff636b78;
    line = dark ? 0xff2a2f37 : 0xffe6e9ef;
    chevron = dark ? 0xff4c535d : 0xffb9bfc9;
    accent = dark ? 0xff5fcfdd : 0xff0f6e7e;
    accentSoft = dark ? 0xff173a40 : 0xffd6eef1;
    onFill = dark ? 0xff062b30 : 0xffffffff;
    green = dark ? 0xff4ad38a : 0xff14915a;
    red = dark ? 0xffff8a8c : 0xffc93b3f;
    redSoft = dark ? 0xff3b1d1f : 0xfffbe4e4;
    orange = dark ? 0xffffb44d : 0xffc77700;
    heroStart = dark ? 0xff1c9daf : 0xff13889a;
    heroEnd = dark ? 0xff0d4f66 : 0xff0b5e78;
  }

  int dp(float n) {
    return (int) (n * a.getResources().getDisplayMetrics().density + 0.5f);
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
    android.view.Window w = a.getWindow();
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
    // Unfolded phones and tablets: keep the column at a phone-like width.
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
    TextView c = text("›", 22, chevron);
    c.setPadding(dp(8), 0, 0, dp(2));
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

  /** Sub-page header: back arrow and title. */
  void nav(LinearLayout page, String title, Runnable back) {
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
    row.addView(t);
    page.addView(row);
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

  TextView note(ViewGroup parent, String value) {
    TextView n = text(value, 12.5f, secondary);
    n.setLineSpacing(0, 1.2f);
    n.setPadding(dp(14), dp(6), dp(14), 0);
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

  /** Big tile with an icon, a small label and a bold value. */
  TextView tile(LinearLayout row, int iconRes, String label, String value, Runnable onClick) {
    LinearLayout t = new LinearLayout(a);
    t.setOrientation(LinearLayout.VERTICAL);
    t.setPadding(dp(16), dp(15), dp(16), dp(15));
    t.setMinimumHeight(dp(96));
    clickable(t, rounded(card, 24), 24, onClick);
    t.addView(icon(iconRes, accent, 24));
    TextView l = text(label, 12.5f, secondary);
    l.setPadding(0, dp(16), 0, dp(1));
    t.addView(l);
    TextView v = bold(text(value, 17, text));
    v.setSingleLine(true);
    v.setEllipsize(TextUtils.TruncateAt.END);
    t.addView(v);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    if (row.getChildCount() > 0) lp.leftMargin = dp(10);
    row.addView(t, lp);
    return v;
  }

  /** Line list item: round badge, title, subtitle and a radio that marks the default exit. */
  void lineCard(
      LinearLayout page,
      String badge,
      int badgeColor,
      String title,
      String subtitle,
      boolean selected,
      Runnable onOpen,
      Runnable onSelect) {
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(dp(16), dp(13), dp(10), dp(13));
    clickable(r, rounded(card, 22), 22, onOpen);
    TextView b = bold(text(badge, badge.length() > 1 ? 13 : 16, 0xffffffff));
    b.setGravity(Gravity.CENTER);
    b.setBackground(oval(badgeColor));
    r.addView(b, new LinearLayout.LayoutParams(dp(40), dp(40)));
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(dp(13), 0, dp(6), 0);
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
    FrameLayout mark = new FrameLayout(a);
    mark.setContentDescription(selected ? "默认出口" : "设为默认出口");
    if (selected) {
      ImageView check = icon(R.drawable.ic_check, dark ? onFill : 0xffffffff, 24);
      check.setPadding(dp(5), dp(5), dp(5), dp(5));
      check.setBackground(oval(accent));
      mark.addView(check, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
    } else {
      View ring = new View(a);
      GradientDrawable d = new GradientDrawable();
      d.setShape(GradientDrawable.OVAL);
      d.setStroke(dp(2), chevron);
      ring.setBackground(d);
      mark.addView(ring, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
    }
    if (onSelect != null) clickable(mark, null, 22, onSelect);
    r.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));
    page.addView(r, margins(-1, -2, 0, 0, 0, 8));
  }

  /** Card row with a title, optional value and chevron. */
  Row rowCard(LinearLayout parent, String title, String value, int topMargin, Runnable onClick) {
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setMinimumHeight(dp(56));
    r.setPadding(dp(16), dp(12), dp(14), dp(12));
    if (onClick != null) clickable(r, rounded(card, 20), 20, onClick);
    else r.setBackground(rounded(card, 20));
    TextView t = medium(text(title, 15.5f, text));
    r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    TextView v = text(value == null ? "" : value, 15, secondary);
    v.setSingleLine(true);
    v.setEllipsize(TextUtils.TruncateAt.END);
    v.setMaxWidth(dp(200));
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
    clickable(r, rounded(card, 20), 20, t::toggle);
    parent.addView(r, margins(-1, -2, 0, topMargin, 0, 0));
    return t;
  }

  /** Outlined text field with its label sitting on the top border. */
  EditText field(ViewGroup parent, String label, String value, String hint, int kind) {
    FrameLayout holder = new FrameLayout(a);
    holder.setPadding(0, dp(8), 0, 0);
    LinearLayout box = new LinearLayout(a);
    box.setGravity(Gravity.CENTER_VERTICAL);
    box.setPadding(dp(16), dp(6), dp(12), dp(4));
    box.setMinimumHeight(dp(56));
    box.setBackground(stroked(card, line, 1.5f, 16));
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
    l.setPadding(dp(6), 0, dp(6), 0);
    l.setBackgroundColor(bg);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    lp.leftMargin = dp(12);
    lp.topMargin = 0;
    holder.addView(l, lp);
    e.setOnFocusChangeListener(
        (v, focus) -> {
          box.setBackground(stroked(card, focus ? accent : line, focus ? 2f : 1.5f, 16));
          l.setTextColor(focus ? accent : secondary);
        });
    box.setOnClickListener(v -> e.requestFocus());
    parent.addView(holder, margins(-1, -2, 0, 10, 0, 0));
    return e;
  }

  /** Read-only field that opens a picker, styled like {@link #field}. */
  TextView pickField(ViewGroup parent, String label, String value, Runnable onClick) {
    FrameLayout holder = new FrameLayout(a);
    holder.setPadding(0, dp(8), 0, 0);
    LinearLayout box = new LinearLayout(a);
    box.setGravity(Gravity.CENTER_VERTICAL);
    box.setPadding(dp(16), dp(6), dp(12), dp(4));
    box.setMinimumHeight(dp(56));
    clickable(box, stroked(card, line, 1.5f, 16), 16, onClick);
    TextView v = text(value, 16, text);
    box.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
    box.addView(chevron());
    holder.addView(box, new FrameLayout.LayoutParams(-1, -2));
    TextView l = medium(text(label, 12, secondary));
    l.setPadding(dp(6), 0, dp(6), 0);
    l.setBackgroundColor(bg);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    lp.leftMargin = dp(12);
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
    parent.addView(scroll, margins(-1, -2, 0, 4, 0, 2));
    return scroll;
  }

  interface OnPick {
    void pick(int index);
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

  /** Filled pill button for the pinned bar or inline actions. */
  TextView barButton(LinearLayout bar, String title, boolean primary, float weight, Runnable onClick) {
    TextView b = bold(text(title, 15.5f, primary ? onFill : text));
    b.setGravity(Gravity.CENTER);
    b.setPadding(0, dp(15), 0, dp(15));
    clickable(b, rounded(primary ? accent : tone, 999), 999, onClick);
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
    clickable(b, rounded(accentSoft, 20), 20, onClick);
    parent.addView(b, margins(-1, -2, 0, topMargin, 0, 0));
    return b;
  }

  /** Label/value line inside an info card. */
  void info(LinearLayout card, String label, String value) {
    if (card.getChildCount() > 0) {
      View d = new View(a);
      d.setBackgroundColor(line);
      card.addView(d, margins(-1, Math.max(1, dp(0.6f)), 16, 0, 0, 0));
    }
    LinearLayout r = new LinearLayout(a);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setMinimumHeight(dp(52));
    r.setPadding(dp(16), dp(10), dp(16), dp(10));
    r.addView(text(label, 15.5f, text), new LinearLayout.LayoutParams(0, -2, 1));
    TextView v = text(value, 15, secondary);
    v.setGravity(Gravity.END);
    r.addView(v, new LinearLayout.LayoutParams(0, -2, 1.3f));
    card.addView(r);
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

package dev.xdial.port;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Settings-style building blocks shared by every page. Colors follow the system dark mode. */
final class Ui {
  final Activity a;
  final boolean dark;
  final int bg, card, text, secondary, separator, accent, fill, onAccent, green, red, orange;

  Ui(Activity a) {
    this.a = a;
    dark =
        (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    bg = dark ? 0xff000000 : 0xfff2f2f7;
    card = dark ? 0xff1c1c1e : 0xffffffff;
    text = dark ? 0xfff2f2f7 : 0xff111418;
    secondary = dark ? 0xff98989f : 0xff6c6c72;
    separator = dark ? 0xff38383a : 0xffe2e2e6;
    accent = dark ? 0xff3fb6c6 : 0xff167d8d;
    fill = dark ? 0xff1f8797 : 0xff167d8d;
    onAccent = 0xffffffff;
    green = dark ? 0xff30d158 : 0xff1f9d55;
    red = dark ? 0xffff453a : 0xffd63a32;
    orange = dark ? 0xffff9f0a : 0xffd97a00;
  }

  int dp(float n) {
    return (int) (n * a.getResources().getDisplayMetrics().density + 0.5f);
  }

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

  /** Scrollable page column, capped at a readable width on tablets and unfolded phones. */
  LinearLayout page(FrameLayout root) {
    ScrollView scroll = new ScrollView(a);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    FrameLayout center = new FrameLayout(a);
    scroll.addView(center, new FrameLayout.LayoutParams(-1, -2));
    LinearLayout column = new LinearLayout(a);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(16), dp(8), dp(16), dp(32));
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL);
    center.addView(column, lp);
    center.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          int max = dp(640);
          int width = r - l;
          FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) column.getLayoutParams();
          int want = width > max ? max : -1;
          if (p.width != want) {
            p.width = want;
            column.post(() -> column.setLayoutParams(p));
          }
        });
    return column;
  }

  TextView text(String value, float sp, int color) {
    TextView t = new TextView(a);
    t.setText(value);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setLineSpacing(0, 1.15f);
    return t;
  }

  TextView bold(TextView t) {
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  /** Large page title, optionally with a back button and a trailing action. */
  LinearLayout topBar(LinearLayout page, String title, Runnable back, String action, Runnable onAction) {
    LinearLayout bar = new LinearLayout(a);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setMinimumHeight(dp(52));
    if (back != null) {
      TextView b = text("‹ 返回", 17, accent);
      b.setPadding(0, dp(8), dp(12), dp(8));
      b.setBackground(ripple(Color.TRANSPARENT, 8));
      b.setOnClickListener(v -> back.run());
      bar.addView(b);
    }
    View spacer = new View(a);
    bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
    if (action != null) {
      TextView act = bold(text(action, 17, accent));
      act.setPadding(dp(12), dp(8), 0, dp(8));
      act.setBackground(ripple(Color.TRANSPARENT, 8));
      act.setOnClickListener(v -> onAction.run());
      bar.addView(act);
    }
    if (back != null || action != null) page.addView(bar);
    TextView t = bold(text(title, 30, text));
    t.setPadding(0, back == null && action == null ? dp(20) : 0, 0, dp(4));
    page.addView(t);
    return bar;
  }

  /** Section with optional header; returns the rounded card that rows are added to. */
  LinearLayout section(LinearLayout page, String header) {
    if (header != null) {
      TextView h = text(header, 13, secondary);
      h.setPadding(dp(16), dp(22), dp(16), dp(7));
      page.addView(h);
    } else {
      View gap = new View(a);
      page.addView(gap, new LinearLayout.LayoutParams(1, dp(18)));
    }
    LinearLayout c = new LinearLayout(a);
    c.setOrientation(LinearLayout.VERTICAL);
    c.setBackground(rounded(card, 14));
    c.setClipToOutline(true);
    page.addView(c, new LinearLayout.LayoutParams(-1, -2));
    return c;
  }

  void footer(LinearLayout page, String note) {
    TextView f = text(note, 13, secondary);
    f.setPadding(dp(16), dp(7), dp(16), 0);
    page.addView(f);
  }

  GradientDrawable rounded(int color, float radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  Drawable ripple(int color, float radius) {
    int pressed = dark ? 0x33ffffff : 0x1a000000;
    GradientDrawable mask = rounded(0xffffffff, radius);
    return new RippleDrawable(
        ColorStateList.valueOf(pressed), color == Color.TRANSPARENT ? null : rounded(color, radius), mask);
  }

  private void divide(LinearLayout card) {
    if (card.getChildCount() == 0) return;
    View line = new View(a);
    line.setBackgroundColor(separator);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
    lp.leftMargin = dp(16);
    card.addView(line, lp);
  }

  LinearLayout rowShell(LinearLayout card) {
    divide(card);
    LinearLayout row = new LinearLayout(a);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(52));
    row.setPadding(dp(16), dp(10), dp(14), dp(10));
    card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    return row;
  }

  View badge(String letters, int color) {
    TextView b = bold(text(letters, letters.length() > 1 ? 11 : 14, 0xffffffff));
    b.setGravity(Gravity.CENTER);
    b.setBackground(rounded(color, 8));
    return b;
  }

  /** Navigation / value row: title, optional subtitle, trailing value and chevron. */
  Row row(LinearLayout card, View icon, String title, String subtitle, String value, Runnable onClick) {
    LinearLayout r = rowShell(card);
    if (icon != null) {
      LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(30), dp(30));
      ip.rightMargin = dp(12);
      r.addView(icon, ip);
    }
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView t = text(title, 16, text);
    texts.addView(t);
    TextView s = text(subtitle == null ? "" : subtitle, 13, secondary);
    s.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
    s.setMaxLines(2);
    s.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(s);
    r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    TextView v = text(value == null ? "" : value, 15, secondary);
    v.setGravity(Gravity.END);
    v.setMaxLines(1);
    v.setEllipsize(TextUtils.TruncateAt.END);
    v.setMaxWidth(dp(190));
    LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
    vp.leftMargin = dp(10);
    r.addView(v, vp);
    if (onClick != null) {
      TextView chevron = text("›", 22, dark ? 0xff5a5a5e : 0xffc4c4c8);
      chevron.setPadding(dp(8), 0, 0, dp(2));
      r.addView(chevron);
      r.setBackground(ripple(Color.TRANSPARENT, 0));
      r.setOnClickListener(x -> onClick.run());
    }
    return new Row(r, t, s, v);
  }

  /** Centered action row, e.g. "添加线路" or destructive "删除线路". */
  TextView action(LinearLayout card, String title, int color, Runnable onClick) {
    LinearLayout r = rowShell(card);
    TextView t = text(title, 16, color);
    r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    r.setBackground(ripple(Color.TRANSPARENT, 0));
    r.setOnClickListener(x -> onClick.run());
    return t;
  }

  /** Single-choice row with a trailing check mark. */
  void choice(LinearLayout card, String title, String subtitle, boolean checked, Runnable onClick) {
    LinearLayout r = rowShell(card);
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.addView(text(title, 16, text));
    if (subtitle != null && !subtitle.isEmpty()) texts.addView(text(subtitle, 13, secondary));
    r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    TextView mark = bold(text(checked ? "✓" : "", 18, accent));
    r.addView(mark);
    r.setBackground(ripple(Color.TRANSPARENT, 0));
    r.setOnClickListener(x -> onClick.run());
  }

  Switch toggle(LinearLayout card, String title, String subtitle, boolean on) {
    LinearLayout r = rowShell(card);
    LinearLayout texts = new LinearLayout(a);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.addView(text(title, 16, text));
    if (subtitle != null && !subtitle.isEmpty()) texts.addView(text(subtitle, 13, secondary));
    r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    Switch s = new Switch(a);
    s.setChecked(on);
    r.addView(s);
    r.setOnClickListener(x -> s.toggle());
    return s;
  }

  /** Label + inline text field, the editable unit of every form. */
  EditText input(LinearLayout card, String label, String value, String hint, int kind) {
    LinearLayout r = rowShell(card);
    TextView l = text(label, 16, text);
    r.addView(l, new LinearLayout.LayoutParams(dp(84), -2));
    EditText e = new EditText(a);
    e.setText(value);
    e.setHint(hint);
    e.setHintTextColor(dark ? 0xff5a5a5e : 0xffb0b0b6);
    e.setTextColor(text);
    e.setTextSize(16);
    e.setSingleLine(true);
    e.setBackground(null);
    e.setPadding(0, dp(4), 0, dp(4));
    e.setInputType(kind);
    r.addView(e, new LinearLayout.LayoutParams(0, -2, 1));
    if ((kind & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
      TextView eye = text("显示", 14, accent);
      eye.setPadding(dp(10), dp(6), 0, dp(6));
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
      r.addView(eye);
    }
    r.setOnClickListener(v -> e.requestFocus());
    return e;
  }

  /** Full-width filled button. */
  TextView button(ViewGroup parent, String title, int color, Runnable onClick) {
    TextView b = bold(text(title, 17, onAccent));
    b.setGravity(Gravity.CENTER);
    b.setMinHeight(dp(50));
    b.setBackground(ripple(color, 12));
    b.setOnClickListener(v -> onClick.run());
    parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
    return b;
  }

  static final class Row {
    final LinearLayout view;
    final TextView title, subtitle, value;

    Row(LinearLayout view, TextView title, TextView subtitle, TextView value) {
      this.view = view;
      this.title = title;
      this.subtitle = subtitle;
      this.value = value;
    }

    void setSubtitle(String s) {
      subtitle.setText(s);
      subtitle.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }
  }

  static int attr(Context c, int attr) {
    TypedArray t = c.obtainStyledAttributes(new int[] {attr});
    int id = t.getResourceId(0, 0);
    t.recycle();
    return id;
  }
}

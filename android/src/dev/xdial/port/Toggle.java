package dev.xdial.port;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Pill switch drawn to match the card style; the owner decides what a tap means. */
final class Toggle extends View {
  private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF box = new RectF();
  private int onColor, offColor, knobColor = 0xffffffff;
  private boolean checked;
  private float position;
  private ValueAnimator animator;

  Toggle(Context c, int onColor, int offColor) {
    super(c);
    this.onColor = onColor;
    this.offColor = offColor;
    knob.setShadowLayer(dp(2), 0, dp(1), 0x40000000);
    setLayerType(LAYER_TYPE_SOFTWARE, knob);
    setFocusable(true);
  }

  private float dp(float v) {
    return v * getResources().getDisplayMetrics().density;
  }

  void setColors(int on, int off) {
    onColor = on;
    offColor = off;
    invalidate();
  }

  boolean isChecked() {
    return checked;
  }

  void setChecked(boolean value, boolean animate) {
    if (animator != null) animator.cancel();
    checked = value;
    float target = value ? 1f : 0f;
    if (!animate || !isLaidOut()) {
      position = target;
      invalidate();
    } else {
      animator = ValueAnimator.ofFloat(position, target).setDuration(180);
      animator.addUpdateListener(
          a -> {
            position = (float) a.getAnimatedValue();
            invalidate();
          });
      animator.start();
    }
  }

  void toggle() {
    setChecked(!checked, true);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float w = getWidth(), h = getHeight(), pad = h * 0.1f;
    box.set(0, 0, w, h);
    track.setColor(blend(offColor, onColor, position));
    canvas.drawRoundRect(box, h / 2, h / 2, track);
    float r = h / 2 - pad;
    float cx = pad + r + (w - 2 * pad - 2 * r) * position;
    knob.setColor(knobColor);
    canvas.drawCircle(cx, h / 2, r, knob);
  }

  private static int blend(int a, int b, float t) {
    int aa = a >>> 24, ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
    int ba = b >>> 24, br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
    return ((int) (aa + (ba - aa) * t) << 24)
        | ((int) (ar + (br - ar) * t) << 16)
        | ((int) (ag + (bg - ag) * t) << 8)
        | (int) (ab + (bb - ab) * t);
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName("android.widget.Switch");
    info.setCheckable(true);
    info.setChecked(checked);
  }
}

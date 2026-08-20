package com.xvpn.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Theme-aware circular glass refresh control drawn without font glyphs. */
final class RefreshActionView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean dark;
    private final int accent;
    private final int soft;
    private ValueAnimator spinner;
    private float phase;
    private final RectF panelBounds = new RectF();

    RefreshActionView(Context context, boolean dark, int accent, int soft) {
        super(context);
        this.dark = dark;
        this.accent = accent;
        this.soft = soft;
        setClickable(true);
        setFocusable(true);
        setContentDescription("刷新节点");
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius=Math.min(getWidth(),getHeight())/2f-dp(3.8f);

        // Circular glass plate stays visually soft in both themes and matches
        // the round connection control without competing with it.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(soft);
        paint.setAlpha(dark ? 128 : 192);
        canvas.drawCircle(cx,cy,radius,paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(accent);
        paint.setAlpha(dark ? 112 : 78);
        canvas.drawCircle(cx,cy,radius,paint);

        float arcRadius=dp(8.2f);
        panelBounds.set(cx-arcRadius,cy-arcRadius,cx+arcRadius,cy+arcRadius);
        paint.setStrokeWidth(dp(2.05f));
        paint.setAlpha(230);
        canvas.drawArc(panelBounds,-58f,286f,false,paint);
        float ax=cx+arcRadius*.88f,ay=cy-arcRadius*.48f;
        canvas.drawLine(ax,ay,ax-dp(4.1f),ay-dp(.3f),paint);
        canvas.drawLine(ax,ay,ax-dp(1.5f),ay+dp(3.6f),paint);

        if(spinner!=null&&spinner.isRunning()){
            double angle=Math.toRadians(-58f+286f*phase);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(dark?225:205);
            canvas.drawCircle(cx+(float)Math.cos(angle)*arcRadius,
                    cy+(float)Math.sin(angle)*arcRadius,dp(1.65f),paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
    }

    void startRefreshMotion() {
        if (spinner != null && spinner.isRunning()) return;
        if (!ValueAnimator.areAnimatorsEnabled()) { phase=0f; invalidate(); return; }
        animate().cancel();
        phase=0f;
        animate().scaleX(.96f).scaleY(.96f).setDuration(120).start();
        spinner = ValueAnimator.ofFloat(0f, 1f);
        spinner.setDuration(820);
        spinner.setRepeatCount(ValueAnimator.INFINITE);
        spinner.setInterpolator(new android.view.animation.LinearInterpolator());
        spinner.addUpdateListener(a -> {phase=(float)a.getAnimatedValue();invalidate();});
        spinner.start();
    }

    void stopRefreshMotion() {
        if (spinner != null) {
            spinner.cancel();
            spinner = null;
        }
        phase=0f;
        invalidate();
        animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) { setScaleX(1f); setScaleY(1f); return; }
        animate().scaleX(1f).scaleY(1f).setDuration(210)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    @Override protected void onDetachedFromWindow() {
        if (spinner != null) {
            spinner.cancel();
            spinner = null;
        }
        phase=0f;
        super.onDetachedFromWindow();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}

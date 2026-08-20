package com.xvpn.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Theme-aware glass sync control drawn without font glyphs. */
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
        float inset = dp(3.8f);
        panelBounds.set(inset,inset,getWidth()-inset,getHeight()-inset);

        // Quiet glass squircle: translucent in light mode, darker and more
        // defined in dark mode so it stays part of the header rather than
        // becoming a bright floating badge.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(soft);
        paint.setAlpha(dark ? 118 : 188);
        canvas.drawRoundRect(panelBounds,dp(12f),dp(12f),paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(accent);
        paint.setAlpha(dark ? 105 : 72);
        canvas.drawRoundRect(panelBounds,dp(12f),dp(12f),paint);

        float left=cx-dp(8.5f),right=cx+dp(8.5f);
        float top=cy-dp(4.5f),bottom=cy+dp(4.5f);
        paint.setStrokeWidth(dp(1.9f));
        paint.setAlpha(235);
        canvas.drawLine(left,top,right,top,paint);
        canvas.drawLine(right,top,right-dp(3.2f),top-dp(2.8f),paint);
        canvas.drawLine(right,top,right-dp(3.2f),top+dp(2.8f),paint);
        paint.setAlpha(dark?190:165);
        canvas.drawLine(right,bottom,left,bottom,paint);
        canvas.drawLine(left,bottom,left+dp(3.2f),bottom-dp(2.8f),paint);
        canvas.drawLine(left,bottom,left+dp(3.2f),bottom+dp(2.8f),paint);

        if(spinner!=null&&spinner.isRunning()){
            float travel=right-left;
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(dark?220:200);
            canvas.drawCircle(left+travel*phase,top,dp(1.7f),paint);
            canvas.drawCircle(right-travel*phase,bottom,dp(1.45f),paint);
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

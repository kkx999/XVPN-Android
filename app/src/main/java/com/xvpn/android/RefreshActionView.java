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

        float arcRadius=dp(8.4f);
        panelBounds.set(cx-arcRadius,cy-arcRadius,cx+arcRadius,cy+arcRadius);
        paint.setStrokeWidth(dp(2.05f));
        paint.setAlpha(230);
        canvas.save();
        if(spinner!=null&&spinner.isRunning())canvas.rotate(phase*360f,cx,cy);
        drawArcArrow(canvas,cx,cy,arcRadius,-72f,132f);
        drawArcArrow(canvas,cx,cy,arcRadius,108f,132f);
        canvas.restore();
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
        setScaleX(.94f);setScaleY(.94f);
        animate().scaleX(1f).scaleY(1f).setDuration(300)
                .setInterpolator(new android.view.animation.OvershootInterpolator(.65f)).start();
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

    private void drawArcArrow(Canvas canvas,float cx,float cy,float radius,float start,float sweep){
        canvas.drawArc(panelBounds,start,sweep,false,paint);
        double end=Math.toRadians(start+sweep);
        float x=cx+(float)Math.cos(end)*radius;
        float y=cy+(float)Math.sin(end)*radius;
        double tangent=end+Math.PI/2d;
        float back=dp(3.8f);
        float wing=dp(2.6f);
        float bx=x-(float)Math.cos(tangent)*back;
        float by=y-(float)Math.sin(tangent)*back;
        float nx=-(float)Math.sin(tangent);
        float ny=(float)Math.cos(tangent);
        canvas.drawLine(x,y,bx+nx*wing,by+ny*wing,paint);
        canvas.drawLine(x,y,bx-nx*wing,by-ny*wing,paint);
    }
}

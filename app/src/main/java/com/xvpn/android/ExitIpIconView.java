package com.xvpn.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Small theme-aware globe badge for the verified VPN egress card. */
final class ExitIpIconView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private final int soft;
    private final boolean dark;
    private final RectF bounds=new RectF();

    ExitIpIconView(Context context,boolean dark,int accent,int soft){
        super(context);this.dark=dark;this.accent=accent;this.soft=soft;
        setContentDescription("当前出口 IP");
    }

    @Override protected void onDraw(Canvas canvas){
        float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.39f;
        paint.setStyle(Paint.Style.FILL);paint.setColor(soft);paint.setAlpha(dark?205:238);canvas.drawCircle(cx,cy,r*1.24f,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(1.55f));paint.setColor(accent);paint.setAlpha(235);
        canvas.drawCircle(cx,cy,r,paint);
        bounds.set(cx-r*.48f,cy-r,cx+r*.48f,cy+r);canvas.drawOval(bounds,paint);
        bounds.set(cx-r,cy-r*.48f,cx+r,cy+r*.48f);canvas.drawOval(bounds,paint);
        paint.setStrokeWidth(dp(1.85f));canvas.drawLine(cx-r*.88f,cy,cx+r*.88f,cy,paint);
        paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);canvas.drawCircle(cx+r*.78f,cy-r*.72f,dp(2.7f),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.1f));paint.setAlpha(dark?180:145);canvas.drawCircle(cx,cy,r*1.23f,paint);
    }

    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
}

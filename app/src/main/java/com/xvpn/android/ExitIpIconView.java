package com.xvpn.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Crystalline egress locator matching XVPN's ice-blue / violet visual language. */
final class ExitIpIconView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.DITHER_FLAG);
    private final int accent;
    private final int soft;
    private final boolean dark;
    private final RectF box=new RectF();
    private final Path pin=new Path();

    ExitIpIconView(Context context,boolean dark,int accent,int soft){
        super(context);this.dark=dark;this.accent=accent;this.soft=soft;
        setContentDescription("当前出口 IP");
    }

    @Override protected void onDraw(Canvas canvas){
        float w=getWidth(),h=getHeight(),cx=w/2f,cy=h/2f;
        float side=Math.min(w,h),radius=side*.30f;
        int violet=dark?0xFF9A7CFF:0xFF765EDB;

        // Frosted rounded tile with the same blue-violet direction as the brand.
        float inset=side*.055f;
        box.set(inset,inset,w-inset,h-inset);
        paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(soft);paint.setAlpha(dark?185:228);
        canvas.drawRoundRect(box,side*.27f,side*.27f,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.05f));paint.setAlpha(dark?135:105);
        paint.setShader(new LinearGradient(box.left,box.top,box.right,box.bottom,accent,violet,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box,side*.27f,side*.27f,paint);

        // A restrained network orbit replaces the old generic globe grid.
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(1.25f));paint.setAlpha(dark?105:82);
        box.set(cx-radius*1.18f,cy-radius*.84f,cx+radius*1.18f,cy+radius*.84f);
        canvas.drawArc(box,-28f,238f,false,paint);

        // Gradient locator represents the current VPN egress / IP region.
        float top=cy-radius*.86f,bottom=cy+radius*.92f,left=cx-radius*.70f,right=cx+radius*.70f;
        pin.reset();pin.moveTo(cx,bottom);
        pin.cubicTo(cx-radius*.18f,cy+radius*.56f,left,cy+radius*.12f,left,cy-radius*.16f);
        pin.cubicTo(left,top+radius*.10f,cx-radius*.34f,top,cx,top);
        pin.cubicTo(cx+radius*.34f,top,right,top+radius*.10f,right,cy-radius*.16f);
        pin.cubicTo(right,cy+radius*.12f,cx+radius*.18f,cy+radius*.56f,cx,bottom);
        pin.close();
        paint.setStyle(Paint.Style.FILL);paint.setAlpha(242);
        paint.setShader(new LinearGradient(left,top,right,bottom,accent,violet,Shader.TileMode.CLAMP));
        canvas.drawPath(pin,paint);

        paint.setShader(null);paint.setColor(dark?0xFFEFF3FF:0xFFFFFFFF);paint.setAlpha(248);
        canvas.drawCircle(cx,cy-radius*.13f,radius*.24f,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.9f));paint.setColor(dark?0xFFCAD6FF:0xFFDDE5FF);paint.setAlpha(190);
        canvas.drawCircle(cx,cy-radius*.13f,radius*.34f,paint);

        // Small crystalline exit glint keeps the icon tied to XVPN's visual system.
        paint.setStyle(Paint.Style.FILL);paint.setColor(violet);paint.setAlpha(220);
        canvas.drawCircle(cx+radius*.92f,cy-radius*.63f,dp(1.8f),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.85f));paint.setColor(accent);paint.setAlpha(120);
        canvas.drawCircle(cx+radius*.92f,cy-radius*.63f,dp(3.1f),paint);
        paint.setShader(null);paint.setAlpha(255);
    }

    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
}

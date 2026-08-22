package com.xvpn.android;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ScrollView;

/** Scroll container whose top handle supports a natural pull-down dismissal. */
final class DismissibleBottomSheetScrollView extends ScrollView {
    interface ProgressListener { void onProgress(float progress); }

    private final int slop;
    private final int minimumFling;
    private View target;
    private Runnable dismiss;
    private ProgressListener listener;
    private VelocityTracker velocity;
    private float downX;
    private float downY;
    private float translation;
    private boolean handleGesture;
    private boolean dragging;

    DismissibleBottomSheetScrollView(Context context){
        super(context);
        ViewConfiguration configuration=ViewConfiguration.get(context);
        slop=configuration.getScaledTouchSlop();
        minimumFling=Math.max(configuration.getScaledMinimumFlingVelocity(),900);
        setFillViewport(false);setClipToPadding(false);setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);setOverScrollMode(OVER_SCROLL_NEVER);
    }

    void configure(View target,Runnable dismiss,ProgressListener listener){
        this.target=target;this.dismiss=dismiss;this.listener=listener;
        // The node picker persists manual country toggles for the current open
        // sheet, but every new picker should start with only the selected
        // node's country expanded. Other bottom sheets have no SelectionDotView
        // descendants and are therefore left untouched.
        post(this::resetNodePickerExpansionIfPresent);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            begin(event);
            // The dismiss gesture owns only the top handle strip. Normal rows
            // must still deliver DOWN to ScrollView so a later MOVE can be
            // intercepted even when the gesture started on a clickable node.
            if(handleGesture)return true;
            return super.onInterceptTouchEvent(event);
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(!handleGesture)return super.onTouchEvent(event);
        if(velocity!=null)velocity.addMovement(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy=event.getY()-downY;
                float dx=Math.abs(event.getX()-downX);
                if(!dragging&&dy>slop&&dy>dx){dragging=true;getParent().requestDisallowInterceptTouchEvent(true);}
                if(dragging){apply(Math.max(0f,dy-slop));return true;}
                return true;
            case MotionEvent.ACTION_UP:
                float speed=0f;
                if(velocity!=null){velocity.computeCurrentVelocity(1000);speed=velocity.getYVelocity();}
                boolean close=dragging&&(translation>Math.max(dp(72),target==null?0f:target.getHeight()*.16f)||speed>minimumFling);
                finishTracker();
                if(close&&dismiss!=null){dismiss.run();}
                else reset();
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishTracker();reset();return true;
            default:return true;
        }
    }

    private void begin(MotionEvent event){
        finishTracker();
        downX=event.getX();downY=event.getY();translation=0f;dragging=false;
        handleGesture=getScrollY()==0&&downY<=dp(32);
        if(handleGesture){velocity=VelocityTracker.obtain();velocity.addMovement(event);}
    }

    private void apply(float value){
        translation=value;
        if(target!=null){
            float progress=Math.min(1f,value/Math.max(dp(180),target.getHeight()*.36f));
            target.setTranslationY(value);
            target.setAlpha(1f-progress*.34f);
            float scale=1f-progress*.012f;target.setScaleX(scale);target.setScaleY(scale);
            if(listener!=null)listener.onProgress(progress);
        }
    }

    private void reset(){
        handleGesture=false;dragging=false;translation=0f;
        if(target==null)return;
        target.animate().cancel();
        target.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(.42f)).start();
        if(listener!=null)listener.onProgress(0f);
    }

    private void resetNodePickerExpansionIfPresent(){
        if(!(target instanceof ViewGroup))return;
        ViewGroup sheet=(ViewGroup)target;
        for(int i=0;i<sheet.getChildCount();i++){
            View candidate=sheet.getChildAt(i);
            if(!(candidate instanceof ViewGroup))continue;
            ViewGroup block=(ViewGroup)candidate;
            if(block.getChildCount()<2)continue;
            View body=block.getChildAt(1);
            if(!(body instanceof ViewGroup)||!containsSelectionMarker(body))continue;
            boolean selected=containsActiveSelection(body);
            body.setVisibility(selected?View.VISIBLE:View.GONE);
            ChevronView chevron=findChevron(block.getChildAt(0));
            if(chevron!=null)chevron.setExpanded(selected);
        }
    }

    private boolean containsSelectionMarker(View view){
        if(view instanceof SelectionDotView)return true;
        if(!(view instanceof ViewGroup))return false;
        ViewGroup group=(ViewGroup)view;
        for(int i=0;i<group.getChildCount();i++)if(containsSelectionMarker(group.getChildAt(i)))return true;
        return false;
    }

    private boolean containsActiveSelection(View view){
        if(view instanceof SelectionDotView)return ((SelectionDotView)view).isActive();
        if(!(view instanceof ViewGroup))return false;
        ViewGroup group=(ViewGroup)view;
        for(int i=0;i<group.getChildCount();i++)if(containsActiveSelection(group.getChildAt(i)))return true;
        return false;
    }

    private ChevronView findChevron(View view){
        if(view instanceof ChevronView)return (ChevronView)view;
        if(!(view instanceof ViewGroup))return null;
        ViewGroup group=(ViewGroup)view;
        for(int i=0;i<group.getChildCount();i++){
            ChevronView found=findChevron(group.getChildAt(i));
            if(found!=null)return found;
        }
        return null;
    }

    private void finishTracker(){
        if(velocity!=null){velocity.recycle();velocity=null;}
    }

    @Override protected void onDetachedFromWindow(){finishTracker();super.onDetachedFromWindow();}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
}

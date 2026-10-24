package com.screencopy.touch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.Button;

/** Drawn locally; no screenshot or OCR content is stored in this view. */
final class BubbleView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int state;
    BubbleView(Context context) {
        super(context);
        setContentDescription("全屏取字，点按识别并复制，按住拖动，长按打开设置");
        setClickable(true); setLongClickable(true); setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setAlpha(.8f);
    }
    void state(int state) { this.state=state; setAlpha(state==0?.8f:1f); invalidate(); }
    @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w=getWidth(),h=getHeight(),cx=w/2,cy=h/2;
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xE62C302F);
        rect.set(1,1,w-1,h-1); canvas.drawRoundRect(rect,w*.26f,w*.26f,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(w*.025f); paint.setColor(0xFF8E9692);
        canvas.drawCircle(cx,cy,w*.31f,paint);
        int color=state==2?0xFFB5F4CB:state==3?0xFFFFAE9E:Color.WHITE;
        paint.setColor(color); paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx,cy,w*.22f,paint);
        if(state==1) {
            paint.setColor(0xFF2C302F); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(w*.055f);
            rect.set(cx-w*.12f,cy-w*.12f,cx+w*.12f,cy+w*.12f);
            canvas.drawArc(rect,(System.currentTimeMillis()%900)*.4f,250,false,paint);
            postInvalidateOnAnimation();
        }
    }
}

package com.screencopy.touch;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

public class CaptureService extends AccessibilityService {
    static volatile CaptureService current;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private SharedPreferences preferences;
    private BubbleView bubble;
    private TextRecognizer recognizer;
    private ValueAnimator docking;
    private Toast lastToast;
    private int minX,maxX,minY,maxY,size;
    private boolean busy,attached;
    private volatile boolean destroyed;
    private long lastCapture = -1000;
    private float downX,downY;
    private int initialX,initialY;
    private boolean dragged,longPressed;
    private final Runnable longPress = () -> {
        if(!dragged && !busy && attached) {
            longPressed=true;
            bubble.performLongClick();
        }
    };

    @Override protected void onServiceConnected() {
        current=this;
        windows=getSystemService(WindowManager.class);
        preferences=getSharedPreferences("position",MODE_PRIVATE);
        recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        size=dp(54);
        showBubble();
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    // Physical screen x uses LEFT in every locale; touch() delegates taps to performClick().
    @SuppressLint({"RtlHardcoded", "ClickableViewAccessibility"})
    void showBubble() {
        if(destroyed) return;
        if(attached) { bubble.setVisibility(busy ? View.INVISIBLE : View.VISIBLE); return; }
        updateBounds();
        bubble=new BubbleView(this);
        params=new WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP | Gravity.LEFT;
        params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.x=preferences.getBoolean("right",true)?maxX:minX;
        params.y=DockMath.fromFraction(preferences.getFloat("y",.45f),minY,maxY);
        bubble.setOnClickListener(v -> capture());
        bubble.setOnLongClickListener(v -> {
            bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return true;
        });
        bubble.setOnTouchListener((v,event) -> touch(event));
        try { windows.addView(bubble,params); attached=true; }
        catch(RuntimeException e) { toast("无法显示小方块，请重新开启无障碍服务"); }
    }
    private void updateBounds() {
        WindowMetrics metrics=windows.getCurrentWindowMetrics();
        Rect bounds=metrics.getBounds();
        Insets safe=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        minX=safe.left+dp(3); minY=safe.top+dp(3);
        maxX=Math.max(minX,bounds.width()-safe.right-size-dp(3));
        maxY=Math.max(minY,bounds.height()-safe.bottom-size-dp(3));
    }
    private boolean touch(MotionEvent e) {
        if(busy) return true;
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if(docking!=null) docking.cancel();
                downX=e.getRawX(); downY=e.getRawY();
                initialX=params.x; initialY=params.y;
                dragged=false; longPressed=false; bubble.setAlpha(1f);
                main.postDelayed(longPress,ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                int slop=ViewConfiguration.get(this).getScaledTouchSlop();
                if(dx*dx+dy*dy>slop*slop) {
                    dragged=true; main.removeCallbacks(longPress);
                }
                if(dragged && !longPressed) {
                    params.x=DockMath.clamp(initialX+Math.round(dx),minX,maxX);
                    params.y=DockMath.clamp(initialY+Math.round(dy),minY,maxY);
                    updatePosition();
                }
                return true;
            case MotionEvent.ACTION_UP:
                main.removeCallbacks(longPress);
                if(!dragged && !longPressed) bubble.performClick(); else dock();
                return true;
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPress); dock(); return true;
            default: return true;
        }
    }
    private void updatePosition() {
        if(attached && !destroyed) {
            try { windows.updateViewLayout(bubble,params); }
            catch(IllegalArgumentException ignored) { attached=false; }
        }
    }
    private void dock() {
        int edge=DockMath.edge(params.x,minX,maxX);
        docking=ValueAnimator.ofInt(params.x,edge);
        docking.setDuration(180);
        docking.addUpdateListener(animation -> {
            params.x=(int)animation.getAnimatedValue(); updatePosition();
        });
        docking.start(); bubble.setAlpha(.8f);
        preferences.edit().putBoolean("right",edge==maxX)
            .putFloat("y",DockMath.fraction(params.y,minY,maxY)).apply();
    }
    private void capture() {
        if(busy || destroyed) return;
        long now=SystemClock.elapsedRealtime();
        if(now-lastCapture<700) return;
        lastCapture=now; busy=true;
        if(lastToast!=null) lastToast.cancel();
        if(docking!=null) docking.cancel();
        bubble.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        bubble.state(1); bubble.setVisibility(View.INVISIBLE);
        // Let the removal of the bubble reach SurfaceFlinger before taking the frame.
        main.postDelayed(() -> {
            if(destroyed) return;
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult result) { recognize(result); }
                    @Override public void onFailure(int error) {
                        String message=error==6?"此页面受系统保护，无法截屏":
                            error==ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT?"点得太快，请稍后重试":
                            error==ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS?"请重新开启无障碍服务":
                            "截屏失败，请换个页面重试（"+error+"）";
                        finish(false,message);
                    }
                });
            } catch(RuntimeException e) { finish(false,"截屏未获授权，请重新开启服务"); }
        },180);
    }
    private void recognize(ScreenshotResult result) {
        Bitmap image=null;
        try(HardwareBuffer buffer=result.getHardwareBuffer()) {
            if(destroyed) return;
            Bitmap hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());
            if(hardware==null) throw new IllegalStateException("No screenshot bitmap");
            try { image=hardware.copy(Bitmap.Config.ARGB_8888,false); }
            finally { hardware.recycle(); }
        } catch(RuntimeException | OutOfMemoryError e) {
            if(image!=null) image.recycle();
            finish(false,"无法处理当前截屏，请稍后重试"); return;
        }
        if(image==null) { finish(false,"截屏为空，请重试"); return; }
        Bitmap input=image;
        main.post(() -> {
            if(destroyed) { input.recycle(); return; }
            if(attached) { bubble.setVisibility(View.VISIBLE); bubble.state(1); }
            try {
                recognizer.process(InputImage.fromBitmap(input,0))
                    .addOnSuccessListener(resultText -> {
                        if(destroyed) return;
                        String text=resultText.getText().trim();
                        if(text.isEmpty()) { finish(false,"未识别到文字，原剪贴板已保留"); return; }
                        try {
                            ClipboardManager clipboard=getSystemService(ClipboardManager.class);
                            ClipData clip=ClipData.newPlainText("屏幕文字",text);
                            PersistableBundle extras=new PersistableBundle();
                            extras.putBoolean("android.content.extra.IS_SENSITIVE",true);
                            clip.getDescription().setExtras(extras);
                            clipboard.setPrimaryClip(clip);
                            finish(true,"已复制 "+text.length()+" 个字符，可直接粘贴");
                        } catch(RuntimeException e) { finish(false,"复制失败，请重新点按"); }
                    })
                    .addOnFailureListener(e -> finish(false,"识别失败，请重试；原剪贴板已保留"))
                    .addOnCompleteListener(task -> input.recycle());
            } catch(RuntimeException | OutOfMemoryError e) {
                input.recycle(); finish(false,"识别器启动失败，请重新开启服务");
            }
        });
    }
    private void finish(boolean success,String message) {
        main.post(() -> {
            if(destroyed) return;
            busy=false;
            if(attached) { bubble.setVisibility(View.VISIBLE); bubble.state(success?2:3); }
            toast(message);
            main.postDelayed(() -> { if(!busy && attached) bubble.state(0); },1300);
        });
    }
    private void toast(String message) {
        if(lastToast!=null) lastToast.cancel();
        lastToast=Toast.makeText(this,message,Toast.LENGTH_SHORT); lastToast.show();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if(windows==null || params==null) return;
        if(docking!=null) docking.cancel();
        updateBounds(); params.x=preferences.getBoolean("right",true)?maxX:minX;
        params.y=DockMath.fromFraction(preferences.getFloat("y",.45f),minY,maxY);
        updatePosition();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* No UI-event collection. */ }
    @Override public void onInterrupt() { main.removeCallbacks(longPress); }
    @Override public void onDestroy() {
        destroyed=true;
        if(current==this) current=null;
        main.removeCallbacks(longPress);
        if(lastToast!=null) lastToast.cancel();
        if(docking!=null) docking.cancel();
        if(attached) { windows.removeView(bubble); attached=false; }
        if(recognizer!=null) recognizer.close();
        super.onDestroy();
    }
}

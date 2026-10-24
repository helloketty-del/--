package com.screencopy.touch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16,23,20), MINT = Color.rgb(181,244,203);
    private TextView status;
    private Button start;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(26),dp(24),dp(26),dp(28));
        scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scroll.setPadding(safe.left,safe.top,safe.right,safe.bottom);
            return insets;
        });
        TextView tag = text("SCREEN → TEXT",12,MINT);
        tag.setLetterSpacing(.18f); page.addView(tag);
        space(page,24);
        TextView title = text("轻触一下，\n屏幕变文字。",36,Color.WHITE);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); page.addView(title);
        space(page,14);
        page.addView(text("拖到顺手的位置。需要文字时，点一下小方块，直接粘贴。",16,0xFFB2C3B9));
        space(page,30);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22),dp(22),dp(22),dp(22));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(0xFF202D26); surface.setCornerRadius(dp(24));
        card.setBackground(surface);
        status = text("检查服务状态…",15,MINT); card.addView(status);
        space(card,18);
        card.addView(text("01   按住拖动，松手自动靠边\n\n02   点一下，识别当前全屏文字\n\n03   文字已复制，去任意输入框粘贴",15,Color.WHITE));
        page.addView(card);
        space(page,26);
        start = button("开启小方块",MINT,BG);
        start.setOnClickListener(v -> {
            CaptureService service = CaptureService.current;
            if (service != null) {
                service.showBubble(); moveTaskToBack(true);
            } else {
                new AlertDialog.Builder(this).setTitle("启用屏幕取字")
                    .setMessage("需要你在系统设置中手动开启「轻触取字」无障碍服务。\n\n仅在你点击小方块时读取当前屏幕，用内置模型在本机识别并复制文字。截图不写入文件，也不上传。识别文字会留在系统剪贴板，供你粘贴。\n\n本应用不读取界面控件，不自动点击其他应用。")
                    .setPositiveButton("去开启",(d,w) -> openAccessibility())
                    .setNegativeButton("取消",null).show();
            }
        }); page.addView(start);
        space(page,10);
        Button stop = button("停止小方块",0xFF26382D,Color.WHITE);
        stop.setOnClickListener(v -> {
            if (CaptureService.current != null) CaptureService.current.disableSelf();
            status.postDelayed(this::updateStatus,350);
        }); page.addView(stop);
        space(page,24);
        page.addView(text("本地识别 · 无需 API · 不保存截图",13,MINT));
        space(page,10);
        page.addView(text("支持 Android 11 及以上。识别当前屏幕可见的内容，不会自动滚动。密码、支付及其他受保护页面可能无法截取。长按小方块可回到这里。",13,0xFF9FB2A6));
        space(page,16);
        TextView help = text("无法开启无障碍？查看帮助",14,MINT);
        help.setPadding(0,dp(10),0,dp(10));
        help.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("首次安装与后台运行")
            .setMessage("如果系统提示「受限制的设置」，进入系统设置 → 应用 → 轻触取字 → 右上角菜单，查找「允许受限制的设置」，再开启无障碍服务。不同品牌名称可能不同。\n\n如果锁屏或清理后台后小方块消失，请在系统电池管理中允许本应用后台运行，并重新打开服务。\n\n受保护页面无法截屏时，原剪贴板不会被清空。")
            .setPositiveButton("应用信息",(d,w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))))
            .setNegativeButton("知道了",null).show()); page.addView(help);
        setContentView(scroll);
    }
    @Override protected void onResume() { super.onResume(); updateStatus(); }
    private void updateStatus() {
        boolean active = CaptureService.current != null;
        status.setText(active ? "● 小方块已就绪" : "○ 尚未启用");
        start.setText(active ? "显示小方块并返回" : "开启小方块");
    }
    private void openAccessibility() {
        Intent intent = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        intent.putExtra(Intent.EXTRA_COMPONENT_NAME,new ComponentName(this,CaptureService.class).flattenToString());
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
    }
    private TextView text(String value,int size,int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(color); view.setLineSpacing(dp(3),1); return view;
    }
    private Button button(String label,int bg,int fg) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(16);
        b.setTextColor(fg); b.setGravity(Gravity.CENTER); b.setMinHeight(dp(58));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(bg); shape.setCornerRadius(dp(18));
        b.setBackground(shape);
        b.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(58)));
        return b;
    }
    private void space(LinearLayout parent,int height) {
        View v = new View(this); parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));
    }
}

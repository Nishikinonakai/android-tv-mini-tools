package com.zy.tvprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class MainActivity extends Activity {

    private TextView infoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(0xFF101418);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        col.setPadding(pad, pad, pad, pad);
        root.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(infoView());

        addSection(col, "Settings.ACTION_*  (官方 intent action)");
        addAction(col, "主设置 ACTION_SETTINGS", Settings.ACTION_SETTINGS);
        addAction(col, "关于本机 ACTION_DEVICE_INFO_SETTINGS", Settings.ACTION_DEVICE_INFO_SETTINGS);
        addAction(col, "开发者选项 APPLICATION_DEVELOPMENT_SETTINGS",
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        addAction(col, "Wi-Fi WIFI_SETTINGS", Settings.ACTION_WIFI_SETTINGS);
        addAction(col, "Wi-Fi 高级 WIFI_IP_SETTINGS", Settings.ACTION_WIFI_IP_SETTINGS);
        addAction(col, "无线/网络 WIRELESS_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS);
        addAction(col, "流量 DATA_USAGE_SETTINGS", Settings.ACTION_DATA_USAGE_SETTINGS);
        addAction(col, "网络运营商 NETWORK_OPERATOR_SETTINGS",
                Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
        addAction(col, "应用 APPLICATION_SETTINGS", Settings.ACTION_APPLICATION_SETTINGS);
        addAction(col, "已安装应用 MANAGE_APPLICATIONS_SETTINGS",
                Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
        addAction(col, "全部应用（含系统） MANAGE_ALL_APPLICATIONS_SETTINGS",
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS);
        addAction(col, "未知来源安装 MANAGE_UNKNOWN_APP_SOURCES",
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        addAction(col, "默认应用 MANAGE_DEFAULT_APPS_SETTINGS",
                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
        addAction(col, "使用情况访问 USAGE_ACCESS_SETTINGS",
                Settings.ACTION_USAGE_ACCESS_SETTINGS);
        addAction(col, "无障碍 ACCESSIBILITY_SETTINGS", Settings.ACTION_ACCESSIBILITY_SETTINGS);
        addAction(col, "存储 INTERNAL_STORAGE_SETTINGS",
                Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        addAction(col, "蓝牙 BLUETOOTH_SETTINGS", Settings.ACTION_BLUETOOTH_SETTINGS);
        addAction(col, "输入法 INPUT_METHOD_SETTINGS", Settings.ACTION_INPUT_METHOD_SETTINGS);
        addAction(col, "语言 LOCALE_SETTINGS", Settings.ACTION_LOCALE_SETTINGS);
        addAction(col, "日期时间 DATE_SETTINGS", Settings.ACTION_DATE_SETTINGS);
        addAction(col, "声音 SOUND_SETTINGS", Settings.ACTION_SOUND_SETTINGS);
        addAction(col, "显示 DISPLAY_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS);
        addAction(col, "隐私 PRIVACY_SETTINGS", Settings.ACTION_PRIVACY_SETTINGS);
        addAction(col, "安全 SECURITY_SETTINGS", Settings.ACTION_SECURITY_SETTINGS);
        addAction(col, "Home 启动器 HOME_SETTINGS", Settings.ACTION_HOME_SETTINGS);

        addSection(col, "组件直拉（绕过 launcher 隐藏）");
        addComponent(col, "无线调试 WirelessDebuggingActivity (A11+)",
                "com.android.settings",
                "com.android.settings.Settings$WirelessDebuggingActivity");
        addComponent(col, "开发者选项 DevelopmentSettingsDashboardActivity",
                "com.android.settings",
                "com.android.settings.Settings$DevelopmentSettingsDashboardActivity");
        addComponent(col, "开发者选项 .DevelopmentSettings (老)",
                "com.android.settings",
                "com.android.settings.DevelopmentSettings");
        addComponent(col, "全部应用 ManageApplications",
                "com.android.settings",
                "com.android.settings.applications.ManageApplications");
        addComponent(col, "运行中的服务 RunningServicesActivity",
                "com.android.settings",
                "com.android.settings.Settings$RunningServicesActivity");
        addComponent(col, "Android TV 原生设置 .MainSettings",
                "com.android.tv.settings",
                "com.android.tv.settings.MainSettings");
        addComponent(col, "Android TV 原生 SettingsActivity",
                "com.android.tv.settings",
                "com.android.tv.settings.SettingsActivity");
        addComponent(col, "Android TV 应用 AppsActivity",
                "com.android.tv.settings",
                "com.android.tv.settings.device.apps.AppsActivity");
        addComponent(col, "Settings 通用 .Settings",
                "com.android.settings",
                "com.android.settings.Settings");

        addSection(col, "其它");
        addClick(col, "选 Home 启动器（弹出 chooser）", v -> {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            try {
                startActivity(Intent.createChooser(i, "选择 Home"));
            } catch (Throwable t) {
                toast("chooser fail: " + t.getMessage());
            }
        });
        addClick(col, "Reflect 出全部 Settings.ACTION_*  到顶栏",
                v -> dumpAllActions());
        addClick(col, "刷新顶栏系统信息", v -> infoView.setText(buildInfo()));

        setContentView(root);
    }

    // ---------- info header ----------

    private View infoView() {
        infoView = new TextView(this);
        infoView.setTextSize(14);
        infoView.setTextColor(0xFFE6EDF3);
        infoView.setTypeface(Typeface.MONOSPACE);
        infoView.setPadding(0, 0, 0, dp(16));
        infoView.setText(buildInfo());
        return infoView;
    }

    private CharSequence buildInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("MODEL    : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("DEVICE   : ").append(Build.DEVICE).append('\n');
        sb.append("BOARD/HW : ").append(Build.BOARD).append(" / ").append(Build.HARDWARE).append('\n');
        sb.append("FP       : ").append(Build.FINGERPRINT).append('\n');
        sb.append("TAGS     : ").append(Build.TAGS).append("    TYPE: ").append(Build.TYPE).append('\n');
        sb.append("SDK      : ").append(Build.VERSION.SDK_INT)
                .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n");
        sb.append("ro.debuggable=").append(getProp("ro.debuggable")).append("  ");
        sb.append("ro.secure=").append(getProp("ro.secure")).append("  ");
        sb.append("ro.adb.secure=").append(getProp("ro.adb.secure")).append('\n');
        sb.append("ro.build.type=").append(getProp("ro.build.type")).append("  ");
        sb.append("ro.build.tags=").append(getProp("ro.build.tags")).append('\n');
        sb.append("persist.sys.usb.config=").append(getProp("persist.sys.usb.config")).append('\n');
        sb.append("service.adb.tcp.port=").append(getProp("service.adb.tcp.port")).append("  ");
        sb.append("persist.adb.tcp.port=").append(getProp("persist.adb.tcp.port")).append('\n');
        sb.append("Settings.Global adb_enabled=")
                .append(Settings.Global.getString(getContentResolver(), Settings.Global.ADB_ENABLED))
                .append('\n');
        sb.append("Settings.Global adb_wifi_enabled=")
                .append(Settings.Global.getString(getContentResolver(), "adb_wifi_enabled"))
                .append('\n');
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            int ip = wi.getIpAddress();
            sb.append("Wi-Fi IP : ")
                    .append((ip & 0xff)).append('.')
                    .append((ip >> 8) & 0xff).append('.')
                    .append((ip >> 16) & 0xff).append('.')
                    .append((ip >> 24) & 0xff).append('\n');
        } catch (Throwable t) {
            sb.append("Wi-Fi IP : ?\n");
        }
        return sb.toString();
    }

    private String getProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method m = c.getMethod("get", String.class, String.class);
            return (String) m.invoke(null, key, "");
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---------- ui helpers ----------

    private void addSection(LinearLayout col, String title) {
        TextView t = new TextView(this);
        t.setText("— " + title + " —");
        t.setTextColor(0xFF7EE2A8);
        t.setTextSize(18);
        t.setPadding(0, dp(20), 0, dp(8));
        col.addView(t);
    }

    private void addAction(LinearLayout col, String label, final String action) {
        addClick(col, label + "\n   " + action, v -> {
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tryStart(i, action);
        });
    }

    private void addComponent(LinearLayout col, String label, final String pkg, final String cls) {
        addClick(col, label + "\n   " + pkg + "/" + cls, v -> {
            Intent i = new Intent();
            i.setComponent(new ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tryStart(i, pkg + "/" + cls);
        });
    }

    private void addClick(LinearLayout col, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(14);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        b.setFocusable(true);
        b.setOnClickListener(l);
        col.addView(b);
    }

    private void tryStart(Intent i, String desc) {
        try {
            startActivity(i);
        } catch (Throwable t) {
            toast("FAIL " + desc + "\n" + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void dumpAllActions() {
        StringBuilder sb = new StringBuilder("Settings.ACTION_*:\n");
        for (Field f : Settings.class.getDeclaredFields()) {
            String n = f.getName();
            if (!n.startsWith("ACTION_")) continue;
            try {
                Object v = f.get(null);
                if (v instanceof String) {
                    sb.append("  ").append(n).append(" = ").append(v).append('\n');
                }
            } catch (Throwable ignored) {
            }
        }
        infoView.setText(sb.toString());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}

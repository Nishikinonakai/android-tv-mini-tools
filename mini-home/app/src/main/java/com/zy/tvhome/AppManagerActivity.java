package com.zy.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Set;

/**
 * App manager — the long-press destination of the desktop "清理" pill, and the
 * stand-in for the recents/overview UI this ROM doesn't ship.
 *
 * Deliberately a single vertical, D-pad-only list (no tabs, no horizontal focus
 * traps) so it's friendly on a TV remote: up/down moves, OK acts, BACK exits.
 *   · 正在运行  — OK force-stops the app, frees its memory
 *   · 开机自启  — OK toggles whether the app starts itself at boot
 */
public class AppManagerActivity extends Activity {

    private HomeActivity.Palette palette;
    private static final int GREEN = 0xFF34C759;   // macOS "on" green

    /** First focusable row — focused on open so the remote works without a
     *  throwaway "wake up the focus" key press. */
    private View firstRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        palette = HomeActivity.Palette.forTime();
        buildUi();
    }

    private void buildUi() {
        firstRow = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(palette.bg);
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(40);
        col.setPadding(pad, dp(28), pad, dp(28));
        scroll.addView(col);

        col.addView(header("应用管理", 26, palette.textPrim, 0));

        // ── Running ──────────────────────────────────────────
        col.addView(sectionTitle("正在运行 · 选中按 OK 结束"));
        List<AppOps.Running> running = AppOps.runningUserApps(this);
        if (!running.isEmpty()) {
            for (AppOps.Running r : running) col.addView(runningRow(r.pkg, r.pssKb));
        } else {
            // REAL_GET_TASKS not granted (pre-reboot) — still let the user stop any
            // launchable app; we just can't show live memory.
            boolean any = false;
            for (String pkg : AppOps.launchablePackages(this)) {
                if (AppOps.isProtected(this, pkg)) continue;
                col.addView(runningRow(pkg, -1));
                any = true;
            }
            if (!any) col.addView(hintRow("没有可结束的应用"));
        }

        // ── Autostart ────────────────────────────────────────
        col.addView(sectionTitle("开机自启 · 选中按 OK 开关"));
        Set<String> boot = AppOps.autostartPackages(this);
        boolean anyBoot = false;
        for (String pkg : boot) {
            if (!AppOps.showInAutostart(this, pkg)) continue;
            col.addView(autostartRow(pkg));
            anyBoot = true;
        }
        if (!anyBoot) col.addView(hintRow("没有可管理的自启项"));

        // ── Footer hint ──────────────────────────────────────
        TextView foot = new TextView(this);
        foot.setText("OK：执行 · 返回键：退出");
        foot.setTextColor(palette.textSec);
        foot.setTextSize(12);
        foot.setPadding(dp(4), dp(24), 0, 0);
        col.addView(foot);

        setContentView(scroll);
        if (firstRow != null) {
            final View f = firstRow;
            f.post(f::requestFocus);
        }
    }

    // ────────────────────────────────────────────────────────────
    // Rows
    // ────────────────────────────────────────────────────────────
    private View runningRow(final String pkg, int pssKb) {
        final TextView right = new TextView(this);
        right.setTextColor(palette.textSec);
        right.setTextSize(14);
        right.setText(pssKb >= 0 ? fmtMb(pssKb) : "");
        right.setGravity(Gravity.END);

        View row = baseRow(pkg, right);
        row.setOnClickListener(v -> {
            boolean hard = AppOps.forceStop(this, pkg);
            right.setText("已结束");
            right.setTextColor(palette.textSec);
            toast((hard ? "已结束 " : "已尝试结束 ") + labelOf(pkg));
        });
        return row;
    }

    private View autostartRow(final String pkg) {
        final boolean[] on = { AppOps.isAutostartEnabled(this, pkg) };
        final TextView right = new TextView(this);
        right.setTextSize(15);
        right.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        right.setGravity(Gravity.END);
        paintToggle(right, on[0]);

        View row = baseRow(pkg, right);
        row.setOnClickListener(v -> {
            boolean next = !on[0];
            if (AppOps.setAutostart(this, pkg, next)) {
                on[0] = next;
                paintToggle(right, next);
                toast(labelOf(pkg) + (next ? " 已允许自启" : " 已禁止自启"));
            } else {
                toast("没有权限（重启后生效）");
            }
        });
        return row;
    }

    private void paintToggle(TextView tv, boolean on) {
        tv.setText(on ? "自启 开" : "自启 关");
        tv.setTextColor(on ? GREEN : palette.textSec);
    }

    /** [icon] name ............... [right]  — focusable, rounded focus highlight. */
    private View baseRow(String pkg, TextView right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(16), dp(12));
        row.setFocusable(true);
        row.setBackground(rowBackground());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4);
        row.setLayoutParams(rlp);

        ImageView icon = new ImageView(this);
        int sz = dp(40);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
        ilp.rightMargin = dp(16);
        icon.setLayoutParams(ilp);
        try { icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); } catch (Throwable ignored) {}
        row.addView(icon);

        TextView name = new TextView(this);
        name.setText(labelOf(pkg));
        name.setTextColor(palette.textPrim);
        name.setTextSize(16);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nlp);
        row.addView(name);

        right.setPadding(dp(12), 0, 0, 0);
        row.addView(right);
        if (firstRow == null) firstRow = row;
        return row;
    }

    private View hintRow(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(palette.textSec);
        tv.setTextSize(14);
        tv.setPadding(dp(14), dp(10), 0, dp(10));
        return tv;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(palette.textSec);
        tv.setTextSize(13);
        tv.setLetterSpacing(0.08f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(dp(4), dp(28), 0, dp(10));
        return tv;
    }

    private TextView header(String text, int size, int color, int topPad) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(size);
        tv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        tv.setPadding(dp(4), topPad, 0, 0);
        return tv;
    }

    // ────────────────────────────────────────────────────────────
    private Drawable rowBackground() {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused},
                roundRect(palette.cardFocus, dp(12), palette.cardBorder, dp(2)));
        sld.addState(new int[]{}, roundRect(palette.cardBg, dp(12), 0, 0));
        return sld;
    }

    private GradientDrawable roundRect(int fill, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeWidth > 0 && strokeColor != 0) g.setStroke(strokeWidth, strokeColor);
        return g;
    }

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) { return pkg; }
    }

    private static String fmtMb(int kb) {
        if (kb <= 0) return "";
        if (kb < 1024) return kb + " KB";
        return ((kb + 512) / 1024) + " MB";
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}

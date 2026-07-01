package com.zy.tvhome;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Debug;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mechanism layer for the "clean background apps" pill and the App-manager screen.
 *
 * The launcher is a privileged system app (/system/priv-app/MiniHome) but is NOT
 * platform-signed, so the privileged permissions it leans on
 * (FORCE_STOP_PACKAGES, CHANGE_COMPONENT_ENABLED_STATE, REAL_GET_TASKS) only grant
 * once the package is in a privapp-permissions allowlist — see
 * privapp-permissions-com.zy.tvhome.xml. Every call here degrades gracefully if a
 * permission is missing, so a half-provisioned device still does *something*
 * sensible instead of crashing.
 */
final class AppOps {
    private AppOps() {}

    // ────────────────────────────────────────────────────────────
    // Keep-alive: packages we must never force-stop. The box has to stay usable
    // and the user's proxy / input / casting must survive a "clean".
    // ────────────────────────────────────────────────────────────
    private static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "com.zy.tvhome",                    // us — the launcher
            "com.github.metacubex.clash.meta",  // Clash.Meta / Mihomo proxy
            "com.github.kr328.clash",           // ClashForAndroid
            "com.kgzn.castscreen",              // screen-cast receiver
            "com.kgzn.boxremote",               // phone-as-remote pairing
            "com.kgzn.websocketmanager"));      // remote-control message bus

    /** A package that must not be killed (keep-list, or the active input method). */
    static boolean isProtected(Context ctx, String pkg) {
        if (pkg == null) return true;
        if (KEEP.contains(pkg)) return true;
        if (imePackages(ctx).contains(pkg)) return true;   // never kill the keyboard
        return false;
    }

    // ────────────────────────────────────────────────────────────
    // Kill
    // ────────────────────────────────────────────────────────────
    /**
     * Real force-stop via the privileged hidden API (clears services, alarms and
     * the task). Falls back to the gentle killBackgroundProcesses when
     * FORCE_STOP_PACKAGES isn't granted yet. Returns true if the hard stop ran.
     */
    static boolean forceStop(Context ctx, String pkg) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        try {
            Method m = ActivityManager.class.getMethod("forceStopPackage", String.class);
            m.invoke(am, pkg);
            return true;
        } catch (Throwable t) {
            try { am.killBackgroundProcesses(pkg); } catch (Throwable ignored) {}
            return false;
        }
    }

    // ────────────────────────────────────────────────────────────
    // Running apps (needs REAL_GET_TASKS to see anything but ourselves)
    // ────────────────────────────────────────────────────────────
    static final class Running {
        final String pkg;
        final int pssKb;     // total PSS across the package's processes, 0 if unknown
        Running(String pkg, int pssKb) { this.pkg = pkg; this.pssKb = pssKb; }
    }

    /**
     * Currently-running third-party (launchable, non-protected) apps with their
     * memory, biggest first. Empty if REAL_GET_TASKS is denied — callers should
     * then fall back to the launchable-candidate set.
     */
    static List<Running> runningUserApps(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> launchable = launchablePackages(ctx);
        Map<String, Integer> mem = new LinkedHashMap<>();
        try {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.pkgList == null) continue;
                    int pss = 0;
                    try {
                        Debug.MemoryInfo[] mi = am.getProcessMemoryInfo(new int[]{p.pid});
                        if (mi != null && mi.length > 0) pss = mi[0].getTotalPss();
                    } catch (Throwable ignored) {}
                    for (String pk : p.pkgList) {
                        if (!launchable.contains(pk)) continue;
                        if (isProtected(ctx, pk)) continue;
                        Integer cur = mem.get(pk);
                        mem.put(pk, (cur == null ? 0 : cur) + pss);
                    }
                }
            }
        } catch (Throwable ignored) {}
        List<Running> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : mem.entrySet()) out.add(new Running(e.getKey(), e.getValue()));
        Collections.sort(out, new Comparator<Running>() {
            @Override public int compare(Running a, Running b) { return Integer.compare(b.pssKb, a.pssKb); }
        });
        return out;
    }

    /** Packages that expose a launcher/leanback entry — the apps a user runs. */
    static Set<String> launchablePackages(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> s = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                if (ri.activityInfo != null) s.add(ri.activityInfo.packageName);
            }
        }
        return s;
    }

    // ────────────────────────────────────────────────────────────
    // Autostart = an app's boot-time broadcast receivers
    // ────────────────────────────────────────────────────────────
    private static final String[] BOOT_ACTIONS = {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",   // MTK fast-boot
            Intent.ACTION_LOCKED_BOOT_COMPLETED};

    private static final int RCV_FLAGS =
            PackageManager.MATCH_DISABLED_COMPONENTS        // still list ones we already turned off
            | PackageManager.MATCH_DIRECT_BOOT_AWARE
            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

    /** All distinct boot receivers declared by a package. */
    static List<ComponentName> bootReceivers(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        List<ComponentName> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String a : BOOT_ACTIONS) {
            Intent i = new Intent(a).setPackage(pkg);
            try {
                for (ResolveInfo ri : pm.queryBroadcastReceivers(i, RCV_FLAGS)) {
                    if (ri.activityInfo == null) continue;
                    if (seen.add(ri.activityInfo.name)) {
                        out.add(new ComponentName(pkg, ri.activityInfo.name));
                    }
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Every package on the device that auto-starts on boot, deduped. */
    static Set<String> autostartPackages(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> pkgs = new TreeSet<>();
        for (String a : BOOT_ACTIONS) {
            try {
                for (ResolveInfo ri : pm.queryBroadcastReceivers(new Intent(a), RCV_FLAGS)) {
                    if (ri.activityInfo != null) pkgs.add(ri.activityInfo.packageName);
                }
            } catch (Throwable ignored) {}
        }
        return pkgs;
    }

    /** True unless ALL of the package's boot receivers are explicitly disabled. */
    static boolean isAutostartEnabled(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        for (ComponentName cn : bootReceivers(ctx, pkg)) {
            int st = pm.getComponentEnabledSetting(cn);
            // DEFAULT means "as declared", and boot receivers ship enabled.
            if (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                return true;
            }
        }
        return false;
    }

    /** Flip every boot receiver of a package on/off. Needs CHANGE_COMPONENT_ENABLED_STATE. */
    static boolean setAutostart(Context ctx, String pkg, boolean enabled) {
        PackageManager pm = ctx.getPackageManager();
        int state = enabled ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        List<ComponentName> rcvs = bootReceivers(ctx, pkg);
        if (rcvs.isEmpty()) return false;
        boolean ok = true;
        for (ComponentName cn : rcvs) {
            try {
                pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
            } catch (Throwable t) { ok = false; }
        }
        return ok;
    }

    // System / platform packages we hide from the autostart screen: turning these
    // off can break the remote (MTK hotkeys), wifi-on-boot, casting, or the
    // keyboard. Leaves the user-facing bloat (kgzn telemetry/upgrade, 3rd-party
    // apps) toggleable.
    private static final Set<String> AUTOSTART_HIDE = new HashSet<>(Arrays.asList(
            "com.zy.tvhome",
            "com.kgzn.setting",            // wifi boot receiver lives here
            "com.kgzn.castscreen",
            "com.kgzn.websocketmanager",
            "com.kgzn.boxremote",
            // ── load-bearing factory / hardware (system uid) — disabling these can
            //    kill the source key, panel/serial comms, or USB. Never expose. ──
            "kgzn.factorymenu.ui",         // FactoryMenu — owns the 信号源/source key
            "com.kgzn.serialport",         // "AutoTest v1.0" → serial bridge to mainboard + USB
            "com.kgzn.tv.factory"));       // "Factory Command Service" — persistent
    private static final String[] AUTOSTART_HIDE_PREFIX = {
            "android", "com.android.", "androidx.",
            "com.mediatek.", "mediatek.", "com.mstar."};

    static boolean showInAutostart(Context ctx, String pkg) {
        if (AUTOSTART_HIDE.contains(pkg)) return false;
        for (String pre : AUTOSTART_HIDE_PREFIX) {
            if (pkg.equals(pre) || pkg.startsWith(pre)) return false;
        }
        if (imePackages(ctx).contains(pkg)) return false;   // keep the keyboard
        // Never offer to disable a PERSISTENT system service — the OS force-keeps
        // it running for a reason (panel/board/command relays). Catches factory
        // services even if they're not in the curated list above.
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) return false;
        } catch (Throwable ignored) {}
        return true;
    }

    // ────────────────────────────────────────────────────────────
    private static Set<String> imePackages(Context ctx) {
        Set<String> s = new HashSet<>();
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (InputMethodInfo imi : imm.getInputMethodList()) s.add(imi.getPackageName());
            }
            String def = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            if (def != null) {
                int slash = def.indexOf('/');
                s.add(slash > 0 ? def.substring(0, slash) : def);
            }
        } catch (Throwable ignored) {}
        return s;
    }
}

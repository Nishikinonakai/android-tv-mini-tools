package com.zy.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

public class HomeActivity extends Activity {

    private static final int COLS = 5;

    // ── Feature toggles (also the demo build-up stages) ──────────
    // Each macOS nicety is independently switchable — flipping them true one rebuild at
    // a time is how the desktop gets built up on camera. The shipped default now leaves
    // the two per-frame-expensive ones OFF: WALLPAPER + FROST drive the wallpaper-blur
    // "vibrancy" (a blurred wallpaper crop re-rasterized behind every tile each frame),
    // and on this weak SoC that cost more than it was worth. Structure is instead carried
    // by solid card fills + drop shadows, and with the frost layer gone everything renders
    // at native full resolution again (the frost backdrop was the only half-res surface).
    // The static niceties (MAC_ICONS / ICON_PLATE / GRID_DEDUP) have no per-frame cost and
    // stay on.
    private static final boolean MAC_ICONS = true;   // macOS icon pack on grid
    private static final boolean GRID_DEDUP = true;  // drop AOSP shells (文件/Search)
    private static final boolean WALLPAPER = false;  // Tahoe wallpaper (off → flat palette bg)
    private static final boolean FROST = false;      // frosted-glass tiles (off → solid card + shadow)
    private static final boolean ICON_PLATE = true;  // uniform rounded-rect backplate behind每个 logo

    private FrameLayout rootFrame;
    private ScrollView scroll;
    private FrostOverlay frostOverlay;
    private TextView clockView;
    private TextView dateView;
    private GridLayout grid;
    private TextView allAppsTitle;
    private Button sourceBtn;
    private Button settingsBtn;
    private LinearLayout clockBlock;

    /** Last focused tile's package — restored on refresh / re-resume */
    private String lastFocusedPkg = null;

    /** Keys (pkg/activity) currently rendered in the grid, in order. Lets onResume
     *  skip a full teardown when nothing changed — the teardown + deferred re-frost
     *  is exactly what made the grid flash solid→blur on every return to home. */
    private final List<String> renderedKeys = new ArrayList<>();

    private Palette palette;

    /** Downscaled, blurred copy of the wallpaper — the frosted-glass backdrop
     *  sampled per tile. null when no wallpaper is present. */
    private Bitmap blurBmp;
    private float blurScale = 1f;   // screen px per blurBmp px

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = this::refreshApps;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(refreshRunnable);
            handler.postDelayed(refreshRunnable, 300);
        }
    };

    // ────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        palette = Palette.forTime();
        buildUi();
        tickClock();
        refreshApps();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-render only when something actually changed. A plain return to the
        // launcher (HOME / BACK out of an app) leaves the app list and palette
        // untouched, so we keep the existing frosted grid exactly as it is —
        // tearing it down and re-frosting here is what made it flash solid→blur.
        // Package add/remove while we were away is already handled by pkgReceiver;
        // the day/night boundary by tickClock. onResume just double-checks both
        // cheaply and otherwise only restores focus.
        Palette next = Palette.forTime();
        if (!next.equals(palette)) {
            palette = next;
            buildUi();
            tickClock();
            refreshApps();
            return;
        }
        List<ResolveInfo> apps = loadApps();
        if (!sameAsRendered(apps)) {
            renderApps(apps);
        } else {
            restoreFocus();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Palette is time-of-day driven, not config-uimode driven, but we still
        // re-evaluate on every config change in case the user is around when one
        // happens (font-size, orientation, etc).
        Palette next = Palette.forTime();
        if (!next.equals(palette)) {
            palette = next;
            // Rebuild UI under new palette; preserve focus target
            buildUi();
            tickClock();
            refreshApps();
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Throwable ignored) {}
        handler.removeCallbacks(refreshRunnable);
        super.onDestroy();
    }

    // ────────────────────────────────────────────────────────────
    // UI scaffolding
    // ────────────────────────────────────────────────────────────
    private void buildUi() {
        int padH = dp(40);
        int headerH = dp(112);

        rootFrame = new FrameLayout(this);
        // Desktop wallpaper — the genuine macOS 26 (Tahoe) system asset, light by
        // day / dark overnight to match the palette. Resolved by name at runtime
        // (not a compile-time R.drawable ref) so the build still works when the
        // wallpaper JPGs are absent — they're Apple's copyrighted artwork and are
        // gitignored, so they live only on the demo device, never in the repo.
        // Absent → graceful fallback to the flat palette bg colour.
        rootFrame.setBackgroundColor(palette.bg);
        if (WALLPAPER) try {
            String name = palette.dark ? "wallpaper_tahoe_dark" : "wallpaper_tahoe_light";
            int wp = getResources().getIdentifier(name, "drawable", getPackageName());
            if (wp != 0) {
                android.graphics.drawable.Drawable wall = getDrawable(wp);
                if (wall != null) rootFrame.setBackground(wall);
                if (FROST) prepareFrost(wp);   // pre-blur the same wallpaper for the tiles
            }
        } catch (Throwable ignored) {}
        // clipChildren=false：focus 1.08x scale 不被边缘裁掉
        rootFrame.setClipChildren(false);
        rootFrame.setClipToPadding(false);

        // ── App list ───────────────────────────────────────────────
        // Added FIRST → sits BELOW the header in z-order, so rows scrolled up
        // slide UNDER the frosted header instead of painting over the clock/pills.
        scroll = new InsetScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalFadingEdgeEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        // Top inset = header height so the first row starts just below the header
        // and scrolls up behind it; clipToPadding=false lets focus-scale overflow.
        scroll.setPadding(padH, headerH, padH, dp(28));
        // Frost lives in ONE fixed half-res FrostOverlay below the grid (added next),
        // so a scroll just re-rasterizes that single cheap layer at the tiles' new
        // positions — the blur stays locked to the wallpaper with zero drift, and the
        // tiles themselves are light (border + icon) so they composite for free.
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (frostOverlay != null) frostOverlay.invalidate();
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);

        allAppsTitle = new TextView(this);
        allAppsTitle.setText(R.string.all_apps);
        allAppsTitle.setTextColor(palette.textSec);
        allAppsTitle.setTextSize(13);
        allAppsTitle.setLetterSpacing(0.15f);
        allAppsTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        allAppsTitle.setAllCaps(false);
        allAppsTitle.setPadding(0, dp(8), 0, dp(14));
        content.addView(allAppsTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new GridLayout(this);
        grid.setColumnCount(COLS);
        grid.setUseDefaultMargins(false);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        // 给 grid 自身留点 inner padding，让边列 tile 放大有空间溢出而不靠近屏幕边
        int gridInner = dp(6);
        grid.setPadding(gridInner, gridInner, gridInner, gridInner);
        content.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Frost overlay (fixed, half-res) ─────────────────────────
        // Added BEFORE the scroll → z-order BELOW the grid. It paints the blurred
        // wallpaper masked to the tiles' current rounded-rect positions; being fixed
        // to the screen, the blur is always locked to the wallpaper. Laid out at half
        // size and scaled 2x (the blur is low-detail, the upscale is invisible) so
        // re-rasterizing it each scroll frame costs ~1/4 as much. Gaps stay sharp:
        // the overlay is transparent there, so the wallpaper background shows through.
        if (FROST && blurBmp != null) {
            frostOverlay = new FrostOverlay(this);
            frostOverlay.setFocusable(false);
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            frostOverlay.setPivotX(0);
            frostOverlay.setPivotY(0);
            frostOverlay.setScaleX(2f);
            frostOverlay.setScaleY(2f);
            // MUST be on a hardware layer for the half-res win: the layer renders the
            // view at its (half) layout size into a texture, then the 2x scale upscales
            // that texture on composite. Without it, setScale just transforms the vector
            // draw ops and they rasterize at FULL res (no saving — actually slower).
            frostOverlay.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            rootFrame.addView(frostOverlay, new FrameLayout.LayoutParams(sw / 2, sh / 2));
        } else {
            frostOverlay = null;
        }

        rootFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── Fixed frosted header ───────────────────────────────────
        // Added LAST → painted ON TOP of the list. It blurs the wallpaper behind
        // it (locked, like the tiles) and, being opaque, cleanly hides rows that
        // scroll underneath — a frosted nav bar within API-30 limits (no live
        // RenderEffect to per-pixel blur the moving icons themselves).
        View header = buildTopBar();
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, headerH);
        hlp.gravity = Gravity.TOP;
        rootFrame.addView(header, hlp);

        setContentView(rootFrame);
    }

    private View buildTopBar() {
        // Frosted header: square (full-bleed) corners, no focus ring, bottom hairline.
        FrostPanel bar = new FrostPanel(this, 0f, false, true);
        bar.setScrims(headerScrim(), headerScrim());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(dp(40), 0, dp(40), 0);
        if (FROST) {
            // Header frost is static → cache it as a texture once and composite that quad
            // on every scroll frame instead of re-running the shader fill each time.
            bar.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            // Solid opaque bar: give it a bounds outline + elevation so it casts a soft
            // shadow onto the rows scrolling beneath it — structure by shadow, no frost.
            bar.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    o.setRect(0, 0, v.getWidth(), v.getHeight());
                }
            });
            bar.setElevation(dpf(6));
        }

        clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clockBlock.setLayoutParams(cblp);

        clockView = new TextView(this);
        clockView.setTextColor(palette.textPrim);
        clockView.setTextSize(36);
        clockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockView.setLetterSpacing(-0.02f);
        clockBlock.addView(clockView);

        dateView = new TextView(this);
        dateView.setTextColor(palette.textSec);
        dateView.setTextSize(13);
        dateView.setPadding(0, dp(2), 0, 0);
        clockBlock.addView(dateView);

        bar.addView(clockBlock);

        sourceBtn = pillButton(getString(R.string.source_btn), v -> openSource());
        settingsBtn = pillButton(getString(R.string.settings_btn), v -> openSettings());
        // 清理: short-press force-stops background apps; long-press / MENU opens
        // the running + autostart manager (the recents UI this ROM doesn't ship).
        Button cleanBtn = pillButton(getString(R.string.clean_btn), v -> cleanAll());
        cleanBtn.setOnLongClickListener(v -> { openManager(); return true; });
        cleanBtn.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_Y)) {
                openManager();
                return true;
            }
            return false;
        });
        bar.addView(sourceBtn);
        bar.addView(settingsBtn);
        bar.addView(cleanBtn);

        return bar;
    }

    private Button pillButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(palette.textPrim);
        b.setPadding(dp(22), dp(10), dp(22), dp(10));
        b.setMinHeight(0);
        b.setMinimumHeight(0);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused},
                roundRect(palette.cardFocus, dp(22), palette.cardBorder, dp(2)));
        sld.addState(new int[]{},
                // palette.bg (not cardBg) so the pill still reads as a recessed chip
                // against the now-solid cardBg header instead of same-colour-on-same.
                roundRect(palette.bg, dp(22), 0, 0));
        b.setBackground(sld);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        b.setLayoutParams(lp);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    // ────────────────────────────────────────────────────────────
    // Tiles
    // ────────────────────────────────────────────────────────────
    private void refreshApps() {
        renderApps(loadApps());
    }

    /** (Re)build the whole grid from the given app list and remember what we
     *  rendered, so onResume can tell a real change from a plain return. */
    private void renderApps(List<ResolveInfo> apps) {
        grid.removeAllViews();
        renderedKeys.clear();
        View tileToFocus = null;
        for (ResolveInfo ri : apps) {
            View tileWrap = buildAppTile(ri);
            grid.addView(tileWrap);
            renderedKeys.add(keyOf(ri));
            if (lastFocusedPkg != null
                    && lastFocusedPkg.equals(ri.activityInfo.packageName)) {
                tileToFocus = tileInside(tileWrap);
            }
        }
        // The FrostOverlay needs measured tile positions to mask the blur. Refresh it
        // on the next pre-draw (after layout, before the frame paints) so the grid is
        // frosted on its first visible frame — no solid→frost flash.
        refreshOverlaySoon();
        // restore focus（lastFocusedPkg 还在列表里时）；用 post 等 layout 算完位置
        final View target = tileToFocus;
        if (target != null) {
            grid.post(target::requestFocus);
        }
    }

    /** Invalidate the FrostOverlay on the next pre-draw, once tiles are laid out. */
    private void refreshOverlaySoon() {
        if (frostOverlay == null || grid == null) return;
        grid.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewTreeObserver o = grid.getViewTreeObserver();
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                if (frostOverlay != null) frostOverlay.invalidate();
                return true;
            }
        });
    }

    private static String keyOf(ResolveInfo ri) {
        return ri.activityInfo.packageName + "/" + ri.activityInfo.name;
    }

    /** True when {@code apps} is the same set, same order, as what's on screen. */
    private boolean sameAsRendered(List<ResolveInfo> apps) {
        if (apps.size() != renderedKeys.size()) return false;
        for (int i = 0; i < apps.size(); i++) {
            if (!keyOf(apps.get(i)).equals(renderedKeys.get(i))) return false;
        }
        return true;
    }

    /** Re-focus the last-used tile without rebuilding (the tiles still exist). */
    private void restoreFocus() {
        if (lastFocusedPkg == null || grid == null) return;
        for (int i = 0; i < grid.getChildCount(); i++) {
            final View tile = tileInside(grid.getChildAt(i));
            if (lastFocusedPkg.equals(tile.getTag())) {
                grid.post(tile::requestFocus);
                return;
            }
        }
    }

    /** Instantly scroll so {@code tile} sits fully in view, below the fixed header —
     *  our own animation-free replacement for ScrollView's scroll-into-view. We reserve
     *  the focus-scale overflow (the tile grows to FOCUS_SCALE, spilling ~4% past its
     *  layout box) and a top/bottom inset, so the focused highlight is never clipped and
     *  the bottom row lands exactly at maxY (no leftover travel to slide into). */
    private void ensureVisible(View tile) {
        if (scroll == null || grid == null || tile == null || grid.getChildCount() == 0) return;
        int vh = scroll.getHeight();
        if (vh == 0) return;
        int[] tl = new int[2], sl = new int[2], ll = new int[2];
        scroll.getLocationInWindow(sl);
        tile.getLocationInWindow(tl);
        int cur = scroll.getScrollY();
        int vTop = tl[1] - sl[1];                 // tile top relative to viewport top
        int h = tile.getHeight();
        int extra = Math.round(h * (FOCUS_SCALE - 1f) / 2f);   // focus-scale spill each side
        int top = vTop - extra;                   // scaled top
        int bottom = vTop + h + extra;            // scaled bottom
        int headerH = dp(112), botInset = dp(28);
        int target = cur;
        if (top < headerH) target = cur - (headerH - top);
        else if (bottom > vh - botInset) target = cur + (bottom - (vh - botInset));
        // Max scroll from the LAST tile's real laid-out bottom — NOT content.getHeight():
        // GridLayout under-measures its height and fillViewport then caps the content at
        // the viewport, so a content-height maxY computes as 0 and nothing scrolls.
        View lastOuter = grid.getChildAt(grid.getChildCount() - 1);
        lastOuter.getLocationInWindow(ll);
        int contentBottom = (ll[1] - sl[1]) + cur + lastOuter.getHeight() + botInset;
        int maxY = Math.max(0, contentBottom - vh);
        target = Math.max(0, Math.min(target, maxY));
        if (target != cur) scroll.scrollTo(0, target);   // instant, one frame
    }

    private View tileInside(View wrap) {
        // wrap = FrameLayout outer; child 0 = the focusable tile
        if (wrap instanceof FrameLayout && ((FrameLayout) wrap).getChildCount() > 0) {
            return ((FrameLayout) wrap).getChildAt(0);
        }
        return wrap;
    }

    private View buildAppTile(final ResolveInfo ri) {
        final FrameLayout outer = new FrameLayout(this);
        outer.setClipChildren(false);
        outer.setClipToPadding(false);

        // Light card: just a focus ring + icon + label. The frosted backdrop is
        // painted behind it by the shared FrostOverlay, so the tile carries no
        // bitmap/shader and composites for free while scrolling.
        final LinearLayout tile = new LinearLayout(this);
        tile.setBackground(tileBorderBg());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        if (FROST) {
            // Frost mode: HW layer so the 1.08x focus-scale composites the cached
            // transparent card texture instead of re-rasterizing the border each frame.
            tile.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            // Solid mode: a resting elevation gives every card a soft drop shadow
            // (structure without frost); animateScale lifts it further on focus. No HW
            // layer — an elevation shadow reads more reliably when it isn't set.
            tile.setElevation(dpf(2));
        }
        int hPad = dp(10);
        int vPad = dp(18);
        tile.setPadding(hPad, vPad, hPad, vPad);
        tile.setFocusable(true);

        ImageView icon = new ImageView(this);
        Integer macIcon = MAC_ICONS ? ICON_OVERRIDE.get(ri.activityInfo.packageName) : null;
        final boolean isMac = (macIcon != null);

        // Shared footprint for every tile's icon zone, sized so a composited vendor
        // icon ends up the SAME visual size as a macOS-pack icon.
        int footprint = dp(80);
        try {
            if (isMac) {
                // Pre-shaped squircle with its own baked shadow → shown as-is.
                icon.setImageDrawable(getDrawable(macIcon));
            } else if (!ICON_PLATE) {
                icon.setImageDrawable(ri.loadIcon(getPackageManager()));
            } else {
                // Un-styled vendor icon: scaled to FILL, masked to the same macOS
                // squircle footprint as the pack, on a plate backing, with a soft
                // drop shadow tuned to match the pack's baked shadow. One compositor
                // → the whole grid lines up as one set: size, corner and shadow.
                icon.setImageDrawable(composeVendorIcon(ri.loadIcon(getPackageManager())));
            }
        } catch (Throwable ignored) {}
        icon.setLayoutParams(new LinearLayout.LayoutParams(footprint, footprint));
        tile.addView(icon);

        TextView name = new TextView(this);
        name.setText(ri.loadLabel(getPackageManager()));
        name.setTextColor(palette.textPrim);
        name.setTextSize(13);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        name.setLayoutParams(nlp);
        tile.addView(name);

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        outer.addView(tile, flp);

        int gap = dp(8);
        GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
        glp.width = 0;
        glp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        glp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        glp.setMargins(gap, gap, gap, gap);
        outer.setLayoutParams(glp);

        // Focus 时缩放 + 记住 package
        final String pkg = ri.activityInfo.packageName;
        tile.setTag(pkg);   // so restoreFocus() can find this tile without a rebuild
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            // ensureVisible BEFORE animateScale so we read the tile's unscaled position.
            if (hasFocus) { lastFocusedPkg = pkg; ensureVisible(tile); }
            animateScale(tile, hasFocus);
            if (frostOverlay != null) frostOverlay.invalidate();   // refresh focus scrim
        });

        tile.setOnClickListener(v -> launchApp(ri));

        tile.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_MENU
                    || keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
                showAppMenu(ri);
                return true;
            }
            return false;
        });

        return outer;
    }

    private void animateScale(View v, boolean focused) {
        float to = focused ? FOCUS_SCALE : 1.0f;
        // The card animates its own scale. In solid mode it also lifts its translationZ
        // so the drop shadow deepens on focus — the "raised" cue the frost scrim used to
        // give (0 in the dormant frost path, where the overlay carries the focus scrim).
        // In frost mode the overlay is refreshed ONCE on focus change (see the focus
        // listener) so we don't repaint it every animation frame.
        v.animate().scaleX(to).scaleY(to)
                .translationZ(FROST ? 0f : (focused ? dpf(6) : 0f))
                .setDuration(120).start();
    }

    private static final float FOCUS_SCALE = 1.08f;

    /** Tile background.
     *  Frost mode: transparent normally, a rounded focus ring when focused — the fill is
     *  painted behind the tile by the FrostOverlay.
     *  Solid mode (default): an opaque rounded card (cardBg), a brighter card + border on
     *  focus. The opaque round-rect also defines the outline the elevation shadow follows,
     *  so structure comes from fill + shadow rather than a blurred backdrop. */
    private Drawable tileBorderBg() {
        if (FROST) {
            GradientDrawable focus = new GradientDrawable();
            focus.setShape(GradientDrawable.RECTANGLE);
            focus.setColor(0x00000000);
            focus.setCornerRadius(dpf(14));
            focus.setStroke(dp(2), palette.cardBorder);
            StateListDrawable sld = new StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_focused}, focus);
            sld.addState(new int[]{}, new GradientDrawable());
            return sld;
        }
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setColor(palette.cardBg);
        normal.setCornerRadius(dpf(14));
        GradientDrawable focus = new GradientDrawable();
        focus.setShape(GradientDrawable.RECTANGLE);
        focus.setColor(palette.cardFocus);
        focus.setCornerRadius(dpf(14));
        focus.setStroke(dp(2), palette.cardBorder);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focus);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    // ────────────────────────────────────────────────────────────
    // Frosted-glass tiles (macOS "vibrancy")
    //
    // The device is API 30 — no RenderEffect/window backdrop blur. But the
    // wallpaper is a static bitmap we own, so we blur it ONCE (downscale +
    // stack blur) and sample the region behind each tile as its background,
    // with a light/dark translucent scrim on top. Real Gaussian-blur-of-
    // wallpaper, not a flat translucent fill.
    // ────────────────────────────────────────────────────────────
    private void prepareFrost(int wallpaperRes) {
        blurBmp = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;                       // 1920x1080 -> ~480x270
            Bitmap small = BitmapFactory.decodeResource(getResources(), wallpaperRes, o);
            if (small == null) return;
            Bitmap mut = small.copy(Bitmap.Config.ARGB_8888, true);
            if (mut != small) small.recycle();
            stackBlur(mut, 12);
            // Hand the tiles an IMMUTABLE copy. A mutable bitmap used as a shader
            // texture is re-uploaded to the GPU every frame (the system can't assume
            // it's unchanged) — with every tile sampling it each scroll frame that was
            // the real scroll-jank cost. Immutable → uploaded once, then cached.
            Bitmap immut = mut.copy(Bitmap.Config.ARGB_8888, false);
            if (immut != null) { mut.recycle(); blurBmp = immut; }
            else blurBmp = mut;
            int sw = getResources().getDisplayMetrics().widthPixels;
            blurScale = (float) sw / blurBmp.getWidth();   // ~4
        } catch (Throwable ignored) { blurBmp = null; }
    }

    /** Translucent material colour over the blur — light in light mode, dark in
     *  dark mode; the focused tile is a touch more opaque so it reads as raised. */
    private int frostScrim(boolean focus) {
        if (palette.dark) {
            return focus ? 0xB23A3A3E : 0xA61C1C1E;   // dark frost
        }
        return focus ? 0xC8FFFFFF : 0x99FFFFFF;       // light frost (~60% / ~60%)
    }

    /** Header material — biased toward the palette bg so the clock/date stay
     *  legible over the blurred wallpaper. Stronger than the tile scrim; the
     *  header is opaque either way (blurBmp has no alpha), so this is purely how
     *  much wallpaper-blur vs. flat tint shows through. Tune to taste. */
    private int headerScrim() {
        return palette.dark ? 0xB31C1C1E : 0xB3F4F5F7;   // ~70%
    }

    // ────────────────────────────────────────────────────────────
    // InsetScrollView — reserves the top padding band (the fixed header) when it
    // scrolls a focused tile into view. The stock ScrollView treats y=0 as the
    // visible top, so D-pad focus moving UP would park a row behind the header;
    // here the visible top is pushed down by paddingTop so focus stays under it.
    // ────────────────────────────────────────────────────────────
    // ScrollView with its built-in scroll-into-view DISABLED. The stock behaviour
    // animates (smooth-scrolls) a newly focused row into view, and it routes through
    // more than one entry point — so instead of trying to force each to be instant we
    // neutralise them all at the source: computeScrollDelta…→0 means ScrollView never
    // auto-scrolls. HomeActivity.ensureVisible() then positions the scroll itself with
    // a single instant scrollTo() on focus change — our own crisp, animation-free move.
    private static class InsetScrollView extends ScrollView {
        InsetScrollView(Context c) { super(c); }
        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }
    }

    // ────────────────────────────────────────────────────────────
    // FrostPanel — a panel that paints a live frosted-glass backdrop in onDraw:
    // it samples the pre-blurred wallpaper at its CURRENT on-screen position every
    // time it draws, so the blur stays locked to the (fixed) wallpaper while the
    // panel scrolls — instead of baking one crop at layout time that then travels
    // with the row. Backs both the app tiles and the fixed header.
    // ────────────────────────────────────────────────────────────
    private class FrostPanel extends LinearLayout {
        private final float radius;       // corner radius px (0 = square / full-bleed)
        private final boolean drawFocus;  // draw the focus ring + use the focus scrim
        private final boolean bottomLine; // hairline divider along the bottom (header)
        private int scrimN, scrimF;

        private final int[] loc = new int[2];
        private final int[] rloc = new int[2];
        private final Matrix sm = new Matrix();
        private BitmapShader shader;   // lazily wraps the shared blurBmp
        private final Paint frostPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

        FrostPanel(Context c, float radiusDp, boolean drawFocus, boolean bottomLine) {
            super(c);
            this.radius = dpf(radiusDp);
            this.drawFocus = drawFocus;
            this.bottomLine = bottomLine;
            setWillNotDraw(false);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dpf(2));
            borderPaint.setColor(palette.cardBorder);
            linePaint.setColor(palette.dark ? 0x1AFFFFFF : 0x14000000);
        }

        void setScrims(int normal, int focus) { scrimN = normal; scrimF = focus; }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (drawFocus) invalidate();   // focus / press → repaint scrim + ring
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) { super.onDraw(canvas); return; }
            boolean focused = drawFocus && isFocused();

            if (blurBmp != null) {
                // Frost = the fixed wallpaper-blur, sampled at our live screen position,
                // drawn as a SHADER-filled rounded rect (a GPU primitive) — NOT clipPath
                // + drawBitmap. A non-rect clipPath forces a per-frame stencil mask on
                // every tile, which is what pinned scroll frames at ~57ms on this SoC.
                // A BitmapShader whose local matrix maps the blur onto our on-screen
                // position rounds the corners for free and stays locked to the wallpaper.
                // drawRoundRect/drawRect are self-bounded, so nothing bleeds even though
                // the parents run clipChildren=false (no clip needed → no veil).
                if (shader == null) {
                    shader = new BitmapShader(blurBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    frostPaint.setShader(shader);
                }
                getLocationInWindow(loc);
                rootFrame.getLocationInWindow(rloc);
                sm.setScale(blurScale, blurScale);
                sm.postTranslate(-(loc[0] - rloc[0]), -(loc[1] - rloc[1]));
                shader.setLocalMatrix(sm);
                scrimPaint.setColor(focused ? scrimF : scrimN);
                if (radius > 0) {
                    canvas.drawRoundRect(0, 0, w, h, radius, radius, frostPaint);
                    canvas.drawRoundRect(0, 0, w, h, radius, radius, scrimPaint);
                } else {
                    canvas.drawRect(0, 0, w, h, frostPaint);
                    canvas.drawRect(0, 0, w, h, scrimPaint);
                }
            } else {
                // No wallpaper → flat material (matches the old solid tileBackground).
                solidPaint.setColor(focused ? palette.cardFocus : palette.cardBg);
                if (radius > 0) canvas.drawRoundRect(0, 0, w, h, radius, radius, solidPaint);
                else canvas.drawRect(0, 0, w, h, solidPaint);
            }

            if (bottomLine) canvas.drawRect(0, h - dpf(1), w, h, linePaint);
            if (focused && radius > 0) {
                float in = borderPaint.getStrokeWidth() / 2f;
                canvas.drawRoundRect(in, in, w - in, h - in, radius, radius, borderPaint);
            }
            super.onDraw(canvas);   // then the children (icon + label) paint on top
        }
    }

    // ────────────────────────────────────────────────────────────
    // FrostOverlay — ONE fixed view that paints the frosted-glass backdrop for the
    // whole grid. Each frame it draws the pre-blurred wallpaper, masked to every
    // tile's CURRENT rounded-rect position, with a translucent scrim on top. Because
    // the view is fixed to the screen (it never scrolls) the blur stays perfectly
    // locked to the wallpaper as tiles slide over it — no drift, no settle delay. It
    // is laid out at HALF resolution and scaled 2x: the blur is low-detail so the
    // upscale is invisible, but re-rasterizing on each scroll frame costs ~1/4 as
    // much (the dominant cost is the destination fill area). Tiles draw their
    // icon/label/border above this view; gaps stay transparent here so the sharp
    // wallpaper background shows through between cards.
    // ────────────────────────────────────────────────────────────
    private class FrostOverlay extends View {
        private final int[] loc = new int[2];
        private final int[] rloc = new int[2];
        private final Matrix sm = new Matrix();
        private BitmapShader shader;
        private final Paint frostPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float rad = dpf(14);
        private final float visTop = dp(112) / 2f;   // header band (half-res); covered by header

        FrostOverlay(Context c) { super(c); }

        @Override protected void onDraw(Canvas canvas) {
            if (blurBmp == null || grid == null) return;
            if (shader == null) {
                shader = new BitmapShader(blurBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                // Half-res: this view's local px = screen px / 2; a blurBmp pixel maps to
                // screen ×blurScale → local ×blurScale/2. Screen-anchored, so set once.
                sm.setScale(blurScale / 2f, blurScale / 2f);
                shader.setLocalMatrix(sm);
                frostPaint.setShader(shader);
            }
            rootFrame.getLocationInWindow(rloc);
            float visBottom = getHeight();
            for (int i = 0; i < grid.getChildCount(); i++) {
                View outer = grid.getChildAt(i);          // wrapper — never scales
                float ow = outer.getWidth(), oh = outer.getHeight();
                if (ow <= 0 || oh <= 0) continue;
                View t = tileInside(outer);
                // Draw the focused tile's frost at the FINAL focus scale (not the live,
                // mid-animation getScaleX) so the overlay needs refreshing only on focus
                // change, not every animation frame.
                float s = t.isFocused() ? FOCUS_SCALE : 1.0f;
                outer.getLocationInWindow(loc);
                float cxh = (loc[0] - rloc[0] + ow / 2f) / 2f;   // half-res centre
                float cyh = (loc[1] - rloc[1] + oh / 2f) / 2f;
                float hwid = ow * s / 4f, hht = oh * s / 4f;     // half-res half-extent
                float top = cyh - hht, bot = cyh + hht;
                if (bot < visTop || top > visBottom) continue;   // off-screen / behind header
                float l = cxh - hwid, r = cxh + hwid;
                float rr = rad * s / 2f;
                canvas.drawRoundRect(l, top, r, bot, rr, rr, frostPaint);
                scrimPaint.setColor(frostScrim(t.isFocused()));
                canvas.drawRoundRect(l, top, r, bot, rr, rr, scrimPaint);
            }
        }
    }

    /** Mario Klingemann's stack blur (in place). Fast, no RenderScript. */
    private static void stackBlur(Bitmap bmp, int radius) {
        if (radius < 1) return;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);
        int wm = w - 1, hm = h - 1, wh = w * h, div = radius + radius + 1;
        int[] r = new int[wh], g = new int[wh], b = new int[wh];
        int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
        int[] vmin = new int[Math.max(w, h)];
        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (i = 0; i < 256 * divsum; i++) dv[i] = (i / divsum);
        yw = yi = 0;
        int[][] stack = new int[div][3];
        int stackpointer, stackstart, rbs, r1 = radius + 1, routsum, goutsum, boutsum, rinsum, ginsum, binsum;
        int[] sir;
        for (y = 0; y < h; y++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p & 0xff0000) >> 16; sir[1] = (p & 0x00ff00) >> 8; sir[2] = (p & 0x0000ff);
                rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs;
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; }
            }
            stackpointer = radius;
            for (x = 0; x < w; x++) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum];
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum;
                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2];
                if (y == 0) vmin[x] = Math.min(x + radius + 1, wm);
                p = pix[yw + vmin[x]];
                sir[0] = (p & 0xff0000) >> 16; sir[1] = (p & 0x00ff00) >> 8; sir[2] = (p & 0x0000ff);
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2];
                rsum += rinsum; gsum += ginsum; bsum += binsum;
                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2];
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2];
                yi++;
            }
            yw += w;
        }
        for (x = 0; x < w; x++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                sir = stack[i + radius];
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi];
                rbs = r1 - Math.abs(i);
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs;
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; }
                if (i < hm) yp += w;
            }
            yi = x; stackpointer = radius;
            for (y = 0; y < h; y++) {
                pix[yi] = (0xff000000 & pix[yi]) | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum;
                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2];
                if (x == 0) vmin[y] = Math.min(y + r1, hm) * w;
                p = x + vmin[y];
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p];
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2];
                rsum += rinsum; gsum += ginsum; bsum += binsum;
                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer];
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2];
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2];
                yi += w;
            }
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }

    private GradientDrawable roundRect(int fill, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeWidth > 0 && strokeColor != 0) {
            g.setStroke(strokeWidth, strokeColor);
        }
        return g;
    }

    // ────────────────────────────────────────────────────────────
    // Unified icon compositor (for apps without a hand-styled icon)
    //
    // Measured from the extracted macOS pack PNGs (512²): the squircle fills
    // 0.797 of the canvas, centred, and the baked shadow sits just below it.
    // We reproduce that exactly for every other icon — scale-to-fill, mask to
    // the squircle, plate backing for transparent logos, matching soft shadow —
    // so the grid reads as one set instead of "macOS icons + shrunken white
    // frames". The macOS PNGs themselves are left untouched (their shadow is
    // already baked in and high quality; we just match it).
    // ────────────────────────────────────────────────────────────
    private static final float ICON_FILL   = 0.797f;   // squircle : footprint (from the pack)
    private static final float ICON_RADIUS  = 0.2237f; // corner radius : squircle size (macOS-ish)

    private Drawable composeVendorIcon(Drawable src) {
        int fp = dp(80);                                   // footprint = ImageView box (== mac icons)
        int rect = Math.round(fp * ICON_FILL);             // visible squircle edge
        float radius = rect * ICON_RADIUS;
        int m = (fp - rect) / 2;                           // centred
        RectF r = new RectF(m, m, m + rect, m + rect);

        Bitmap bmp = Bitmap.createBitmap(fp, fp, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);                        // software canvas → setShadowLayer works
        int plateFill = palette.dark ? 0xFF2A2F39 : 0xFFFFFFFF;

        // (1) soft drop shadow — replicates the pack's baked shadow
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(plateFill);
        shadow.setShadowLayer(dpf(2.5f), 0, dpf(1f), palette.dark ? 0x40000000 : 0x2B000000);
        c.drawRoundRect(r, radius, radius, shadow);

        // (2) plate backing (shows through transparent-logo icons)
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(plateFill);
        c.drawRoundRect(r, radius, radius, fill);

        // (3) the icon, centre-cropped to fill, clipped to the squircle
        int save = c.save();
        Path clip = new Path();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        if (src != null) {
            int iw = src.getIntrinsicWidth(), ih = src.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = ih = rect; }
            float s = Math.max((float) rect / iw, (float) rect / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            int dx = Math.round(r.centerX() - dw / 2f), dy = Math.round(r.centerY() - dh / 2f);
            src.setBounds(dx, dy, dx + dw, dy + dh);
            src.draw(c);
        }
        c.restoreToCount(save);

        // (4) hairline edge — keeps light icons crisp against the frosted tile
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dpf(1f));
        edge.setColor(palette.dark ? 0x1FFFFFFF : 0x14000000);
        c.drawRoundRect(r, radius, radius, edge);

        return new BitmapDrawable(getResources(), bmp);
    }

    private float dpf(float v) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ────────────────────────────────────────────────────────────
    // Menu key actions
    // ────────────────────────────────────────────────────────────
    private void showAppMenu(ResolveInfo ri) {
        final String pkg = ri.activityInfo.packageName;
        final CharSequence label = ri.loadLabel(getPackageManager());
        String[] items = new String[]{
                getString(R.string.menu_uninstall),
                getString(R.string.menu_app_info),
                getString(R.string.menu_cancel),
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label)
                .setItems(items, (dlg, which) -> {
                    if (which == 0) uninstall(pkg);
                    else if (which == 1) appInfo(pkg);
                })
                .show();
    }

    private void uninstall(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("卸载失败: " + t.getMessage());
        }
    }

    private void appInfo(String pkg) {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("应用信息失败: " + t.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────
    // App list
    // ────────────────────────────────────────────────────────────
    // Packages already reachable from the top-bar pills (信号源 / 设置) —
    // keep them out of the app grid so the two zones don't show the same
    // destination twice. The settings pill resolves to com.kgzn.setting.
    //
    // com.android.documentsui is AOSP's bare stock file browser — a redundant
    // second "文件" entry next to the richer, dark-themed vendor file manager
    // (com.kgzn.filemanager / Finder). One destination, one tile.
    // com.android.quicksearchbox is AOSP's stock "Search" launcher stub — dead
    // weight on a TV with no global search backend behind it.
    private static final Set<String> GRID_EXCLUDE = new HashSet<>(Arrays.asList(
            "com.kgzn.setting"));
    // Curated out only when GRID_DEDUP is on (the demo's "drop the AOSP shells" beat).
    private static final Set<String> DEDUP_EXCLUDE = new HashSet<>(Arrays.asList(
            "com.android.documentsui",        // bare stock "文件" — dup of vendor Finder
            "com.android.quicksearchbox"));   // stock "Search" stub, no backend

    // macOS icon pack — give a few apps real macOS icons (extracted .icns) so the
    // grid reads as one cohesive set instead of mismatched vendor/photo icons.
    private static final Map<String, Integer> ICON_OVERRIDE = new HashMap<>();
    static {
        ICON_OVERRIDE.put("com.kgzn.filemanager", R.drawable.ic_mac_finder);     // Finder
        ICON_OVERRIDE.put("com.kgzn.media", R.drawable.ic_mac_quicktime);        // QuickTime
        ICON_OVERRIDE.put("com.neilturner.aerialviews", R.drawable.ic_mac_tv);   // Apple TV
    }

    private List<ResolveInfo> loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<>();
        List<ResolveInfo> result = new ArrayList<>();
        for (String cat : new String[]{
                Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            for (ResolveInfo ri : ris) {
                String key = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                if (ri.activityInfo.packageName.equals(getPackageName())) continue;
                if (GRID_EXCLUDE.contains(ri.activityInfo.packageName)) continue;
                if (GRID_DEDUP && DEDUP_EXCLUDE.contains(ri.activityInfo.packageName)) continue;
                if (seen.add(key)) result.add(ri);
            }
        }
        Collections.sort(result, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                CharSequence la = a.loadLabel(pm); CharSequence lb = b.loadLabel(pm);
                if (la == null) la = a.activityInfo.packageName;
                if (lb == null) lb = b.activityInfo.packageName;
                return la.toString().compareToIgnoreCase(lb.toString());
            }
        });
        return result;
    }

    private void launchApp(ResolveInfo ri) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i, openAnim());
        } catch (Throwable t) {
            toast("启动失败: " + t.getMessage());
        }
    }

    private void openSource() {
        try {
            Intent i = new Intent("android.intent.action.INPUT_SOURCE_ACTIVITY");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i, openAnim());
        } catch (Throwable t) {
            toast("信号源失败: " + t.getMessage());
        }
    }

    private void openSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i, openAnim());
        } catch (Throwable t) {
            toast("设置失败: " + t.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────
    // One-tap clean + app manager
    // ────────────────────────────────────────────────────────────
    /** Short-press the 清理 pill: force-stop every running, non-protected app. */
    private void cleanAll() {
        List<AppOps.Running> running = AppOps.runningUserApps(this);
        int n = 0;
        long freedKb = 0;
        if (!running.isEmpty()) {
            for (AppOps.Running r : running) {
                AppOps.forceStop(this, r.pkg);
                n++;
                freedKb += r.pssKb;
            }
        } else {
            // REAL_GET_TASKS not granted yet — stop the launchable, non-protected
            // candidates blind (a force-stop on an idle app is a harmless no-op).
            for (String pkg : AppOps.launchablePackages(this)) {
                if (pkg.equals(getPackageName()) || AppOps.isProtected(this, pkg)) continue;
                AppOps.forceStop(this, pkg);
                n++;
            }
        }
        if (n == 0) {
            toast("没有需要清理的后台应用");
        } else if (freedKb > 0) {
            toast("已清理 " + n + " 个应用 · 释放约 " + ((freedKb + 512) / 1024) + " MB");
        } else {
            toast("已清理 " + n + " 个后台应用");
        }
    }

    /** Long-press / MENU on the 清理 pill: open the running + autostart manager. */
    private void openManager() {
        try {
            startActivity(new Intent(this, AppManagerActivity.class), openAnim());
        } catch (Throwable t) {
            toast("打开失败: " + t.getMessage());
        }
    }

    /**
     * Open transition for launching ANY app from the launcher. The opened app's
     * own theme — not ours — governs its default launch animation, so the only
     * lever a launcher has is ActivityOptions. makeCustomAnimation lets us supply
     * both halves: the app zooms up (app_enter) while the desktop zooms in + fades
     * (home_exit) — the exact mirror of the theme-driven return (home_enter +
     * app_exit) on BACK / HOME. Returns null (system default) if it can't build.
     */
    private Bundle openAnim() {
        try {
            return ActivityOptions.makeCustomAnimation(
                    this, R.anim.app_enter, R.anim.home_exit).toBundle();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────
    // Clock
    // ────────────────────────────────────────────────────────────
    private void tickClock() {
        final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        final SimpleDateFormat md = new SimpleDateFormat("yyyy 年 M 月 d 日 EEEE", Locale.getDefault());
        clockView.post(new Runnable() {
            @Override public void run() {
                Date now = new Date();
                clockView.setText(hm.format(now));
                dateView.setText(md.format(now));

                // Re-evaluate palette every tick. If the time boundary just
                // crossed (e.g. 19:59 → 20:00), rebuild the UI under the new
                // palette and stop this clock runnable — the new tickClock()
                // call inside the rebuild will resume the tick chain.
                Palette next = Palette.forTime();
                if (!next.equals(palette)) {
                    palette = next;
                    buildUi();
                    tickClock();
                    refreshApps();
                    return;
                }

                clockView.postDelayed(this, 20000);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ────────────────────────────────────────────────────────────
    // Palette — time-of-day driven (NOT system uimode driven).
    //
    // 这台 KGZN ROM 上 Settings.Secure.ui_night_mode 和 cmd uimode night
    // 都没有传到 Configuration.UI_MODE_NIGHT_MASK 上来 — 2026-05-27 用户测过。
    // 所以我们彻底走时间窗口：DARK_START_HOUR ≤ hour < 24 || hour < DARK_END_HOUR → dark
    // 例如 (20, 6) → 20/21/22/23/0..5 = dark；6..19 = light。
    //
    // Mini LED 面板 — 配色避开纯黑/纯白：
    //  - 纯黑 (#000) 让 local dimming zone 全关，但卡片/文字 zone 仍亮 →
    //    边界出现明显 blooming（光晕）。bg 抬到 ~7% luminance 保持 zone
    //    始终有基础亮度，dimming 过渡才柔和。
    //  - 纯白 (#FFF) 在 Mini LED 峰值 1000+ nit 下 = 视网膜冲击，2-3m
    //    距离很累。light 用 paper white ~96%。
    //  - 饱和的 border 颜色在 dark 上变"灯泡"（高亮度+小面积+黑 bg） →
    //    用 muted slate blue。
    // 参考: Apple tvOS systemBackground, YouTube TV #0F0F0F, Disney+ #1A1D29
    // ────────────────────────────────────────────────────────────
    static final class Palette {
        // Tunable: 24h boundaries for dark mode window. Inclusive start, exclusive end.
        // Default (20, 6) means dark from 20:00 through 05:59, light from 06:00 to 19:59.
        // If start < end (e.g. 13, 17) the window is the single contiguous slice;
        // if start > end (default, crossing midnight) the dark slice wraps.
        private static final int DARK_START_HOUR = 20;
        private static final int DARK_END_HOUR   = 6;

        final boolean dark;
        final int bg, cardBg, cardFocus, cardBorder, textPrim, textSec;

        private Palette(boolean dark, int bg, int cardBg, int cardFocus,
                        int cardBorder, int textPrim, int textSec) {
            this.dark = dark;
            this.bg = bg;
            this.cardBg = cardBg;
            this.cardFocus = cardFocus;
            this.cardBorder = cardBorder;
            this.textPrim = textPrim;
            this.textSec = textSec;
        }

        static Palette forTime() {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            boolean dark;
            if (DARK_START_HOUR < DARK_END_HOUR) {
                dark = (hour >= DARK_START_HOUR && hour < DARK_END_HOUR);
            } else {
                // wraps midnight (default config)
                dark = (hour >= DARK_START_HOUR || hour < DARK_END_HOUR);
            }
            if (dark) {
                return new Palette(true,
                        0xFF14181F,  // bg: ~8% luminance, "soft black"
                        0xFF1F242D,  // card: ~13%, 抬出层次
                        0xFF2A313D,  // card focus: ~19%, 微微抬起
                        0xFF4E6FA0,  // border: muted slate blue, 不灯泡
                        0xFFDEE3EB,  // text: ~88% off-white
                        0xFF8A929C); // text secondary: 中性灰
            }
            return new Palette(false,
                    0xFFEDEFF2,      // bg: 暖灰，不刺眼
                    0xFFF7F8FA,      // card: paper white ~97%
                    0xFFE0EAF6,      // card focus: 蓝色淡 tint
                    0xFF6F8DC4,      // border: 与 dark 同色相，muted
                    0xFF1F2937,      // text: gray-800，比纯黑舒缓
                    0xFF5C6471);     // text secondary
        }

        @Override public boolean equals(Object o) {
            return o instanceof Palette && ((Palette) o).dark == this.dark;
        }
        @Override public int hashCode() { return dark ? 1 : 0; }
    }
}

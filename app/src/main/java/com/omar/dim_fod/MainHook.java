package com.omar.dim_fod;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module implementing MTK GHBM UDFPS dim layer fix.
 *
 * Replicates the behavior from the Lineage patch:
 *   - MtkUdfpsScrimController: brightness → alpha calculation
 *   - UdfpsController: onFingerDown/onFingerUp hbmView visibility + alpha update
 *   - UdfpsControllerOverlay: hbmView + dimView creation and WM add/remove lifecycle
 *
 * Target: com.android.systemui
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "UdfpsMtkFix";
    private static final String SYSUI_PKG = "com.android.systemui";

    // Class names we hook
    private static final String CLS_UDFPS_CONTROLLER =
            "com.android.systemui.biometrics.UdfpsController";
    private static final String CLS_UDFPS_OVERLAY =
            "com.android.systemui.biometrics.UdfpsControllerOverlay";

    // The name HWC expects for the dim layer — must match config_udfpsHbmDimLayer
    private static final String HBM_DIM_LAYER_NAME = "OnScreenFingerprintDimLayer";

    // -----------------------------------------------------------------------
    // Per-overlay state — stored as tags on the overlay instance so we don't
    // need a separate map (overlays are created/destroyed per auth session).
    // -----------------------------------------------------------------------
    private static final int TAG_HBM_VIEW    = 0x4d544b01; // "MTK\x01"
    private static final int TAG_DIM_VIEW    = 0x4d544b02;
    private static final int TAG_HBM_PARAMS  = 0x4d544b03;
    private static final int TAG_DIM_PARAMS  = 0x4d544b04;
    private static final int TAG_IS_ADDED    = 0x4d544b05; // Boolean

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSUI_PKG.equals(lpparam.packageName)) return;

        Log.i(TAG, "SystemUI loaded — installing MTK UDFPS dim layer hooks");

        hookOverlayShow(lpparam.classLoader);
        hookOverlayHide(lpparam.classLoader);
        hookOverlayUpdateLayout(lpparam.classLoader);
        hookFingerDown(lpparam.classLoader);
        hookFingerUp(lpparam.classLoader);
        hookHideUdfpsOverlay(lpparam.classLoader);
    }

    // -----------------------------------------------------------------------
    // 1. UdfpsControllerOverlay — show()
    //    Called when the overlay is first added to WindowManager.
    //    We add hbmView + dimView here, then add the core view as usual.
    // -----------------------------------------------------------------------
    private void hookOverlayShow(ClassLoader cl) {
        // The method that calls windowManager.addView(view, coreLayoutParams...)
        // In AOSP it's named "show" and takes a UdfpsAnimation parameter.
        XposedHelpers.findAndHookMethod(
                CLS_UDFPS_OVERLAY, cl,
                "show",
                // argument: UdfpsAnimation (we don't care about the type, just hook)
                XposedHelpers.findClass(
                        "com.android.systemui.biometrics.UdfpsAnimation", cl),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object overlay = param.thisObject;
                        Context ctx = getContext(overlay);
                        if (ctx == null) return;

                        WindowManager wm = getWindowManager(overlay);
                        if (wm == null) return;

                        // Create views and params if not already created
                        if (getTag(overlay, TAG_HBM_VIEW) == null) {
                            initViews(overlay, ctx, wm);
                        }

                        // Add hbmView and dimView before the core overlay view
                        Boolean isAdded = (Boolean) getTag(overlay, TAG_IS_ADDED);
                        if (isAdded == null || !isAdded) {
                            WindowManager.LayoutParams hbmParams =
                                    (WindowManager.LayoutParams) getTag(overlay, TAG_HBM_PARAMS);
                            WindowManager.LayoutParams dimParams =
                                    (WindowManager.LayoutParams) getTag(overlay, TAG_DIM_PARAMS);
                            View hbmView = (View) getTag(overlay, TAG_HBM_VIEW);
                            View dimView = (View) getTag(overlay, TAG_DIM_VIEW);

                            // Update dimensions to match the sensor bounds
                            Rect bounds = getSensorBounds(overlay);
                            if (bounds != null && hbmParams != null && dimParams != null) {
                                hbmParams.width  = bounds.width();
                                hbmParams.height = bounds.height();
                                hbmParams.x      = bounds.left;
                                hbmParams.y      = bounds.top;

                                // dimView covers the full screen
                                dimParams.width  = WindowManager.LayoutParams.MATCH_PARENT;
                                dimParams.height = WindowManager.LayoutParams.MATCH_PARENT;
                            }

                            try {
                                if (hbmView != null && hbmParams != null)
                                    wm.addView(hbmView, hbmParams);
                                if (dimView != null && dimParams != null)
                                    wm.addView(dimView, dimParams);
                                setTag(overlay, TAG_IS_ADDED, true);
                                Log.d(TAG, "Added HBM + Dim views to WM");
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to add HBM/Dim views", e);
                            }
                        }
                    }
                }
        );
    }

    // -----------------------------------------------------------------------
    // 2. UdfpsControllerOverlay — hide() / destroy()
    //    Remove hbmView + dimView from WindowManager.
    // -----------------------------------------------------------------------
    private void hookOverlayHide(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(
                CLS_UDFPS_OVERLAY, cl,
                "hide",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object overlay = param.thisObject;
                        removeHbmViews(overlay);
                    }
                }
        );
    }

    // -----------------------------------------------------------------------
    // 3. UdfpsControllerOverlay — updateLayout() / onConfigurationChanged()
    //    Keep hbmView layout in sync when sensor bounds change.
    // -----------------------------------------------------------------------
    private void hookOverlayUpdateLayout(ClassLoader cl) {
        // The method that calls windowManager.updateViewLayout(it, coreLayoutParams...)
        // AOSP calls this "updateOverlayParams"
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_UDFPS_OVERLAY, cl,
                    "updateOverlayParams",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            syncHbmLayout(param.thisObject);
                        }
                    }
            );
        } catch (Throwable t) {
            Log.w(TAG, "updateOverlayParams not found, trying onConfigChanged: " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 4. UdfpsController — onFingerDown()
    //    Make hbmView visible, calculate brightness-based alpha, apply it.
    // -----------------------------------------------------------------------
    private void hookFingerDown(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(
                CLS_UDFPS_CONTROLLER, cl,
                "onFingerDown",
                long.class,    // requestId
                int.class,     // pointerId
                float.class,   // x
                float.class,   // y
                float.class,   // minor
                float.class,   // major
                float.class,   // orientation
                long.class,    // time
                long.class,    // gestureStart
                boolean.class, // isAod
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object controller = param.thisObject;
                        Object overlay = getOverlay(controller);
                        if (overlay == null) return;

                        View hbmView = (View) getTag(overlay, TAG_HBM_VIEW);
                        WindowManager.LayoutParams hbmParams =
                                (WindowManager.LayoutParams) getTag(overlay, TAG_HBM_PARAMS);
                        if (hbmView == null || hbmParams == null) return;

                        Context ctx = getContext(overlay);
                        if (ctx == null) return;

                        int brightness = getSystemBrightness(ctx);
                        float alpha = calculateAlpha(brightness);

                        Log.d(TAG, "onFingerDown — brightness=" + brightness
                                + " alpha=" + alpha);

                        if (hbmView.getVisibility() != View.VISIBLE) {
                            hbmView.setVisibility(View.VISIBLE);
                        }

                        if (Math.abs(hbmParams.alpha - alpha) > 0.001f) {
                            hbmParams.alpha = alpha;
                            WindowManager wm = getWindowManager(overlay);
                            if (wm != null) {
                                try {
                                    wm.updateViewLayout(hbmView, hbmParams);
                                } catch (Exception e) {
                                    Log.w(TAG, "updateViewLayout failed in onFingerDown", e);
                                }
                            }
                        }
                    }
                }
        );
    }

    // -----------------------------------------------------------------------
    // 5. UdfpsController — onFingerUp()
    //    Hide hbmView.
    // -----------------------------------------------------------------------
    private void hookFingerUp(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(
                CLS_UDFPS_CONTROLLER, cl,
                "onFingerUp",
                long.class, // requestId
                XposedHelpers.findClass(
                        "com.android.systemui.biometrics.UdfpsTouchOverlay", cl),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object controller = param.thisObject;
                        Object overlay = getOverlay(controller);
                        if (overlay == null) return;

                        View hbmView = (View) getTag(overlay, TAG_HBM_VIEW);
                        if (hbmView != null) {
                            hbmView.setVisibility(View.GONE);
                            Log.d(TAG, "onFingerUp — hbmView hidden");
                        }
                    }
                }
        );
    }

    // -----------------------------------------------------------------------
    // 6. UdfpsController — hideUdfpsOverlay()
    //    Safety net: ensure hbmView is gone even if fingerUp wasn't called.
    // -----------------------------------------------------------------------
    private void hookHideUdfpsOverlay(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(
                CLS_UDFPS_CONTROLLER, cl,
                "hideUdfpsOverlay",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object controller = param.thisObject;
                        Object overlay = getOverlay(controller);
                        if (overlay == null) return;

                        View hbmView = (View) getTag(overlay, TAG_HBM_VIEW);
                        if (hbmView != null) {
                            hbmView.setVisibility(View.GONE);
                        }
                    }
                }
        );
    }

    // -----------------------------------------------------------------------
    // Helpers — view / param creation
    // -----------------------------------------------------------------------

    private void initViews(Object overlay, Context ctx, WindowManager wm) {
        // hbmView: full-black layer, initially invisible
        View hbmView = new View(ctx);
        hbmView.setBackgroundColor(Color.BLACK);
        hbmView.setVisibility(View.INVISIBLE);

        // dimView: transparent overlay that satisfies HWC layer expectations
        View dimView = new View(ctx);
        dimView.setBackgroundColor(Color.TRANSPARENT);

        // hbmLayoutParams — named "OnScreenFingerprintDimLayer" for HWC
        WindowManager.LayoutParams hbmParams = buildLayerParams(HBM_DIM_LAYER_NAME);
        hbmParams.alpha = 0.1f;

        // dimLayoutParams
        WindowManager.LayoutParams dimParams = buildLayerParams("UdfpsDim");

        setTag(overlay, TAG_HBM_VIEW,   hbmView);
        setTag(overlay, TAG_DIM_VIEW,   dimView);
        setTag(overlay, TAG_HBM_PARAMS, hbmParams);
        setTag(overlay, TAG_DIM_PARAMS, dimParams);
        setTag(overlay, TAG_IS_ADDED,   false);

        Log.d(TAG, "initViews: created hbmView + dimView");
    }

    private WindowManager.LayoutParams buildLayerParams(String title) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        lp.title = title;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.fitInsetsTypes = 0;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.accessibilityTitle = " ";

        // PRIVATE_FLAG_TRUSTED_OVERLAY — value 0x20000000, constant available API 31+
        try {
            int trustedOverlay = (int) WindowManager.LayoutParams.class
                    .getField("PRIVATE_FLAG_TRUSTED_OVERLAY").get(null);
            lp.privateFlags |= trustedOverlay;
        } catch (Exception e) {
            Log.w(TAG, "PRIVATE_FLAG_TRUSTED_OVERLAY not found: " + e.getMessage());
        }

        // INPUT_FEATURE_SPY — value 0x00000004
        try {
            int spyFlag = (int) WindowManager.LayoutParams.class
                    .getField("INPUT_FEATURE_SPY").get(null);
            lp.inputFeatures |= spyFlag;
        } catch (Exception e) {
            Log.w(TAG, "INPUT_FEATURE_SPY not found: " + e.getMessage());
        }

        return lp;
    }

    private void syncHbmLayout(Object overlay) {
        Boolean isAdded = (Boolean) getTag(overlay, TAG_IS_ADDED);
        if (isAdded == null || !isAdded) return;

        WindowManager wm = getWindowManager(overlay);
        if (wm == null) return;

        View hbmView = (View) getTag(overlay, TAG_HBM_VIEW);
        View dimView = (View) getTag(overlay, TAG_DIM_VIEW);
        WindowManager.LayoutParams hbmParams =
                (WindowManager.LayoutParams) getTag(overlay, TAG_HBM_PARAMS);
        WindowManager.LayoutParams dimParams =
                (WindowManager.LayoutParams) getTag(overlay, TAG_DIM_PARAMS);

        Rect bounds = getSensorBounds(overlay);
        if (bounds != null && hbmParams != null) {
            hbmParams.width  = bounds.width();
            hbmParams.height = bounds.height();
            hbmParams.x      = bounds.left;
            hbmParams.y      = bounds.top;
        }

        try {
            if (hbmView != null && hbmParams != null)
                wm.updateViewLayout(hbmView, hbmParams);
            if (dimView != null && dimParams != null)
                wm.updateViewLayout(dimView, dimParams);
        } catch (Exception e) {
            Log.w(TAG, "syncHbmLayout failed: " + e.getMessage());
        }
    }

    private void removeHbmViews(Object overlay) {
        Boolean isAdded = (Boolean) getTag(overlay, TAG_IS_ADDED);
        if (isAdded == null || !isAdded) return;

        WindowManager wm = getWindowManager(overlay);
        View hbmView = (View) getTag(overlay, TAG_HBM_VIEW);
        View dimView = (View) getTag(overlay, TAG_DIM_VIEW);

        if (wm != null) {
            try {
                if (hbmView != null) wm.removeView(hbmView);
                if (dimView != null) wm.removeView(dimView);
                Log.d(TAG, "Removed HBM + Dim views from WM");
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove HBM/Dim views: " + e.getMessage());
            }
        }

        setTag(overlay, TAG_IS_ADDED, false);
    }

    // -----------------------------------------------------------------------
    // MtkUdfpsScrimController logic — inlined (no separate class needed)
    // -----------------------------------------------------------------------

    /**
     * Reads the current screen brightness.
     * Tries screen_brightness_float first (0.0–1.0), falls back to SCREEN_BRIGHTNESS (0–255).
     */
    private int getSystemBrightness(Context ctx) {
        try {
            float brightFloat = Settings.System.getFloat(
                    ctx.getContentResolver(), "screen_brightness_float", -1f);
            if (brightFloat >= 0f) {
                return (int) (brightFloat * 255f);
            }
        } catch (Exception ignored) {}

        try {
            return Settings.System.getInt(
                    ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 127);
        } catch (Exception e) {
            return 127;
        }
    }

    /**
     * Converts brightness (0–255) to a window alpha (0.0–1.0).
     * Mirrors MtkUdfpsScrimController.calculateAlpha():
     *   alpha = 1 - (brightness / 255)
     *   if brightness < 25: apply extra 5% transparency (× 0.95)
     */
    private float calculateAlpha(int brightness) {
        float alpha = 1.0f - (brightness / 255.0f);
        if (brightness < 25) {
            alpha = alpha * 0.95f;
        }
        return Math.max(0.0f, Math.min(1.0f, alpha));
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------

    /** Get the Context from a UdfpsControllerOverlay instance. */
    private Context getContext(Object overlay) {
        try {
            return (Context) XposedHelpers.getObjectField(overlay, "context");
        } catch (Throwable t) {
            // Kotlin "context" might be stored differently
            try {
                Field f = overlay.getClass().getDeclaredField("mContext");
                f.setAccessible(true);
                return (Context) f.get(overlay);
            } catch (Throwable t2) {
                Log.w(TAG, "Could not get Context from overlay");
                return null;
            }
        }
    }

    /** Get the WindowManager from a UdfpsControllerOverlay instance. */
    private WindowManager getWindowManager(Object overlay) {
        try {
            return (WindowManager) XposedHelpers.getObjectField(overlay, "windowManager");
        } catch (Throwable t) {
            Log.w(TAG, "Could not get WindowManager from overlay: " + t.getMessage());
            return null;
        }
    }

    /** Get the current overlay from a UdfpsController instance. */
    private Object getOverlay(Object controller) {
        try {
            return XposedHelpers.getObjectField(controller, "mOverlay");
        } catch (Throwable t) {
            Log.w(TAG, "Could not get mOverlay from controller: " + t.getMessage());
            return null;
        }
    }

    /**
     * Get sensorBounds (Rect) from overlay — stored in UdfpsOverlayParams.
     * Path: overlay.overlayParams.sensorBounds  (Kotlin property)
     */
    private android.graphics.Rect getSensorBounds(Object overlay) {
        try {
            Object overlayParams = XposedHelpers.getObjectField(overlay, "overlayParams");
            if (overlayParams == null) return null;
            return (android.graphics.Rect) XposedHelpers.getObjectField(
                    overlayParams, "sensorBounds");
        } catch (Throwable t) {
            // Fallback: try sensorRect directly
            try {
                return (android.graphics.Rect) XposedHelpers.getObjectField(
                        overlay, "sensorRect");
            } catch (Throwable t2) {
                Log.w(TAG, "getSensorBounds failed: " + t2.getMessage());
                return null;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tag storage — uses View.setTag(int, Object) on a sentinel View to attach
    // arbitrary state to the overlay instance without needing a WeakHashMap.
    // We use the overlay object's identity hash as an anchor by keeping a
    // simple per-overlay ViewGroup tag holder.
    // -----------------------------------------------------------------------

    /**
     * We store our per-overlay state in a small Object[] attached directly to
     * the overlay via reflection into a synthetic field we inject once.
     * Simpler: use a static WeakHashMap keyed by overlay identity.
     */
    private static final java.util.WeakHashMap<Object, java.util.HashMap<Integer, Object>>
            sStateMap = new java.util.WeakHashMap<>();

    private void setTag(Object overlay, int key, Object value) {
        java.util.HashMap<Integer, Object> map =
                sStateMap.computeIfAbsent(overlay, k -> new java.util.HashMap<>());
        map.put(key, value);
    }

    private Object getTag(Object overlay, int key) {
        java.util.HashMap<Integer, Object> map = sStateMap.get(overlay);
        return map != null ? map.get(key) : null;
    }

    // Rect import shim (android.graphics.Rect is always available)
    private static class Rect extends android.graphics.Rect {}
}

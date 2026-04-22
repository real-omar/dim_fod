package com.omar.dim_fod;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
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

    private static final String CLS_UDFPS_CONTROLLER =
            "com.android.systemui.biometrics.UdfpsController";
    private static final String CLS_UDFPS_OVERLAY =
            "com.android.systemui.biometrics.UdfpsControllerOverlay";

    // The name MTK HWC expects for the dim layer
    private static final String HBM_DIM_LAYER_NAME = "OnScreenFingerprintDimLayer";

    // Keys for per-overlay state map
    private static final int TAG_HBM_VIEW   = 1;
    private static final int TAG_DIM_VIEW   = 2;
    private static final int TAG_HBM_PARAMS = 3;
    private static final int TAG_DIM_PARAMS = 4;
    private static final int TAG_IS_ADDED   = 5;

    // WindowManager.LayoutParams values not in the public SDK — set via reflection
    private static final int TYPE_NAVIGATION_BAR_PANEL         = 2024;
    private static final int PRIVATE_FLAG_TRUSTED_OVERLAY       = 0x20000000;
    private static final int INPUT_FEATURE_SPY                  = 0x00000004;
    private static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3;

    // Weak map to store per-overlay state without leaking
    private static final WeakHashMap<Object, HashMap<Integer, Object>> sStateMap =
            new WeakHashMap<>();

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
    // 1. UdfpsControllerOverlay.show() — add hbmView + dimView before core view
    // -----------------------------------------------------------------------
    private void hookOverlayShow(ClassLoader cl) {
        try {
            Class<?> animClass = XposedHelpers.findClass(
                    "com.android.systemui.biometrics.UdfpsAnimation", cl);
            XposedHelpers.findAndHookMethod(CLS_UDFPS_OVERLAY, cl, "show", animClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object overlay = param.thisObject;
                            Context ctx = getContext(overlay);
                            if (ctx == null) return;
                            WindowManager wm = getWindowManager(overlay);
                            if (wm == null) return;

                            if (getTag(overlay, TAG_HBM_VIEW) == null) {
                                initViews(overlay, ctx);
                            }

                            Boolean isAdded = (Boolean) getTag(overlay, TAG_IS_ADDED);
                            if (isAdded != null && isAdded) return;

                            WindowManager.LayoutParams hbmParams =
                                    (WindowManager.LayoutParams) getTag(overlay, TAG_HBM_PARAMS);
                            WindowManager.LayoutParams dimParams =
                                    (WindowManager.LayoutParams) getTag(overlay, TAG_DIM_PARAMS);
                            View hbmView = (View) getTag(overlay, TAG_HBM_VIEW);
                            View dimView = (View) getTag(overlay, TAG_DIM_VIEW);

                            Rect bounds = getSensorBounds(overlay);
                            if (bounds != null && hbmParams != null) {
                                hbmParams.width  = bounds.width();
                                hbmParams.height = bounds.height();
                                hbmParams.x      = bounds.left;
                                hbmParams.y      = bounds.top;
                            }
                            if (dimParams != null) {
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
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hookOverlayShow failed: " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 2. UdfpsControllerOverlay.hide() — remove hbmView + dimView
    // -----------------------------------------------------------------------
    private void hookOverlayHide(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_UDFPS_OVERLAY, cl, "hide",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            removeHbmViews(param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hookOverlayHide failed: " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 3. UdfpsControllerOverlay.updateOverlayParams() — sync layout on change
    // -----------------------------------------------------------------------
    private void hookOverlayUpdateLayout(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_UDFPS_OVERLAY, cl, "updateOverlayParams",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            syncHbmLayout(param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "updateOverlayParams not found (non-fatal): " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 4. UdfpsController.onFingerDown() — show hbmView, apply brightness alpha
    // -----------------------------------------------------------------------
    private void hookFingerDown(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_UDFPS_CONTROLLER, cl, "onFingerDown",
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
                            Log.d(TAG, "onFingerDown — brightness=" + brightness + " alpha=" + alpha);

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
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hookFingerDown failed: " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 5. UdfpsController.onFingerUp() — hide hbmView
    // -----------------------------------------------------------------------
    private void hookFingerUp(ClassLoader cl) {
        XC_MethodHook fingerUpHook = new XC_MethodHook() {
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
        };

        // Try UdfpsTouchOverlay signature first, fall back to View
        boolean hooked = false;
        try {
            Class<?> touchOverlayClass = XposedHelpers.findClass(
                    "com.android.systemui.biometrics.UdfpsTouchOverlay", cl);
            XposedHelpers.findAndHookMethod(
                    CLS_UDFPS_CONTROLLER, cl, "onFingerUp",
                    long.class, touchOverlayClass, fingerUpHook);
            hooked = true;
        } catch (Throwable ignored) {}

        if (!hooked) {
            try {
                XposedHelpers.findAndHookMethod(
                        CLS_UDFPS_CONTROLLER, cl, "onFingerUp",
                        long.class, View.class, fingerUpHook);
            } catch (Throwable t) {
                Log.e(TAG, "hookFingerUp failed: " + t.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // 6. UdfpsController.hideUdfpsOverlay() — safety net
    // -----------------------------------------------------------------------
    private void hookHideUdfpsOverlay(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_UDFPS_CONTROLLER, cl, "hideUdfpsOverlay",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object overlay = getOverlay(param.thisObject);
                            if (overlay == null) return;
                            View hbmView = (View) getTag(overlay, TAG_HBM_VIEW);
                            if (hbmView != null) hbmView.setVisibility(View.GONE);
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hookHideUdfpsOverlay failed: " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // View + params init
    // -----------------------------------------------------------------------
    private void initViews(Object overlay, Context ctx) {
        View hbmView = new View(ctx);
        hbmView.setBackgroundColor(Color.BLACK);
        hbmView.setVisibility(View.INVISIBLE);

        View dimView = new View(ctx);
        dimView.setBackgroundColor(Color.TRANSPARENT);

        WindowManager.LayoutParams hbmParams = buildLayerParams(HBM_DIM_LAYER_NAME);
        hbmParams.alpha = 0.1f;

        WindowManager.LayoutParams dimParams = buildLayerParams("UdfpsDim");

        setTag(overlay, TAG_HBM_VIEW,   hbmView);
        setTag(overlay, TAG_DIM_VIEW,   dimView);
        setTag(overlay, TAG_HBM_PARAMS, hbmParams);
        setTag(overlay, TAG_DIM_PARAMS, dimParams);
        setTag(overlay, TAG_IS_ADDED,   false);

        Log.d(TAG, "initViews: created hbmView + dimView");
    }

    /**
     * Builds WindowManager.LayoutParams for a trusted overlay layer.
     * Hidden fields (title, privateFlags, fitInsetsTypes, etc.) are set via
     * reflection since they are absent from the public SDK stubs.
     */
    private WindowManager.LayoutParams buildLayerParams(String title) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        lp.gravity = Gravity.TOP | Gravity.LEFT;

        // Hidden public/package-private fields — set reflectively
        setFieldSafeString(lp, "title",              title);
        setFieldSafeString(lp, "accessibilityTitle", " ");
        setFieldSafeInt(lp, "fitInsetsTypes",           0);
        setFieldSafeInt(lp, "layoutInDisplayCutoutMode", LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS);

        // privateFlags |= PRIVATE_FLAG_TRUSTED_OVERLAY
        try {
            Field pf = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
            pf.setAccessible(true);
            pf.setInt(lp, pf.getInt(lp) | PRIVATE_FLAG_TRUSTED_OVERLAY);
        } catch (Exception e) {
            Log.w(TAG, "privateFlags not set: " + e.getMessage());
        }

        // inputFeatures |= INPUT_FEATURE_SPY
        try {
            Field inf = WindowManager.LayoutParams.class.getDeclaredField("inputFeatures");
            inf.setAccessible(true);
            inf.setInt(lp, inf.getInt(lp) | INPUT_FEATURE_SPY);
        } catch (Exception e) {
            Log.w(TAG, "inputFeatures not set: " + e.getMessage());
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
    // MtkUdfpsScrimController logic (inlined)
    // -----------------------------------------------------------------------
    private int getSystemBrightness(Context ctx) {
        try {
            float brightFloat = Settings.System.getFloat(
                    ctx.getContentResolver(), "screen_brightness_float", -1f);
            if (brightFloat >= 0f) return (int) (brightFloat * 255f);
        } catch (Exception ignored) {}
        try {
            return Settings.System.getInt(
                    ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 127);
        } catch (Exception e) {
            return 127;
        }
    }

    private float calculateAlpha(int brightness) {
        float alpha = 1.0f - (brightness / 255.0f);
        if (brightness < 25) alpha *= 0.95f;
        return Math.max(0.0f, Math.min(1.0f, alpha));
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------
    private Context getContext(Object overlay) {
        try {
            return (Context) XposedHelpers.getObjectField(overlay, "context");
        } catch (Throwable t) {
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

    private WindowManager getWindowManager(Object overlay) {
        try {
            return (WindowManager) XposedHelpers.getObjectField(overlay, "windowManager");
        } catch (Throwable t) {
            Log.w(TAG, "Could not get WindowManager: " + t.getMessage());
            return null;
        }
    }

    private Object getOverlay(Object controller) {
        try {
            return XposedHelpers.getObjectField(controller, "mOverlay");
        } catch (Throwable t) {
            Log.w(TAG, "Could not get mOverlay: " + t.getMessage());
            return null;
        }
    }

    private Rect getSensorBounds(Object overlay) {
        try {
            Object overlayParams = XposedHelpers.getObjectField(overlay, "overlayParams");
            if (overlayParams == null) return null;
            return (Rect) XposedHelpers.getObjectField(overlayParams, "sensorBounds");
        } catch (Throwable t) {
            try {
                return (Rect) XposedHelpers.getObjectField(overlay, "sensorRect");
            } catch (Throwable t2) {
                Log.w(TAG, "getSensorBounds failed: " + t2.getMessage());
                return null;
            }
        }
    }

    private void setFieldSafeString(Object obj, String fieldName, String value) {
        try {
            Field f = obj.getClass().getField(fieldName);
            f.set(obj, value);
        } catch (Exception e1) {
            try {
                Field f = obj.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
            } catch (Exception e2) {
                Log.w(TAG, "setField(" + fieldName + ") failed: " + e2.getMessage());
            }
        }
    }

    private void setFieldSafeInt(Object obj, String fieldName, int value) {
        try {
            Field f = obj.getClass().getField(fieldName);
            f.setInt(obj, value);
        } catch (Exception e1) {
            try {
                Field f = obj.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                f.setInt(obj, value);
            } catch (Exception e2) {
                Log.w(TAG, "setField(" + fieldName + ") failed: " + e2.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // State map (synchronized for thread safety)
    // -----------------------------------------------------------------------
    private synchronized void setTag(Object overlay, int key, Object value) {
        sStateMap.computeIfAbsent(overlay, k -> new HashMap<>()).put(key, value);
    }

    private synchronized Object getTag(Object overlay, int key) {
        HashMap<Integer, Object> map = sStateMap.get(overlay);
        return map != null ? map.get(key) : null;
    }
}

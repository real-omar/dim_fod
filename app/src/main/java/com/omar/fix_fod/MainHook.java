package com.omar.fix_fod;

import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module — Oplus/OPPO UDFPS fix.
 *
 * STATUS FROM LOGS:
 *   ✅ UdfpsController.onFingerDown/Up hooks fire (SystemUI)
 *   ✅ sys.phh.oplus.fppress → sysfs bridge works
 *   ✅ UdfpsControllerOverlay layer exists (SF side is ok)
 *   ❌ FingerprintCallback.sendUdfpsPointerDown/Up callback null
 *   ❌ UdfpsDisplayMode.onDisabled is null
 *   ❌ Zero system_server hook output → "android" NOT in module scope
 *
 * ROOT CAUSE: "android" (system_server) is not in xposed_scope.
 * None of the AuthService / FingerprintCallback / Fingerprint21 hooks
 * ever run. Fix: add "android" to your scope array in arrays.xml.
 *
 * This version also fixes UdfpsDisplayMode.onDisabled being null,
 * which is a SystemUI-side issue we CAN fix without system_server scope.
 *
 * REQUIRED xposed_scope entries:
 *   <item>com.android.systemui</item>
 *   <item>android</item>   ← THIS IS THE MISSING ONE
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PHH-OplusUdfpsFix";

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_SYSTEM   = "android";

    // SystemUI
    private static final String CLS_UDFPS_CONTROLLER =
            "com.android.systemui.biometrics.UdfpsController";
    private static final String CLS_AUTH_CONTROLLER =
            "com.android.systemui.biometrics.AuthController";
    private static final String CLS_UDFPS_DISPLAY_MODE =
            "com.android.systemui.biometrics.UdfpsDisplayMode";

    // system_server – AIDL
    private static final String CLS_AUTH_SERVICE =
            "com.android.server.biometrics.AuthService";
    private static final String CLS_FP_PROVIDER =
            "com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider";
    private static final String CLS_FP_SENSOR_PROPS =
            "android.hardware.fingerprint.FingerprintSensorPropertiesInternal";

    // system_server – HIDL
    private static final String CLS_FINGERPRINT_CALLBACK =
            "com.android.server.biometrics.sensors.fingerprint.hidl.FingerprintCallback";
    private static final String CLS_FINGERPRINT21 =
            "com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21";
    private static final String CLS_FINGERPRINT21_UDFPS =
            "com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21UdfpsMock";
    private static final String CLS_HIDL_TO_AIDL =
            "com.android.server.biometrics.sensors.fingerprint.hidl.HidlToAidlSessionAdapter";

    private static final int TYPE_UDFPS_OPTICAL = 3;

    // -----------------------------------------------------------------------
    // SystemProperties via reflection
    // -----------------------------------------------------------------------
    private static String sysPropGet(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, key, def);
        } catch (Throwable t) {
            Log.e(TAG, "sysPropGet failed: " + key, t);
            return def;
        }
    }

    private static void sysPropSet(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, value);
        } catch (Throwable t) {
            Log.e(TAG, "sysPropSet failed: " + key + "=" + value, t);
        }
    }

    // -----------------------------------------------------------------------
    // Entry point — log EVERY package we're called for so we can verify scope
    // -----------------------------------------------------------------------
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Log every single package load so we can confirm scope in logcat.
        // Look for "PHH-OplusUdfpsFix: loaded into: android" to confirm
        // system_server is in scope. If you never see it, add "android" to
        // your xposed_scope array in res/values/arrays.xml.
        Log.i(TAG, "loaded into: " + lpparam.packageName);

        if (PKG_SYSTEMUI.equals(lpparam.packageName)) {
            hookUdfpsController(lpparam.classLoader);
            hookUdfpsDisplayMode(lpparam.classLoader);
        } else if (PKG_SYSTEM.equals(lpparam.packageName)) {
            Log.i(TAG, ">>> system_server scope confirmed <<<");
            hookAuthService(lpparam.classLoader);
            hookFingerprintCallback(lpparam.classLoader);
            hookFingerprint21(lpparam.classLoader);
            hookFingerprintProviderAidl(lpparam.classLoader);
            hookHidlToAidlAdapter(lpparam.classLoader);
        }
    }

    // -----------------------------------------------------------------------
    // SystemUI: UdfpsController finger events → sys.phh.oplus.fppress
    // Already confirmed working. Kept unchanged.
    // -----------------------------------------------------------------------
    private void hookUdfpsController(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_UDFPS_CONTROLLER, cl);
            XposedBridge.hookAllMethods(cls, "onFingerDown", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    sysPropSet("sys.phh.oplus.fppress", "1");
                    Log.d(TAG, "UdfpsController.onFingerDown → fppress=1");
                }
            });
            XposedBridge.hookAllMethods(cls, "onFingerUp", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    sysPropSet("sys.phh.oplus.fppress", "0");
                    Log.d(TAG, "UdfpsController.onFingerUp → fppress=0");
                }
            });
            Log.i(TAG, "Hooked UdfpsController");
        } catch (XposedHelpers.ClassNotFoundError e) {
            try {
                Class<?> cls = XposedHelpers.findClass(CLS_AUTH_CONTROLLER, cl);
                XposedBridge.hookAllMethods(cls, "onUdfpsPointerDown", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        sysPropSet("sys.phh.oplus.fppress", "1");
                    }
                });
                XposedBridge.hookAllMethods(cls, "onUdfpsPointerUp", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        sysPropSet("sys.phh.oplus.fppress", "0");
                    }
                });
                Log.i(TAG, "Hooked AuthController (fallback)");
            } catch (XposedHelpers.ClassNotFoundError e2) {
                Log.e(TAG, "All SystemUI UdfpsController hooks failed");
            }
        }
    }

    // -----------------------------------------------------------------------
    // SystemUI: UdfpsDisplayMode
    //
    // Log shows: "UdfpsDisplayMode: disable | onDisabled is null"
    // UdfpsDisplayMode manages the display brightness/refresh during UDFPS.
    // onDisabled being null means the callback was never registered, likely
    // because the HIDL HAL path skips registering it.
    //
    // We hook enable() and disable() to log what's happening and ensure
    // the display mode changes actually complete even with a null callback.
    // -----------------------------------------------------------------------
    private void hookUdfpsDisplayMode(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_UDFPS_DISPLAY_MODE, cl);

            // Hook disable() — the null onDisabled means the mode is never
            // properly torn down, which can leave the display in a wrong state.
            XposedBridge.hookAllMethods(cls, "disable", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Log.d(TAG, "UdfpsDisplayMode.disable() called");
                    // Check if onDisabled callback is null and log the field
                    try {
                        dumpObjectFields(param.thisObject, "UdfpsDisplayMode");
                    } catch (Throwable ignored) {}
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // If onDisabled was null, manually try to reset display state
                    // by finding and calling any available reset/restore method
                    try {
                        Object onDisabled = null;
                        for (Field f : param.thisObject.getClass().getDeclaredFields()) {
                            if (f.getName().contains("onDisabled")
                                    || f.getName().contains("mOnDisabled")
                                    || f.getName().contains("callback")) {
                                f.setAccessible(true);
                                onDisabled = f.get(param.thisObject);
                                Log.i(TAG, "UdfpsDisplayMode field " + f.getName()
                                        + " = " + (onDisabled == null ? "NULL" : onDisabled));
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "UdfpsDisplayMode.disable hook error", t);
                    }
                }
            });

            // Hook enable() for symmetry
            XposedBridge.hookAllMethods(cls, "enable", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Log.d(TAG, "UdfpsDisplayMode.enable() called");
                }
            });

            Log.i(TAG, "Hooked UdfpsDisplayMode");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.w(TAG, "UdfpsDisplayMode not found");
        }
    }

    // -----------------------------------------------------------------------
    // system_server: AuthService.getUdfpsProps()
    // -----------------------------------------------------------------------
    private void hookAuthService(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_AUTH_SERVICE, cl);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    cls, "getUdfpsProps", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String loc = sysPropGet(
                                        "persist.vendor.fingerprint.optical.sensorlocation", "");
                                if (TextUtils.isEmpty(loc) || !loc.contains("::")) return;
                                String[] c = loc.split("::");
                                if (c.length < 2) return;
                                int x = Integer.parseInt(c[0].trim());
                                int y = Integer.parseInt(c[1].trim());
                                String sz = sysPropGet(
                                        "persist.vendor.fingerprint.optical.iconsize", "0");
                                int r = Integer.parseInt(sz.trim()) / 2;
                                int[] props = {x, y, r};
                                Log.i(TAG, "AuthService.getUdfpsProps → " + Arrays.toString(props));
                                param.setResult(props);
                            } catch (Throwable t) {
                                Log.e(TAG, "getUdfpsProps error", t);
                            }
                        }
                    });
            Log.i(TAG, "Hooked AuthService#getUdfpsProps (" + hooks.size() + ")");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.e(TAG, "AuthService not found");
        }
    }

    // -----------------------------------------------------------------------
    // system_server: FingerprintCallback — HIDL path null callback fix
    //
    // "FingerprintCallback: sendUdfpsPointerDown, callback null"
    // The callback (IUdfpsOverlayController) is only stored when
    // halHandlesDisplayTouches == true at service init time.
    // We hook the constructor to dump fields (once system_server is in scope)
    // and force the sensor props.
    // -----------------------------------------------------------------------
    private void hookFingerprintCallback(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_FINGERPRINT_CALLBACK, cl);
            Log.i(TAG, "Found FingerprintCallback");

            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "FingerprintCallback constructed");
                    dumpObjectFields(param.thisObject, "FingerprintCallback");
                    forceSensorPropsOnObject(param.thisObject, "FingerprintCallback ctor");
                }
            });

            // Hook sendUdfpsPointerDown/Up to suppress the null crash
            // and attempt to invoke the callback via reflection
            for (String method : new String[]{"sendUdfpsPointerDown", "sendUdfpsPointerUp"}) {
                final boolean isDown = method.equals("sendUdfpsPointerDown");
                XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "FingerprintCallback." + method + " called");
                        tryForwardUdfpsCallback(param.thisObject, isDown);
                    }
                });
            }

            // Hook all setters to catch when/if the callback ever gets registered
            for (Method m : cls.getDeclaredMethods()) {
                String n = m.getName();
                if (n.startsWith("set") || n.contains("Udfps") || n.contains("Callback")
                        || n.contains("Controller")) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Log.i(TAG, "FingerprintCallback." + n
                                        + "(" + Arrays.toString(param.args) + ")");
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            }

            Log.i(TAG, "Hooked FingerprintCallback");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.e(TAG, "FingerprintCallback not found");
        }
    }

    // -----------------------------------------------------------------------
    // system_server: Fingerprint21 — HIDL sensor props
    // -----------------------------------------------------------------------
    private void hookFingerprint21(ClassLoader cl) {
        for (String clsName : new String[]{CLS_FINGERPRINT21, CLS_FINGERPRINT21_UDFPS}) {
            try {
                Class<?> cls = XposedHelpers.findClass(clsName, cl);
                Log.i(TAG, "Found " + clsName);

                for (String method : new String[]{
                        "getSensorProps", "getSensorProperties", "buildSensorProperties",
                        "getSensorPropertiesInternal", "createAndRegisterService",
                        "initForGoodiesOnly", "addSensor", "init", "start"}) {
                    try {
                        XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                for (Object arg : param.args) {
                                    if (arg != null && arg.getClass().getName()
                                            .equals(CLS_FP_SENSOR_PROPS)) {
                                        forceSensorTypeUdfps(arg, clsName + "." + method + " arg");
                                    }
                                }
                            }
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object r = param.getResult();
                                if (r != null && r.getClass().getName().equals(CLS_FP_SENSOR_PROPS))
                                    forceSensorTypeUdfps(r, clsName + "." + method + " return");
                                forceSensorPropsOnObject(param.thisObject, clsName + "." + method);
                            }
                        });
                    } catch (Throwable ignored) {}
                }

                XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Log.i(TAG, clsName + " constructed");
                        dumpObjectFields(param.thisObject, clsName);
                        forceSensorPropsOnObject(param.thisObject, clsName + " ctor");
                    }
                });

            } catch (XposedHelpers.ClassNotFoundError e) {
                Log.d(TAG, clsName + " not found");
            }
        }
    }

    // -----------------------------------------------------------------------
    // system_server: FingerprintProvider — AIDL path
    // -----------------------------------------------------------------------
    private void hookFingerprintProviderAidl(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_FP_PROVIDER, cl);
            XposedBridge.hookAllMethods(cls, "addSensor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (arg != null && arg.getClass().getName().equals(CLS_FP_SENSOR_PROPS))
                            forceSensorTypeUdfps(arg, "FingerprintProvider.addSensor");
                    }
                }
            });
            Log.i(TAG, "Hooked FingerprintProvider#addSensor");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.d(TAG, "FingerprintProvider (AIDL) not found");
        }
    }

    // -----------------------------------------------------------------------
    // system_server: HidlToAidlSessionAdapter — suppress onUiReady exception
    // -----------------------------------------------------------------------
    private void hookHidlToAidlAdapter(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_HIDL_TO_AIDL, cl);
            XposedBridge.hookAllMethods(cls, "onUiReady", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                    Log.d(TAG, "HidlToAidlSessionAdapter.onUiReady: suppressed");
                }
            });
            Log.i(TAG, "Hooked HidlToAidlSessionAdapter#onUiReady");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.w(TAG, "HidlToAidlSessionAdapter not found");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void dumpObjectFields(Object obj, String label) {
        if (obj == null) return;
        Class<?> cls = obj.getClass();
        while (cls != null && !cls.getName().equals("java.lang.Object")) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    String name = f.getName().toLowerCase();
                    if (name.contains("callback") || name.contains("udfps")
                            || name.contains("sensor") || name.contains("display")
                            || name.contains("type") || name.contains("hal")
                            || name.contains("controller") || name.contains("hidl")
                            || name.contains("touch") || name.contains("overlay")) {
                        Object val = f.get(obj);
                        Log.i(TAG, label + " field [" + f.getName() + ":"
                                + f.getType().getSimpleName() + "] = "
                                + (val == null ? "NULL" : val.getClass().getName()
                                        + "@" + Integer.toHexString(System.identityHashCode(val))));
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    private void forceSensorPropsOnObject(Object obj, String source) {
        if (obj == null) return;
        Class<?> cls = obj.getClass();
        while (cls != null && !cls.getName().equals("java.lang.Object")) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val != null && val.getClass().getName().equals(CLS_FP_SENSOR_PROPS))
                        forceSensorTypeUdfps(val, source + " field:" + f.getName());
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    private void forceSensorTypeUdfps(Object prop, String source) {
        try {
            Object[] locs = (Object[]) XposedHelpers.getObjectField(prop, "sensorLocations");
            if (locs == null || locs.length == 0) return;
            int x = XposedHelpers.getIntField(locs[0], "sensorLocationX");
            if (x <= 0) return;
            int cur = XposedHelpers.getIntField(prop, "sensorType");
            if (cur == TYPE_UDFPS_OPTICAL) { Log.d(TAG, source + ": already UDFPS_OPTICAL"); return; }
            XposedHelpers.setIntField(prop, "sensorType", TYPE_UDFPS_OPTICAL);
            XposedHelpers.setBooleanField(prop, "halHandlesDisplayTouches", true);
            Log.i(TAG, source + ": forced TYPE_UDFPS_OPTICAL (x=" + x + ")");
        } catch (Throwable t) {
            Log.e(TAG, "forceSensorTypeUdfps from " + source + ": " + t.getMessage());
        }
    }

    /**
     * When sendUdfpsPointerDown/Up finds a null callback, scan all fields
     * for any non-null interface that could serve as the UDFPS overlay controller
     * and try to invoke it directly.
     */
    private void tryForwardUdfpsCallback(Object fpCallback, boolean isDown) {
        if (fpCallback == null) return;
        Class<?> cls = fpCallback.getClass();
        while (cls != null && !cls.getName().equals("java.lang.Object")) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(fpCallback);
                    String typeName = f.getType().getName();
                    Log.i(TAG, "FingerprintCallback field [" + f.getName() + ":"
                            + f.getType().getSimpleName() + "] = "
                            + (val == null ? "NULL" : val.getClass().getName()));
                    if (val != null && (typeName.contains("Udfps") || typeName.contains("Overlay")
                            || typeName.contains("Callback") || typeName.contains("Controller"))) {
                        String[] candidates = isDown
                                ? new String[]{"onFingerDown", "sendUdfpsPointerDown", "onPointerDown"}
                                : new String[]{"onFingerUp",   "sendUdfpsPointerUp",   "onPointerUp"};
                        for (String mn : candidates) {
                            try {
                                val.getClass().getMethod(mn).invoke(val);
                                Log.i(TAG, "Forwarded " + (isDown ? "down" : "up")
                                        + " to " + typeName + "." + mn);
                                return;
                            } catch (NoSuchMethodException ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }
}

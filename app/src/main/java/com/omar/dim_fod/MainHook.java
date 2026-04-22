package com.omar.dim_fod;

import android.text.TextUtils;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PHH-OplusUdfpsFix";

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_SYSTEM   = "android";

    // SystemUI
    private static final String CLS_UDFPS_CONTROLLER =
            "com.android.systemui.biometrics.UdfpsController";

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

    private static final String[] SYSFS_NODES = {
        "/sys/kernel/oplus_display/oplus_notify_fppress", // OnePlus / newer Oplus
        "/sys/kernel/oppo_display/oppo_notify_fppress",   // older OPPO
    };

    // Resolved once on first write
    private static volatile String sActiveSysfsNode = null;

    // Persistent root shell — opened once, reused for every finger event
    private static volatile Process sRootShell = null;
    private static volatile OutputStream sRootStdin = null;
    private static final Object sRootShellLock = new Object();

    // Off-thread executor so sysfs writes never block the touch event thread
    private static final ExecutorService sWriter =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "udfps-fppress-writer");
                t.setDaemon(true);
                return t;
            });

    // -----------------------------------------------------------------------
    // Root shell write
    // -----------------------------------------------------------------------
    private static void setFpPress(boolean pressed) {
        final String value = pressed ? "1" : "0";
        sWriter.execute(() -> writeViaRootShell(value));
    }

    private static void writeViaRootShell(String value) {
        synchronized (sRootShellLock) {
            if (sActiveSysfsNode == null) {
                for (String node : SYSFS_NODES) {
                    if (new java.io.File(node).exists()) {
                        sActiveSysfsNode = node;
                        break;
                    }
                }
                if (sActiveSysfsNode == null) {
                    Log.e(TAG, "No fppress sysfs node found");
                    return;
                }
            }
            if (sRootShell == null || !isShellAlive()) {
                try {
                    sRootShell = Runtime.getRuntime().exec("su");
                    sRootStdin = sRootShell.getOutputStream();
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to open root shell: " + t.getMessage());
                    sRootShell = null;
                    sRootStdin = null;
                    return;
                }
            }
            try {
                sRootStdin.write(("echo " + value + " > " + sActiveSysfsNode + "\n").getBytes());
                sRootStdin.flush();
                Log.d(TAG, "fppress=" + value);
            } catch (Throwable t) {
                Log.w(TAG, "Root shell write failed: " + t.getMessage());
                closeRootShell();
            }
        }
    }

    private static boolean isShellAlive() {
        try { sRootShell.exitValue(); return false; }
        catch (IllegalThreadStateException e) { return true; }
    }

    private static void closeRootShell() {
        try { if (sRootStdin != null) sRootStdin.close(); } catch (Throwable ignored) {}
        try { if (sRootShell != null) sRootShell.destroy(); } catch (Throwable ignored) {}
        sRootShell = null;
        sRootStdin = null;
    }

    // -----------------------------------------------------------------------
    // SystemProperties via reflection (needed for AuthService hook)
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

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (PKG_SYSTEMUI.equals(lpparam.packageName)) {
            hookUdfpsController(lpparam.classLoader);
        } else if (PKG_SYSTEM.equals(lpparam.packageName)) {
            hookAuthService(lpparam.classLoader);
            hookFingerprintCallback(lpparam.classLoader);
            hookFingerprint21(lpparam.classLoader);
            hookFingerprintProviderAidl(lpparam.classLoader);
            hookHidlToAidlAdapter(lpparam.classLoader);
        }
    }

    // -----------------------------------------------------------------------
    // SystemUI: UdfpsController → sysfs write
    // -----------------------------------------------------------------------
    private void hookUdfpsController(ClassLoader cl) {
        Class<?> cls = XposedHelpers.findClass(CLS_UDFPS_CONTROLLER, cl);
        XposedBridge.hookAllMethods(cls, "onFingerDown", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                setFpPress(true);
            }
        });
        XposedBridge.hookAllMethods(cls, "onFingerUp", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                setFpPress(false);
            }
        });
    }

    // -----------------------------------------------------------------------
    // system_server: AuthService.getUdfpsProps()
    // -----------------------------------------------------------------------
    private void hookAuthService(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_AUTH_SERVICE, cl);
            XposedBridge.hookAllMethods(cls, "getUdfpsProps", new XC_MethodHook() {
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
                        int r = Integer.parseInt(sysPropGet(
                                "persist.vendor.fingerprint.optical.iconsize", "0").trim()) / 2;
                        param.setResult(new int[]{x, y, r});
                    } catch (Throwable t) {
                        Log.e(TAG, "getUdfpsProps error", t);
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError ignored) {}
    }

    // -----------------------------------------------------------------------
    // system_server: FingerprintCallback
    // -----------------------------------------------------------------------
    private void hookFingerprintCallback(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_FINGERPRINT_CALLBACK, cl);

            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    forceSensorPropsOnObject(param.thisObject, "FingerprintCallback");
                }
            });

            for (String method : new String[]{"sendUdfpsPointerDown", "sendUdfpsPointerUp"}) {
                final boolean isDown = method.equals("sendUdfpsPointerDown");
                XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        tryForwardUdfpsCallback(param.thisObject, isDown);
                    }
                });
            }
        } catch (XposedHelpers.ClassNotFoundError ignored) {}
    }

    // -----------------------------------------------------------------------
    // system_server: Fingerprint21
    // -----------------------------------------------------------------------
    private void hookFingerprint21(ClassLoader cl) {
        for (String clsName : new String[]{CLS_FINGERPRINT21, CLS_FINGERPRINT21_UDFPS}) {
            try {
                Class<?> cls = XposedHelpers.findClass(clsName, cl);
                for (String method : new String[]{
                        "getSensorProps", "getSensorProperties", "buildSensorProperties",
                        "getSensorPropertiesInternal", "createAndRegisterService",
                        "initForGoodiesOnly", "addSensor", "init", "start"}) {
                    try {
                        XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                for (Object arg : param.args)
                                    if (arg != null && arg.getClass().getName()
                                            .equals(CLS_FP_SENSOR_PROPS))
                                        forceSensorTypeUdfps(arg);
                            }
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object r = param.getResult();
                                if (r != null && r.getClass().getName().equals(CLS_FP_SENSOR_PROPS))
                                    forceSensorTypeUdfps(r);
                                forceSensorPropsOnObject(param.thisObject, clsName);
                            }
                        });
                    } catch (Throwable ignored) {}
                }
                XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        forceSensorPropsOnObject(param.thisObject, clsName);
                    }
                });
            } catch (XposedHelpers.ClassNotFoundError ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // system_server: FingerprintProvider (AIDL)
    // -----------------------------------------------------------------------
    private void hookFingerprintProviderAidl(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_FP_PROVIDER, cl);
            XposedBridge.hookAllMethods(cls, "addSensor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args)
                        if (arg != null && arg.getClass().getName().equals(CLS_FP_SENSOR_PROPS))
                            forceSensorTypeUdfps(arg);
                }
            });
        } catch (XposedHelpers.ClassNotFoundError ignored) {}
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
                }
            });
        } catch (XposedHelpers.ClassNotFoundError ignored) {}
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private void forceSensorPropsOnObject(Object obj, String source) {
        if (obj == null) return;
        Class<?> cls = obj.getClass();
        while (cls != null && !cls.getName().equals("java.lang.Object")) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val != null && val.getClass().getName().equals(CLS_FP_SENSOR_PROPS))
                        forceSensorTypeUdfps(val);
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    private void forceSensorTypeUdfps(Object prop) {
        try {
            Object[] locs = (Object[]) XposedHelpers.getObjectField(prop, "sensorLocations");
            if (locs == null || locs.length == 0) return;
            if (XposedHelpers.getIntField(locs[0], "sensorLocationX") <= 0) return;
            if (XposedHelpers.getIntField(prop, "sensorType") == TYPE_UDFPS_OPTICAL) return;
            XposedHelpers.setIntField(prop, "sensorType", TYPE_UDFPS_OPTICAL);
            XposedHelpers.setBooleanField(prop, "halHandlesDisplayTouches", true);
        } catch (Throwable ignored) {}
    }

    private void tryForwardUdfpsCallback(Object fpCallback, boolean isDown) {
        if (fpCallback == null) return;
        Class<?> cls = fpCallback.getClass();
        while (cls != null && !cls.getName().equals("java.lang.Object")) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(fpCallback);
                    if (val == null) continue;
                    String tn = f.getType().getName();
                    if (tn.contains("Udfps") || tn.contains("Overlay")
                            || tn.contains("Callback") || tn.contains("Controller")) {
                        for (String mn : isDown
                                ? new String[]{"onFingerDown", "sendUdfpsPointerDown", "onPointerDown"}
                                : new String[]{"onFingerUp",   "sendUdfpsPointerUp",   "onPointerUp"}) {
                            try {
                                val.getClass().getMethod(mn).invoke(val);
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

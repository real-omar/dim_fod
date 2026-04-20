package com.omar.fix_fod;

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

    // Sysfs nodes, tried in order
    private static final String[] SYSFS_NODES = {
        "/sys/kernel/oplus_display/oplus_notify_fppress",
        "/sys/kernel/oppo_display/oppo_notify_fppress",
    };

    // Cached active node path
    private static volatile String sActiveSysfsNode = null;

    // Whether we're running in system_server (root-equivalent, no su needed)
    private static volatile boolean sIsSystemServer = false;

    // Single-thread executor for async root writes from SystemUI
    // (Runtime.exec su can block briefly — we don't want to stall the UI thread)
    private static final ExecutorService sWriteExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "udfps-fppress-writer");
                t.setDaemon(true);
                return t;
            });

    // Persistent root shell stdin — opened once and reused to avoid
    // the overhead of spawning a new `su` process on every finger event
    private static volatile Process sRootShell = null;
    private static volatile OutputStream sRootStdin = null;
    private static final Object sRootShellLock = new Object();

    // -----------------------------------------------------------------------
    // Core: write "1" or "0" to the fppress sysfs node
    //
    // Strategy (in priority order):
    //   1. system_server context → direct FileOutputStream (already root)
    //   2. SystemUI context → persistent root shell via `su`
    //   3. Fallback → sysprop bridge (slow ~50ms but always works)
    // -----------------------------------------------------------------------
    private static void setFpPress(boolean pressed) {
        String value = pressed ? "1" : "0";

        if (sIsSystemServer) {
            // system_server runs as root — write directly, no su needed
            if (writeDirectly(value)) return;
        } else {
            // SystemUI (priv_app) — write via persistent root shell
            // Do it asynchronously to avoid blocking the touch event thread
            final String v = value;
            sWriteExecutor.execute(() -> {
                if (!writeViaRootShell(v)) {
                    // Root shell failed — fall back to sysprop
                    sysPropSet("sys.phh.oplus.fppress", v);
                    Log.w(TAG, "setFpPress(" + v + ") via sysprop fallback");
                }
            });
            return;
        }

        // Fallback if direct write failed
        sysPropSet("sys.phh.oplus.fppress", value);
        Log.w(TAG, "setFpPress(" + value + ") via sysprop fallback");
    }

    // -----------------------------------------------------------------------
    // Direct FileOutputStream write (works from system_server / root context)
    // -----------------------------------------------------------------------
    private static boolean writeDirectly(String value) {
        String node = resolveNode();
        if (node == null) return false;
        try (FileOutputStream fos = new FileOutputStream(node)) {
            fos.write(value.getBytes());
            fos.flush();
            Log.d(TAG, "direct write: " + node + " = " + value);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "direct write failed: " + t.getMessage());
            sActiveSysfsNode = null;
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Persistent root shell write (for SystemUI / priv_app context)
    //
    // Opens `su` once and keeps the process alive, writing commands to its
    // stdin. This avoids the ~5-10ms overhead of forking a new process for
    // every finger event. The shell is re-spawned if it dies.
    // -----------------------------------------------------------------------
    private static boolean writeViaRootShell(String value) {
        String node = resolveNode();
        if (node == null) return false;

        synchronized (sRootShellLock) {
            // Spawn or re-spawn the root shell if needed
            if (sRootShell == null || !isShellAlive()) {
                try {
                    sRootShell = Runtime.getRuntime().exec("su");
                    sRootStdin = sRootShell.getOutputStream();
                    Log.i(TAG, "Root shell opened");
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to open root shell: " + t.getMessage());
                    sRootShell = null;
                    sRootStdin = null;
                    return false;
                }
            }

            // Write the echo command to the shell's stdin
            try {
                String cmd = "echo " + value + " > " + node + "\n";
                sRootStdin.write(cmd.getBytes());
                sRootStdin.flush();
                Log.d(TAG, "root shell write: " + node + " = " + value);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "root shell write failed: " + t.getMessage());
                // Shell died — close and let it be re-spawned next time
                closeRootShell();
                return false;
            }
        }
    }

    private static boolean isShellAlive() {
        if (sRootShell == null) return false;
        try {
            sRootShell.exitValue();
            return false; // exitValue() succeeds only if process has terminated
        } catch (IllegalThreadStateException e) {
            return true; // still running
        }
    }

    private static void closeRootShell() {
        try { if (sRootStdin != null) sRootStdin.close(); } catch (Throwable ignored) {}
        try { if (sRootShell != null) sRootShell.destroy(); } catch (Throwable ignored) {}
        sRootShell = null;
        sRootStdin = null;
    }

    // -----------------------------------------------------------------------
    // Resolve which sysfs node exists on this device
    // -----------------------------------------------------------------------
    private static String resolveNode() {
        if (sActiveSysfsNode != null) return sActiveSysfsNode;
        for (String node : SYSFS_NODES) {
            if (new java.io.File(node).exists()) {
                sActiveSysfsNode = node;
                Log.i(TAG, "Resolved sysfs node: " + node);
                return node;
            }
        }
        Log.w(TAG, "No fppress sysfs node found");
        return null;
    }

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
    // Entry point
    // -----------------------------------------------------------------------
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        Log.i(TAG, "loaded into: " + lpparam.packageName);

        if (PKG_SYSTEMUI.equals(lpparam.packageName)) {
            hookUdfpsController(lpparam.classLoader);
            hookUdfpsDisplayMode(lpparam.classLoader);
        } else if (PKG_SYSTEM.equals(lpparam.packageName)) {
            sIsSystemServer = true;
            Log.i(TAG, ">>> system_server scope confirmed <<<");
            hookAuthService(lpparam.classLoader);
            hookFingerprintCallback(lpparam.classLoader);
            hookFingerprint21(lpparam.classLoader);
            hookFingerprintProviderAidl(lpparam.classLoader);
            hookHidlToAidlAdapter(lpparam.classLoader);
        }
    }

    // -----------------------------------------------------------------------
    // SystemUI: UdfpsController → setFpPress
    // -----------------------------------------------------------------------
    private void hookUdfpsController(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_UDFPS_CONTROLLER, cl);
            XposedBridge.hookAllMethods(cls, "onFingerDown", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    setFpPress(true);
                    Log.d(TAG, "UdfpsController.onFingerDown → fppress=1");
                }
            });
            XposedBridge.hookAllMethods(cls, "onFingerUp", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    setFpPress(false);
                    Log.d(TAG, "UdfpsController.onFingerUp → fppress=0");
                }
            });
            Log.i(TAG, "Hooked UdfpsController");
        } catch (XposedHelpers.ClassNotFoundError e) {
            try {
                Class<?> cls = XposedHelpers.findClass(CLS_AUTH_CONTROLLER, cl);
                XposedBridge.hookAllMethods(cls, "onUdfpsPointerDown", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        setFpPress(true);
                    }
                });
                XposedBridge.hookAllMethods(cls, "onUdfpsPointerUp", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        setFpPress(false);
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
    // -----------------------------------------------------------------------
    private void hookUdfpsDisplayMode(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_UDFPS_DISPLAY_MODE, cl);
            XposedBridge.hookAllMethods(cls, "disable", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Log.d(TAG, "UdfpsDisplayMode.disable()");
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        for (Field f : param.thisObject.getClass().getDeclaredFields()) {
                            if (f.getName().contains("onDisabled")
                                    || f.getName().contains("mOnDisabled")
                                    || f.getName().contains("callback")) {
                                f.setAccessible(true);
                                Object val = f.get(param.thisObject);
                                Log.i(TAG, "UdfpsDisplayMode." + f.getName()
                                        + " = " + (val == null ? "NULL" : val));
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "UdfpsDisplayMode.disable hook error", t);
                    }
                }
            });
            XposedBridge.hookAllMethods(cls, "enable", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Log.d(TAG, "UdfpsDisplayMode.enable()");
                }
            });
            Log.i(TAG, "Hooked UdfpsDisplayMode");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.w(TAG, "UdfpsDisplayMode not found");
        }
    }

    // -----------------------------------------------------------------------
    // system_server: AuthService
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
                                int r = Integer.parseInt(sysPropGet(
                                        "persist.vendor.fingerprint.optical.iconsize", "0").trim()) / 2;
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
    // system_server: FingerprintCallback
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

            for (String method : new String[]{"sendUdfpsPointerDown", "sendUdfpsPointerUp"}) {
                final boolean isDown = method.equals("sendUdfpsPointerDown");
                XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "FingerprintCallback." + method);
                        tryForwardUdfpsCallback(param.thisObject, isDown);
                    }
                });
            }

            for (Method m : cls.getDeclaredMethods()) {
                String n = m.getName();
                if (n.startsWith("set") || n.contains("Udfps")
                        || n.contains("Callback") || n.contains("Controller")) {
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
    // system_server: Fingerprint21
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
                                for (Object arg : param.args)
                                    if (arg != null && arg.getClass().getName()
                                            .equals(CLS_FP_SENSOR_PROPS))
                                        forceSensorTypeUdfps(arg, clsName + "." + method);
                            }
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object r = param.getResult();
                                if (r != null && r.getClass().getName().equals(CLS_FP_SENSOR_PROPS))
                                    forceSensorTypeUdfps(r, clsName + "." + method + " ret");
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
                            forceSensorTypeUdfps(arg, "FingerprintProvider.addSensor");
                }
            });
            Log.i(TAG, "Hooked FingerprintProvider#addSensor");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.d(TAG, "FingerprintProvider (AIDL) not found");
        }
    }

    // -----------------------------------------------------------------------
    // system_server: HidlToAidlSessionAdapter
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
                            || name.contains("controller") || name.contains("touch")
                            || name.contains("overlay")) {
                        Object val = f.get(obj);
                        Log.i(TAG, label + " [" + f.getName() + ":"
                                + f.getType().getSimpleName() + "] = "
                                + (val == null ? "NULL" : val.getClass().getName()));
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
            Log.e(TAG, "forceSensorTypeUdfps [" + source + "]: " + t.getMessage());
        }
    }

    private void tryForwardUdfpsCallback(Object fpCallback, boolean isDown) {
        if (fpCallback == null) return;
        Class<?> cls = fpCallback.getClass();
        while (cls != null && !cls.getName().equals("java.lang.Object")) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(fpCallback);
                    Log.i(TAG, "FingerprintCallback [" + f.getName() + ":"
                            + f.getType().getSimpleName() + "] = "
                            + (val == null ? "NULL" : val.getClass().getName()));
                    if (val != null) {
                        String tn = f.getType().getName();
                        if (tn.contains("Udfps") || tn.contains("Overlay")
                                || tn.contains("Callback") || tn.contains("Controller")) {
                            for (String mn : isDown
                                    ? new String[]{"onFingerDown", "sendUdfpsPointerDown", "onPointerDown"}
                                    : new String[]{"onFingerUp",   "sendUdfpsPointerUp",   "onPointerUp"}) {
                                try {
                                    val.getClass().getMethod(mn).invoke(val);
                                    Log.i(TAG, "Forwarded " + (isDown ? "down" : "up")
                                            + " → " + tn + "." + mn);
                                    return;
                                } catch (NoSuchMethodException ignored) {}
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }
}

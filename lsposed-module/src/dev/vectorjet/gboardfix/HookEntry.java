package dev.vectorjet.gboardfix;

import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.view.View;
import android.view.WindowInsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Clamps the bottom gesture insets as seen by Gboard so the keyboard pads to
 * the pill height (40px) instead of the 80px gesture strip. Scope: Gboard
 * packages only.
 *
 * NOTE: only APIs present in the android-34 SDK stub may be referenced
 * directly (getMandatorySystemGestures was removed from the stub); anything
 * newer/uncertain goes through reflection with try/catch.
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String GBOARD = "com.google.android.inputmethod.latin";
    private static final String GBOARD_JASON = "dev.jason.com.google.android.inputmethod.latin";
    private static final int CLAMP_BOTTOM_PX = 40;
    private static final int[] DIMEN_IDS = {
            0x01050277, 0x01050278, 0x01050279};

    private static Method sClassForm;
    private static Method sNameForm;
    private static Method sCtorClassForm;
    private static Method sCtorNameForm;
    private static int sClampLogs;
    private static int sSpyLogs;
    private static Field[] sWinFields;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD.equals(lpparam.packageName)
                && !GBOARD_JASON.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("[GboardGapFix] loaded in " + lpparam.packageName);
        if (!pickHook()) {
            return;
        }
        auditInsetsApi();
        ClassLoader loader = lpparam.classLoader;
        doHook(View.class, "android.view.View", loader, "dispatchApplyWindowInsets",
                null, dispatchRewrite());
        doHook(WindowInsets.class, "android.view.WindowInsets", loader,
                "getSystemGestureInsets", null, getterClamp());
        doHook(WindowInsets.class, "android.view.WindowInsets", loader,
                "getTappableElementInsets", null, getterClamp());
        doHook(WindowInsets.class, "android.view.WindowInsets", loader,
                "getInsets", new Class<?>[]{int.class}, maskedClamp());
        doHook(WindowInsets.class, "android.view.WindowInsets", loader,
                "getInsetsIgnoringVisibility", new Class<?>[]{int.class}, maskedClamp());
        doHook(WindowInsets.class, "android.view.WindowInsets", loader,
                "getSystemWindowInsets", null, sysWinClamp());
        doHook(Resources.class, "android.content.res.Resources", loader,
                "getDimensionPixelSize", new Class<?>[]{int.class}, dimenIntClamp());
        doHook(Resources.class, "android.content.res.Resources", loader,
                "getDimension", new Class<?>[]{int.class}, dimenFloatClamp());
        doHookCtor(loader, new Class<?>[]{Parcel.class}, ctorRewrite());
    }

    /** Log which gesture/mandatory/tappable members exist at runtime. */
    private void auditInsetsApi() {
        try {
            StringBuilder sb = new StringBuilder("[GboardGapFix] api:");
            for (Method m : WindowInsets.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("mandatory") || n.contains("tappable")
                        || n.contains("gesture")) {
                    sb.append(' ').append(m.getName()).append(';');
                }
            }
            try {
                Class<?> builder = Class.forName("android.view.WindowInsets$Builder");
                for (Method m : builder.getDeclaredMethods()) {
                    String n = m.getName().toLowerCase();
                    if (n.contains("mandatory") || n.contains("tappable")
                            || n.contains("gesture") || n.equals("setinsets")
                            || n.equals("build")) {
                        sb.append(" B.").append(m.getName()).append(';');
                    }
                }
            } catch (Throwable t) {
                sb.append(" Builder.?;");
            }
            XposedBridge.log(sb.toString());
        } catch (Throwable t) {
            XposedBridge.log("[GboardGapFix] audit failed: " + t);
        }
    }

    private boolean pickHook() {
        try {
            Method[] methods = XposedHelpers.class.getDeclaredMethods();
            StringBuilder sb = new StringBuilder("[GboardGapFix] helpers:");
            for (Method m : methods) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                boolean isHook = "findAndHookMethod".equals(m.getName());
                boolean isCtor = "findAndHookConstructor".equals(m.getName());
                if (!isHook && !isCtor) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                sb.append(' ').append(m.getName()).append(sig(p)).append(';');
                if (isHook) {
                    if (p.length == 3 && p[0] == Class.class
                            && p[1] == String.class && p[2] == Object[].class) {
                        sClassForm = m;
                    }
                    if (p.length == 4 && p[0] == String.class
                            && p[1] == ClassLoader.class
                            && p[2] == String.class && p[3] == Object[].class) {
                        sNameForm = m;
                    }
                } else {
                    if (p.length == 2 && p[0] == Class.class
                            && p[1] == Object[].class) {
                        sCtorClassForm = m;
                    }
                    if (p.length == 3 && p[0] == String.class
                            && p[1] == ClassLoader.class && p[2] == Object[].class) {
                        sCtorNameForm = m;
                    }
                }
            }
            XposedBridge.log(sb.toString());
            if (sClassForm == null && sNameForm == null) {
                XposedBridge.log("[GboardGapFix] NO compatible findAndHookMethod");
                return false;
            }
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[GboardGapFix] pick failed: " + t);
            return false;
        }
    }

    private void doHook(Class<?> target, String targetName, ClassLoader loader,
            String name, Class<?>[] params, XC_MethodHook cb) {
        try {
            Object[] tail;
            if (params == null) {
                tail = new Object[]{cb};
            } else {
                tail = new Object[params.length + 1];
                System.arraycopy(params, 0, tail, 0, params.length);
                tail[params.length] = cb;
            }
            Throwable firstError = null;
            if (sClassForm != null) {
                try {
                    sClassForm.invoke(null, new Object[]{target, name, tail});
                    XposedBridge.log("[GboardGapFix] hooked " + name);
                    return;
                } catch (Throwable t) {
                    firstError = t;
                }
            }
            if (sNameForm != null) {
                try {
                    sNameForm.invoke(null, new Object[]{targetName, loader, name, tail});
                    XposedBridge.log("[GboardGapFix] hooked " + name + " (name form)");
                    return;
                } catch (Throwable t) {
                    firstError = t;
                }
            }
            XposedBridge.log("[GboardGapFix] hook failed: " + name + " cause="
                    + causeOf(firstError));
        } catch (Throwable t) {
            XposedBridge.log("[GboardGapFix] hook failed: " + name + " cause="
                    + causeOf(t));
        }
    }

    private void doHookCtor(ClassLoader loader, Class<?>[] params, XC_MethodHook cb) {
        Object[] tail = new Object[params.length + 1];
        System.arraycopy(params, 0, tail, 0, params.length);
        tail[params.length] = cb;
        if (sCtorClassForm != null) {
            try {
                sCtorClassForm.invoke(null, new Object[]{WindowInsets.class, tail});
                XposedBridge.log("[GboardGapFix] hooked ctor");
                return;
            } catch (Throwable t) {
                XposedBridge.log("[GboardGapFix] ctor class form cause=" + causeOf(t));
            }
        }
        if (sCtorNameForm != null) {
            try {
                sCtorNameForm.invoke(null, new Object[]{
                        "android.view.WindowInsets", loader, tail});
                XposedBridge.log("[GboardGapFix] hooked ctor (name form)");
                return;
            } catch (Throwable t) {
                XposedBridge.log("[GboardGapFix] ctor name form cause=" + causeOf(t));
            }
        }
        if (sCtorClassForm == null && sCtorNameForm == null) {
            XposedBridge.log("[GboardGapFix] NO ctor hook form available");
        }
    }

    private static String causeOf(Throwable t) {
        if (t == null) {
            return "null";
        }
        Throwable c = t;
        String out = t.getClass().getSimpleName();
        for (int i = 0; i < 3 && c.getCause() != null; i++) {
            c = c.getCause();
            String msg = String.valueOf(c.getMessage());
            out += "<-" + c.getClass().getSimpleName() + ":"
                    + msg.substring(0, Math.min(90, msg.length()));
        }
        return out;
    }

    private static String sig(Class<?>[] p) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < p.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(p[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static void logClamp(String what, int from) {
        if (sClampLogs < 8) {
            sClampLogs++;
            XposedBridge.log("[GboardGapFix] CLAMPED " + what + " bottom "
                    + from + "->" + CLAMP_BOTTOM_PX);
        }
    }

    private static void logSpy(String what) {
        if (sSpyLogs < 8) {
            sSpyLogs++;
            XposedBridge.log("[GboardGapFix] SPY " + what);
        }
    }

    private static boolean isNavDimen(int id) {
        for (int d : DIMEN_IDS) {
            if (d == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuild insets with clamped gesture bottoms, using only reflection so a
     * missing Builder API can never break the build: Builder(WindowInsets) +
     * setInsets(int, Insets) + build().
     */
    private WindowInsets rebuildClamped(WindowInsets in, boolean[] changedOut) {
        try {
            Insets sys = in.getSystemGestureInsets();
            Insets tap = null;
            try {
                tap = in.getTappableElementInsets();
            } catch (Throwable ignored) {
            }
            Class<?> builderCls = Class.forName("android.view.WindowInsets$Builder");
            Constructor<?> copyCtor = builderCls.getConstructor(WindowInsets.class);
            Method setInsets = builderCls.getMethod("setInsets", int.class, Insets.class);
            Object builder = null;
            if (sys != null && sys.bottom > CLAMP_BOTTOM_PX) {
                builder = copyCtor.newInstance(in);
                setInsets.invoke(builder, WindowInsets.Type.systemGestures(),
                        Insets.of(sys.left, sys.top, sys.right, CLAMP_BOTTOM_PX));
                changedOut[0] = true;
            }
            if (tap != null && tap.bottom > CLAMP_BOTTOM_PX) {
                if (builder == null) {
                    builder = copyCtor.newInstance(in);
                }
                setInsets.invoke(builder, WindowInsets.Type.tappableElement(),
                        Insets.of(tap.left, tap.top, tap.right, CLAMP_BOTTOM_PX));
                changedOut[0] = true;
            }
            if (builder != null) {
                Method build = builderCls.getMethod("build");
                return (WindowInsets) build.invoke(builder);
            }
        } catch (Throwable t) {
            logClamp("rebuild-fail " + t.getClass().getSimpleName(), -1);
        }
        return in;
    }

    /** Choke point: every listener/view receives insets through here. */
    private XC_MethodHook dispatchRewrite() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    WindowInsets in = (WindowInsets) param.getResult();
                    if (in == null) {
                        return;
                    }
                    boolean[] changed = new boolean[1];
                    WindowInsets fixed = rebuildClamped(in, changed);
                    if (changed[0] && fixed != in) {
                        logClamp("dispatch", -1);
                        param.setResult(fixed);
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[GboardGapFix] dispatch rewrite failed: " + t);
                }
            }
        };
    }

    private XC_MethodHook getterClamp() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Insets in = (Insets) param.getResult();
                if (in != null && in.bottom > CLAMP_BOTTOM_PX) {
                    logClamp("getter", in.bottom);
                    param.setResult(Insets.of(in.left, in.top, in.right, CLAMP_BOTTOM_PX));
                }
            }
        };
    }

    private XC_MethodHook maskedClamp() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int mask = (Integer) param.args[0];
                // Never touch IME-height queries.
                if ((mask & WindowInsets.Type.ime()) != 0) {
                    return;
                }
                int want = WindowInsets.Type.systemGestures()
                        | WindowInsets.Type.tappableElement();
                try {
                    want |= (Integer) WindowInsets.Type.class
                            .getMethod("mandatorySystemGestures").invoke(null);
                } catch (Throwable ignored) {
                }
                if ((mask & want) == 0) {
                    return;
                }
                Insets in = (Insets) param.getResult();
                if (in != null && in.bottom > CLAMP_BOTTOM_PX) {
                    logClamp("masked/" + mask, in.bottom);
                    param.setResult(Insets.of(in.left, in.top, in.right, CLAMP_BOTTOM_PX));
                }
            }
        };
    }

    private XC_MethodHook sysWinClamp() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Rect r = (Rect) param.getResult();
                if (r != null && r.bottom > CLAMP_BOTTOM_PX) {
                    logClamp("syswin", r.bottom);
                    param.setResult(new Rect(r.left, r.top, r.right, CLAMP_BOTTOM_PX));
                }
            }
        };
    }

    private XC_MethodHook dimenIntClamp() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int id = (Integer) param.args[0];
                if (isNavDimen(id)) {
                    logClamp("dimenPx/" + Integer.toHexString(id),
                            (Integer) param.getResult());
                    param.setResult(CLAMP_BOTTOM_PX);
                    return;
                }
                // Spy: which dimens resolve to exactly the gap height?
                Integer v = (Integer) param.getResult();
                if (v != null && v == 80) {
                    logSpy("dimenPx id=0x" + Integer.toHexString(id));
                }
            }
        };
    }

    private XC_MethodHook dimenFloatClamp() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int id = (Integer) param.args[0];
                if (isNavDimen(id)) {
                    logClamp("dimen/" + Integer.toHexString(id),
                            ((Float) param.getResult()).intValue());
                    param.setResult((float) CLAMP_BOTTOM_PX);
                }
            }
        };
    }

    private XC_MethodHook ctorRewrite() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (sWinFields == null) {
                        Field[] fs = WindowInsets.class.getDeclaredFields();
                        for (Field f : fs) {
                            f.setAccessible(true);
                        }
                        sWinFields = fs;
                    }
                    Object win = param.thisObject;
                    int n = 0;
                    for (Field f : sWinFields) {
                        Class<?> t = f.getType();
                        if (t == Insets.class) {
                            Insets v = (Insets) f.get(win);
                            if (v != null && v.bottom > CLAMP_BOTTOM_PX) {
                                f.set(win, Insets.of(v.left, v.top, v.right,
                                        CLAMP_BOTTOM_PX));
                                n++;
                            }
                        } else if (t == Insets[].class) {
                            Insets[] arr = (Insets[]) f.get(win);
                            if (arr != null) {
                                for (int i = 0; i < arr.length; i++) {
                                    Insets v = arr[i];
                                    if (v != null && v.bottom > CLAMP_BOTTOM_PX) {
                                        arr[i] = Insets.of(v.left, v.top, v.right,
                                                CLAMP_BOTTOM_PX);
                                        n++;
                                    }
                                }
                            }
                        } else if (t == Rect.class) {
                            Rect r = (Rect) f.get(win);
                            if (r != null && r.bottom > CLAMP_BOTTOM_PX) {
                                f.set(win, new Rect(r.left, r.top, r.right,
                                        CLAMP_BOTTOM_PX));
                                n++;
                            }
                        }
                    }
                    if (n > 0 && sClampLogs < 8) {
                        sClampLogs++;
                        XposedBridge.log("[GboardGapFix] REWROTE ctor fields=" + n);
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[GboardGapFix] ctor rewrite failed: " + t);
                }
            }
        };
    }
}

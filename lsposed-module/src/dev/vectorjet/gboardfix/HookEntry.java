package dev.vectorjet.gboardfix;

import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.view.WindowInsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Clamps the bottom mandatory/system gesture insets as seen by Gboard so the
 * keyboard pads to the pill height (40px) instead of the 80px gesture strip.
 * Scope: Gboard packages only. System-wide insets and the swipe area untouched.
 *
 * Calls the host bridge's findAndHookMethod adaptively: Vector's legacy bridge
 * obfuscates the Xposed API and may not carry the canonical overloads, so we
 * enumerate them at runtime and invoke a compatible form via reflection, with
 * fallback to the alternate form when one throws.
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String GBOARD = "com.google.android.inputmethod.latin";
    private static final String GBOARD_JASON = "dev.jason.com.google.android.inputmethod.latin";
    // 16dp @ 400dpi == current navigationBars bottom inset. Gboard pads to
    // max(nav, mandatory); clamping mandatory to the same value closes the gap.
    private static final int CLAMP_BOTTOM_PX = 40;
    // Framework nav/gesture dimen family. Gboard may read one of these directly
    // instead of the dispatched insets: gesture_height, frame_height, height.
    private static final int[] DIMEN_IDS = {
            0x01050277, 0x01050278, 0x01050279};

    private static Method sClassForm;
    private static Method sNameForm;
    private static Method sCtorClassForm;
    private static Method sCtorNameForm;
    private static int sClampLogs;
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
        doHook(lpparam.classLoader, "getMandatorySystemGestures", null, getterClamp());
        doHook(lpparam.classLoader, "getSystemGestureInsets", null, getterClamp());
        doHook(lpparam.classLoader, "getInsets",
                new Class<?>[]{int.class}, maskedClamp());
        doHook(lpparam.classLoader, "getInsetsIgnoringVisibility",
                new Class<?>[]{int.class}, maskedClamp());
        doHookRes("getDimensionPixelSize", new Class<?>[]{int.class}, dimenIntClamp());
        doHookRes("getDimension", new Class<?>[]{int.class}, dimenFloatClamp());
        doHookSysWin();
        // Birth-rewrite: every cross-process Insets arrives via the Parcel
        // constructor. Clamping bottoms there covers even read paths whose
        // getters the bridge refuses to hook.
        doHookCtor(lpparam.classLoader, new Class<?>[]{Parcel.class}, ctorRewrite());
    }

    private boolean pickHook() {
        try {
            Method[] methods = XposedHelpers.class.getDeclaredMethods();
            StringBuilder sb = new StringBuilder("[GboardGapFix] helpers:");
            StringBuilder all = new StringBuilder("[GboardGapFix] all:");
            for (Method m : methods) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                all.append(' ').append(m.getName()).append(sig(m.getParameterTypes()))
                        .append(';');
                if (!"findAndHookMethod".equals(m.getName())
                        && !"findAndHookConstructor".equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                sb.append(' ').append(sig(p)).append(';');
                if (p.length == 3 && p[0] == Class.class
                        && p[1] == String.class && p[2] == Object[].class) {
                    sClassForm = m;
                }
                if (p.length == 4 && p[0] == String.class
                        && p[1] == ClassLoader.class
                        && p[2] == String.class && p[3] == Object[].class) {
                    sNameForm = m;
                }
                if (!"findAndHookConstructor".equals(m.getName())) {
                    continue;
                }
                if (p.length == 2 && p[0] == Class.class && p[1] == Object[].class) {
                    sCtorClassForm = m;
                }
                if (p.length == 3 && p[0] == String.class
                        && p[1] == ClassLoader.class && p[2] == Object[].class) {
                    sCtorNameForm = m;
                }
            }
            XposedBridge.log(sb.toString());
            String allStr = all.toString();
            XposedBridge.log(allStr.length() > 950 ? allStr.substring(0, 950) : allStr);
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

    private void doHook(ClassLoader loader, String name,
            Class<?>[] params, XC_MethodHook cb) {
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
                    sClassForm.invoke(null,
                            new Object[]{WindowInsets.class, name, tail});
                    XposedBridge.log("[GboardGapFix] hooked " + name);
                    return;
                } catch (Throwable t) {
                    firstError = t;
                }
            }
            if (sNameForm != null) {
                try {
                    sNameForm.invoke(null, new Object[]{
                            "android.view.WindowInsets", loader, name, tail});
                    XposedBridge.log("[GboardGapFix] hooked " + name + " (name form)");
                    return;
                } catch (Throwable t) {
                    firstError = t;
                }
            }
            XposedBridge.log("[GboardGapFix] hook failed: " + name + " " + firstError);
        } catch (Throwable t) {
            XposedBridge.log("[GboardGapFix] hook failed: " + name + " " + t);
        }
    }

    private void doHookRes(String name, Class<?>[] params, XC_MethodHook cb) {
        try {
            Object[] tail = new Object[params.length + 1];
            System.arraycopy(params, 0, tail, 0, params.length);
            tail[params.length] = cb;
            Throwable firstError = null;
            if (sClassForm != null) {
                try {
                    sClassForm.invoke(null,
                            new Object[]{Resources.class, name, tail});
                    XposedBridge.log("[GboardGapFix] hooked res " + name);
                    return;
                } catch (Throwable t) {
                    firstError = t;
                }
            }
            if (sNameForm != null) {
                try {
                    sNameForm.invoke(null, new Object[]{
                            "android.content.res.Resources",
                            HookEntry.class.getClassLoader(), name, tail});
                    XposedBridge.log("[GboardGapFix] hooked res " + name + " (name form)");
                    return;
                } catch (Throwable t) {
                    firstError = t;
                }
            }
            XposedBridge.log("[GboardGapFix] hook failed: res " + name + " " + firstError);
        } catch (Throwable t) {
            XposedBridge.log("[GboardGapFix] hook failed: res " + name + " " + t);
        }
    }

    private void doHookSysWin() {
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Rect r = (Rect) param.getResult();
                if (r != null && r.bottom > CLAMP_BOTTOM_PX) {
                    logClampOnce("syswin", r.bottom);
                    param.setResult(new Rect(r.left, r.top, r.right, CLAMP_BOTTOM_PX));
                }
            }
        };
        try {
            Object[] tail = new Object[]{cb};
            if (sClassForm != null) {
                try {
                    sClassForm.invoke(null,
                            new Object[]{WindowInsets.class, "getSystemWindowInsets", tail});
                    XposedBridge.log("[GboardGapFix] hooked getSystemWindowInsets");
                    return;
                } catch (Throwable t) {
                    XposedBridge.log("[GboardGapFix] syswin class form: " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[GboardGapFix] hook failed: getSystemWindowInsets " + t);
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
                XposedBridge.log("[GboardGapFix] ctor class form: " + t);
            }
        }
        if (sCtorNameForm != null) {
            try {
                sCtorNameForm.invoke(null, new Object[]{
                        "android.view.WindowInsets", loader, tail});
                XposedBridge.log("[GboardGapFix] hooked ctor (name form)");
                return;
            } catch (Throwable t) {
                XposedBridge.log("[GboardGapFix] ctor name form: " + t);
            }
        }
        if (sCtorClassForm == null && sCtorNameForm == null) {
            XposedBridge.log("[GboardGapFix] NO ctor hook form available");
        }
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
                    if (n > 0 && sClampLogs < 6) {
                        sClampLogs++;
                        XposedBridge.log("[GboardGapFix] REWROTE ctor fields=" + n);
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[GboardGapFix] ctor rewrite failed: " + t);
                }
            }
        };
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

    private static void logClampOnce(String what, int from) {
        if (sClampLogs < 6) {
            sClampLogs++;
            XposedBridge.log("[GboardGapFix] CLAMPED " + what + " bottom "
                    + from + "->" + CLAMP_BOTTOM_PX);
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

    private XC_MethodHook getterClamp() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Insets in = (Insets) param.getResult();
                if (in != null && in.bottom > CLAMP_BOTTOM_PX) {
                    logClampOnce("getter", in.bottom);
                    param.setResult(Insets.of(in.left, in.top, in.right, CLAMP_BOTTOM_PX));
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
                    logClampOnce("dimenPx/" + Integer.toHexString(id),
                            (Integer) param.getResult());
                    param.setResult(CLAMP_BOTTOM_PX);
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
                    logClampOnce("dimen/" + Integer.toHexString(id),
                            ((Float) param.getResult()).intValue());
                    param.setResult((float) CLAMP_BOTTOM_PX);
                }
            }
        };
    }

    private XC_MethodHook maskedClamp() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int mask = (Integer) param.args[0];
                // Never touch IME-height queries; only gesture-containing masks.
                if ((mask & WindowInsets.Type.ime()) != 0) {
                    return;
                }
                if ((mask & WindowInsets.Type.mandatorySystemGestures()) == 0
                        && (mask & WindowInsets.Type.systemGestures()) == 0) {
                    return;
                }
                Insets in = (Insets) param.getResult();
                if (in != null && in.bottom > CLAMP_BOTTOM_PX) {
                    logClampOnce("masked/" + mask, in.bottom);
                    param.setResult(Insets.of(in.left, in.top, in.right, CLAMP_BOTTOM_PX));
                }
            }
        };
    }
}

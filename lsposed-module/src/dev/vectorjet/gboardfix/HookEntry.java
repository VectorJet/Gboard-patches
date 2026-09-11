package dev.vectorjet.gboardfix;

import android.graphics.Insets;
import android.view.WindowInsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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

    private static Method sClassForm;
    private static Method sNameForm;
    private static boolean sLoggedClamp;

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
    }

    private boolean pickHook() {
        try {
            Method[] methods = XposedHelpers.class.getDeclaredMethods();
            StringBuilder sb = new StringBuilder("[GboardGapFix] helpers:");
            for (Method m : methods) {
                if (!"findAndHookMethod".equals(m.getName())) {
                    continue;
                }
                if (!Modifier.isStatic(m.getModifiers())) {
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
        if (!sLoggedClamp) {
            sLoggedClamp = true;
            XposedBridge.log("[GboardGapFix] CLAMPED " + what + " bottom "
                    + from + "->" + CLAMP_BOTTOM_PX);
        }
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

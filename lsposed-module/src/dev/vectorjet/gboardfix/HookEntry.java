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
 * enumerate them at runtime and invoke a compatible form via reflection.
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String GBOARD = "com.google.android.inputmethod.latin";
    private static final String GBOARD_JASON = "dev.jason.com.google.android.inputmethod.latin";
    // 16dp @ 400dpi == current navigationBars bottom inset. Gboard pads to
    // max(nav, mandatory); clamping mandatory to the same value closes the gap.
    private static final int CLAMP_BOTTOM_PX = 40;

    private static final class HookSpec {
        final boolean classForm; // (Class, String, Object[]) vs (String, ClassLoader, String, Object[])
        final Method method;

        HookSpec(boolean classForm, Method method) {
            this.classForm = classForm;
            this.method = method;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD.equals(lpparam.packageName)
                && !GBOARD_JASON.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("[GboardGapFix] loaded in " + lpparam.packageName);
        HookSpec spec = pickHook();
        if (spec == null) {
            return;
        }
        doHook(spec, lpparam.classLoader, "getMandatorySystemGestures", null, getterClamp());
        doHook(spec, lpparam.classLoader, "getSystemGestureInsets", null, getterClamp());
        doHook(spec, lpparam.classLoader, "getInsets",
                new Class<?>[]{int.class}, maskedClamp());
        doHook(spec, lpparam.classLoader, "getInsetsIgnoringVisibility",
                new Class<?>[]{int.class}, maskedClamp());
    }

    private HookSpec pickHook() {
        try {
            Method[] methods = XposedHelpers.class.getDeclaredMethods();
            StringBuilder sb = new StringBuilder("[GboardGapFix] helpers:");
            Method classForm = null;
            Method nameForm = null;
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
                    classForm = m;
                }
                if (p.length == 4 && p[0] == String.class
                        && p[1] == ClassLoader.class
                        && p[2] == String.class && p[3] == Object[].class) {
                    nameForm = m;
                }
            }
            XposedBridge.log(sb.toString());
            if (classForm != null) {
                XposedBridge.log("[GboardGapFix] using (Class,String) form");
                return new HookSpec(true, classForm);
            }
            if (nameForm != null) {
                XposedBridge.log("[GboardGapFix] using (String,Loader) form");
                return new HookSpec(false, nameForm);
            }
            XposedBridge.log("[GboardGapFix] NO compatible findAndHookMethod");
        } catch (Throwable t) {
            XposedBridge.log("[GboardGapFix] pick failed: " + t);
        }
        return null;
    }

    private void doHook(HookSpec spec, ClassLoader loader, String name,
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
            Object[] invokeArgs;
            if (spec.classForm) {
                invokeArgs = new Object[]{WindowInsets.class, name, tail};
            } else {
                invokeArgs = new Object[]{"android.view.WindowInsets", loader, name, tail};
            }
            spec.method.invoke(null, invokeArgs);
            XposedBridge.log("[GboardGapFix] hooked " + name);
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

    private XC_MethodHook getterClamp() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Insets in = (Insets) param.getResult();
                if (in != null && in.bottom > CLAMP_BOTTOM_PX) {
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
                    param.setResult(Insets.of(in.left, in.top, in.right, CLAMP_BOTTOM_PX));
                }
            }
        };
    }
}

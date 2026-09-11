package dev.vectorjet.gboardfix;

import android.graphics.Insets;
import android.view.WindowInsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Clamps the bottom mandatory/system gesture insets as seen by Gboard so the
 * keyboard pads to the pill height (40px) instead of the 80px gesture strip.
 * Scope: com.google.android.inputmethod.latin only. System-wide insets and
 * the actual swipe area are untouched.
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String GBOARD = "com.google.android.inputmethod.latin";
    private static final String GBOARD_JASON = "dev.jason.com.google.android.inputmethod.latin";
    // 16dp @ 400dpi == current navigationBars bottom inset. Gboard pads to
    // max(nav, mandatory); clamping mandatory to the same value closes the gap.
    private static final int CLAMP_BOTTOM_PX = 40;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD.equals(lpparam.packageName)
                && !GBOARD_JASON.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("[GboardGapFix] loaded in " + lpparam.packageName);

        final XC_MethodHook clampGetter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Insets in = (Insets) param.getResult();
                if (in != null && in.bottom > CLAMP_BOTTOM_PX) {
                    param.setResult(Insets.of(in.left, in.top, in.right, CLAMP_BOTTOM_PX));
                }
            }
        };

        final XC_MethodHook clampMasked = new XC_MethodHook() {
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

        hook("getMandatorySystemGestures", clampGetter);
        hook("getSystemGestureInsets", clampGetter);
        hook("getInsets", clampMasked);
        hook("getInsetsIgnoringVisibility", clampMasked);
    }

    private void hook(String name, XC_MethodHook cb) {
        try {
            XposedHelpers.findAndHookMethod(WindowInsets.class, name, cb);
            XposedBridge.log("[GboardGapFix] hooked " + name);
        } catch (Throwable t) {
            XposedBridge.log("[GboardGapFix] hook failed: " + name + " " + t);
        }
    }
}

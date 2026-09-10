package dev.jason.gboardpatches.extension.roundedkeyboard;

import android.content.SharedPreferences;
import android.view.View;

import dev.jason.gboardpatches.extension.flagsettings.GboardFlagRuntimeContext;

public final class GboardRoundedKeyboardRuntime {
    private static final String MAIN_PANEL_SELECTOR =
            ".keyboard-base-area.keyboard-outline";
    private static final String BOTTOM_OUTLINE_SELECTOR =
            ".keyboard-body-area.keyboard-outline-bottom.clip-to-outline-for-old-android";
    // Loup (LatinIME) -> InputView field, same as calculator targets (18.0.3).
    private static final String LATIN_IME_CLASS = "oup";
    private static final String INPUT_VIEW_FIELD = "j";

    private GboardRoundedKeyboardRuntime() {
    }

    public static boolean applyThemeAdmission(boolean stockResult) {
        if (stockResult) {
            return true;
        }
        try {
            GboardRoundedKeyboardConfig config = readConfigOrNull();
            return config != null && config.isEnabled();
        } catch (Throwable ignored) {
            return stockResult;
        }
    }

    public static float[] resolveEffectiveRadiiDp(String selector) {
        try {
            if (!MAIN_PANEL_SELECTOR.equals(selector)
                    && !BOTTOM_OUTLINE_SELECTOR.equals(selector)) {
                return null;
            }
            GboardRoundedKeyboardConfig config = readConfigOrNull();
            if (config == null || !config.isEnabled()) {
                return null;
            }
            float top = config.getMode() == GboardRoundedKeyboardConfig.Mode.BOTTOM
                    ? 0.0f : config.getTopRadiusDp();
            float bottom = config.getMode() == GboardRoundedKeyboardConfig.Mode.TOP
                    ? 0.0f : config.getBottomRadiusDp();
            if (BOTTOM_OUTLINE_SELECTOR.equals(selector)) {
                top = 0.0f;
            }
            return new float[] {top, top, bottom, bottom};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Called on Loup.onStartInputView exit: pull keyboard down to sit just on top of pill. */
    public static void onInputViewStarted(Object latinIme) {
        try {
            GboardRoundedKeyboardConfig config = readConfigOrNull();
            if (config == null || !config.isEnabled()) {
                return;
            }
            int gapDp = config.getBottomGapDp();
            View inputView = extractInputView(latinIme);
            if (inputView == null) {
                return;
            }
            final float density = inputView.getResources().getDisplayMetrics().density;
            final int gapPx = Math.round(gapDp * density);
            inputView.post(() -> {
                try {
                    // Keep L/R/T padding, force bottom to gapPx (pill height ~4dp).
                    // Stock Gboard uses nav inset (~24dp+) here, which is the black strip.
                    inputView.setPadding(
                            inputView.getPaddingLeft(),
                            inputView.getPaddingTop(),
                            inputView.getPaddingRight(),
                            gapPx);
                    View parent = (View) inputView.getParent();
                    if (parent != null) {
                        parent.setPadding(
                                parent.getPaddingLeft(),
                                parent.getPaddingTop(),
                                parent.getPaddingRight(),
                                0);
                        parent.requestLayout();
                    }
                    inputView.requestLayout();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static int resolveBottomGapDp() {
        try {
            GboardRoundedKeyboardConfig config = readConfigOrNull();
            if (config == null || !config.isEnabled()) {
                return -1;
            }
            return config.getBottomGapDp();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static View extractInputView(Object latinIme) {
        try {
            if (latinIme == null) {
                return null;
            }
            if (latinIme instanceof View view) {
                return view;
            }
            Class<?> imeClass = latinIme.getClass();
            // Fast path: obfuscated Loup.j field holding InputView (FrameLayout).
            try {
                java.lang.reflect.Field field = imeClass.getDeclaredField(INPUT_VIEW_FIELD);
                field.setAccessible(true);
                Object value = field.get(latinIme);
                if (value instanceof View view) {
                    return view;
                }
            } catch (NoSuchFieldException ignored) {
            }
            // Fallback: scan View-typed fields for InputView.
            for (java.lang.reflect.Field field : imeClass.getDeclaredFields()) {
                if (!View.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(latinIme);
                    if (value instanceof View view
                            && view.getClass().getName().contains("InputView")) {
                        return view;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static GboardRoundedKeyboardConfig readConfigOrNull() {
        SharedPreferences preferences = GboardFlagRuntimeContext.preferencesOrNull();
        return GboardRoundedKeyboardSettings.readSnapshotOrNull(preferences);
    }
}

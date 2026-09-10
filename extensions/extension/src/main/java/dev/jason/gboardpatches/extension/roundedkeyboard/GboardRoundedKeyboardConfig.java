package dev.jason.gboardpatches.extension.roundedkeyboard;

public final class GboardRoundedKeyboardConfig {
    public static final boolean DEFAULT_ENABLED = false;
    public static final Mode DEFAULT_MODE = Mode.ALL;
    public static final int MIN_RADIUS_DP = 0;
    public static final int MAX_RADIUS_DP = 64;
    public static final int DEFAULT_RADIUS_DP = 32;
    public static final int MIN_BOTTOM_GAP_DP = 0;
    public static final int MAX_BOTTOM_GAP_DP = 32;
    public static final int DEFAULT_BOTTOM_GAP_DP = 4;

    public enum Mode {
        ALL("all"),
        TOP("top"),
        BOTTOM("bottom");

        private final String storedValue;

        Mode(String storedValue) {
            this.storedValue = storedValue;
        }

        public String storedValue() {
            return storedValue;
        }

        public static Mode fromStoredValue(String value) {
            if (value != null) {
                for (Mode mode : values()) {
                    if (mode.storedValue.equalsIgnoreCase(value.trim())) {
                        return mode;
                    }
                }
            }
            return null;
        }
    }

    private final boolean enabled;
    private final Mode mode;
    private final int topRadiusDp;
    private final int bottomRadiusDp;
    private final int bottomGapDp;

    public GboardRoundedKeyboardConfig(
            boolean enabled, Mode mode, int topRadiusDp, int bottomRadiusDp) {
        this(enabled, mode, topRadiusDp, bottomRadiusDp, DEFAULT_BOTTOM_GAP_DP);
    }

    public GboardRoundedKeyboardConfig(
            boolean enabled, Mode mode, int topRadiusDp, int bottomRadiusDp, int bottomGapDp) {
        this.enabled = enabled;
        this.mode = mode == null ? DEFAULT_MODE : mode;
        this.topRadiusDp = sanitizeRadiusDp(topRadiusDp);
        this.bottomRadiusDp = sanitizeRadiusDp(bottomRadiusDp);
        this.bottomGapDp = sanitizeBottomGapDp(bottomGapDp);
    }

    public static GboardRoundedKeyboardConfig defaults() {
        return new GboardRoundedKeyboardConfig(
                DEFAULT_ENABLED, DEFAULT_MODE, DEFAULT_RADIUS_DP, DEFAULT_RADIUS_DP,
                DEFAULT_BOTTOM_GAP_DP);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public int getTopRadiusDp() {
        return topRadiusDp;
    }

    public int getBottomRadiusDp() {
        return bottomRadiusDp;
    }

    public int getBottomGapDp() {
        return bottomGapDp;
    }

    public static int sanitizeRadiusDp(int value) {
        return Math.max(MIN_RADIUS_DP, Math.min(value, MAX_RADIUS_DP));
    }

    public static int sanitizeBottomGapDp(int value) {
        return Math.max(MIN_BOTTOM_GAP_DP, Math.min(value, MAX_BOTTOM_GAP_DP));
    }
}

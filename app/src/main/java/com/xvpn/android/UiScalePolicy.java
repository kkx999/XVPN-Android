package com.xvpn.android;

/** Conservative phone layout scaling; large screens keep the approved design. */
final class UiScalePolicy {
    private UiScalePolicy() {}

    static float layoutScale(int widthDp, int heightDp) {
        int safeWidth = widthDp > 0 ? widthDp : 393;
        int safeHeight = heightDp > 0 ? heightDp : 780;
        float widthScale = Math.max(.86f, Math.min(1f, safeWidth / 393f));
        float heightScale = Math.max(.90f, Math.min(1f, safeHeight / 760f));
        return Math.min(widthScale, heightScale);
    }

    static float textScale(float layoutScale, float fontScale) {
        float safeFontScale = fontScale > 0f ? fontScale : 1f;
        float fontCompensation = safeFontScale > 1.15f ? 1.15f / safeFontScale : 1f;
        return Math.max(.94f, Math.min(1f, layoutScale)) * fontCompensation;
    }
}

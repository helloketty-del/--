package com.screencopy.touch;

/** Screen coordinates are constrained to safe system insets, including cutouts. */
public final class DockMath {
    private DockMath() {}
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }
    public static int edge(int x, int minX, int maxX) {
        return (long) x * 2 <= (long) minX + maxX ? minX : maxX;
    }
    public static float fraction(int y, int minY, int maxY) {
        return maxY <= minY ? 0.5f : (clamp(y, minY, maxY) - minY) / (float) (maxY - minY);
    }
    public static int fromFraction(float value, int minY, int maxY) {
        if (!Float.isFinite(value)) value = 0.5f;
        return clamp(minY + Math.round(value * (maxY - minY)), minY, maxY);
    }
}

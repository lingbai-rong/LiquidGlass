package rongyi.lingbai;

/** PixelCopy uses window coordinates; adapter media frames use physical screen coordinates. */
public final class PortBackdropCoordinates {
    private PortBackdropCoordinates() {}
    public static int windowCoordinate(int screen, int rootScreen, int rootWindow) {
        return screen - rootScreen + rootWindow;
    }
    public static int[] clipStrip(int left, int top, int width, int height, int padding,
                                  int rootWidth, int rootHeight) {
        if (width <= 0 || height <= 0 || rootWidth <= 0 || rootHeight <= 0) return null;
        int l = Math.max(0, left - padding), t = Math.max(0, top - padding);
        int r = Math.min(rootWidth, left + width + padding), b = Math.min(rootHeight, top + height + padding);
        return r > l && b > t ? new int[]{l, t, r, b} : null;
    }
}

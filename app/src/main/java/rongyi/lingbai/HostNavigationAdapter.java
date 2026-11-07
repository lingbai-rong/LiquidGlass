package rongyi.lingbai;

import android.graphics.Bitmap;
import android.view.View;

/**
 * Optional bridge used when the shared liquid-glass view is mounted into a host
 * other than CoolAPK. Each target keeps its own native navigation implementation.
 */
public interface HostNavigationAdapter {
    boolean select(int index);
    boolean post();
    int detectSelectedIndex();

    /**
     * Hosts that replace Fragment/Texture/Surface content asynchronously may request a short
     * backdrop hand-off window. Zero keeps the normal real-time capture path unchanged.
     */
    default long backdropTransitionDelayMs() { return 0L; }

    /**
     * Optional page-aware hardware-layer policy for the legacy Bitmap backdrop.
     *
     * Return {@code 1} when the selected page needs TextureView/SurfaceView pixels,
     * {@code 0} when it must use only the ordinary View hierarchy, or {@code -1} to keep the
     * host-wide capture profile.  The explicit three-state contract prevents a page without a
     * camera from accidentally reusing a still-attached SurfaceView owned by the previous page.
     */
    default int hardwareBackdropCaptureModeForSelection(int selectedIndex) { return -1; }

    /** Visible tab rendered as a CoolAPK-style circular center action, or -1. */
    default int centerActionTabIndex() { return -1; }

    /**
     * Douyin-style bottom bar layout: four ordinary tabs in a row plus a separate circular
     * "+" action at the right end of the pill. The host's real publish view is still layered
     * above that circle and taps go through the original publish controller.
     */
    default boolean separateCirclePlus() { return false; }

    /** Optional host-owned center action View (for example Douyin's real PublishButton). */
    default View getNativeCenterActionView() { return null; }

    /** True when the center slot must never draw the module's synthetic action icon/text. */
    default boolean usesNativeCenterActionView() { return false; }

    /** Resolve the page that is currently visible behind the glass. */
    default View getBackdropSourceView() { return null; }

    /**
     * Use the adapter's page view as the actual capture root instead of only using it as a
     * change hint. Hosts should opt in only when that view covers the glass strip in stable screen
     * coordinates; ordinary hosts continue sampling the Activity content root.
     */
    default boolean useExactBackdropSource() { return false; }

    /**
     * Optional host-owned drawable ids for the visible tabs. The ids are resolved and rasterized
     * by the host-context MiuixGlassNavView before entering the isolated Compose resource context.
     */
    default int[] getNavigationIconResourceIds() { return new int[0]; }

    /** Optional host-owned raster icons, used when the host builds icons from raw/SVG assets. */
    default Bitmap[] getNavigationIconBitmaps() { return new Bitmap[0]; }

    /**
     * Host-owned overlay views that must stay visible above LiquidGlass but must not be sampled
     * into the backdrop texture. This is primarily used by NetEase's mini player: drawing it into
     * the captured bottom strip duplicates and stretches the player inside the glass.
     */
    default View[] getBackdropExcludedViews() { return new View[0]; }
}

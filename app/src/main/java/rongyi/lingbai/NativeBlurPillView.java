package rongyi.lingbai;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;

/** Small Android View used only as the physical HyperOS blur surface behind the Compose bar. */
public final class NativeBlurPillView extends View {
    private final GradientDrawable background = new GradientDrawable();
    private boolean active;

    public NativeBlurPillView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        background.setShape(GradientDrawable.RECTANGLE);
        setBackground(background);
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        setClipToOutline(true);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setVisibility(INVISIBLE);
    }

    public boolean update(float cornerRadiusPx, int blurRadiusPx, boolean dark, float surfaceAlpha) {
        background.setCornerRadius(Math.max(1f, cornerRadiusPx));

        // surfaceAlpha is zero for the full bottom bar and non-zero only for the moving native
        // indicator. Xiaomi's blur API can blend a translucent colour into the compositor result,
        // which gives the selected lens a cleaner white-frosted appearance without reading pixels
        // back into a Bitmap. Keep the drawable fill weaker than the compositor blend so coloured
        // host content remains visible instead of becoming a flat white tile.
        float safeAlpha = Math.max(0f, Math.min(surfaceAlpha, 0.72f));
        int blendAlpha = Math.round(safeAlpha * 255f);
        int drawableAlpha = Math.round(safeAlpha * 255f * (dark ? 0.32f : 0.74f));
        int whiteBlend = Color.argb(blendAlpha, 255, 255, 255);
        background.setColor(Color.argb(drawableAlpha, 255, 255, 255));

        setVisibility(VISIBLE);
        active = HyperOsNativeBlur.enable(
                this,
                blurRadiusPx,
                blendAlpha > 0 ? whiteBlend : Color.TRANSPARENT
        );
        if (!active) setVisibility(INVISIBLE);
        invalidate();
        return active;
    }

    public boolean isActive() {
        return active;
    }

    public void disable() {
        active = false;
        HyperOsNativeBlur.disable(this);
        setVisibility(INVISIBLE);
    }

    @Override
    protected void onDetachedFromWindow() {
        disable();
        super.onDetachedFromWindow();
    }
}


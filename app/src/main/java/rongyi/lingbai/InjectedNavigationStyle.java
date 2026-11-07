package rongyi.lingbai;

/** Host-only style values and geometry shared by Compose, touch gates and native overlays. */
public final class InjectedNavigationStyle {
    public static final String HYPER_OS = "hyper_os";
    public static final String HYPER_OS_FLOATING = "hyper_os_floating";
    public static final String LIQUID_GLASS = "liquid_glass";
    // Literal HyperChanger proportions; its reference capsule contains two tabs.
    public static final float FLOATING_SCALE = 0.82f;
    public static final float FLOATING_WIDTH_FRACTION = 0.53f * FLOATING_SCALE;
    public static final float FLOATING_HEIGHT_DP = 72f * FLOATING_SCALE;
    public static final float FLOATING_INNER_PADDING_DP = 4f * FLOATING_SCALE;
    public static final float FLOATING_BOTTOM_GAP_DP = 8f * FLOATING_SCALE;
    // Before the first Compose measurement only; the standard bar keeps Miuix's natural height.
    public static final float INITIAL_STANDARD_CONTENT_HEIGHT_DP = 56f;

    private InjectedNavigationStyle() {}

    public static String normalize(String value) {
        if (HYPER_OS.equals(value) || HYPER_OS_FLOATING.equals(value)) return value;
        return LIQUID_GLASS;
    }

    /** The action is clickable, but releasing a selection drag must never launch it. */
    public static int resolveDragSlot(int target, int selected, int blocked, int count) {
        if (count <= 1) return 0;
        int safeTarget = Math.max(0, Math.min(target, count - 1));
        int safeSelected = Math.max(0, Math.min(selected, count - 1));
        return safeTarget == blocked ? safeSelected : safeTarget;
    }

    public static Layout layout(
            String style, float hostWidthDp, float viewportWidthDp, int labelCount,
            boolean showPostButton, int centerActionIndex, boolean separateCircle,
            float standardContentHeightDp, float bottomInsetDp
    ) {
        int count = Math.max(1, labelCount);
        boolean standard = HYPER_OS.equals(normalize(style));
        boolean separate = !standard && separateCircle && count >= 5;
        int center = centerActionIndex >= 0 && centerActionIndex < count
                ? centerActionIndex : -1;
        int omitted = separate ? (center >= 0 ? center : 2) : -1;
        int slots = separate ? count - 1 : count + (showPostButton ? 1 : 0);
        int actionSlot = separate ? -1 : showPostButton ? (count + 1) / 2 : center;
        int[] logicalIndices = new int[slots];
        for (int slot = 0, logical = 0; slot < slots; slot++) {
            if (separate && logical == omitted) logical++;
            logicalIndices[slot] = showPostButton && !separate && slot == actionSlot
                    ? -1 : logical++;
        }
        float hostWidth = Math.max(1f, hostWidthDp);
        float circle = separate ? Math.min(46f, Math.max(0f, (hostWidth - 1f) / 2f)) : 0f;
        float gap = separate ? Math.min(8f, Math.max(0f, hostWidth - circle - 1f)) : 0f;
        // HyperChanger's reference has two tabs. Preserve its per-tab spacing when hosts
        // expose more tabs, then fit the complete row inside a 12dp margin on either side.
        float rowAvailable = standard ? hostWidth
                : Math.min(hostWidth, Math.max(1f, viewportWidthDp - 24f));
        float available = Math.max(1f, rowAvailable - circle - gap);
        float width = standard ? available : Math.min(floatingWidthDp(viewportWidthDp, slots), available);
        float rowWidth = width + circle + gap;
        float contentHeight = standard ? Math.max(1f, standardContentHeightDp) : FLOATING_HEIGHT_DP;
        float inset = Math.max(0f, bottomInsetDp);
        return new Layout(logicalIndices, actionSlot, separate, center >= 0,
                width, rowWidth, (hostWidth - rowWidth) / 2f, circle, gap, standard,
                contentHeight + (standard ? inset : 0f),
                standard ? 0f : FLOATING_BOTTOM_GAP_DP + inset,
                contentHeight / 2f + (standard ? inset : 0f));
    }

    private static float floatingWidthDp(float viewportWidthDp, int slots) {
        float padding = 2f * FLOATING_INNER_PADDING_DP;
        float referenceWidth = Math.max(1f, viewportWidthDp) * FLOATING_WIDTH_FRACTION;
        float slotWidth = Math.max(1f, (referenceWidth - padding) / 2f);
        return slotWidth * Math.max(1, slots) + padding;
    }

    public static float overlayWidthDp(String style, float viewportWidthDp, int labelCount,
            boolean showPostButton, boolean separateCircle) {
        float viewport = Math.max(1f, viewportWidthDp);
        if (HYPER_OS.equals(normalize(style))) return viewport;
        int count = Math.max(1, labelCount);
        boolean separate = separateCircle && count >= 5;
        int slots = separate ? count - 1 : count + (showPostButton ? 1 : 0);
        return Math.min(viewport, floatingWidthDp(viewport, slots)
                + (separate ? 54f : 0f) + 68f);
    }

    public static final class Layout {
        public final int[] logicalIndices;
        public final int actionSlot;
        public final boolean separateCircle;
        public final boolean separateActionEnabled;
        public final float barWidthDp;
        public final float rowWidthDp;
        public final float barLeftDp;
        public final float circleDiameterDp;
        public final float circleGapDp;
        public final boolean standard;
        public final float barHeightDp;
        public final float bottomPaddingDp;
        public final float contentCenterYDp;
        public final float captureHeightDp;

        private Layout(int[] logicalIndices, int actionSlot, boolean separateCircle,
                boolean separateActionEnabled, float barWidthDp, float rowWidthDp,
                float barLeftDp, float circleDiameterDp, float circleGapDp, boolean standard,
                float barHeightDp, float bottomPaddingDp, float contentCenterYDp) {
            this.logicalIndices = logicalIndices;
            this.actionSlot = actionSlot;
            this.separateCircle = separateCircle;
            this.separateActionEnabled = separateActionEnabled;
            this.barWidthDp = barWidthDp;
            this.rowWidthDp = rowWidthDp;
            this.barLeftDp = barLeftDp;
            this.circleDiameterDp = circleDiameterDp;
            this.circleGapDp = circleGapDp;
            this.standard = standard;
            this.barHeightDp = barHeightDp;
            this.bottomPaddingDp = bottomPaddingDp;
            this.contentCenterYDp = contentCenterYDp;
            this.captureHeightDp = Math.min(160f, Math.max(84f, barHeightDp + bottomPaddingDp + 16f));
        }

        public int selectedSlot(int logicalIndex) {
            for (int i = 0; i < logicalIndices.length; i++) {
                if (i != actionSlot && logicalIndices[i] == logicalIndex) return i;
            }
            for (int i = 0; i < logicalIndices.length; i++) if (i != actionSlot) return i;
            return 0;
        }

        public float actionCenterXDp() {
            if (separateCircle) return barLeftDp + barWidthDp + circleGapDp + circleDiameterDp / 2f;
            float inset = standard ? 0f : Math.min(FLOATING_INNER_PADDING_DP, barWidthDp / 2f);
            return barLeftDp + inset + (barWidthDp - inset * 2f)
                    * (actionSlot + 0.5f) / logicalIndices.length;
        }

        /** Coordinates in dp; y is the distance above the visible bar's bottom edge. */
        public boolean contains(float x, float y) {
            boolean bar = x >= barLeftDp - 8f && x <= barLeftDp + barWidthDp + 8f
                    && y >= -8f && y <= barHeightDp + 8f;
            if (bar) return true;
            if (!separateCircle || !separateActionEnabled) return false;
            float radius = circleDiameterDp / 2f + 8f;
            float dx = x - actionCenterXDp();
            float dy = y - contentCenterYDp;
            return dx * dx + dy * dy <= radius * radius;
        }
    }
}

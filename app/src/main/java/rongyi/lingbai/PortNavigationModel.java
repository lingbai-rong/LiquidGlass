package rongyi.lingbai;

/** Physical native indices remain stable when a user hides or reverses visible slots. */
public final class PortNavigationModel {
    private PortNavigationModel() {}
    public static int[] visibleIndices(int count, int hiddenMask, boolean reverse) {
        if (count < 2 || count > 7) return new int[0];
        int kept = 0;
        for (int i = 0; i < count; i++) if ((hiddenMask & (1 << i)) == 0) kept++;
        boolean allHidden = kept == 0;
        if (allHidden) kept = count;
        int[] result = new int[kept];
        int n = 0;
        for (int i = 0; i < count; i++) {
            int index = reverse ? count - 1 - i : i;
            if (allHidden || (hiddenMask & (1 << index)) == 0) result[n++] = index;
        }
        return result;
    }
}

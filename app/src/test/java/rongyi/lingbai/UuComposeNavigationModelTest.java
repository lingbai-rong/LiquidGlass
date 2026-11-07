package rongyi.lingbai;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class UuComposeNavigationModelTest {
    private enum Tab { DEVICE, CLOUD, ASSIST, MINE }

    public static final class Presenter {
        public final Flow x;
        Presenter(Tab... tabs) { x = new Flow(Arrays.asList(tabs)); }
    }

    public static final class Flow {
        private final List<Tab> tabs;
        Flow(List<Tab> tabs) { this.tabs = tabs; }
        public List<Tab> getValue() { return tabs; }
    }

    @Test public void fourTabsRemainAvailableWithoutSemantics() {
        assertArrayEquals(new int[]{0, 1, 2, 3}, UniversalHostAdapter.uuComposeNativeIds(
                new Presenter(Tab.DEVICE, Tab.CLOUD, Tab.ASSIST, Tab.MINE)));
    }

    @Test public void absentCloudDoesNotShiftAssistAndMine() {
        assertArrayEquals(new int[]{0, 2, 3}, UniversalHostAdapter.uuComposeNativeIds(
                new Presenter(Tab.DEVICE, Tab.ASSIST, Tab.MINE)));
    }

    @Test public void unavailableOrChangedModelsAreRejected() {
        assertNull(UniversalHostAdapter.uuComposeNativeIds(null));
        assertNull(UniversalHostAdapter.uuComposeNativeIds(new Presenter()));
        assertNull(UniversalHostAdapter.uuComposeNativeIds(
                new Presenter(Tab.DEVICE, Tab.ASSIST, Tab.CLOUD, Tab.MINE)));
        assertNull(UniversalHostAdapter.uuComposeNativeIds(
                new Presenter(Tab.DEVICE, Tab.MINE, Tab.MINE)));
    }
}

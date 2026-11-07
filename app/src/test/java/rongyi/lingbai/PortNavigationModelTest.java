package rongyi.lingbai;

import org.junit.Test;
import static org.junit.Assert.*;

public class PortNavigationModelTest {
    @Test public void everyMaskPreservesNativeTapIndicesInBothOrders() {
        for (int count=2;count<=7;count++) for (int mask=0;mask<(1<<count);mask++) {
            int[] normal=PortNavigationModel.visibleIndices(count,mask,false);
            int[] reverse=PortNavigationModel.visibleIndices(count,mask,true);
            assertEquals(normal.length,reverse.length);
            for (int slot=0;slot<normal.length;slot++) {
                assertEquals(normal[slot],reverse[reverse.length-1-slot]);
                assertTrue(normal[slot]>=0 && normal[slot]<count);
                if (mask != (1<<count)-1) assertEquals(0,mask & (1<<normal[slot]));
                if (slot>0) assertTrue(normal[slot]>normal[slot-1]);
            }
        }
    }
    @Test public void hiddenTabsDoNotShiftTheNativeAction() {
        assertArrayEquals(new int[]{0,2,4},PortNavigationModel.visibleIndices(5,(1<<1)|(1<<3),false));
        assertArrayEquals(new int[]{4,2,0},PortNavigationModel.visibleIndices(5,(1<<1)|(1<<3),true));
    }
    @Test public void allHiddenRetainsAUsableNavigationRow() {
        assertArrayEquals(new int[]{0,1,2,3,4},PortNavigationModel.visibleIndices(5,31,false));
    }
    @Test public void unsupportedNativeCountsAreRejected() {
        assertEquals(0,PortNavigationModel.visibleIndices(1,0,false).length);
        assertEquals(0,PortNavigationModel.visibleIndices(8,0,true).length);
    }
}

package rongyi.lingbai;

import org.junit.Test;
import static org.junit.Assert.*;

public class PortBackdropCoordinatesTest {
    @Test public void statusBarOffsetIsConvertedOnce() {
        assertEquals(1920,PortBackdropCoordinates.windowCoordinate(1980,60,0));
        assertEquals(1980,PortBackdropCoordinates.windowCoordinate(1980,60,60));
    }
    @Test public void landscapeStripIsClippedToTheOriginalWindow() {
        assertArrayEquals(new int[]{1840,920,2400,1080},PortBackdropCoordinates.clipStrip(1900,980,600,120,60,2400,1080));
    }
    @Test public void stripOutsideWindowIsRejected() {
        assertNull(PortBackdropCoordinates.clipStrip(3000,100,200,80,24,2400,1080));
    }
    @Test public void emptyWindowDoesNotAllocateACaptureBuffer() {
        assertNull(PortBackdropCoordinates.clipStrip(0,0,0,80,24,2400,1080));
        assertNull(PortBackdropCoordinates.clipStrip(0,0,400,80,24,0,0));
    }
}

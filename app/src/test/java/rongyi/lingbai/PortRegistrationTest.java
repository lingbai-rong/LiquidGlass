package rongyi.lingbai;

import org.junit.Test;
import static org.junit.Assert.*;

public class PortRegistrationTest {
    @Test public void fiveAppsAreRegisteredAsOrdinaryApplications() {
        TargetApp[] apps={TargetApp.AMAP,TargetApp.XIANYU,TargetApp.YOUTUBE,TargetApp.XJTU,TargetApp.ZHIHU};
        for (TargetApp app:apps) {
            assertSame(app,TargetApp.fromPackage(app.packageName));
            assertTrue(TargetApp.all().contains(app));
            assertFalse(app.isMiSystemApp());
            assertTrue(LiquidTabPortAdapter.supports(app));
        }
    }
    @Test public void campusUiProcessesAreBoundedToTheExtractedOwners() {
        assertTrue(LiquidTabPortAdapter.acceptsProcess(TargetApp.XJTU,"com.supwisdom.xjtu"));
        for (int i=0;i<5;i++) assertTrue(LiquidTabPortAdapter.acceptsProcess(TargetApp.XJTU,"com.supwisdom.xjtu:unimp"+i));
        assertFalse(LiquidTabPortAdapter.acceptsProcess(TargetApp.XJTU,"com.supwisdom.xjtu:unimp5"));
        assertFalse(LiquidTabPortAdapter.acceptsProcess(TargetApp.XJTU,"com.supwisdom.xjtu:push"));
        assertFalse(LiquidTabPortAdapter.acceptsProcess(TargetApp.YOUTUBE,"com.google.android.youtube:remote"));
    }
    @Test public void youtubeSubscriptionsRemainsASelectableDestination() {
        assertFalse(TargetApp.YOUTUBE.hasPostAction);
        assertEquals(-1,TargetApp.YOUTUBE.centerActionLabelIndex);
        assertEquals("订阅内容",TargetApp.YOUTUBE.labels[2]);
    }
}

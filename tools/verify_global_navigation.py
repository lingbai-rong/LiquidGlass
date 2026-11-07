#!/usr/bin/env python3
"""Run real global store/reader and geometry code with Android boundary stubs.

Requires Python 3.10+ and JDK 17+. Does not compile Compose or replace device testing.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/rongyi/lingbai"

STUBS = {
    "android/content/SharedPreferences.java": '''package android.content;
public interface SharedPreferences {
 boolean contains(String key); boolean getBoolean(String key, boolean fallback);
 int getInt(String key, int fallback); long getLong(String key, long fallback);
 String getString(String key, String fallback); Editor edit();
 interface Editor {
  Editor putBoolean(String key, boolean value); Editor putInt(String key, int value);
  Editor putLong(String key, long value); Editor putString(String key, String value);
  boolean commit();
 }
}''',
    "android/content/ContentResolver.java": '''package android.content;
public class ContentResolver {
 public Runnable onNotify = () -> {}; public int notifications;
 public void notifyChange(Object uri, Object observer) { onNotify.run(); notifications++; }
}''',
    "android/content/Context.java": '''package android.content;
public class Context {
 public boolean deviceProtected; public Context device;
 public SharedPreferences prefs; public final ContentResolver resolver = new ContentResolver();
 public boolean isDeviceProtectedStorage() { return deviceProtected; }
 public ContentResolver getContentResolver() { return resolver; }
}''',
    "android/os/SystemClock.java": '''package android.os;
public class SystemClock {
 public static long now = 10; public static long elapsedRealtime() { return now; }
}''',
    "rongyi/lingbai/HookPreferenceTransport.java": '''package rongyi.lingbai;
import android.content.*;
public class HookPreferenceTransport {
 public static SharedPreferences open(Context context, String name) { return context.prefs; }
}''',
    "rongyi/lingbai/XposedBridge.java": '''package rongyi.lingbai;
import android.content.SharedPreferences;
public class XposedBridge {
 public static SharedPreferences remote; public static int reads;
 public static SharedPreferences getRemotePreferences(String name) {
  reads++; if(remote == null) throw new IllegalStateException("Offline"); return remote;
 }
}''',
    "rongyi/lingbai/GlassSettings.java": '''package rongyi.lingbai;
import android.content.Context;
public class GlassSettings {
 public static final Object CONTENT_URI = new Object();
 public static final String KEY_TAB_WIDTH="width", KEY_BOTTOM_PADDING="bottom", KEY_BLUR_RADIUS="blur",
   KEY_LIGHT_ALPHA="light", KEY_DARK_ALPHA="dark", KEY_ICON_SCALE="icon", KEY_TEXT_SIZE="text";
 public long revision; public int themeMode, customPostColor;
 public boolean enabled, iconOnly, showPostButton, customColorEnabled, showChannel;
 public float tabWidthDp, bottomPaddingDp, blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp;
 public GlassSettings(long revision, boolean enabled, int themeMode, float width, float bottom,
  float blur, float light, float dark, float icon, float text, boolean iconOnly, boolean post,
  boolean custom, int color, boolean channel) {
  this.revision=revision; this.enabled=enabled; this.themeMode=themeMode; tabWidthDp=width;
  bottomPaddingDp=bottom; blurRadiusDp=blur; lightAlpha=light; darkAlpha=dark; iconScale=icon;
  textSizeSp=text; this.iconOnly=iconOnly; showPostButton=post; customColorEnabled=custom;
  customPostColor=color; showChannel=channel;
 }
 public static Context storageContext(Context c) { return c == null ? null : c.device == null ? c : c.device; }
 public static GlassSettings defaults() { return new GlassSettings(0,true,0,72,12,4,.3f,.4f,1,11,false,true,false,0,false); }
 public static GlassSettings loadLocal(Context c) { return defaults(); }
 public static void saveLocal(Context c, GlassSettings value) {}
 public GlassSettings withRevision(long value) { revision=value; return this; }
 public static void notifyChanged(Context c) { c.getContentResolver().notifyChange(CONTENT_URI,null); }
}''',
    "rongyi/lingbai/TargetApp.java": '''package rongyi.lingbai;
public class TargetApp { public static TargetApp[] all() { return new TargetApp[]{new TargetApp()}; } }
''',
    "rongyi/lingbai/TargetAppSettings.java": '''package rongyi.lingbai;
import android.content.Context;
public class TargetAppSettings {
 public int themeMode; public boolean iconOnly;
 public static TargetAppSettings loadLocal(Context c, TargetApp app) { return new TargetAppSettings(); }
 public TargetAppSettings withAppearance(int theme,float a,float b,float c,float d,float e,float f,float g,boolean icons) { return this; }
 public TargetAppSettings withRevision(long value) { return this; }
 public static void saveLocal(Context c,TargetApp app,TargetAppSettings value,boolean notify) {}
}''',
    "rongyi/lingbai/MiuixGlassNavView.java": '''package rongyi.lingbai;
public class MiuixGlassNavView {
 public String style; public boolean capture, nativeBlur, accent, traditional, haptics;
 public void setNavigationHapticsEnabled(boolean value) { haptics=value; }
 public void setNavigationStyle(String value) { style=value; }
 public void setBackdropCaptureEnabled(boolean value) { capture=value; }
 public void setNativeBlurEnabled(boolean value) { nativeBlur=value; }
 public void setSelectedAccentEnabled(boolean value) { accent=value; }
 public void setBottomBarTraditionalChinese(boolean value) { traditional=value; }
}''',
    "rongyi/lingbai/CoolapkLegacyGlassNavView.java": '''package rongyi.lingbai;
public class CoolapkLegacyGlassNavView extends MiuixGlassNavView {}''',
    "rongyi/lingbai/GlobalNavigationBehaviorTest.java": '''package rongyi.lingbai;
import android.content.*;
import java.util.*;
import static rongyi.lingbai.InjectedNavigationStyle.*;

public class GlobalNavigationBehaviorTest {
 static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
 static void near(float a,float b,String message) { check(Math.abs(a-b)<0.01f,message+": "+a+" != "+b); }
 static class Prefs implements SharedPreferences {
  final Map<String,Object> values=new HashMap<>();
  public boolean contains(String key) { return values.containsKey(key); }
  public boolean getBoolean(String k,boolean d) { return values.containsKey(k)?(Boolean)values.get(k):d; }
  public int getInt(String k,int d) { return values.containsKey(k)?(Integer)values.get(k):d; }
  public long getLong(String k,long d) { return values.containsKey(k)?(Long)values.get(k):d; }
  public String getString(String k,String d) { return values.containsKey(k)?(String)values.get(k):d; }
  public Editor edit() { return new Editor() {
   final Map<String,Object> pending=new HashMap<>();
   public Editor putBoolean(String k,boolean v) { pending.put(k,v);return this; }
   public Editor putInt(String k,int v) { pending.put(k,v);return this; }
   public Editor putLong(String k,long v) { pending.put(k,v);return this; }
   public Editor putString(String k,String v) { pending.put(k,v);return this; }
   public boolean commit() { values.putAll(pending);return true; }
  }; }
 }
 static void storeAndReader() {
  Context app=new Context(), device=new Context(); app.device=device; device.deviceProtected=true;
  Prefs credentialPrefs=new Prefs(), devicePrefs=new Prefs(); app.prefs=credentialPrefs; device.prefs=devicePrefs;
  check(LIQUID_GLASS.equals(GlobalAppearanceStore.getNavigationStyle(app)),"Older installs retain liquid style");
  check(GlobalAppearanceStore.isNavigationHapticsEnabled(app),"Vibration defaults on for old preferences");
  XposedBridge.remote=devicePrefs;
  MiuixGlassNavView normal=new MiuixGlassNavView(); CoolapkLegacyGlassNavView coolapk=new CoolapkLegacyGlassNavView();
  GlobalRenderSettingsReader.apply(app,normal,true);
  check(LIQUID_GLASS.equals(normal.style),"Host defaults before first global save");
  check(normal.haptics,"Host defaults include vibration");
  GlobalAppearanceStore.setNavigationHapticsEnabled(app,false);
  GlobalAppearanceStore.setBackdropCaptureEnabled(app,false);
  GlobalAppearanceStore.setNativeBlurEnabled(app,true);
  GlobalAppearanceStore.setSelectedAccentEnabled(app,true);
  GlobalAppearanceStore.setBottomBarTraditionalChinese(app,true);
  for(String style:new String[]{HYPER_OS,HYPER_OS_FLOATING,LIQUID_GLASS}) {
   long before=GlobalAppearanceStore.getRenderRevision(app);
   app.resolver.onNotify=()->{
    check(style.equals(devicePrefs.getString(GlobalAppearanceStore.KEY_NAVIGATION_STYLE,"")),"Commit precedes notification");
    check(style.equals(credentialPrefs.getString(GlobalAppearanceStore.KEY_NAVIGATION_STYLE,"")),"Both stores precede notification");
   };
   GlobalAppearanceStore.setNavigationStyle(app,style);
   check(GlobalAppearanceStore.getRenderRevision(app)>before,"Revision advances even within one millisecond");
   check(style.equals(GlobalAppearanceStore.getNavigationStyle(app)),"Style survives reopening preferences");
   // Simulate the remote observer in the first 30 seconds of process lifetime.
   GlobalRenderSettingsReader.invalidate();
   long revision=GlobalRenderSettingsReader.apply(app,normal,false);
   GlobalRenderSettingsReader.apply(app,coolapk,false);
   check(revision==GlobalAppearanceStore.getRenderRevision(app),"Host consumes current revision");
   check(style.equals(normal.style) && style.equals(coolapk.style),"Both host renderers receive each style");
   check(!normal.capture && normal.nativeBlur && normal.accent && normal.traditional,"Changing style retains other render options");
   check(!normal.haptics && !coolapk.haptics,"Every style in both renderers observes disabled vibration");
   check(!GlobalAppearanceStore.isNavigationHapticsEnabled(app),"Style and render edits retain vibration choice");
   int reads=XposedBridge.reads;
   GlobalRenderSettingsReader.apply(app,normal,false);
   check(XposedBridge.reads==reads,"Unchanged settings use process cache");
  }
  app.resolver.onNotify=()->{};
  GlobalAppearanceStore.setNavigationStyle(app,HYPER_OS_FLOATING);
  GlobalAppearanceStore.setBackdropCaptureEnabled(app,true);
  GlobalAppearanceStore.saveAndSyncAll(app,GlassSettings.defaults());
  check(HYPER_OS_FLOATING.equals(GlobalAppearanceStore.getNavigationStyle(app)),"Slider/language/boolean saves must preserve style");
  GlobalRenderSettingsReader.apply(app,normal,true);
  check(!normal.haptics,"Slider saves preserve vibration");
  XposedBridge.remote=null;
  GlobalRenderSettingsReader.apply(app,normal,true);
  check(HYPER_OS_FLOATING.equals(normal.style),"Transient remote failure retains last good style");
  check(!normal.haptics,"Remote outage must not re-enable vibration");
  XposedBridge.remote=devicePrefs;
  for(boolean enabled:new boolean[]{true,false,true,false}) {
   app.resolver.onNotify=()->{
    check(devicePrefs.getBoolean(GlobalAppearanceStore.KEY_NAVIGATION_HAPTICS_ENABLED,!enabled)==enabled,"Device vibration committed before notify");
    check(credentialPrefs.getBoolean(GlobalAppearanceStore.KEY_NAVIGATION_HAPTICS_ENABLED,!enabled)==enabled,"Credential vibration committed before notify");
   };
   GlobalAppearanceStore.setNavigationHapticsEnabled(app,enabled);
   GlobalRenderSettingsReader.invalidate();
   GlobalRenderSettingsReader.apply(app,normal,false);
   GlobalRenderSettingsReader.apply(app,coolapk,false);
   check(normal.haptics==enabled && coolapk.haptics==enabled,"Live vibration updates reach both host renderers");
  }
  app.resolver.onNotify=()->{};
  devicePrefs.values.put(GlobalAppearanceStore.KEY_NAVIGATION_HAPTICS_ENABLED,"invalid");
  GlobalRenderSettingsReader.apply(app,normal,true);
  check(normal.haptics && normal.nativeBlur && normal.accent,"Malformed vibration key does not block other render options");
  check(GlobalAppearanceStore.isNavigationHapticsEnabled(app),"Local malformed vibration key safely falls back");
  devicePrefs.values.remove(GlobalAppearanceStore.KEY_NAVIGATION_HAPTICS_ENABLED);
  GlobalRenderSettingsReader.apply(app,coolapk,true);
  check(coolapk.haptics,"Missing vibration key uses default");
  for(Object corrupt:new Object[]{"unknown",123}) {
   devicePrefs.values.put(GlobalAppearanceStore.KEY_NAVIGATION_STYLE,corrupt);
   GlobalRenderSettingsReader.apply(app,normal,true);
   check(LIQUID_GLASS.equals(normal.style),"Invalid preference safely falls back to liquid");
   check(normal.nativeBlur && normal.accent && normal.traditional,"Bad new key cannot block existing switches");
  }
  devicePrefs.values.remove(GlobalAppearanceStore.KEY_NAVIGATION_STYLE);
  GlobalRenderSettingsReader.apply(app,normal,true);
  check(LIQUID_GLASS.equals(normal.style),"Old preference group without new key is supported");
  GlobalAppearanceStore.setNavigationStyle(app,null);
  check(LIQUID_GLASS.equals(GlobalAppearanceStore.getNavigationStyle(app)),"Null style normalizes safely");
  System.out.println("PASS: persisted vibration and styles, both storage areas and host readers, revisions/cache/observer, old settings, corrupt keys and remote failures");
 }
 static void geometryAndActions() {
  for(String style:new String[]{HYPER_OS,HYPER_OS_FLOATING}) {
   for(int count=2;count<=7;count++) {
    for(float width:new float[]{240,360,600,960}) {
     for(boolean post:new boolean[]{false,true}) {
      var row=layout(style,width,width,count,post,-1,false,56,0);
      check(row.barWidthDp>0 && row.rowWidthDp<=width,"Row fits small and large hosts");
      check(row.logicalIndices.length==count+(post?1:0),"Visible tab count retained");
      for(int logical=0;logical<count;logical++) {
       int slot=row.selectedSlot(logical);
       check(slot!=row.actionSlot && row.logicalIndices[slot]==logical,"Tab route survives inserted post slot");
      }
      check(row.contains(row.barLeftDp+row.barWidthDp/2,32),"Visible bar accepts touch");
      check(!row.contains(width/2,90),"Transparent space above bar passes touch through");
      if(HYPER_OS.equals(style)) {
       near(row.barWidthDp,width,"Standard is full width");
       check(row.contains(1,32) && row.contains(width-1,32),"Standard edge tabs remain clickable");
      }
      if(post) {
       check(row.logicalIndices[row.actionSlot]==-1,"Independent post has no page route");
       int previous=row.selectedSlot(0);
       check(resolveDragSlot(row.actionSlot,previous,row.actionSlot,row.logicalIndices.length)==previous,"Dragging to post restores selection");
      }
     }
    }
   }
   var center=layout(style,360,360,5,false,2,false,56,0);
   near(center.actionCenterXDp(),180,"Native center action stays aligned");
   check(center.selectedSlot(2)!=center.actionSlot,"Publish cannot become selected page");
   var circle=layout(style,360,360,5,false,2,true,56,0);
   if(HYPER_OS_FLOATING.equals(style)) {
    check(Arrays.equals(circle.logicalIndices,new int[]{0,1,3,4}),"Separate action preserves native indices");
    check(circle.rowWidthDp<=360 && circle.barLeftDp>=0,"Circle and pill fit together");
    check(circle.contains(circle.actionCenterXDp(),circle.contentCenterYDp),"Separate action accepts touch");
    var hidden=layout(style,360,360,5,false,-1,true,56,0);
    check(!hidden.separateActionEnabled && Arrays.equals(hidden.logicalIndices,new int[]{0,1,3,4}),"Hidden action remains hidden without shifting pages");
   } else {
    check(!circle.separateCircle && circle.actionSlot==2,"Standard style keeps publish in the full-width row");
    near(circle.barWidthDp,360,"Standard stays full width even for circle-capable hosts");
   }
  }
  check(resolveDragSlot(99,0,-1,4)==3 && resolveDragSlot(-5,2,-1,4)==0,"Drag clamps to existing tabs");
  // Regression: 27.09 used legacy per-tab width / 64dp height and sized the standard bar
  // inside a compact liquid-glass panel. Compare full decor and compact panel installations.
  for(float viewport:new float[]{240,360,600,960}) {
   for(boolean circle:new boolean[]{false,true}) {
    float panel=overlayWidthDp(HYPER_OS_FLOATING,viewport,5,false,circle);
    var direct=layout(HYPER_OS_FLOATING,viewport,viewport,5,false,2,circle,56,24);
    var overlay=layout(HYPER_OS_FLOATING,panel,viewport,5,false,2,circle,56,24);
    float desired = (viewport*0.4346f-6.56f)/2f*(circle?4:5)+6.56f;
    near(direct.barWidthDp,Math.min(desired,viewport-24f-(circle?54f:0f)),"Floating width preserves reference spacing within screen margins");
    near(overlay.barWidthDp,direct.barWidthDp,"Compact panel must not multiply the width fraction again");
    near(overlay.barHeightDp,59.04f,"Floating height is the reference 72dp * 0.82");
    near(overlay.bottomPaddingDp,30.56f,"Gesture inset plus reference 6.56dp gap");
    near(overlay.contentCenterYDp,29.52f,"Native action shares the capsule center");
    check(overlay.captureHeightDp>=overlay.barHeightDp+overlay.bottomPaddingDp+16,"Capture covers the whole capsule, system inset and lens margin");
    near((viewport-panel)/2+overlay.actionCenterXDp(),direct.actionCenterXDp(),"Native post stays aligned across window types");
    check(!overlay.contains(-20,30),"Transparent horizontal gutter is outside the hit region");
   }
   near(overlayWidthDp(HYPER_OS,viewport,5,false,true),viewport,"Standard outer window must be full width");
  }
  var two=layout(HYPER_OS_FLOATING,360,360,2,false,-1,false,56,0);
  var four=layout(HYPER_OS_FLOATING,360,360,4,false,-1,false,56,0);
  near(two.barWidthDp,360*0.4346f,"Two reference tabs retain exact original width");
  near((two.barWidthDp-6.56f)/2,(four.barWidthDp-6.56f)/4,"Four tabs preserve reference per-tab spacing");
  for(int count=2;count<=7;count++) for(boolean post:new boolean[]{true,false}) {
   float panel=overlayWidthDp(HYPER_OS_FLOATING,360,count,post,false);
   var decor=layout(HYPER_OS_FLOATING,360,360,count,post,-1,false,56,0);
   var overlay=layout(HYPER_OS_FLOATING,panel,360,count,post,-1,false,56,0);
   near(overlay.barWidthDp,decor.barWidthDp,"Overlay sizing agrees for all tab/post counts");
  }
  var standard=layout(HYPER_OS,360,360,3,false,-1,false,62,24);
  near(standard.barHeightDp,86,"Natural Miuix measurement plus system inset drives native blur");
  near(standard.contentCenterYDp,55,"Standard native action is centered above the system inset");
  near(standard.bottomPaddingDp,0,"Standard background extends to the physical bottom");
  near(FLOATING_INNER_PADDING_DP,3.28f,"Reference inner padding drives native action coordinates");
  System.out.println("PASS: reference dimensions, natural standard height, decor/panel equality, system insets, 2-7 tabs, post/hidden/circle routes and blocked drags");
 }
 public static void main(String[] args) { storeAndReader(); geometryAndActions(); }
}''',
}


def main():
    with TemporaryDirectory(prefix="liquidglass-global-nav-") as directory:
        folder = Path(directory)
        for name, content in STUBS.items():
            path = folder / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        sources = [str(path) for path in folder.rglob("*.java")]
        sources += [str(SOURCE / name) for name in (
            "InjectedNavigationStyle.java", "GlobalAppearanceStore.java", "GlobalRenderSettingsReader.java",
        )]
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", str(folder / "classes"),
                        *sources], check=True)
        subprocess.run(["java", "-cp", str(folder / "classes"),
                        "rongyi.lingbai.GlobalNavigationBehaviorTest"], check=True)


if __name__ == "__main__":
    main()

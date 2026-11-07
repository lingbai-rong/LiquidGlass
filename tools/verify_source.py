#!/usr/bin/env python3
"""Offline source checks, Java parsing and real ModuleLog behavior with Android boundary stubs.

Needs Python 3.10+ and JDK 17+. This does NOT replace a Gradle build or device tests.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/rongyi/lingbai"
RES = ROOT / "app/src/main/res"


def run(*args):
    subprocess.run(args, check=True, cwd=ROOT)


def resources():
    tables = {}
    for path in RES.glob("values*/strings.xml"):
        entries = ET.parse(path).getroot().findall("string")
        values = {entry.attrib["name"]: "".join(entry.itertext()) for entry in entries}
        assert len(entries) == len(values), f"Duplicate resource in {path}"
        tables[path.parent.name] = values
    base = tables["values"]
    for locale, values in tables.items():
        assert not set(values) - set(base), f"Unknown localized keys: {locale}"
        if locale in ("values-en", "values-zh-rTW"):
            assert values.keys() == base.keys(), f"Incomplete locale: {locale}"
        for key, value in values.items():
            pattern = r"%\d+\$[a-zA-Z]"
            assert sorted(re.findall(pattern, value)) == sorted(re.findall(pattern, base[key])), (locale, key)
    for path in RES.rglob("*.xml"):
        ET.parse(path)
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    assert manifest.find("application").attrib["{http://schemas.android.com/apk/res/android}enableOnBackInvokedCallback"] == "true"
    for path in SOURCE.rglob("*.kt"):
        referenced = set(re.findall(r"(?<![\w.])R\.string\.(\w+)", path.read_text()))
        assert not referenced - set(base), (path.name, referenced - set(base))
    template = json.loads((ROOT / "app/src/main/assets/languages/example.json").read_text())
    assert set(template["strings"]) == set(base)
    assert len(json.dumps(template, ensure_ascii=False).encode()) < 128 * 1024
    for path in SOURCE.rglob("*"):
        if path.suffix not in (".java", ".kt") or path.name in ("ModuleLog.java", "XposedBridge.java"):
            continue
        text = path.read_text()
        assert not re.search(r"android\.util\.Log\.[vdiwe]\(", text), f"Ungated log: {path}"
        if "import rongyi.lingbai.liquidtab.hook.PortLog as Log" not in text:
            assert not re.search(r"(?<![\w.])Log\.[vdiwe]\(", text), f"Ungated log: {path}"
    print(f"PASS: {len(base)} resource keys, localized format arguments, XML, language template and logging call sites", flush=True)


def java_checks():
    stubs = {
        "android/content/SharedPreferences.java": '''package android.content;
public interface SharedPreferences {
 boolean getBoolean(String key, boolean fallback);
 void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
 void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
 Editor edit();
 interface Editor { Editor putBoolean(String key, boolean value); boolean commit(); }
 interface OnSharedPreferenceChangeListener { void onSharedPreferenceChanged(SharedPreferences preferences, String key); }
}''',
        "android/content/BroadcastReceiver.java": '''package android.content;
public abstract class BroadcastReceiver { public abstract void onReceive(Context context, Intent intent); }''',
        "android/content/IntentFilter.java": '''package android.content;
public class IntentFilter { public IntentFilter(String action) {} }''',
        "android/content/Intent.java": '''package android.content;
public class Intent {
 private final String action; public String packageName; private boolean enabled;
 public Intent(String value) { action=value; }
 public String getAction() { return action; }
 public Intent setPackage(String value) { packageName=value; return this; }
 public Intent putExtra(String key, boolean value) { enabled=value; return this; }
 public boolean getBooleanExtra(String key, boolean fallback) { return enabled; }
}''',
        "android/content/Context.java": '''package android.content;
public class Context {
 public static final int MODE_PRIVATE=0, RECEIVER_NOT_EXPORTED=4;
 public SharedPreferences prefs; public BroadcastReceiver receiver; public Intent broadcast; public int flags;
 public Context createDeviceProtectedStorageContext() { return this; }
 public Context getApplicationContext() { return this; }
 public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
 public String getPackageName() { return "rongyi.lingbai"; }
 public Intent registerReceiver(BroadcastReceiver value, IntentFilter filter, int mode) { receiver=value; flags=mode; return null; }
 public void sendBroadcast(Intent intent) { broadcast=intent; if(receiver!=null) receiver.onReceive(this,intent); }
}''',
        "android/util/Log.java": '''package android.util;
public class Log {
 public static int calls=0;
 public static int i(String t,String m) { return ++calls; }
 public static int w(String t,String m) { return ++calls; }
 public static int w(String t,String m,Throwable e) { return ++calls; }
 public static int e(String t,String m,Throwable e) { return ++calls; }
}''',
        "rongyi/lingbai/HookPreferenceTransport.java": '''package rongyi.lingbai;
import android.content.*;
final class HookPreferenceTransport {
 static SharedPreferences open(Context context, String name) { return context.prefs; }
}''',
        "rongyi/lingbai/XposedBridge.java": '''package rongyi.lingbai;
import android.content.SharedPreferences;
public class XposedBridge {
 static SharedPreferences remote;
 static SharedPreferences getRemotePreferences(String name) {
  if(remote==null) throw new IllegalStateException("Service unavailable");
  return remote;
 }
}''',
        "rongyi/lingbai/ModuleLogBehaviorTest.java": '''package rongyi.lingbai;
import android.content.*;
import android.util.Log;
import java.lang.reflect.Field;
import java.util.*;
public class ModuleLogBehaviorTest {
 static void check(boolean condition, String description) { if(!condition) throw new AssertionError(description); }
 static void reset() throws Exception {
  for(String name:new String[]{"preferences","receiver"}) { Field f=ModuleLog.class.getDeclaredField(name); f.setAccessible(true); f.set(null,null); }
  Field f=ModuleLog.class.getDeclaredField("enabled"); f.setAccessible(true); f.setBoolean(null,false);
  XposedBridge.remote=null; Log.calls=0;
 }
 static void output() { ModuleLog.i("test","info"); ModuleLog.w("test","warning"); ModuleLog.w("test","warning",new Exception()); ModuleLog.e("test","error",new Exception()); }
 static class Prefs implements SharedPreferences {
  boolean present, value, readable=true, commitSucceeds=true, registrationSucceeds=true;
  Set<OnSharedPreferenceChangeListener> listeners=new HashSet<>();
  public boolean getBoolean(String key, boolean fallback) { if(!readable) throw new ClassCastException(); return present?value:fallback; }
  public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { if(!registrationSucceeds) throw new IllegalStateException(); listeners.add(l); }
  public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.remove(l); }
  void change(boolean next) { present=true; value=next; signal("enabled"); }
  void signal(String key) { for(OnSharedPreferenceChangeListener l:new ArrayList<>(listeners)) l.onSharedPreferenceChanged(this,key); }
  public Editor edit() { return new Editor() { boolean next; public Editor putBoolean(String key,boolean v) { next=v;return this; } public boolean commit() { if(!commitSucceeds)return false;change(next);return true; } }; }
 }
 public static void main(String[] args) throws Exception {
  reset(); output(); check(Log.calls==0,"Must be silent before initialization");
  Context app=new Context(); Prefs local=new Prefs(); app.prefs=local;
  ModuleLog.initialize(app); output(); check(Log.calls==0,"Fresh install must be silent, including errors");
  ModuleLog.initialize(app); check(local.listeners.size()==1,"Repeated initialization must preserve the listener");
  check(ModuleLog.setEnabled(app,true),"Enable persists"); output(); check(Log.calls==4,"All explicit levels become available");
  check(app.flags==Context.RECEIVER_NOT_EXPORTED,"Broadcast must be app-private");
  check("rongyi.lingbai".equals(app.broadcast.packageName),"Broadcast must target the module package");
  check(ModuleLog.setEnabled(app,false),"Disable persists");output();check(Log.calls==4,"Disabling stops all explicit levels");
  local.commitSucceeds=false; check(!ModuleLog.setEnabled(app,true),"Failed persistence is reported");check(!ModuleLog.isEnabled(),"Failed write must not opt in");
  app.receiver.onReceive(app,new Intent("rongyi.lingbai.LOGGING_CHANGED").putExtra("enabled",true));
  check(ModuleLog.isEnabled(),"Running service process receives opt-in");
  app.receiver.onReceive(app,new Intent("rongyi.lingbai.LOGGING_CHANGED").putExtra("enabled",false));
  check(!ModuleLog.isEnabled(),"Running service process receives opt-out");
  reset(); ModuleLog.attachRemote();output();check(Log.calls==0,"Unavailable remote preferences fail closed");
  Prefs remote=new Prefs();remote.change(true);XposedBridge.remote=remote;
  ModuleLog.attachRemote();output();check(Log.calls==4,"Remote attach retries after service unavailability");
  remote.change(false);output();check(Log.calls==4,"Existing host observes remote opt-out");
  remote.change(true);check(ModuleLog.isEnabled(),"Existing host observes remote opt-in");
  remote.present=false;remote.signal(null);check(!ModuleLog.isEnabled(),"Cleared remote group defaults off");
  remote.change(true);remote.readable=false;remote.signal("enabled");check(!ModuleLog.isEnabled(),"Malformed preferences fail closed");
  reset();remote=new Prefs();remote.change(true);remote.registrationSucceeds=false;XposedBridge.remote=remote;
  ModuleLog.attachRemote();check(!ModuleLog.isEnabled(),"Listener registration failure stays silent");
  remote.registrationSucceeds=true;ModuleLog.attachRemote();check(ModuleLog.isEnabled(),"Listener registration failure can retry");
  System.out.println("PASS: ModuleLog default-off, all levels, persistence failure, private broadcast, remote updates, clear, malformed settings and retries");
 }
}''',
        "JavaSyntaxCheck.java": '''import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.nio.file.*;
import java.util.*;
public class JavaSyntaxCheck {
 public static void main(String[] args) throws Exception {
  var compiler=ToolProvider.getSystemJavaCompiler();
  var diagnostics=new DiagnosticCollector<JavaFileObject>();
  try(var manager=compiler.getStandardFileManager(diagnostics,null,null);var paths=Files.walk(Path.of(args[0]))) {
   var files=paths.filter(p->p.toString().endsWith(".java")).map(Path::toFile).toList();
   var task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none"),null,manager.getJavaFileObjectsFromFiles(files));
   task.parse();
   for(var diagnostic:diagnostics.getDiagnostics()) if(diagnostic.getKind()==Diagnostic.Kind.ERROR) throw new AssertionError(diagnostic.toString());
   System.out.println("PASS: Java syntax parsed for "+files.size()+" source files (no Android type checking)");
  }
 }
}''',
    }
    with TemporaryDirectory(prefix="liquidglass-verify-") as tmp:
        folder = Path(tmp)
        for name, source in stubs.items():
            target = folder / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(source)
        files = [str(folder / name) for name in stubs]
        run("java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", str(folder / "classes"),
            *files, str(SOURCE / "ModuleLog.java"))
        run("java", "-cp", str(folder / "classes"), "rongyi.lingbai.ModuleLogBehaviorTest")
        run("java", "-cp", str(folder / "classes"), "JavaSyntaxCheck", str(ROOT / "app/src/main/java"))


if __name__ == "__main__":
    resources()
    java_checks()
    print("NOTE: Kotlin/Compose compilation, Gradle unit tests, APK packaging and on-device behavior still require the Android toolchain.")

# LiquidGlass release R8 rules
#
# The project is an LSPosed/Xposed module. Most implementation classes may be
# optimized, renamed and repackaged. Only runtime entry points that are loaded
# from names stored outside the DEX must retain stable names.

# Official API 102 entry rule. R8 may rename the class and rewrites java_init.list to match.
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-adaptresourcefilecontents META-INF/xposed/java_init.list

# API and annotations are supplied by the API 102 framework at runtime.
-dontwarn io.github.libxposed.annotation.**

# The service AIDL descriptor strings are part of the Binder wire protocol shared with the
# framework. Renaming or adapting them makes every service call fail before preferences publish.
-keep class io.github.libxposed.service.** { *; }
-keep interface io.github.libxposed.service.** { *; }

# Preserve metadata needed by Kotlin, Compose, generic signatures and anonymous
# callback classes while still allowing class/member renaming.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# Make the remaining implementation harder to map back to the source layout.
# R8 safely rewrites direct references and opens access where repackaging requires it.
-allowaccessmodification
-repackageclasses 'l'
-adaptresourcefilecontents META-INF/services/**

# Remove original source file names from release stack traces. Keep mapping.txt
# from every release build so crashes can still be retraced by the developer.
-renamesourcefileattribute SourceFile

# NativeCore is resolved by JNI_OnLoad/RegisterNatives.
-keep class rongyi.lingbai.NativeCore { *; }

# The supplied WAA payload resolves this exact class and its native method names during
# JNI_OnLoad. There is intentionally no Java call site for R8 to discover.
-keep class com.waa.bridge.NativeBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# The imported AM++ entry, host profile, embedded settings and glass renderer contain
# reflective hooks and source-owned settings. Keep their identities intact after R8.
-keep class dev.amenhancer.** { *; }
-keep class io.github.proify.lyricon.** { *; }
-keep class com.juren233.hyperlyricsenhanced.** { *; }
# Activity-scoped editor is created by SavedStateViewModelFactory through reflection.
-keepclassmembers class rongyi.lingbai.AppleMusicSettingsModel {
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}

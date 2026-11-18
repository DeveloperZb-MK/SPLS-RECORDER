# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel{*;}
-keepnames class io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel.**


-repackageclasses
-allowaccessmodification
-overloadaggressively

# ---- Added when re-enabling R8/ProGuard (version 1.0.3) ----

# Keep data classes sent to/from the backend and GitHub API (Gson reads them by field name)
-keep class io.github.jqssun.gpssetter.access.** { *; }
-keep class io.github.jqssun.gpssetter.update.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }
-keep class * implements java.io.Serializable { *; }

# Retrofit / OkHttp / Okio: kept in full. These are well-known open-source libraries with
# nothing secret in them, and R8 stripping or renaming their internals (reflection-based proxy
# creation, suspend-function continuations, TLS platform detection) is what broke network calls
# such as /api/verify in an earlier build. Keeping them whole costs a little size, not security.
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-keep interface okio.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault, Exceptions
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Kotlin coroutines: suspend functions (used by every Retrofit call in AccessApi/GitHubService)
# rely on this class's generic signature surviving R8.
-keep class kotlinx.coroutines.** { *; }
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Gson: keep its own classes, plus the generic type info it needs to read list/response types
-keepattributes *Annotation*
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Parcelize
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# WorkManager workers are created by reflection: keep the class and its (Context, WorkerParameters) constructor
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room entities/DAOs
-keep class io.github.jqssun.gpssetter.room.** { *; }

# Hilt / Dagger generated code
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <methods>;
}

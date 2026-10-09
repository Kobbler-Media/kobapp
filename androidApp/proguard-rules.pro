# ---- Manifest-reachable entry points ------------------------------------------------
# MainActivity and PlaybackService are <activity>/<service> elements, so AAPT already
# emits keeps for them; restated because the TvActivity activity-alias resolves to
# MainActivity and an alias target is easy to lose track of.
-keep class dev.kobbler.app.MainActivity { *; }
-keep class dev.kobbler.app.PlaybackService { *; }

# CastOptionsProvider is named only in an <meta-data android:value> string. AAPT does not
# generate keeps for meta-data values, so nothing else references this class and R8 will
# remove it — the Cast framework then throws on first session.
-keep class dev.kobbler.app.CastOptionsProvider { *; }
-keep class * implements com.google.android.gms.cast.framework.OptionsProvider { *; }

# ---- libvlc -------------------------------------------------------------------------
# libvlc-all ships no consumer rules and its native layer resolves Java classes, methods
# and fields by name through JNI (VLCObject.mInstance, MediaPlayer$Event, the IVLCVout
# callbacks, the custom views inside vlc_video_layout.xml). Keeping the whole package is
# ~136 classes of thin JNI shim; anything narrower fails at playback, not at build.
-keep class org.videolan.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---- ktor ---------------------------------------------------------------------------
# App.kt builds HttpClient() with no explicit engine, so ktor discovers OkHttp through
# META-INF/services. The service file survives packaging but the class it names is
# otherwise unreferenced and gets shrunk away.
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }

# ---- kotlinx.serialization ----------------------------------------------------------
# Redundant with the rules kotlinx-serialization-core 1.11 ships, kept explicit because
# ~100 DTOs in KobblerApi.kt deserialize through generated $$serializer lookups and a
# silent regression here surfaces as an empty library, not a crash. Verbatim upstream.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# R8 full mode strips annotations from classes, which breaks the sealed-vs-polymorphic
# decision inside serializer<T>().
-if @kotlinx.serialization.Serializable class **
-keep,allowshrinking,allowoptimization,allowobfuscation,allowaccessmodification class <1>

# ---- media3 -------------------------------------------------------------------------
# media3-session ships no consumer rules. The Player implementations are referenced
# statically, but the session package is the Binder surface an external controller
# (Assistant, Auto, the notification bridge) connects across, so it is kept whole.
-keep class androidx.media3.session.** { *; }
-dontwarn androidx.media3.**

# ---- Compose ------------------------------------------------------------------------
# Nothing in this app reaches Compose internals reflectively and the Compose artifacts
# ship their own rules; no keeps are needed here.

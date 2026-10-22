# Shrink only: the win is dropping the unused bulk of material-icons-extended and friends.
# Obfuscation and optimization buy little on desktop and make stack traces useless.
-dontobfuscate
-dontoptimize
-dontwarn **

# The shrinker drops an interface a class lists redundantly (JobSupport: Job, ChildJob), but
# Kotlin's `super<Job>.cancel()` compiles to invokespecial on it, which the verifier rejects
# unless it is a direct superinterface.
-keep interface **

# JNA/vlcj bind native structs and callbacks by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uk.co.caprica.** { *; }
-keep class dev.kobbler.kobplayer.** { *; }

# dbus-java exports and proxies our interfaces reflectively.
-keep class org.freedesktop.dbus.** { *; }
-keep class dev.kobbler.app.platform.linux.** { *; }

# kotlinx.serialization looks up generated serializers by reflection for some call shapes.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class **$$serializer { *; }

# ServiceLoader-discovered implementations (ktor engines, coil fetchers/decoders, slf4j).
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }
-keep class io.ktor.** { *; }
-keep class org.slf4j.** { *; }
-keep class kotlinx.coroutines.swing.** { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }

# skiko/Compose resources and AWT integration.
-keep class org.jetbrains.skiko.** { *; }
-keep class org.jetbrains.skia.** { *; }

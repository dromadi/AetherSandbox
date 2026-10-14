# Guests link against the API at runtime: keep every name.
-keep class com.aether.sandbox.api.** { *; }
-keep interface com.aether.sandbox.api.** { *; }
-keep class com.aether.sandbox.core.NativeDiagnostics { *; }

# Android calls this non-SDK method virtually; it is absent from public SDK stubs.
-keep class com.aether.sandbox.core.GuestActivityRuntime { *; }

# JNI and reflection entry points for the opt-in Camera2 process hook.
-keep class de.robv.android.xposed.** { *; }
-keep class com.aether.sandbox.media.Camera2VideoBridge$* { *; }

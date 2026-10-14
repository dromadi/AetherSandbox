# Guests link against the API at runtime: keep every name.
-keep class com.aether.sandbox.api.** { *; }
-keep interface com.aether.sandbox.api.** { *; }
-keep class com.aether.sandbox.core.NativeDiagnostics { *; }

# Android calls this non-SDK method virtually; it is absent from public SDK stubs.
-keep class com.aether.sandbox.core.GuestActivityRuntime { *; }

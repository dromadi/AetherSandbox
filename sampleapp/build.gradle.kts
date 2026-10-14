plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.aether.sample"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig { applicationId = "com.aether.sample"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1.0"; ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

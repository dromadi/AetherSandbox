plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.aether.sample"
    compileSdk = 36
    defaultConfig { applicationId = "com.aether.sample"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

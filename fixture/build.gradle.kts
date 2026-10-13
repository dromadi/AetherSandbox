plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.aether.fixture"
    compileSdk = 36
    defaultConfig { applicationId = "com.aether.fixture"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
// Host supplies the API interface at runtime. Do not package a second copy in the guest.
dependencies { compileOnly(project(":guest-api")) }

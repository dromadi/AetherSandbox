plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.aether.sandbox"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "com.aether.sandbox"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.0-alpha.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++20" } }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    lint { abortOnError = true }
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/fixtureAssets"))
}
val prepareFixture by tasks.registering(Copy::class) {
    dependsOn(":fixture:assembleDebug", ":sampleapp:assembleDebug")
    from(project(":fixture").layout.buildDirectory.file("outputs/apk/debug/fixture-debug.apk")) { rename { "fixture.apk" } }
    from(project(":sampleapp").layout.buildDirectory.file("outputs/apk/debug/sampleapp-debug.apk")) { rename { "sampleapp.apk" } }
    into(layout.buildDirectory.dir("generated/fixtureAssets"))
}
tasks.matching { it.name in setOf(
    "mergeDebugAndroidTestAssets", "lintAnalyzeDebugAndroidTest", "generateDebugAndroidTestLintModel",
) }.configureEach { dependsOn(prepareFixture) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":domain"))
    implementation(project(":guest-api"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.core)
    implementation(libs.coroutines)
    testImplementation(libs.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext)
    androidTestImplementation(libs.test.uiautomator)
}

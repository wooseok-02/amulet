import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val localSettings = Properties().apply {
    val file = rootProject.file("local.properties")
    if(file.exists())file.inputStream().use { load(it) }
}
val shareHost = providers.gradleProperty("bukku.shareHost").orElse(localSettings.getProperty("bukku.shareHost", "amulet-api.onrender.com")).get()
val kakaoKey = localSettings.getProperty("bukku.kakaoNativeKey", "")
val releaseSigning = Properties().apply {
    val file = file(providers.environmentVariable("BUKKU_SIGNING_PROPERTIES").orElse(rootProject.file("signing.properties").absolutePath).get())
    if(file.exists())file.inputStream().use { load(it) }
}
android {
    namespace = "com.example.bukku"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.bukku"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        manifestPlaceholders["shareHost"] = shareHost
        manifestPlaceholders["kakaoScheme"] = if(kakaoKey.isBlank())"kakao-bukku-unconfigured" else "kakao$kakaoKey"
        buildConfigField("String", "SHARE_HOST", "\"$shareHost\"")
        buildConfigField("String", "KAKAO_NATIVE_KEY", "\"$kakaoKey\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        if(releaseSigning.isNotEmpty())create("distribution") {
            storeFile=file(releaseSigning.getProperty("storeFile"))
            storePassword=releaseSigning.getProperty("storePassword")
            keyAlias=releaseSigning.getProperty("keyAlias")
            keyPassword=releaseSigning.getProperty("keyPassword")
        }
    }
    buildTypes { getByName("release") {
        isMinifyEnabled=false
        if(releaseSigning.isNotEmpty())signingConfig=signingConfigs.getByName("distribution")
    } }
}
dependencies {
    implementation("com.kakao.sdk:v2-share:2.26.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

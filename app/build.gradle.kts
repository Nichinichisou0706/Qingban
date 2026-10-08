import java.util.Properties

plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
val releaseSecrets=Properties().apply { rootProject.file(".signing/release.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
android {
    namespace = "io.github.ming.qingban"
    compileSdk = 35
    buildFeatures { compose = true }
    defaultConfig {
        applicationId = "io.github.ming.qingban"
        minSdk = 26; targetSdk = 35
        versionCode = 12; versionName = "0.4.5"
    }
    signingConfigs {
        if(releaseSecrets.isNotEmpty()) create("localRelease") {
            storeFile=rootProject.file(".signing/qingban-release.jks")
            storePassword=releaseSecrets.getProperty("storePassword")
            keyAlias=releaseSecrets.getProperty("keyAlias")
            keyPassword=releaseSecrets.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled=true; isShrinkResources=true
            if(releaseSecrets.isNotEmpty()) signingConfig=signingConfigs.getByName("localRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources=true }
}
dependencies {
    implementation(project(":core:domain")); implementation(project(":core:agent"))
    implementation(project(":data:local")); implementation(project(":integration:model"))
    implementation(project(":platform:android")); implementation(project(":feature:ui"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.room:room-ktx:2.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

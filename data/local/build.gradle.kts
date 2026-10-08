plugins { id("com.android.library"); kotlin("android"); id("com.google.devtools.ksp") }
android {
    namespace = "io.github.ming.qingban.data"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources=true }
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
}
dependencies {
    api(project(":core:domain"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

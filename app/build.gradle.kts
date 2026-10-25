plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "de.tridash"
    compileSdk = 34
    defaultConfig { applicationId = "de.tridash"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
}

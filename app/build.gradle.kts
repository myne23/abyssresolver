plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.myne23.abyssresolver"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mmilew.kinoresolvers"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

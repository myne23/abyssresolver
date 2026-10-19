plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing values are supplied locally through ~/.gradle/gradle.properties.
// Never commit the keystore or signing passwords to this repository.
val releaseStoreFile = providers.gradleProperty("KINO_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("KINO_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("KINO_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("KINO_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.myne23.abyssresolver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mmilew.kinoresolvers"
        minSdk = 24
        targetSdk = 35
        versionCode = 16
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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

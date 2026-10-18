# Kino Resolvers

Android companion app for Kino's external-resolver protocol.

- Android package: `com.mmilew.kinoresolvers`
- Implemented protocol: `abyss` (experimental)
- Planned resolver module: Mega

This repository is a development build, not a release. Do not treat the resolver as working until unit tests, Android build, Kino integration, and real playback have all been checked.

## Build

Open the project in Android Studio with Android SDK 36 and JDK 17. The project uses Android Gradle Plugin 8.10, Kotlin 2.2.21, and Gradle 8.11.1. A Gradle wrapper is not included; use a local Gradle 8.11.1 installation for command-line builds.

## How it is intended to work

1. Kino binds the Android external-resolver service and passes an opaque `abyss:` reference.
2. The app requests the public Abyss player page and parses its source payload.
3. It decrypts the media metadata and prepares a local loopback HTTP endpoint.
4. Kino's player requests the local endpoint; the app fetches and caches upstream byte ranges, decrypting the MP4 prefix when required.

## Current status

The package and app branding have been generalized so additional resolver modules can be added. The Abyss algorithm and MP4 prefix transformation remain provisional until checked against real videos. CI results and real-device playback must be confirmed before an APK is shared as a working build.

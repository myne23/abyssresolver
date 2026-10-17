# Abyss Resolver for Kino

Android companion app for the Kino external-resolver protocol. Package: `com.myne23.abyssresolver`; protocol: `abyss`.

This repository is being developed as an independent companion for the AnimeYT Kino plugin. It is not yet a release build. Do not install or publish an APK until the resolver is tested against real playback and the Kino SDK contract.

## Build
Open this project in Android Studio with Android SDK 36 and JDK 17. The project uses Android Gradle Plugin 8.10 and Kotlin 2.2.21. A Gradle wrapper is not included yet; use a local Gradle 8.11.1 installation for command-line builds.

## What it does
- Kino binds the Android external resolver service and passes an opaque `abyss:` ref.
- The app retrieves the public Abyss player page, parses its source payload, decrypts the media metadata, and serves the video through a loopback HTTP server.
- The loopback server is intended to support GET, HEAD, Range, CORS, and `X-Resolver-Error`.
- The MP4 prefix transformation and real-player interoperability must be verified before a release.

## Status
Work in progress. No APK release exists yet. Never claim the app is working until unit tests, SDK validation, and real playback on a device have passed.

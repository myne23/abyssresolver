# Abyss Resolver for Kino

Android companion app for the Kino external-resolver protocol. Package: `com.myne23.abyssresolver`; supported protocols: `abyss`, `mega`.

This repository is being developed as an independent companion for the AnimeYT Kino plugin. It is not yet a release build. Do not install or publish an APK until the resolver is tested against real playback and the Kino SDK contract.

## Build
Open this project in Android Studio with Android SDK 36 and JDK 17. The project uses Android Gradle Plugin 8.10 and Kotlin 2.2.21. A Gradle wrapper is not included yet; use a local Gradle 8.11.1 installation for command-line builds.

## Protocols
- `abyss`: retrieves the Abyss player page, parses its source payload, decrypts the media metadata, and serves the video through the existing loopback HTTP server.
- `mega`: resolves public MEGA `/file/` and `/embed/` links using the fragment key and MEGA public-node API, then range-fetches and AES-CTR decrypts the file through a separate loopback HTTP server.
- Both local servers are designed to support GET, HEAD, byte ranges, and CORS. Real-player interoperability still needs device validation.

## Status
Work in progress. No APK release exists yet. MEGA support has not yet been validated against real files or a device build. Never claim the app is working until unit tests, SDK validation, and real playback on a device have passed.

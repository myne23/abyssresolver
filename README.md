# Kino Resolvers

Android companion app for the Kino external-resolver protocol.

- **Visible app name:** Kino Resolvers
- **Application ID (must not change):** `com.mmilew.kinoresolvers`
- **Kotlin namespace:** `com.myne23.abyssresolver`
- **Supported protocols:** `abyss`, `mega`

This single app is intended to serve plugins that return their own `external.protocol` and `external.ref`. The Android service advertises both supported protocols and dispatches each to its own resolver implementation.

## Protocols

- `abyss`: resolves Abyss player sources and serves video through a loopback HTTP server.
- `mega`: resolves public MEGA `/file/` and `/embed/` links using the fragment key and MEGA public-node API, then range-fetches and AES-CTR decrypts the file through a separate loopback HTTP server.

Both local servers are designed to support GET, HEAD, byte ranges, and CORS. Unit/CI results and real-device playback are separate checks; do not assume a release is validated until the signed APK has been installed and tested with Kino.

## Build

Requirements: JDK 17, Android SDK 36, and Gradle 8.11.1. A Gradle wrapper is not included yet, so use Android Studio or an installed Gradle 8.11.1 for command-line builds.

Run tests and build a debug APK:

```bash
gradle testDebugUnitTest assembleDebug
```

## Local release signing

The release build uses a local `keystore.properties` file if it exists and contains all required values. Never commit this file or your keystore.

1. Add a signing keystore to a secure location outside this repository, with a backup.
2. Create `keystore.properties` in the repository root, using absolute paths:

```properties
storeFile=/absolute/path/to/kinoresolvers.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=kinoresolvers
keyPassword=YOUR_KEY_PASSWORD
```

3. Build the release APK:

```bash
gradle :app:assembleRelease
```

The output is `app/build/outputs/apk/release/app-release.apk`. If no signing properties are present, Gradle can still build a release variant, but it will not be signed with your release key. Verify the signature before distributing it. Do not publish an unsigned APK.

The project ignores `*.jks`, `*.keystore`, and `keystore.properties` to help prevent accidental credential commits. Keep a secure backup of the signing key; future updates must use the same key.

## Release status

Release preparation is in progress. No public APK release is available yet. The initial release is configured as version name `1.0.0`, version code `5`, with minification disabled. Before publishing, verify the signed APK, compute its SHA-256, and test installation and playback on a real device with Kino for both protocols.

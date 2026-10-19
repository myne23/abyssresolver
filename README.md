# Kino Resolvers

Android companion app for Kino's external-resolver protocol.

- Android package: `com.mmilew.kinoresolvers`
- Kotlin namespace: `com.myne23.abyssresolver`
- Version: `1.0.1` (version code `17`)
- Implemented protocols: `abyss`, `abyss2` (permission-refresh alias), and `mega`

## Build and validation

The project uses Android SDK 36, Android Gradle Plugin 8.10.0, and Gradle 8.11.1. CI runs unit tests and builds debug and release APKs. The CI release artifact is unsigned by design; sign the release locally before distributing it.

A successful CI build confirms compilation and unit tests, not successful playback in every Kino player. Test both Abyss and MEGA with real links on a device before treating the build as stable.

## Local release signing

Keep the private keystore outside the repository. Add these properties to `~/.gradle/gradle.properties`:

```properties
KINO_RELEASE_STORE_FILE=/absolute/path/to/kinoresolvers.jks
KINO_RELEASE_STORE_PASSWORD=your-keystore-password
KINO_RELEASE_KEY_ALIAS=kinoresolvers
KINO_RELEASE_KEY_PASSWORD=your-key-password
```

Use the real path and values only on your machine. Never commit this file or the keystore. With all four properties present, `gradle assembleRelease` signs the APK locally.

## Protocols

- `abyss` and `abyss2`: resolve the player payload and serve media through a loopback HTTP endpoint. `abyss2` is a compatibility alias intended to obtain a fresh Kino external-resolver permission entry.
- `mega`: resolves public MEGA links and decrypts/range-serves media through its own loopback HTTP endpoint.

Both protocols require real-device playback checks; CI success alone is not proof of playback compatibility.

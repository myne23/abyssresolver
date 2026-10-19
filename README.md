# Kino Resolvers

Android companion app for Kino's external-resolver protocol.

- Android package: `com.mmilew.kinoresolvers`
- App version: `1.0.0` (version code `15`)
- Implemented protocol: `abyss` (experimental)
- Planned resolver module: Mega

The package and app branding are prepared for the Kino Resolvers companion app. Do not treat the resolver as stable until unit tests, Android build, Kino integration, and real playback have all been checked.

## Build

Open the project in Android Studio with Android SDK 36 and JDK 17. The project uses Android Gradle Plugin 8.10, Kotlin 2.2.21, and Gradle 8.11.1. A Gradle wrapper is not included; use a local Gradle 8.11.1 installation for command-line builds.

CI runs unit tests and builds both a debug APK and an unsigned release APK. The release APK produced by CI is intentionally unsigned; it is not suitable for installing as a stable release until it is signed with the private project keystore.

## Local release signing

Keep the private keystore outside the repository. Add these properties to your user-level Gradle properties file at `~/.gradle/gradle.properties` (create the directory/file if needed):

```properties
KINO_RELEASE_STORE_FILE=/absolute/path/to/kinoresolvers.jks
KINO_RELEASE_STORE_PASSWORD=your-keystore-password
KINO_RELEASE_KEY_ALIAS=kinoresolvers
KINO_RELEASE_KEY_PASSWORD=your-key-password
```

Replace the example path and values locally. Never commit this file, paste its contents into issues or chat, or upload the keystore to GitHub. Then run `gradle assembleRelease`; with all four properties present, Gradle signs the release APK using that key.

## How it is intended to work

1. Kino binds the Android external-resolver service and passes an opaque `abyss:` reference.
2. The app requests the public Abyss player page and parses its source payload.
3. It decrypts the media metadata and prepares a local loopback HTTP endpoint.
4. Kino's player requests the local endpoint; the app fetches and caches upstream byte ranges, decrypting the MP4 prefix when required.

The Abyss algorithm and MP4 prefix transformation remain provisional until checked against real videos. CI success does not replace real-device playback testing.

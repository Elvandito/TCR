# Genshin Data Compressor

Android utility for Genshin Impact data. Kotlin + Jetpack Compose, single `app` module,
minSdk 26 / targetSdk 34.

## Features

- **Storage**: scans the selected package's `files` folder through root (`su -c`) or a
  persistable SAF tree. Top-level folders can be zipped at Store/Fast/Max levels with
  progress, restored from a zip with path-traversal rejection, and deleted after
  confirmation. Root restores are `chown`ed and `restorecon`ed.
- **Data**: per-app mobile/Wi-Fi usage for today, 7 days and 30 days via
  `NetworkStatsManager` (requires Usage Access). A local `VpnService` can block only the
  selected game package on mobile data while showing the required persistent
  notification; it auto-stops when Wi-Fi becomes available. It never reads, inspects or
  forwards packets.
- **Unity**: parses UnityFS v6–v8 headers and block tables, reports None/LZMA/LZ4/LZ4HC
  with compressed/uncompressed sizes, extracts the concatenated raw payload, and repacks
  to None/LZ4/LZMA. Repacked output is validated before it is published. `.blk`, `.pck`,
  `.usm`, encrypted bundles, v9+ and bad signatures are rejected with reasons.

Game packages offered: `com.miHoYo.GenshinImpact`, `com.miHoYo.Yuanshen`,
`com.miHoYo.ys.bilibili`.

## Build and install in GitHub Actions

The workflow `.github/workflows/build.yml` does the full job on GitHub-hosted runners:

1. installs JDK 17, Android SDK platform/build-tools 34 and Gradle 8.7;
2. builds the debug APK and packages the project source;
3. uploads both artifacts;
4. boots an Android 34 emulator, installs the APK, launches `app.gic/.MainActivity`,
   verifies the process and uploads a screenshot.

Open the repository's **Actions** tab, choose `build-and-install`, and download
`GenshinCompressor-debug-apk` from the completed run. Installing on a physical phone is
not possible from a hosted runner; use the downloaded APK with `adb install -r` or a local
file manager.

## Local build (optional)

```bash
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Safety and limitations

Genshin's assets are already compressed and partly encrypted. Re-zipping usually saves
little space, and deleting game data forces the game to re-download it. UnityFS extraction
produces the raw concatenated block payload, not individually decoded Unity assets.
Encrypted/custom containers (`.blk`) are not unlocked. The mobile-data guard is
device-local and does not change server-side usage accounting.

# Genshin Data Compressor

An Android app for measuring and reducing the installed Genshin Impact data footprint,
with explicit opt-in trade-offs and mandatory backups. Kotlin + Jetpack Compose
(Material 3), one `app` module, minSdk 26, targetSdk 34.

**No 40% number is promised.** The app measures a sample of the real install with
zstd-19 and xz, shows the projection, and reports only actual before/after sizes after a
change. If the measured steps cannot reach 40%, it shows the gap and why.

## Tabs

### Optimize

1. **Scan** groups files by top-level and second-level folder and shows real sizes.
   Category labels are *name hints* derived from the current scan (cache/log/temp/crash,
   audio/voice/video/media); they are not asserted facts about Genshin's layout.
2. **Estimator (read-only, default)** samples up to 64 MB or 64 files per top-level group
   with zstd-19 and xz. It reports the measured compressed sizes, projected group savings,
   a total projection as a percentage of the scanned install, and a 40% target gauge.
3. **Safe cleanup** lists name-matched cache/log/temp/crash/dump groups. Nothing is
   preselected. It runs only after confirmation.
4. **Opt-in trade-offs** lists name-matched audio/voice/video groups. Nothing is
   preselected. The confirmation states the trade-off (possible voice-over/cutscene loss
   and a possible in-game re-download).
5. **Lossless recompression** checks `/proc/filesystems` in root mode for erofs, squashfs
   or f2fs and lists sampled groups above 10% gain. This build does **not** rewrite files:
   standard Android exposes no verified per-file in-place compression API, and replacing
   bytes with zstd/xz data would break game reads. The step is reported as skipped.
6. **Final report** shows before -> after, actual reduction, per-step savings, removed
   opt-in items, the remaining gap, and whether all remaining opt-in candidates could
   close it. Estimator projections are never counted as achieved savings.

Every delete is preceded by a user-chosen **zip backup** (Store 0 / Fast 1 / Max 9).
Backup and restore use SAF, so they also work outside the root game directory. Restore
rejects absolute paths, backslashes and `..`, then in root mode fixes ownership with
`chown -R` and SELinux labels with `restorecon -R`.

Access modes share one `Src` interface:
`list`, `read`, `write`, `delete(top)`, `deletePath`, `done`.

- **RootSrc** uses `su -c` with single-quoted paths for find/stat/cat/mkdir/rm, plus
  chown/restorecon on restore.
- **SafSrc** uses a persistable `OpenDocumentTree` tree. If the picked document is the
  package folder, the app descends into its `files` child. Android 13+ normally blocks
  SAF access to `Android/data`; root mode is required there.

A one-time warning covers the game's ToS and possible account risk.

### Data

Per-app mobile/Wi-Fi bytes for today, 7 days and 30 days using `NetworkStatsManager`,
with a `PACKAGE_USAGE_STATS` check and a link to Usage Access settings.

The optional **mobile-data guard** is a local `VpnService` that allows only the selected
game package and holds the tunnel without reading, logging or forwarding packets. It
shows the required persistent notification and stops itself when Wi-Fi is available.

### Unity

Select a UnityFS bundle, parse the header/block table and show None/LZMA/LZ4/LZ4HC
compression plus sizes. Extract the concatenated raw payload or repack into a **new**
file with None/LZ4/LZMA; the original is never overwritten. Repacking validates the
complete output and checksum before publishing. `.blk`, `.pck`, `.usm`, encrypted
bundles, version 9+ and bad signatures are rejected with a reason and no partial output.

Small self-checks run for the estimator (zstd/xz) and the UnityFS header parser.

## Packages

- `com.miHoYo.GenshinImpact`
- `com.miHoYo.Yuanshen`
- `com.miHoYo.ys.bilibili`

## Build and install in GitHub Actions

The workflow `.github/workflows/build.yml`:

1. installs JDK 17, Android SDK 34 and Gradle 8.7;
2. builds `app-debug.apk` and packages the source;
3. uploads `GenshinCompressor-debug-apk` and `GenshinCompressor-source`;
4. boots an Android 34 x86_64 emulator, installs the APK, launches the app, checks that
   the process and activity are alive, and uploads a screenshot plus diagnostics.

Open the repository's **Actions** tab, select `build-and-install`, then download the
APK artifact from a green run. For a physical device:

```bash
adb install -r app-debug.apk
```

## Local build (optional)

```bash
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Limits and risks

- Genshin assets are already compressed and partly encrypted. Sampled savings can be low;
  a 40% reduction may be unreachable. The report says so instead of inventing a number.
- Safe cleanup can only *estimate* which folders are disposable from their names. Review
  every item and stop the game before changing files.
- Deleting voice/video data is opt-in and can force a re-download. It may also be
  required for some scenes or languages.
- In-place lossless recompression is skipped unless a verified filesystem API exists.
  The current build never writes zstd/xz data in place.
- SAF cannot normally reach `Android/data` on Android 13+; use root there.
- Modifying game files may violate the game's terms of service and carries an account
  risk. Use at your own risk.
- The Unity tab supports unencrypted UnityFS v6-v8 and raw concatenated extraction, not
  individual Unity asset decoding. `.blk`, `.pck` and `.usm` are not unlocked.

# Development notes

This file collects the build and release details that don't need to be in the main README.

## Requirements

- JDK 17
- Android SDK Platform 36 (the app targets Android 14 / API 34 and supports Android 8.0 / API 26+)
- Gradle is provided by the project's wrapper

From the repository root:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

A local `assembleRelease` build is not automatically signed with the repository's distribution key.

## Release builds

GitHub Actions checks English/Ukrainian translation parity, runs unit tests and builds the APK. Pushes to `main` also build, sign, verify and publish a versioned APK to GitHub Releases for [Obtainium](https://github.com/ImranR98/Obtainium).

The workflow uses a stable signing key. Maintainers must configure these repository secrets:

- `RELEASE_KEYSTORE_BASE64`: Base64-encoded signing keystore
- `RELEASE_SIGNING_PASSWORD`: keystore/key password

The keystore alias is `video-wallpaper`. Never commit the keystore or its password to Git. Back up both securely: losing the signing key prevents normal upgrades to APKs signed with that key.

The GitHub Actions run number determines the release's version code and version name. Debug artifacts built for pull requests are **not** interchangeable with the signed release APK. A differently signed debug installation may need to be uninstalled before the first signed release can be installed.

## Playback and rendering

The wallpaper service offers two playback engines:

- Media3 ExoPlayer, the default when no saved engine preference exists
- Android MediaPlayer, available as an alternative

Both send decoded video either directly to the wallpaper Surface, when geometry permits, or through the OpenGL ES renderer for aspect-correct cropping and letterboxing. GIFs use a separate decoder. The app preview retains its own VideoView player.

Runtime diagnostics are written to logcat under `VideoWallpaperService` and `Media3WallpaperPlayer`. For example:

```bash
adb logcat -d -s VideoWallpaperService:I Media3WallpaperPlayer:I VideoFrameRenderer:W
```

Log entries can help distinguish a newly prepared decoder from a reused player, dropped frames or playback-loop transitions. They do not replace on-device performance profiling, and Android can still discard the wallpaper service after a long period in the background.

The OpenGL renderer handles SDR video; accurate end-to-end HDR and wide-gamut rendering are not implemented in that path.

## Contributions

Please use this repository's [issues](https://github.com/imadraude/video-live-wallpaper/issues) for bugs and suggestions. If a change affects user-facing text, update both `app/src/main/res/values/strings.xml` and `app/src/main/res/values-uk/strings.xml`.

For project credits, third-party libraries and the original source authorship notes, see [ATTRIBUTION.md](../ATTRIBUTION.md).

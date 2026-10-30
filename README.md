> **imadraude fork:** Unlock-performance improvements and signed Android
> releases for [Obtainium](https://github.com/ImranR98/Obtainium).
> The fork uses the separate Android package ID `io.github.imadraude.videowallpaper`.

## Languages (fork)

The entire in-app interface, wallpaper label, descriptions and user-facing
error messages are available in **Ukrainian** and **English**. The app follows
the Android system language by default. On Android 13+ you can also select a
language specifically for this app in Android's **App languages** settings.
English remains the fallback for other system languages.

The APK build checks that both language files contain the same complete set
of translation keys, preventing new UI strings from being left untranslated.

## Install and update through Obtainium

1. In Obtainium add `https://github.com/imadraude/video-live-wallpaper` as a GitHub source.
2. Install the newest `video-wallpaper-v*.apk` from this fork's [Releases](https://github.com/imadraude/video-live-wallpaper/releases) (NOT a `-debug.apk`).
3. Later releases use the same signing certificate and higher Android version codes, allowing in-place updates.

**One-time migration:** previous automated APKs were debug-signed with ephemeral keys.
Uninstall any earlier `io.github.imadraude.videowallpaper` debug build before
installing the first signed release. This clears that app's saved wallpaper choice;
the original F-Droid app is a separate package and is not affected.

**Maintainer secrets:** `RELEASE_KEYSTORE_BASE64` and
`RELEASE_SIGNING_PASSWORD` must be configured in repository GitHub Actions
secrets. The keystore must use alias `video-wallpaper`. The workflow does
not print the secrets, and fails rather than publishing unsigned builds.
Back up the keystore/password securely: losing them prevents updates to
previously installed signed releases.

Pull requests run unit tests and produce a short-lived debug artifact.
Each push to `main` runs tests, builds and verifies a signed release APK,
then publishes a GitHub Release for Obtainium.

## Wallpaper rendering modes

- **Efficient (Ефективний)** sends **Fill** video directly from Android's
  MediaPlayer to the wallpaper Surface, avoiding the app's extra OpenGL pass.
  Some devices ignore native scaling on wallpaper surfaces. With **Fit**
  selected, Efficient automatically uses the OpenGL renderer so the complete
  video is visible without stretching, with black letterbox/pillarbox bars.
  Switching Fill/Fit while Efficient is selected reconfigures the pipeline.
- **Compatible (Сумісний)** uses the OpenGL ES pipeline for both Fill and Fit
  with aspect-correct cropping and letterboxing. It remains the default for
  existing installations.
- GIF wallpapers always use Compatible rendering, even if Efficient is
  selected, because the custom GIF decoder needs a GPU texture.
- Switching modes restarts the wallpaper player once. Simple lock/unlock
  visibility changes do not switch modes. The application never recompresses
  your source video in either mode.

For meaningful power and smoothness comparisons, try the same 1080p/30 fps
video in both modes on the same phone. Results depend on the Android device,
codec, launcher, and wallpaper implementation.

## SDR color fidelity and OpenGL reliability (fork)

The **Compatible** renderer uses the default EGL window surface rather
than forcing an sRGB-encoded framebuffer. The forced sRGB surface introduced
earlier could apply unwanted gamma encoding to RGB values obtained from a
video decoder's external OES texture, causing pale, washed-out colors on
some phones. This change restores the existing video shader's passthrough
behavior: no saturation filter, gamma curve, transcoding or sharpening.
Compare the same source in Efficient and Compatible modes on the target
device to confirm whether this fixes the visible regression.

The video and GIF fragment shaders use high-precision floats where the GPU
supports them and fall back to medium precision on older OpenGL ES devices.
Frame callbacks are delivered on the EGL rendering thread, previously
queued renderer initialization is cancelled safely after surface destruction,
the app skips presenting uninitialized video textures, and opaque Fill frames
avoid redundant full-screen clears. Lost EGL swaps are logged once.

**Important color limitation:** Display P3, BT.2020, HDR10, HLG and 10-bit
output are **not** implemented in the custom OpenGL pipeline. Correct HDR
rendering needs source color metadata, precision-preserving decode, supported
window formats and tone/gamut mapping. Simply tagging an SDR texture as P3
or HDR would make colors *less* accurate. If an HDR source is used, test
the native **Efficient** mode first; actual color handling is device-specific
and still needs validation against native playback.

## Unlock responsiveness (fork)

The wallpaper now caches video dimensions, coalesces rapid frame callbacks
instead of accumulating GL work, and defers pausing the video decoder for
300 ms when visibility is briefly lost during lock/unlock animations.
The decoder still pauses when the wallpaper stays hidden. This is a targeted
first fix, not a guarantee against system/OEM delays during surface recreation.

---

# Video Live Wallpaper

A small Android app that lets you pick any video or animated GIF from your device and set it as a looping live wallpaper.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="260" alt="Main screen: choose a video, playback speed, crop mode">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="260" alt="Android's live wallpaper preview screen">
</p>

## Features

- Pick a video or animated GIF with the system file picker
- Preview it looping (muted) before setting it
- Adjustable playback speed (0.25x–2.0x)
- Fill (crop to cover the screen) or Fit (show the whole frame, letterboxed if needed) scaling, shown in both the preview and the wallpaper
- Portrait phone videos display the right way up
- One-tap wallpaper setting through Android's own live wallpaper picker
- No ads, no analytics, no network access of any kind

## Install

- **F-Droid:** [Video Live Wallpaper on F-Droid](https://f-droid.org/packages/io.github.codeg0blin.videowallpaper/)
- **GitHub:** download the APK from the [Releases](https://github.com/codeg0blin/video-live-wallpaper/releases) page.

Requires Android 8.0 (API 26) or newer.

## How it works

The app implements an Android `WallpaperService` that loops your chosen video using `MediaPlayer`. Since version 1.2.0, decoded frames go to a `SurfaceTexture` and are drawn with a small OpenGL ES 2.0 renderer (`VideoFrameRenderer.kt`), which applies the video's own rotation so portrait phone clips come out upright. This is what makes Fill and Fit scaling work: Android's usual video scaling relies on resizing the display surface, which live wallpapers aren't allowed to do (see issue #1).

GIFs (version 1.3.0) don't go through `MediaPlayer`. `GifDecoder.kt` is a small pure-Kotlin GIF89a decoder written for this app, and `GifPlayer.kt` shows one frame at a time, timed by each frame's own delay divided by your speed setting. Frames are uploaded as textures to the same renderer, so Fill and Fit work identically for GIFs and videos. The app recognises GIFs by their file header rather than their file name. Transparent areas of a GIF are drawn black, and GIF files over 50 MB are not supported.

Setting the wallpaper goes through Android's own system picker (`ACTION_CHANGE_LIVE_WALLPAPER`). The OS requires this for all live wallpaper apps, not just this one.

Android applies a live wallpaper to the home screen and lock screen together, and the live wallpaper API has no per-screen setting. Some devices' system pickers may still offer extra choices; if yours does, the video plays on whichever screens you select.

## Privacy

The app has no internet permission and contains no analytics, tracking or advertising code. The only thing it stores is a reference (URI) to the video or GIF you picked, kept locally in `SharedPreferences` on your device.

## Building

Open the project in Android Studio, or build from the command line:

```
./gradlew assembleDebug
./gradlew assembleRelease
```

Unit tests for the GIF decoder run with `./gradlew test`.

Requires JDK 17 and Android SDK 34 (`compileSdk`/`targetSdk` 34, `minSdk` 26).

## Changelog

Per-version release notes are in [`fastlane/metadata/android/en-US/changelogs`](fastlane/metadata/android/en-US/changelogs), named by version code.

## Contributing

Bug reports and suggestions are welcome via [issues](https://github.com/codeg0blin/video-live-wallpaper/issues).

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

See [ATTRIBUTION.md](ATTRIBUTION.md) for a full list of the open-source libraries this app depends on, and a note on how the source code itself was written.

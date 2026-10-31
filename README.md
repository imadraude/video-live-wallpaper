# Video Wallpaper

Make your favourite video or GIF your Android wallpaper. Pick a file, choose how it fits your screen, and you're done.

**[Українською](README.uk.md)** · **[Download APK](https://github.com/imadraude/video-live-wallpaper/releases/latest)** · **[Report an issue](https://github.com/imadraude/video-live-wallpaper/issues)**

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="260" alt="Video Wallpaper settings screen">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="260" alt="Android wallpaper preview">
</p>

## What you can do

- Use a video or animated GIF from your phone.
- See a looping, muted preview before applying it.
- Adjust playback speed from 0.25× to 2×.
- **Fill** the screen by cropping the edges, or **Fit** the entire frame with black bars when needed. Neither mode should stretch the picture.
- Choose between two video engines and two rendering modes.
- Use the app in English or Ukrainian. It follows your system language, with a per-app language option on Android 13+.

No ads, accounts, trackers or internet connection required.

## Install

Requires **Android 8.0 or newer**.

Download the signed APK from [GitHub Releases](https://github.com/imadraude/video-live-wallpaper/releases/latest) and install it. To receive updates through [Obtainium](https://github.com/ImranR98/Obtainium), add this repository as a source:

`https://github.com/imadraude/video-live-wallpaper`

Obtainium will then pick up future GitHub releases. Your chosen video and settings are kept when updating the signed app.

> **Upgrading from a very old debug build?** Those builds were signed differently, so Android may require you to uninstall the debug version first. Current signed releases update normally. This fork also has a different package name from the [original F-Droid app](https://f-droid.org/packages/io.github.codeg0blin.videowallpaper/).

## Get started

1. Open Video Wallpaper and select a video or GIF.
2. Set the playback speed and choose **Fill** or **Fit**.
3. Tap **Set as Wallpaper** and confirm in Android's wallpaper picker.

Android controls which screens support live wallpapers. Depending on your phone, the picker may let you apply them to the home screen or both the home and lock screens.

## Playback settings

The default video engine is **Media3**, which uses Android's hardware decoder when available and loops the video. **MediaPlayer** remains available in case it works better with a particular device or file. Changing engines doesn't remove your selected video; an engine you've already chosen stays selected after an update.

**Efficient** tries to send decoded video directly to the wallpaper surface. When a video needs cropping or letterboxing to keep its proportions, it automatically uses the graphics renderer instead. **Compatible** always uses that renderer. GIFs use their own decoder and the graphics renderer in either mode.

You don't need to manage these technical details to use the app. The defaults work for most videos; the alternatives are there for devices that behave differently.

## Good to know

- Wallpaper playback is silent, even when the source file contains audio.
- A live wallpaper uses more power than a still image. Resolution, frame rate, codec and your phone all make a difference.
- The OpenGL renderer is intended for ordinary SDR video. HDR and wide-colour video may not look the same as in your phone's video player.
- Android may recreate the wallpaper after the screen has been off for a while. The app tries to reuse its decoder where possible, but unlock performance can vary by device.
- GIFs have a **50 MB** file-size limit.

## Privacy

Your files stay on your device. The app stores the selected file's local URI and playback preferences, and requests access only to the media you choose. It has no internet permission, analytics or advertising SDK.

## Build from source

You'll need **JDK 17** and **Android SDK 36**. Build or run tests with:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

For signing, releases and build automation, see [Development notes](docs/DEVELOPMENT.md).

## Credits and license

This project is a maintained fork of [codeg0blin/video-live-wallpaper](https://github.com/codeg0blin/video-live-wallpaper), with additional playback options, rendering fixes, translations and GitHub releases.

Released under the **Apache License 2.0**. See [LICENSE](LICENSE), [NOTICE](NOTICE) and [ATTRIBUTION.md](ATTRIBUTION.md).

Issues and suggestions are welcome in this repository's [issue tracker](https://github.com/imadraude/video-live-wallpaper/issues).

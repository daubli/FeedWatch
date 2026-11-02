# FeedWatch

Android app to view [NDI®](https://ndi.video/) or UVC (**U**SB **V**ideo **C**lass) feeds on an Android phone, with the
monitoring tools of a field monitor.

**[Try the live demo](https://feedwatch.daubli.de)**: the app runs right in your browser, with virtual sources and all
monitoring tools. The latest APK is on the [releases page](https://github.com/daubli/FeedWatch/releases/latest).

> **Is NDI® HX supported?**
>
> NDI® HX is currently not supported. I invested some effort and implemented a working prototype. However, in order
> to receive NDI® HX streams for longer than 5 minutes, you need currently a NDI® Advanced SDK license. Even if I had a
> license - which I do not - publishing the source code would not be useful, because I would not be able to publish the
> license itself. Anyone who wanted to use this functionality would need to obtain the appropriate license
> independently.

## Changes in this fork

This fork replaces the UVC capture layer and adds audio and a resolution choice for capture cards:

- **UVC through libuvc** instead of a bulk-only USB reader. Cards that stream over isochronous endpoints now work at
  all (the original code stopped with `No BULK IN endpoint found`), and the frame size is taken from the formats the
  device reports instead of being assumed.
- **RGBX frames from libuvc**: no JPEG decoding in Java any more, and the pixels are copied once into a small pool of
  recycled buffers instead of allocating a new 3.7 MB buffer per frame. 1280×720 went from about 24 to about 30 fps
  on an SM-T800.
- **UVC audio**: the card's USB audio input is recorded, played through the speaker and shown on the stereo level
  meter. The audio button mutes playback and keeps the meter live.
- **Resolution picker**: the resolution chip in the top bar opens 480 p / 576 p / 720 p / 1080 p for UVC sources.
- **Build**: AGP 8.1.4, Gradle 8.7, JDK 17 and platform 34; the NDI SDK is fetched by `tools/fetch-ndi-sdk.sh`.
- **Troubleshooting**: a capture card that was killed while streaming needs its power cycled, see
  [BUILDING.md](BUILDING.md).

## Features

### Sources

- **NDI® High Bandwidth** sources from the network. NDI® can be network-intensive, so a **USB-C Ethernet adapter** is
  strongly recommended.
- **Additional NDI® senders** outside mDNS discovery can be added by their IP address in the Settings.
- **UVC capture cards**, such as USB HDMI grabbers, show up as sources as soon as they are plugged in. Capture goes
  through [libuvc](https://github.com/libuvc/libuvc), so cards that stream over isochronous endpoints work as well as
  bulk-only ones, and the frame size is chosen from the formats the card actually reports. MJPEG is preferred.
  Tested with a low-cost UGREEN Video Capture Card and a MACROSILICON MS2109 based HDMI grabber.
- **Default source**: long-press a source to open it automatically when the app starts, after a short countdown.
- **Low Bandwidth Mode** uses the NDI® proxy stream instead of full resolution, for phones on Wi-Fi.

### Monitoring Tools

Tap a tool button to turn the tool on or off, long-press it for its settings.

- **Focus peaking**: sharp edges in red, yellow or blue, sensitivity fixed at 70%
- **Zebra**: stripes above a threshold from 50 to 100 IRE, 95 IRE by default
- **Waveform monitor**: luma or RGB, drag to move it, pinch to resize it
- **Vectorscope**: Rec. 709 with targets for the 75% color bars and a skin tone line, trace in color or white, zoom 1×
  or 2×
- **Framing guides**: rule of thirds or center cross, frame lines for 2.39:1, 4:3 or 9:16, a 90% title safe area,
  in white, yellow or red

### Stream

- **NDI® tally**: red for program, green for preview, in the top bar and as a border around the picture
- **Audio**: NDI® audio in sync with the picture, with a stereo level meter and a jitter buffer of 50 to 200 ms. Muted,
  the picture is shown with the lowest latency. For UVC sources the card's USB audio input is played and metered as
  well; there the audio button mutes playback but keeps the meter live.
- **Resolution** (UVC only): tap the resolution chip in the top bar to pick 480 p, 576 p, 720 p or 1080 p. The choice
  is stored and the stream is reopened with it; if the card does not offer that height, the closest one is used.
- **Stream statistics**: frame rates, dropped and skipped frames, bandwidth and audio buffer, updated twice a second.
  For UVC sources also the data rate and the time to copy a frame.
- **Viewer mode**: only the picture, with the close and audio buttons

## Build Requirements

Before building, we need to make sure that the following components are installed:

- Java Development Kit (JDK) 17
- Android SDK Platform 34
- Android NDK and CMake 3.22.1
- NDI SDK for Android

### 1. Java 17

This project is built with the Android Gradle Plugin 8.1.4 and Gradle 8.7, which need JDK 17. Android Studio's
bundled JDK is fine; an older JDK 11 will fail before the build starts.

### 2. Android SDK

Install Android SDK Platform 34. Instruction can be
found [here](https://developer.android.com/about/versions/14/setup-sdk)

Platform 34 is needed for the UVC library; the app still runs on the older devices it always supported.

### 3. NDI SDK for Android

`libndi.so` and the NDI headers are not part of this repository. The script downloads the SDK from the
[official site](https://ndi.video/for-developers/ndi-sdk/download/), accepts its license and copies the four Android
ABIs plus the headers into the project:

```sh
./tools/fetch-ndi-sdk.sh
```

If you prefer to do it by hand, copy `<NDI_SDK_INSTALL_DIR>/NDI SDK for Android/include/*` into
`app/src/main/cpp/include/` and `<NDI_SDK_INSTALL_DIR>/NDI SDK for Android/lib/*` into
`app/src/main/jniLibs/`.

### 4. Build the Project

`./gradlew assembleDebug`

The APK is written to `app/build/outputs/apk/debug/`. See [BUILDING.md](BUILDING.md) for the UVC capture details and
for the troubleshooting notes (a capture card that was killed mid-stream needs its power cycled).

## State of this project

This app is in a very early development phase. Therefore it is not fully tested. It is possible that the app does not
work stable and that there are some ui or- performance glitches.

## License

NDI® is a registered trademark of Vizrt NDI AB. Also visit https://ndi.video/ for details.

This project is licensed under the APACHE License 2.0. See included [LICENSE](LICENSE) file.

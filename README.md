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

- **Focus peaking**: marks edges by their sharpness, not by their contrast, in red, yellow or blue, with adjustable
  sensitivity
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
  well. This needs the microphone permission, which the app asks for together with the camera permission.
- **Resolution** (UVC only): tap the resolution chip in the top bar to pick 480p, 576p, 720p or 1080p. The choice
  is stored and the stream is reopened with it; if the card does not offer that height, the closest one is used.
- **Stream statistics**: frame rates, dropped and skipped frames, bandwidth and audio buffer, updated twice a second.
  For UVC sources also the data rate and the time to copy a frame.
- **Viewer mode**: only the picture, with the close and audio buttons

## Build Requirements

Before building, we need to make sure that the following components are installed:

- Java Development Kit (JDK) 17
- Android SDK Platform 36, NDK 27.3.13750724 and CMake 3.22.1
- NDI SDK for Android

### 1. Java 17

Build the project with JDK 17, like the release workflow does. If your default JDK is a different one, point Gradle to
JDK 17:

`./gradlew -Dorg.gradle.java.home=<JDK_17_HOME> assembleDebug`

The JDK only runs the build. The app itself is compiled for Java 8 and runs on Android 7.1 (API level 25) and newer.

### 2. Android SDK

Install Android SDK Platform 36 and, from the SDK Manager's SDK Tools, NDK 27.3.13750724 and CMake 3.22.1. Instructions
can be found [here](https://developer.android.com/about/versions/16/setup-sdk).

### 3. NDI SDK for Android

`libndi.so` and the NDI headers are not part of this repository. Download the NDI SDK for Android from the
[official site](https://ndi.video/for-developers/ndi-sdk/download/) and run its installer, which asks you to accept the
NDI SDK license. Then copy the headers and the libraries of the Android ABIs into the project:

```sh
cp "<NDI_SDK_INSTALL_DIR>/NDI SDK for Android/include/"* app/src/main/cpp/include/
cp -R "<NDI_SDK_INSTALL_DIR>/NDI SDK for Android/lib/"* app/src/main/jniLibs/
```

### 4. Build the Project

`./gradlew assembleDebug`

The APK is written to `app/build/outputs/apk/debug/`.

## Troubleshooting

**A UVC card shows no picture and logcat says `failed start_streaming (-5)`.** The card got stuck, usually because an
app was killed while it was streaming. Unplug the card and plug it in again; it needs its power cycled.

**A UVC card does not open at all.** Another app that opens USB cameras when they are attached can hold the card. Close
it or keep it from opening the card.

## State of this project

This app is in a very early development phase. Therefore it is not fully tested. It is possible that the app does not
work stable and that there are some ui or- performance glitches.

## License

NDI® is a registered trademark of Vizrt NDI AB. Also visit https://ndi.video/ for details.

This project is licensed under the APACHE License 2.0. See included [LICENSE](LICENSE) file.

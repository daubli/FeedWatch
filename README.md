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
- **UVC capture cards**, such as USB HDMI grabbers, show up as sources as soon as they are plugged in (tested with a
  low-cost UGREEN Video Capture Card).
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
  the picture is shown with the lowest latency.
- **Stream statistics**: frame rates, dropped and skipped frames, bandwidth and audio buffer, updated twice a second.
  For UVC sources also the data rate and the time to decode a frame.
- **Viewer mode**: only the picture, with the close and audio buttons

## Build Requirements

Before building, we need to make sure that the following components are installed:

- Java Development Kit (JDK) 11
- Android SDK Platform 33
- NDI SDK for Android

### 1. Java 11

This project must be built with Java 11. Newer Java versions (e.g. Java 17) may cause Gradle or NDK build failures.
The reason is, that this project should be runnable on older phones. Android SDK Platform Level 33 uses Java 11.

### 2. Android SDK

Install Android SDK Platform 33. Instruction can be
found [here](https://developer.android.com/about/versions/13/setup-sdk)

### 3. Download and Install the NDI SDK

Download the NDI SDK for Android from the [official site](https://ndi.video/for-developers/ndi-sdk/download/)

Follow the installer instructions for your platform and note the installation directory.

### 3.1 Copy NDI Header Files

After installation, copy the NDI header files into the project’s C++ include directory:

`cp <NDI_SDK_INSTALL_DIR>/NDI\ SDK\ for\ Android/include/* \ <PROJECT_ROOT>/FeedWatch/app/src/main/cpp/include/`

### 3.2 Copy NDI Library Binaries

Next, copy the NDI library binaries into the project’s jniLibs directory:

`cp <NDI_SDK_INSTALL_DIR>/NDI\ SDK\ for\ Android/lib/* <PROJECT_ROOT>/FeedWatch/app/src/main/jniLibs/`

Ensure that the copied libraries match the ABIs you intend to build for (e.g. arm64-v8a, armeabi-v7a).

### 4. Build the Project

`./gradlew assemble`

## State of this project

This app is in a very early development phase. Therefore it is not fully tested. It is possible that the app does not
work stable and that there are some ui or- performance glitches.

## License

NDI® is a registered trademark of Vizrt NDI AB. Also visit https://ndi.video/ for details.

This project is licensed under the APACHE License 2.0. See included [LICENSE](LICENSE) file.

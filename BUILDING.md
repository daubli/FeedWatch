# Building this fork

This fork replaces FeedWatch's UVC capture layer with
[libuvc](https://github.com/libuvc/libuvc) through
[UVCAndroid](https://github.com/shiyinghan/UVCAndroid).

## Why

The original implementation only handled **bulk** USB endpoints and only
**MJPEG** frames. Most HDMI-to-USB capture sticks send their video over
**isochronous** endpoints, and many of them also offer uncompressed (YUV)
formats. On those devices the original code stopped with
`No BULK IN endpoint found` and never produced a picture.

The new `UvcCaptureManager` uses libuvc, which

- supports isochronous and bulk endpoints,
- enumerates the formats and frame sizes the device actually reports,
- picks MJPEG when available (16:9 and the largest frame first),
- logs every supported mode, so an unsupported adapter is easy to diagnose.

The monitoring overlays (waveform, vectorscope, histogram, zebra, framing
guides, focus peaking) are untouched.

## Requirements

- JDK 17 (Android Studio's bundled JDK works)
- Android SDK with **platform 34** (`compileSdk` was raised for UVCAndroid)
- Android NDK and CMake (`3.22.1`) for the native audio/NDI parts
- The NDI SDK, see below

## NDI libraries

`libndi.so` and the NDI headers are not shipped in this repository. Install the NDI SDK
for Android and copy them into the project as described in the [README](README.md).

## Build

```sh
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`.

## If the build fails while transforming dependencies

Gradle keeps transformed AARs in `~/.gradle/caches/transforms-3`. Interrupted
builds can leave that cache half-written, which shows up as errors like
`.../META-INF/com/android/build/gradle/aar-metadata.properties (No such file
or directory)` or an `AndroidManifest.xml` that is suddenly missing.

Fix:

```sh
./gradlew --stop
rm -rf ~/.gradle/caches/transforms-3
./gradlew assembleDebug
```

Delete the project directory `.gradle` as well if the error persists.

## Troubleshooting

### The picture stays black, logcat shows `failed start_streaming (-5)`

`-5` is `LIBUSB_ERROR_NOT_FOUND` while setting the video interface's alternate
setting. In practice the capture card is stuck because the app or another app
was killed while streaming. Unplug the card and plug it back in; a USB port
rebind or toggling `authorized` in sysfs is usually not enough, the card needs
its power cycled.

Make sure no other app grabs the card on attach. `OTG View`
(`com.dingtai.snakecamera`) is known to do that; check with:

```sh
for p in /proc/[0-9]*; do ls -l $p/fd 2>/dev/null | grep -q '/dev/bus/usb/' && echo $p $(cat $p/comm); done
```

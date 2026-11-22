# UVCAndroid with fixes for bulk capture cards and UVC 1.5 cameras

`UVCAndroid-1.0.13-feedwatch.aar` is [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) 1.0.13 with two fixes
in libuvc. It replaces `com.herohan:UVCAndroid:1.0.13` until a release upstream contains the fixes; then switch back
to the Maven artifact and delete this folder.

## The fixes

UVCAndroid limits bulk USB transfers to 16 KB on Android, but libuvc still treated every transfer as a complete UVC
payload. Cards that send larger payloads lose every frame: the UGREEN 25854 (1de1:f105) sends each MJPEG frame of
about 900 KB as one payload with a single header, and with the unpatched library FeedWatch shows no signal for it.
The fix collects the transfers of a payload until it ends and processes it then. Isochronous cards are not affected.

libuvc also rejected every camera that reports UVC 1.5 (`bcdUVC` 0x0150) with error -12 when opening it, although
the rest of the library already handles 1.5. The DJI Osmo Action 5 Pro (05c6:8004) is such a camera. The fix accepts
1.5 like 1.1.

## Source

- Fork: https://github.com/daubli/UVCAndroid, branch `fix/bulk-payload-reassembly`
- Built from commit `0b3b124` (the fixes are `2400a12` and `0b3b124`, `4ac1dd7` pins NDK 27.3.13750724)
- SHA-256 of the AAR: `9d782f95016fc12dd0f193e7fac04aec9536361587aaa946b1fb47f76551ed1f`
- Licenses: UVCAndroid is Apache 2.0, libuvc BSD, libusb LGPL 2.1 (as a separate shared library), libjpeg-turbo and
  libyuv BSD-style; the license texts are in the fork's source folders

## Rebuilding

With JDK 17 and NDK 27.3.13750724 installed (on Apple Silicon also Rosetta, the NDK's yasm for the x86 ABIs is an
Intel binary):

```sh
git clone --branch fix/bulk-payload-reassembly https://github.com/daubli/UVCAndroid.git
cd UVCAndroid
echo "sdk.dir=<ANDROID_SDK>" > local.properties
sh ./gradlew -Dorg.gradle.java.home=<JDK_17_HOME> "-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8" \
    :libuvccamera:assembleRelease
cp libuvccamera/build/outputs/aar/libuvccamera-release.aar <FEEDWATCH>/app/libs/UVCAndroid-1.0.13-feedwatch.aar
```

The JVM arguments are given on the command line because the project's `gradle.properties` sets `-XX:MaxPermSize`,
which JDK 17 does not accept.

Only the `com.serenegiant.usb` classes are used, which need nothing beyond `androidx.annotation`, so the library's
other Maven dependencies (appcompat 1.6.1, hutool, Kotlin) are not added.

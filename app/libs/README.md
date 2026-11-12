# UVCAndroid with a fix for bulk capture cards

`UVCAndroid-1.0.13-feedwatch.aar` is [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) 1.0.13 with one fix
in libuvc. It replaces `com.herohan:UVCAndroid:1.0.13` until a release upstream contains the fix; then switch back to
the Maven artifact and delete this folder.

## The fix

UVCAndroid limits bulk USB transfers to 16 KB on Android, but libuvc still treated every transfer as a complete UVC
payload. Cards that send larger payloads lose every frame: the UGREEN 25854 (1de1:f105) sends each MJPEG frame of
about 900 KB as one payload with a single header, and with the unpatched library FeedWatch shows no signal for it.
The fix collects the transfers of a payload until it ends and processes it then. Isochronous cards are not affected.

## Source

- Fork: https://github.com/daubli/UVCAndroid, branch `fix/bulk-payload-reassembly`
- Built from commit `4ac1dd7` (the fix is `2400a12`, the other commit pins NDK 27.3.13750724)
- SHA-256 of the AAR: `c65c2c40b21c02b6031fb031f1bcc4934e744da7354a779338b4ce6caf48de4f`
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

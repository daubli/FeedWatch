#!/bin/sh
# Installs the NDI native libraries required to link the app.
#
# FeedWatch links against NDI (libndi.so). The SDK is not part of this
# repository, so this script downloads it from ndi.tv and copies the four
# Android ABIs into app/src/main/jniLibs/.
#
# By downloading you accept the NDI SDK license agreement:
#   https://downloads.ndi.tv/SDK/NDI_SDK/NDI%20SDK%20License%20Agreement.pdf
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP_DIR=${TMPDIR:-/tmp}/feedwatch-ndi-sdk
ARCHIVE=ndi-sdk-android.tar.gz
URL="https://downloads.ndi.tv/SDK/NDI_SDK_Android/Install_NDI_SDK_v6_Android.tar.gz"
ABIS="armeabi-v7a arm64-v8a x86 x86_64"

mkdir -p "$TMP_DIR"
cd "$TMP_DIR"

if [ ! -f "$ARCHIVE" ]; then
    echo "Downloading the NDI SDK for Android..."
    curl -fL "$URL" -o "$ARCHIVE"
fi

rm -rf sdk
mkdir sdk

echo "Unpacking..."
tar -xzf "$ARCHIVE" -C sdk >/dev/null

INSTALLER=$(find sdk -name 'Install_NDI_SDK_v6_Android.sh' | head -n 1)
if [ -z "$INSTALLER" ]; then
    echo "Installer not found in the archive." >&2
    exit 1
fi

echo "Accepting the NDI SDK license and unpacking..."
yes | sh "$INSTALLER" >/dev/null

LIB_DIR=$(find sdk -type d -path '*/NDI SDK for Android/lib' | head -n 1)
if [ -z "$LIB_DIR" ]; then
    echo "NDI lib directory not found." >&2
    exit 1
fi

INCLUDE_DIR=$(find sdk -type d -path '*/NDI SDK for Android/include' | head -n 1)
if [ -n "$INCLUDE_DIR" ]; then
    mkdir -p "$ROOT/app/src/main/cpp/include"
    cp "$INCLUDE_DIR"/*.h "$ROOT/app/src/main/cpp/include/"
    echo "  installed app/src/main/cpp/include/*.h"
else
    echo "NDI include directory not found." >&2
    exit 1
fi

for abi in $ABIS; do
    src="$LIB_DIR/$abi/libndi.so"
    if [ -f "$src" ]; then
        mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
        cp "$src" "$ROOT/app/src/main/jniLibs/$abi/libndi.so"
        echo "  installed app/src/main/jniLibs/$abi/libndi.so"
    else
        echo "  no libndi.so for $abi, skipped" >&2
    fi
done

echo "Done. You can now run ./gradlew assembleDebug"

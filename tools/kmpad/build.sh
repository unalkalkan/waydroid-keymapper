#!/usr/bin/env bash
# Builds kmpad for every ABI into tools/kmpad/out/<abi>/kmpad using the project NDK.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
NDK=${NDK:-$HERE/../../.sdk/ndk/27.2.12479018}
BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
for pair in arm64-v8a:aarch64-linux-android26 armeabi-v7a:armv7a-linux-androideabi26 x86_64:x86_64-linux-android26 x86:i686-linux-android26; do
    abi=${pair%%:*}; target=${pair#*:}
    mkdir -p "$HERE/out/$abi"
    "$BIN/$target-clang" -O2 -Wall -Wextra -o "$HERE/out/$abi/kmpad" "$HERE/kmpad.c"
done
echo "built: $(ls "$HERE"/out/*/kmpad | wc -l) binaries"

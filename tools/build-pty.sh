#!/bin/sh
set -eu
sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
ndk_dir=${ANDROID_NDK_HOME:-"$sdk_dir/ndk/27.2.12479018"}
compiler_dir="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
for runtime_abi in arm64-v8a x86_64; do
  case "$runtime_abi" in
    arm64-v8a) runtime_target=aarch64-linux-android28 ;;
    x86_64) runtime_target=x86_64-linux-android28 ;;
  esac
  mkdir -p "app/src/main/jniLibs/$runtime_abi"
  "$compiler_dir/$runtime_target-clang" -shared -fPIC -O2 -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now app/src/main/cpp/pty.c -o "app/src/main/jniLibs/$runtime_abi/libwpty.so"
done

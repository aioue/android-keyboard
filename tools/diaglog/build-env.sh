#!/usr/bin/env bash
# Source this: `. tools/diaglog/build-env.sh` before ./gradlew on macOS arm64.
# JDK 17 is Homebrew's keg-only openjdk@17, used via JAVA_HOME so nothing is linked system-wide.
# NDK 28.2.13676358 and cmake 3.31.6 are what build.gradle pins (ndkVersion); installed with sdkmanager.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
# build.gradle reads these from the environment; otherwise it falls back to git describe / rev-list.
export VERSION_NAME="${VERSION_NAME:-diag-0.1}"
export VERSION_CODE="${VERSION_CODE:-1}"
# AGP finds the SDK via ANDROID_HOME, but writing local.properties keeps IDEs and tools consistent.
# local.properties is gitignored; no secrets. Pass a directory to write it elsewhere (e.g. a scratch copy).
_diag_root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." && pwd)}"
if [ ! -f "$_diag_root/local.properties" ]; then
  echo "sdk.dir=$ANDROID_HOME" > "$_diag_root/local.properties"
fi
unset _diag_root

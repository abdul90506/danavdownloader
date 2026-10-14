#!/bin/bash
# Build Vidra APKs. Needs JDK 17 + Android SDK (paths below are the sandbox defaults).
export JAVA_HOME=${JAVA_HOME:-$HOME/.cache/sdk/jdk}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/.cache/sdk/android}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.cache/gradle-home}
GRADLE=${GRADLE:-$HOME/.cache/sdk/gradle-8.7/bin/gradle}
cd "$(dirname "$0")"
echo "sdk.dir=$ANDROID_HOME" > local.properties
$GRADLE --no-daemon -q assembleRelease "$@"

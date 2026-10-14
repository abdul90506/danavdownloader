#!/bin/bash
# Re-creates the build toolchain in ~/.cache/sdk (wiped when the sandbox resets).
set -e
S=$HOME/.cache/sdk; mkdir -p $S; cd $S
if [ ! -x jdk/bin/java ]; then
  curl -sL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk && tar xzf jdk.tgz -C jdk --strip-components=1 && rm jdk.tgz
fi
if [ ! -x gradle-8.7/bin/gradle ]; then
  curl -sL -o g.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip && unzip -q g.zip && rm g.zip
fi
if [ ! -d android/platforms/android-34 ]; then
  mkdir -p android/cmdline-tools
  curl -sL -o c.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q c.zip -d android/cmdline-tools && mv android/cmdline-tools/cmdline-tools android/cmdline-tools/latest && rm c.zip
  export JAVA_HOME=$S/jdk
  yes | android/cmdline-tools/latest/bin/sdkmanager --sdk_root=$S/android --licenses >/dev/null
  android/cmdline-tools/latest/bin/sdkmanager --sdk_root=$S/android "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/dev/null
fi
echo SETUP-DONE

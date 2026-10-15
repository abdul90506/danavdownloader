#!/bin/bash
# Build both Danav apps. Old APKs and build files are deleted first, so the repo never grows past the space limit.
#
#   Danav Downloader  — all-sites downloader + browser with ad blocker + video / music player
#   Danav AI          — voice assistant: chat, live calls, phone control
#
# First run:  bash danav-downloader/tools/setup.sh   (one time: JDK 17 + Gradle 8.7 + Android SDK 34)
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "-> cleaning old builds..."
rm -f *.apk
rm -rf danav-downloader/app/build danav-downloader/.gradle danav-downloader/local.properties
rm -rf danav-ai/app/build danav-ai/.gradle danav-ai/local.properties

echo "-> building Danav Downloader..."
(cd danav-downloader && bash build.sh)
echo "-> building Danav AI..."
(cd danav-ai && bash build.sh)

cp -f danav-downloader/app/build/outputs/apk/release/app-arm64-v8a-release.apk DanavDownloader-2.3.0-arm64.apk
cp -f danav-ai/app/build/outputs/apk/release/app-arm64-v8a-release.apk         DanavAI-2.3.0-arm64.apk

echo "-> removing build folders (not needed after the APKs are copied)..."
rm -rf danav-downloader/app/build danav-downloader/.gradle danav-downloader/local.properties
rm -rf danav-ai/app/build danav-ai/.gradle danav-ai/local.properties

ls -la *.apk
du -sh "$ROOT"

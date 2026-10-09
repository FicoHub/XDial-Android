#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
export JAVA_HOME="$PWD/build/android-tools/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
TOOLS="$SDK/build-tools/36.0.0"
DIR="$PWD/build/android-probe"
mkdir -p "$DIR/classes" "$DIR/dex"
"$TOOLS/aapt2" link -o "$DIR/probe-unsigned.apk" -I "$SDK/platforms/android-36/android.jar" --manifest test/android/probe/AndroidManifest.xml
javac --release 8 -classpath "$SDK/platforms/android-36/android.jar" -d "$DIR/classes" test/android/probe/*.java
jar cf "$DIR/classes.jar" -C "$DIR/classes" .
"$TOOLS/d8" --lib "$SDK/platforms/android-36/android.jar" --min-api 26 --output "$DIR/dex" "$DIR/classes.jar"
(cd "$DIR/dex" && zip -q "$DIR/probe-unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$DIR/probe-unsigned.apk" "$DIR/probe-aligned.apk"
"$TOOLS/apksigner" sign --ks build/android-signing/xdial-port.jks --ks-pass file:build/android-signing/password --out "$DIR/xdial-probe.apk" "$DIR/probe-aligned.apk"

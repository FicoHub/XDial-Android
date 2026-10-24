#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$PWD"
if [[ -z "${ANDROID_HOME:-}" && -d "$ROOT/build/android-sdk/platforms" ]]; then
 ANDROID_HOME="$ROOT/build/android-sdk"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.1.12297006}"
export JAVA_HOME="${JAVA_HOME:-$ROOT/build/android-tools/jdk/Contents/Home}"
export GOPATH="${GOPATH:-$ROOT/build/android-tools/gopath}"
# Keep the build cache with this toolchain: Go indexes the read-only module cache by path.
export GOCACHE="${GOCACHE:-$ROOT/build/android-tools/gocache}"
export GOBIN="$ROOT/build/android-tools/bin"
export PATH="$ROOT/build/android-tools/go/bin:$GOBIN:$JAVA_HOME/bin:$PATH"
BUILD="$ROOT/build/android-apk"
mkdir -p "$BUILD" "$ROOT/build/android-evidence"
SDK_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
if [[ "${SKIP_CORE:-0}" != 1 ]]; then
 bash scripts/prepare-patched-go-mod.sh > build/android-evidence/prepare-go.log 2>&1
 export GOWORK="$ROOT/build/patched-go/xdial.work"
 gomobile bind -target=android/arm64,android/arm -androidapi 26 -tags with_gvisor,with_utls -ldflags='-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384' -o build/android-tools/libbox.aar ./core/libbox > build/android-evidence/gomobile.log 2>&1
fi
rm -rf "$BUILD/classes" "$BUILD/generated" "$BUILD/dex"
mkdir -p "$BUILD/aar" "$BUILD/classes" "$BUILD/generated" "$BUILD/dex"
unzip -oq build/android-tools/libbox.aar -d "$BUILD/aar"
"$SDK_TOOLS/aapt2" compile --dir android/res -o "$BUILD/resources.zip"
# Private builds bundle the enterprise starting profile from the git-ignored PrivateConfig/.
# PUBLIC=1 never does: public releases must not carry enterprise endpoints or rules.
rm -rf "$BUILD/assets" && cp -R android/assets "$BUILD/assets"
if [[ "${PUBLIC:-0}" != 1 && -f PrivateConfig/android/company-profile.json ]]; then
 cp PrivateConfig/android/company-profile.json "$BUILD/assets/company-profile.json"
 echo "bundled PrivateConfig/android/company-profile.json"
fi
"$SDK_TOOLS/aapt2" link -o "$BUILD/unsigned.apk" -I "$ANDROID_JAR" --manifest android/AndroidManifest.xml --java "$BUILD/generated" -A "$BUILD/assets" "$BUILD/resources.zip"
find android/src "$BUILD/generated" -name '*.java' > "$BUILD/java-sources.txt"
javac -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR:$BUILD/aar/classes.jar" -d "$BUILD/classes" @"$BUILD/java-sources.txt"
jar cf "$BUILD/app-classes.jar" -C "$BUILD/classes" .
"$SDK_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$BUILD/dex" "$BUILD/app-classes.jar" "$BUILD/aar/classes.jar"
python3 scripts/android/package-apk.py "$BUILD"
"$SDK_TOOLS/zipalign" -f -P 16 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
# Private test signing key is stable across rebuilds and never included in source.
KEYDIR="$ROOT/build/android-signing"
mkdir -p "$KEYDIR";chmod 700 "$KEYDIR"
if [[ ! -f "$KEYDIR/xdial-port.jks" ]]; then
 python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > "$KEYDIR/password"
 chmod 600 "$KEYDIR/password"
 keytool -genkeypair -keystore "$KEYDIR/xdial-port.jks" -storepass:file "$KEYDIR/password" -keypass:file "$KEYDIR/password" -alias xdial-port -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=XDial Android Private Test, OU=Unofficial Port' > build/android-evidence/signing-create.log 2>&1
 chmod 600 "$KEYDIR/xdial-port.jks"
fi
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' android/AndroidManifest.xml)"
mkdir -p deliverables
NAME="XDial-Android-$VERSION$([[ "${PUBLIC:-0}" == 1 ]] && echo -public).apk"
APK="$ROOT/deliverables/$NAME"
"$SDK_TOOLS/apksigner" sign --v4-signing-enabled false --ks "$KEYDIR/xdial-port.jks" --ks-key-alias xdial-port --ks-pass "file:$KEYDIR/password" --out "$APK" "$BUILD/aligned.apk"
"$SDK_TOOLS/apksigner" verify --verbose --print-certs "$APK" > build/android-evidence/apk-signature.txt
"$SDK_TOOLS/aapt2" dump badging "$APK" > build/android-evidence/apk-metadata.txt
"$SDK_TOOLS/zipalign" -c -P 16 4 "$APK"
(cd deliverables && shasum -a 256 "$NAME" > "$NAME.sha256")
printf 'APK: %s\n' "$APK"

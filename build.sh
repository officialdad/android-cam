#!/usr/bin/env bash
# Builds android-cam.apk with javac + d8 + aapt2 + apksigner.
# No Gradle: the app has one class, no resources and no dependencies.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
BT=$(printf '%s\n' "$SDK"/build-tools/*/ | sort -V | tail -1)
PLATFORM=$(printf '%s\n' "$SDK"/platforms/android-*/ | sort -V | tail -1)
JAR="$PLATFORM/android.jar"
[ -f "$JAR" ] || { echo "no android.jar under $SDK — install an SDK platform" >&2; exit 1; }

MIN_SDK=21
TARGET_SDK=32   # <33 avoids the POST_NOTIFICATIONS runtime prompt, <34 the FGS type enforcement
OUT=build
KS=debug.keystore

rm -rf "$OUT"
mkdir -p "$OUT/classes"

# core-lambda-stubs.jar carries the LambdaMetafactory android.jar omits; d8 desugars it away.
javac -nowarn -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" \
    -d "$OUT/classes" $(find app/src -name '*.java')

"$BT/d8" --min-api "$MIN_SDK" --lib "$JAR" --output "$OUT" \
    $(find "$OUT/classes" -name '*.class')

"$BT/aapt2" link -I "$JAR" --manifest app/AndroidManifest.xml \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    -o "$OUT/unsigned.apk"

(cd "$OUT" && "$BT/aapt" add -f unsigned.apk classes.dex >/dev/null)
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Stable keystore, so `adb install -r` never trips the signature check.
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androidcam -keyalg RSA -keysize 2048 -validity 10000 -dname CN=android-cam 2>/dev/null

"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out android-cam.apk "$OUT/aligned.apk"
echo "built android-cam.apk"

if [ "${1:-}" = "--install" ]; then
    # -g grants CAMERA at install. Some vendor ROMs refuse; grant it by hand there.
    adb install -r -g android-cam.apk
fi

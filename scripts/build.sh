#!/data/data/com.termux/files/usr/bin/bash
# Builds build/wispr.apk on Termux with no Gradle, then installs it over adb.
# Needs: pkg install rust openjdk-17 aapt2 d8 apksigner zip cmake android-tools
# Usage: scripts/build.sh [--install]
set -euo pipefail
cd "$(dirname "$0")/.."

B=build
JAR=$B/android.jar
KS=$HOME/.android/wispr.jks
MODEL=models/ggml-base.en-q5_1.bin

mkdir -p $B models "$(dirname "$KS")"
if [ ! -f $JAR ]; then
  curl -sSfL -o $B/p.zip https://dl.google.com/android/repository/platform-35_r02.zip
  unzip -p $B/p.zip android-35/android.jar > $JAR
  rm $B/p.zip
fi
[ -f $MODEL ] || curl -sSfL -o $MODEL https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias wispr -keyalg RSA -keysize 2048 -validity 10000 -dname CN=wispr >/dev/null 2>&1

(cd rust && cargo build --release -q)

rm -rf $B/classes $B/dex $B/lib $B/*.apk
mkdir -p $B/classes $B/dex $B/lib/arm64-v8a
cp rust/target/release/libwispr.so $PREFIX/lib/libc++_shared.so $B/lib/arm64-v8a/

aapt2 compile --dir res -o $B/res.zip
aapt2 link -I $JAR --manifest AndroidManifest.xml --min-sdk-version 31 --target-sdk-version 35 \
  -o $B/unsigned.apk $B/res.zip
javac --release 17 -nowarn -Xlint:-options -cp $JAR -d $B/classes $(find src -name '*.java')
d8 --release --min-api 31 --lib $JAR --output $B/dex $(find $B/classes -name '*.class')
(cd $B/dex && zip -q ../unsigned.apk classes.dex)
(cd $B && zip -q -0 unsigned.apk lib/arm64-v8a/*.so)   # stored, so the .so loads straight from the APK
zipalign -f -P 16 4 $B/unsigned.apk $B/aligned.apk
apksigner sign --ks "$KS" --ks-pass pass:android --out $B/wispr.apk $B/aligned.apk
rm $B/unsigned.apk $B/aligned.apk $B/res.zip
ls -l $B/wispr.apk

if [ "${1:-}" = "--install" ]; then
  S=$(adbon)
  adb -s "$S" install -r -g $B/wispr.apk
  D=/sdcard/Android/data/dev.wispr/files
  adb -s "$S" shell mkdir -p $D
  adb -s "$S" shell "[ -f $D/model.bin ]" || adb -s "$S" push $MODEL $D/model.bin
  SVC=dev.wispr/dev.wispr.Svc
  CUR=$(adb -s "$S" shell settings get secure enabled_accessibility_services | tr -d '\r')
  case "$CUR" in *"$SVC"*) ;; null|"") adb -s "$S" shell settings put secure enabled_accessibility_services "$SVC" ;;
    *) adb -s "$S" shell settings put secure enabled_accessibility_services "$CUR:$SVC" ;; esac
fi

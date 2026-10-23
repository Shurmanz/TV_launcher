#!/usr/bin/env bash
# Сборка лаунчера «Дом» без Gradle: javac → d8 → aapt2 → zipalign → apksig.
# Инструменты берутся с maven.google.com (aapt2, r8/d8, apksig) и android.jar (API 30).
set -euo pipefail
cd "$(dirname "$0")"

TOOLS="${TOOLS:-$PWD/.tools}"
ANDROID_JAR="${ANDROID_JAR:?укажите путь к android.jar (API 30)}"
AAPT2_VER=8.7.3-12006047
R8_VER=8.7.18
APKSIG_VER=8.7.3
G=https://maven.google.com

mkdir -p "$TOOLS" build
fetch() { [[ -f "$2" ]] || curl -sSfL -o "$2" "$1"; }

fetch "$G/com/android/tools/build/aapt2/$AAPT2_VER/aapt2-$AAPT2_VER-linux.jar" "$TOOLS/aapt2.jar"
fetch "$G/com/android/tools/r8/$R8_VER/r8-$R8_VER.jar" "$TOOLS/r8.jar"
fetch "$G/com/android/tools/build/apksig/$APKSIG_VER/apksig-$APKSIG_VER.jar" "$TOOLS/apksig.jar"
[[ -x "$TOOLS/aapt2" ]] || (cd "$TOOLS" && unzip -oq aapt2.jar aapt2 && chmod +x aapt2)

# 1. ресурсы
rm -rf build/res build/gen build/classes build/dex
mkdir -p build/res build/gen build/classes build/dex
"$TOOLS/aapt2" compile --dir res -o build/res/res.zip
"$TOOLS/aapt2" link -o build/unsigned.apk -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 26 --target-sdk-version 29 build/res/res.zip

# 2. код (заглушка LambdaMetafactory — аналог core-lambda-stubs.jar из build-tools)
mkdir -p build/stubs
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d build/stubs \
  stubs/java/lang/invoke/LambdaMetafactory.java
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR:build/stubs" -d build/classes \
  $(find src build/gen -name '*.java')
java -cp "$TOOLS/r8.jar" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')

# 3. упаковка, выравнивание, подпись (v1+v2)
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
python3 zipalign.py build/unsigned.apk build/aligned.apk
[[ -f release.jks ]] || keytool -genkeypair -keystore release.jks -storepass tvhome1 -keypass tvhome1 \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=TV Home" >/dev/null 2>&1
javac -cp "$TOOLS/apksig.jar" -d build/signer SignApk.java
java -cp "$TOOLS/apksig.jar:build/signer" SignApk release.jks tvhome1 tvhome build/aligned.apk tvhome.apk
echo "Готово: $PWD/tvhome.apk"

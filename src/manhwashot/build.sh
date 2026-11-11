#!/usr/bin/env bash
# Compila la extensión ManhwaShot sin Android SDK.
#
# Uso:
#   KEYSTORE=/ruta/manhwashot.p12 KEYSTORE_PASS=... KEY_ALIAS=manhwashot ./build.sh
#
# Requisitos: java (JDK 17+), maven (mvn), python3. Las herramientas se
# descargan de Maven Central en .tools/ (ignorado por git).
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=3
VERSION_NAME=1.4.3
PKG=eu.kanade.tachiyomi.extension.es.manhwashot
OUT=${OUT:-../../apk/tachiyomi-es.manhwashot-v$VERSION_NAME.apk}
: "${KEYSTORE:?Falta KEYSTORE}" "${KEYSTORE_PASS:?Falta KEYSTORE_PASS}"
KEY_ALIAS=${KEY_ALIAS:-manhwashot}
MVN=${MVN:-mvn}

fetch() { # $1 = nombre del pom en tools/
  local dir=.tools/$1
  if [ ! -d "$dir" ]; then
    "$MVN" -q -B -f "tools/$1.pom.xml" dependency:copy-dependencies -DoutputDirectory="$PWD/$dir"
  fi
}
fetch kotlinc; fetch compile; fetch dx

KOTLINC=(java -cp ".tools/kotlinc/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler)
KFLAGS=(-jvm-target 1.8 -api-version 1.8 -language-version 1.9 -Xlambdas=class -Xsam-conversions=class -no-reflect -nowarn)
LIBS=$(ls .tools/compile/*.jar | tr '\n' ':')

rm -rf build && mkdir -p build/stubs build/classes build/apk
"${KOTLINC[@]}" "${KFLAGS[@]}" -no-stdlib -cp "$LIBS" -d build/stubs stubs
"${KOTLINC[@]}" "${KFLAGS[@]}" -no-stdlib -cp "$LIBS:build/stubs" -d build/classes src
rm -rf build/classes/META-INF

java -cp ".tools/dx/*" com.android.dx.command.Main --dex --min-sdk-version=14 \
  --output=build/apk/classes.dex build/classes

python3 tools/patch_manifest.py base/AndroidManifest.xml build/apk/AndroidManifest.xml "$VERSION_CODE" "$VERSION_NAME"
cp base/resources.arsc build/apk/resources.arsc

(cd build/apk && rm -f ../unsigned.apk && python3 -c "
import zipfile
z = zipfile.ZipFile('../unsigned.apk', 'w')
z.write('AndroidManifest.xml', compress_type=zipfile.ZIP_DEFLATED)
z.write('resources.arsc', compress_type=zipfile.ZIP_STORED)
z.write('classes.dex', compress_type=zipfile.ZIP_DEFLATED)
z.close()")

jarsigner -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -sigalg SHA256withRSA -digestalg SHA-256 \
  -sigfile MANHWASH -signedjar build/signed.apk build/unsigned.apk "$KEY_ALIAS" >/dev/null
python3 tools/align.py build/signed.apk "$OUT"
jarsigner -verify "$OUT" >/dev/null && echo "APK: $OUT"
keytool -printcert -jarfile "$OUT" | grep 'SHA256:'

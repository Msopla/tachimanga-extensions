#!/usr/bin/env bash
# Compila una extensión de este repositorio sin Android SDK.
#
# Uso:
#   KEYSTORE=/ruta/clave.p12 KEYSTORE_PASS=... ./src/build.sh <manhwashot|lectorxd|olympusxyz>
#
# Cada extensión tiene en src/<nombre>/:
#   ext.conf         versión, paquete y nombre de la firma
#   src/             código (.kt y/o .java)
#   base/            manifiesto binario y resources.arsc originales, más archivos
#                    extra del APK (assets/, res/)
#   bundle.pom.xml   (opcional) librerías que se incluyen dentro del APK
#
# Requisitos: JDK 17+, Maven (mvn) y Python 3. Las herramientas se descargan de
# Maven Central en src/.tools/ (ignorado por git).
set -euo pipefail
cd "$(dirname "$0")"

EXT=${1:?Indica la extensión: manhwashot, lectorxd u olympusxyz}
[ -f "$EXT/ext.conf" ] || { echo "No existe $EXT/ext.conf" >&2; exit 1; }
# shellcheck disable=SC1090
source "$EXT/ext.conf"
OUT=${OUT:-../apk/$APK_NAME-v$VERSION_NAME.apk}
: "${KEYSTORE:?Falta KEYSTORE}" "${KEYSTORE_PASS:?Falta KEYSTORE_PASS}"
MVN=${MVN:-mvn}
COMMON=common
BUILD=$EXT/build

fetch() { # $1 = pom, $2 = carpeta destino
  if [ ! -d "$2" ]; then
    "$MVN" -q -B -f "$1" dependency:copy-dependencies -DoutputDirectory="$PWD/$2"
  fi
}
fetch $COMMON/tools/kotlinc.pom.xml .tools/kotlinc
fetch $COMMON/tools/compile.pom.xml .tools/compile
fetch $COMMON/tools/dx.pom.xml .tools/dx
[ -f "$EXT/bundle.pom.xml" ] && fetch "$EXT/bundle.pom.xml" ".tools/bundle-$EXT"

KOTLINC=(java -cp ".tools/kotlinc/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler)
KFLAGS=(-jvm-target 1.8 -api-version 1.8 -language-version 1.9 -Xlambdas=class -Xsam-conversions=class -no-reflect -nowarn)
BUNDLE_JARS=$( [ -d ".tools/bundle-$EXT" ] && ls .tools/bundle-$EXT/*.jar | tr '\n' ':' || true)
LIBS=$(ls .tools/compile/*.jar | tr '\n' ':')$BUNDLE_JARS

rm -rf "$BUILD" && mkdir -p "$BUILD/stubs" "$BUILD/classes" "$BUILD/apk"

# 1) Stubs de la API de extensiones (solo para compilar; no van en el APK).
"${KOTLINC[@]}" "${KFLAGS[@]}" -no-stdlib -cp "$LIBS" -d "$BUILD/stubs" $COMMON/stubs

# 2) Código de la extensión (Kotlin y/o Java).
KT=$(find "$EXT/src" -name '*.kt' | sort)
JAVA=$(find "$EXT/src" -name '*.java' | sort)
if [ -n "$KT" ]; then
  # shellcheck disable=SC2086
  "${KOTLINC[@]}" "${KFLAGS[@]}" -no-stdlib -cp "$LIBS:$BUILD/stubs" -d "$BUILD/classes" $KT $JAVA
fi
if [ -n "$JAVA" ]; then
  # shellcheck disable=SC2086
  javac --release 8 -nowarn -encoding UTF-8 -cp "$LIBS:$BUILD/stubs:$BUILD/classes" -d "$BUILD/classes" $JAVA
fi
rm -rf "$BUILD/classes/META-INF"

# 3) Librerías incluidas en el APK (sin module-info ni META-INF).
if [ -d ".tools/bundle-$EXT" ]; then
  for jar in .tools/bundle-$EXT/*.jar; do
    (cd "$BUILD/classes" && unzip -q -o "../../../$jar" -x 'META-INF/*')
  done
  find "$BUILD/classes" -name module-info.class -delete
fi

# 4) Dex, manifiesto (versión) y archivos originales.
java -cp ".tools/dx/*" com.android.dx.command.Main --dex --min-sdk-version=14 \
  --output="$BUILD/apk/classes.dex" "$BUILD/classes"
python3 $COMMON/tools/patch_manifest.py "$EXT/base/AndroidManifest.xml" "$BUILD/apk/AndroidManifest.xml" "$VERSION_CODE" "$VERSION_NAME"
(cd "$EXT/base" && find . -type f ! -name AndroidManifest.xml -exec cp --parents {} "../build/apk/" \;)

# 5) ZIP, firma v1 y alineado de resources.arsc.
python3 - "$BUILD/apk" "$BUILD/unsigned.apk" <<'PY'
import os, sys, zipfile
root, out = sys.argv[1], sys.argv[2]
order = ['AndroidManifest.xml', 'resources.arsc', 'classes.dex']
files = sorted(os.path.relpath(os.path.join(d, f), root) for d, _, fs in os.walk(root) for f in fs)
files = [f for f in order if f in files] + [f for f in files if f not in order]
with zipfile.ZipFile(out, 'w') as z:
    for f in files:
        z.write(os.path.join(root, f), f, compress_type=zipfile.ZIP_STORED if f == 'resources.arsc' else zipfile.ZIP_DEFLATED)
PY
jarsigner -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -sigalg SHA256withRSA -digestalg SHA-256 \
  -sigfile "$SIGFILE" -signedjar "$BUILD/signed.apk" "$BUILD/unsigned.apk" "$KEY_ALIAS" >/dev/null
python3 $COMMON/tools/align.py "$BUILD/signed.apk" "$OUT"
jarsigner -verify "$OUT" >/dev/null && echo "APK: $OUT"
keytool -printcert -jarfile "$OUT" | grep 'SHA256:'

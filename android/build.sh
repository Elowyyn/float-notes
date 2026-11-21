#!/usr/bin/env bash
# 构建 浮窗便签 APK —— 不依赖 Gradle / AndroidX，只用 SDK 命令行工具
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-/c/Users/15697/.workbuddy/tools/android/sdk}"
JDK="${JAVA_HOME_DIR:-/c/Users/15697/.workbuddy/tools/android/jdk/jdk-17.0.20.1+1}"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
PY="C:/Users/15697/.workbuddy/binaries/python/versions/3.13.12/python.exe"

VER_CODE=7
VER_NAME="1.6"
OUT_APK="$(dirname "$ROOT")/浮窗便签_v${VER_NAME}.apk"

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

W() { cygpath -w "$1"; }

cd "$ROOT"
mkdir -p build/gen build/classes build/dex build/apk

echo "▶ 1/6 编译资源"
"$BT/aapt2.exe" compile --dir res -o build/apk/res.zip

echo "▶ 2/6 链接资源 + 生成 R.java"
"$BT/aapt2.exe" link \
  -o build/apk/base.apk \
  -I "$(W "$ANDROID_JAR")" \
  --manifest AndroidManifest.xml \
  -R build/apk/res.zip \
  --java "$(W build/gen)" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code "$VER_CODE" \
  --version-name "$VER_NAME" \
  --auto-add-overlay

echo "▶ 3/6 javac 编译"
rm -rf build/classes && mkdir -p build/classes
find src build/gen -name '*.java' > build/sources.txt
set +e
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$(W "$ANDROID_JAR")" \
  -classpath "$(W "$ANDROID_JAR")" \
  -d "$(W build/classes)" \
  @"build/sources.txt" > build/javac.log 2>&1
JC=$?
set -e
if [ $JC -ne 0 ]; then
  echo "❌ javac 编译失败："
  iconv -f GBK -t UTF-8 build/javac.log 2>/dev/null | tail -30 || tail -30 build/javac.log
  exit 1
fi

echo "▶ 4/6 d8 生成 dex"
rm -rf build/dex && mkdir -p build/dex
mapfile -t CLASSES < <(find build/classes -name '*.class')
java -cp "$(W "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
  --release --min-api 26 --lib "$(W "$ANDROID_JAR")" \
  --output "$(W build/dex)" "${CLASSES[@]}"

echo "▶ 5/6 打包 + 对齐"
"$PY" - <<'PYEOF'
import shutil, zipfile
base = 'build/apk/base.apk'
out = 'build/apk/unaligned.apk'
shutil.copyfile(base, out)
with zipfile.ZipFile(out, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
print('  classes.dex 已写入')
PYEOF
"$BT/zipalign.exe" -f -p 4 build/apk/unaligned.apk build/apk/aligned.apk

echo "▶ 6/6 签名"
KS="build/float-notes.keystore"
KSPASS="floatnotes2026"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -alias floatnotes -keyalg RSA -keysize 2048 \
    -validity 20000 -storepass "$KSPASS" -keypass "$KSPASS" \
    -dname "CN=Float Notes, OU=Local, O=Local, L=Local, ST=Local, C=CN" > /dev/null 2>&1
  echo "  已创建签名证书"
fi
java -jar "$(W "$BT/lib/apksigner.jar")" sign \
  --ks "$KS" --ks-key-alias floatnotes \
  --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --min-sdk-version 26 --out "$OUT_APK" build/apk/aligned.apk

java -jar "$(W "$BT/lib/apksigner.jar")" verify "$OUT_APK" > /dev/null

echo
echo "✅ 构建完成"
ls -lh "$OUT_APK" | awk '{print "  APK：" $NF "  " $5}'

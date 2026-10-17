#!/bin/bash
# ============================================================
#  哈哈哈 LaughBox —— 纯命令行构建 Android APK（不依赖 Gradle）
#
#  为什么不用 Gradle：本项目只有一个 Activity，构建链极短，
#  直接调用 aapt2 / javac / d8 / zipalign / apksigner 更快，
#  也避免几百 MB 的 Gradle 依赖。
#
#  用法：
#     ./build.sh                      # 自动在常见路径查找 SDK
#     BT=/path/to/toolchains ./build.sh   # 指定工具链根目录
#
#  工具链根目录 (BT) 需要包含：
#     $BT/<jdk>/                 JDK 17+        （目录名任意）
#     $BT/android-sdk/platforms/android-34/android.jar
#     $BT/android-sdk/build-tools/34.0.0/{aapt2,d8,zipalign,apksigner}
# ============================================================
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build"
APK_OUT="${APK_OUT:-$ROOT/dist}"
KEY_ALIAS="laughbox"
KS_PASS="android"

# ---------- 定位工具链 ----------
BT="${BT:-}"
if [ -z "$BT" ]; then
  for c in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$HOME/Android/Sdk" \
           /usr/lib/android-sdk /opt/android-sdk "$ROOT/../build-toolchain"; do
    [ -n "$c" ] && [ -d "$c" ] && BT="$(cd "$c" && pwd)" && break
  done
fi
if [ -z "$BT" ] || [ ! -d "$BT" ]; then
  echo "❌ 找不到 Android SDK。请设置 ANDROID_HOME 或用 BT=<目录> 指定工具链根目录。" >&2
  exit 1
fi

# JDK：优先 $JAVA_HOME，其次在 BT 下找一个带 bin/java 的目录
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  :
else
  JAVA_HOME=""
  for d in "$BT"/*/; do
    if [ -x "$d/bin/javac" ]; then JAVA_HOME="${d%/}"; break; fi
  done
fi
if [ -z "$JAVA_HOME" ]; then
  JAVAC_BIN="$(command -v javac || true)"
  if [ -n "$JAVAC_BIN" ]; then
    JAVA_HOME="$(dirname "$(dirname "$JAVAC_BIN")")"
  fi
fi
[ -x "$JAVA_HOME/bin/javac" ] || { echo "❌ 找不到 JDK 17+（需要 javac）。" >&2; exit 1; }

AJ="$BT/android-sdk/platforms/android-34/android.jar"
BT34="$BT/android-sdk/build-tools/34.0.0"
[ -f "$AJ" ] || { echo "❌ 缺少 $AJ（用 sdkmanager 安装 'platforms;android-34'）。" >&2; exit 1; }
[ -x "$BT34/d8" ] || { echo "❌ 缺少 $BT34（用 sdkmanager 安装 'build-tools;34.0.0'）。" >&2; exit 1; }

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> 环境"
echo "    JDK     $JAVA_HOME"
echo "    SDK     $BT/android-sdk"
echo "    输出    $APK_OUT"

# ---------- 构建 ----------
echo "==> 清理"
rm -rf "$OUT" "$APK_OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$APK_OUT"

echo "==> [1/6] aapt2 compile（编译资源）"
"$BT34/aapt2" compile --dir "$APP/res" -o "$OUT/res/res.zip"

echo "==> [2/6] aapt2 link（生成资源 APK + R.java）"
"$BT34/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  -A "$APP/assets" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code 2 \
  --version-name 1.1.0 \
  --auto-add-overlay \
  "$OUT/res/res.zip"

echo "==> [3/6] javac"
# 构建期生成资源尺寸常量：assets/laugh.mp4 的精确字节数。
# 运行时用它校验缓存文件是否完整，避免"截断的缓存被当成好文件"导致永久无法播放。
ASSET_BYTES=$(stat -c%s "$APP/assets/laugh.mp4")
mkdir -p "$OUT/gen/com/lazy/laughbox"
cat > "$OUT/gen/com/lazy/laughbox/AssetSize.java" <<EOF
package com.lazy.laughbox;
/** 构建期自动生成：assets/laugh.mp4 的精确字节数，用于缓存完整性校验 */
final class AssetSize { static final long BYTES = ${ASSET_BYTES}L; }
EOF
echo "    assets/laugh.mp4 = $ASSET_BYTES 字节 → AssetSize.java"
find "$OUT/gen" "$APP/src" -name "*.java" > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" \
  -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$AJ" -classpath "$AJ" \
  -d "$OUT/classes" -nowarn \
  @"$OUT/sources.txt" 2>&1 | grep -vE "bootstrap class path|^Note:|warning" || true

echo "==> [4/6] d8（生成 dex）"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$BT34/d8" --lib "$AJ" --min-api 21 --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> [5/6] 打包 dex 进 APK"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
# dex 必须位于 APK 根目录（-j 丢弃路径），否则 ART 加载不到会直接崩
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex )
rm -f "$OUT/dex/classes.dex"

echo "==> [6/6] zipalign + 签名"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -v \
    -keystore "$KS" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=LaughBox, OU=Fun, O=Comedy, L=CN, ST=GD, C=CN" > /dev/null 2>&1
  echo "    已生成 debug.keystore（口令 $KS_PASS）"
fi

"$BT34/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
FINAL="$APK_OUT/laughbox.apk"
"$BT34/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$FINAL" "$OUT/aligned.apk"

"$BT34/apksigner" verify --print-certs "$FINAL" | head -2
echo ""
echo "✅ 构建完成"
ls -lh "$FINAL"

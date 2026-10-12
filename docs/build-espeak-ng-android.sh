#!/usr/bin/env bash
#
# 为 Android 交叉编译 16 KB 页对齐的 espeak-ng。
#
# 背景：项目原先使用第三方预编译的 libttsespeak.so（4 KB 对齐），
# 在 Android 15+ 的 16 KB 页设备上无法加载，Google Play 也会拒收。
# 本脚本从 espeak-ng 官方源码重新编译出 16 KB 对齐的 libespeak-ng.so。
#
# 用法：
#   bash docs/build-espeak-ng-android.sh            # 编译全部 4 个 ABI 并安装到 jniLibs
#   bash docs/build-espeak-ng-android.sh arm64-v8a  # 只编译指定 ABI
#
# 依赖：curl、cmake、Android NDK
#
set -euo pipefail

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
NDK_VERSION="${NDK_VERSION:-25.2.9519653}"
# espeak-ng master (CMakeLists VERSION 1.53.0)。
# 必须与 assets/espeak-ng-data 的数据版本一致 —— 见下方 DATA_VERSION 检查。
ESPEAK_TARBALL_URL="${ESPEAK_TARBALL_URL:-https://codeload.github.com/espeak-ng/espeak-ng/tar.gz/refs/heads/master}"
ESPEAK_TARBALL_SHA256="${ESPEAK_TARBALL_SHA256:-b4c30c70d990587833b4e47735db5ac962a783373f7c05c207ce247008a26296}"

# 我们内置的数据要求的版本号（phondata 前 4 字节，小端）。
# 见 espeak-ng 的 src/libespeak-ng/synthdata.c: const int version_phdata = 0x014801;
EXPECTED_DATA_VERSION="0x014801"

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_LIBS="$PROJECT_ROOT/app/src/main/jniLibs"
DATA_DIR="$PROJECT_ROOT/app/src/main/assets/espeak-ng-data"
WORK_DIR="${WORK_DIR:-/tmp/espeak-ng-build}"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"

ABIS=("$@")
if [ ${#ABIS[@]} -eq 0 ]; then
  ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
fi

# ---------------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------------
[ -f "$TOOLCHAIN" ] || { echo "❌ 找不到 NDK $NDK_VERSION：$TOOLCHAIN" >&2; exit 1; }
command -v cmake >/dev/null || { echo "❌ 缺少 cmake" >&2; exit 1; }
command -v curl  >/dev/null || { echo "❌ 缺少 curl" >&2; exit 1; }

echo "== 校验内置 espeak-ng-data 的数据版本 =="
if [ -f "$DATA_DIR/phondata" ]; then
  ACTUAL=$(python3 - "$DATA_DIR/phondata" <<'PY'
import sys
b = open(sys.argv[1],'rb').read(4)
print("0x%06x" % sum(b[i] << (i*8) for i in range(4)))
PY
)
  echo "  内置数据版本 = $ACTUAL，源码期望 = $EXPECTED_DATA_VERSION"
  if [ "$ACTUAL" != "$EXPECTED_DATA_VERSION" ]; then
    echo "  ⚠️  版本不一致！新编译的 libespeak-ng.so 会在运行时拒绝加载该数据，"
    echo "      报 'Wrong version of espeak-ng-data'。请改用匹配的 espeak-ng 源码版本，"
    echo "      或重新编译 espeak-ng-data。"
    exit 1
  fi
  echo "  ✅ 匹配，可复用现有数据，无需重新编译 19MB 的 espeak-ng-data"
else
  echo "  ⚠️  未找到 $DATA_DIR/phondata，跳过检查"
fi

# ---------------------------------------------------------------------------
# 下载源码
# ---------------------------------------------------------------------------
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

if [ ! -d espeak-ng ]; then
  echo
  echo "== 下载 espeak-ng 源码 =="
  curl -sSL --max-time 600 -o espeak-ng.tar.gz "$ESPEAK_TARBALL_URL"
  GOT=$(shasum -a 256 espeak-ng.tar.gz | awk '{print $1}')
  if [ "$GOT" != "$ESPEAK_TARBALL_SHA256" ]; then
    echo "  ⚠️  tarball sha256 与记录不符（上游可能已更新）："
    echo "      期望 $ESPEAK_TARBALL_SHA256"
    echo "      实际 $GOT"
    echo "  继续构建，但结果可能与之前不完全一致。"
  fi
  tar xzf espeak-ng.tar.gz
  mv espeak-ng-master espeak-ng
fi

# ---------------------------------------------------------------------------
# 逐 ABI 构建
# ---------------------------------------------------------------------------
for abi in "${ABIS[@]}"; do
  echo
  echo "== 构建 $abi =="
  BUILD="$WORK_DIR/build-$abi"
  rm -rf "$BUILD"

  cmake -S "$WORK_DIR/espeak-ng" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DCOMPILE_INTONATIONS=OFF \
    -DENABLE_TESTS=OFF \
    -DUSE_MBROLA=OFF \
    -DUSE_LIBSONIC=OFF \
    -DUSE_LIBPCAUDIO=OFF \
    -DUSE_SPEECHPLAYER=OFF \
    -DUSE_ASYNC=OFF \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    > "$BUILD-configure.log" 2>&1 \
    || { echo "  ❌ configure 失败，见 $BUILD-configure.log"; exit 1; }

  cmake --build "$BUILD" --target espeak-ng -j4 > "$BUILD-build.log" 2>&1 \
    || { echo "  ❌ build 失败，见 $BUILD-build.log"; exit 1; }

  SO="$BUILD/src/libespeak-ng/libespeak-ng.so"
  [ -f "$SO" ] || { echo "  ❌ 未产出 $SO"; exit 1; }

  # 校验 16 KB 对齐
  python3 - "$SO" <<'PY'
import struct, sys
p = sys.argv[1]
d = open(p,'rb').read()
is64 = d[4] == 2
phoff = struct.unpack_from('<Q',d,0x20)[0] if is64 else struct.unpack_from('<I',d,0x1c)[0]
phes  = struct.unpack_from('<H',d,0x36)[0] if is64 else struct.unpack_from('<H',d,0x2a)[0]
phn   = struct.unpack_from('<H',d,0x38)[0] if is64 else struct.unpack_from('<H',d,0x2c)[0]
segs = []
for i in range(phn):
    o = phoff + i*phes
    if struct.unpack_from('<I',d,o)[0] != 1: continue
    if is64:
        po = struct.unpack_from('<Q',d,o+0x08)[0]
        pv = struct.unpack_from('<Q',d,o+0x10)[0]
        pa = struct.unpack_from('<Q',d,o+0x30)[0]
    else:
        po = struct.unpack_from('<I',d,o+0x04)[0]
        pv = struct.unpack_from('<I',d,o+0x08)[0]
        pa = struct.unpack_from('<I',d,o+0x1c)[0]
    segs.append((po,pv,pa))
ok = all(a >= 16384 for _,_,a in segs) and all((o%16384)==(v%16384) for o,v,_ in segs)
print(f"  对齐检查: align={sorted(set(a for _,_,a in segs))} 16K={'✅' if ok else '❌'}")
sys.exit(0 if ok else 1)
PY
  [ $? -eq 0 ] || { echo "  ❌ 16 KB 对齐校验未通过"; exit 1; }

  mkdir -p "$JNI_LIBS/$abi"
  cp "$SO" "$JNI_LIBS/$abi/libespeak-ng.so"
  echo "  ✅ 已安装到 jniLibs/$abi/libespeak-ng.so"
done

echo
echo "== 完成 =="
echo "下一步："
echo "  1. 确认 app/src/main/cpp/CMakeLists.txt 链接的是 libespeak-ng.so（不是旧的 libttsespeak.so）"
echo "  2. ./gradlew assembleDebug"
echo "  3. 校验 APK："
echo "     unzip -o app/build/outputs/apk/debug/app-arm64-v8a-debug.apk 'lib/arm64-v8a/*' -d /tmp/chk"
echo "     ~/Library/Android/sdk/build-tools/36.0.0/zipalign -c -P 16 -v 4 <apk>"

#!/usr/bin/env bash
#
# 下载 Piper 中文语音模型（侍读郎 / Leedor）
#
# 用法:
#   bash docs/download-zh-models.sh [x_low|medium|both] [--push]
#
# 默认源是 hf-mirror.com，因为 huggingface.co 在部分网络下不可达。
# 可用 PIPER_BASE 覆盖，例如:
#   PIPER_BASE=https://huggingface.co/rhasspy/piper-voices/resolve/main bash docs/download-zh-models.sh
#
set -uo pipefail

PKG="com.leedor.tts"
DEST_DIR_DEVICE="/sdcard/Android/data/${PKG}/files/piper_downloads"
BASE="${PIPER_BASE:-https://hf-mirror.com/rhasspy/piper-voices/resolve/main}"
OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/zh-models"
MAX_TRIES=5

VARIANT="${1:-x_low}"
PUSH=0
for a in "$@"; do [ "$a" = "--push" ] && PUSH=1; done

# ---------- 文件表: id|相对路径|字节数|md5 ----------
ALL_FILES=(
  "zh_CN-huayan-medium|zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx|63201294|40cdb7930ff91b81574d5f0489e076ea"
  "zh_CN-huayan-medium|zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx.json|4822|1fda3ec1d0d3a5a74064397ea8fe0af0"
  "zh_CN-huayan-x_low|zh/zh_CN/huayan/x_low/zh_CN-huayan-x_low.onnx|20628813|2b96570db6becd09814a608c8d14a64f"
  "zh_CN-huayan-x_low|zh/zh_CN/huayan/x_low/zh_CN-huayan-x_low.onnx.json|4164|39efce50e05f04893ae656f9008698f7"
)

case "$VARIANT" in
  x_low)  WANT="zh_CN-huayan-x_low" ;;
  medium) WANT="zh_CN-huayan-medium" ;;
  both)   WANT="zh_CN-huayan-x_low zh_CN-huayan-medium" ;;
  *) echo "用法: $0 [x_low|medium|both] [--push]" >&2; exit 2 ;;
esac

md5_of() {
  if command -v md5 >/dev/null 2>&1;   then md5 -q "$1"
  elif command -v md5sum >/dev/null 2>&1; then md5sum "$1" | awk '{print $1}'
  else echo ""; fi
}

mkdir -p "$OUT_DIR"
echo "源     : $BASE"
echo "输出   : $OUT_DIR"
echo "目标   : $WANT"
echo

fail=0
for rec in "${ALL_FILES[@]}"; do
  IFS='|' read -r vid relpath bytes md5 <<< "$rec"
  case " $WANT " in *" $vid "*) ;; *) continue ;; esac

  fname="$(basename "$relpath")"
  out="$OUT_DIR/$fname"

  echo "── $fname  (期望 ${bytes} bytes)"

  # 已存在且正确则跳过
  if [ -f "$out" ] && [ "$(md5_of "$out")" = "$md5" ]; then
    echo "   ✅ 已存在且校验通过，跳过"
    continue
  fi

  ok=0
  for try in $(seq 1 "$MAX_TRIES"); do
    echo "   ⬇️  第 $try/$MAX_TRIES 次尝试..."
    # -C -                        断点续传
    # --connect-timeout 20        连接阶段最多等 20s
    # --speed-limit/--speed-time  30s 内均速低于 1KB/s 视为卡死并中断
    #   ↑ hf-mirror 偶发无限挂起，必须靠这个才能快速失败并重试
    if curl -fL -C - -o "$out" \
         --no-progress-meter \
         --connect-timeout 20 \
         --speed-limit 1024 --speed-time 30 \
         --max-time 3600 \
         --retry 0 \
         "$BASE/$relpath"; then
      got="$(md5_of "$out")"
      if [ "$got" = "$md5" ]; then
        echo "   ✅ 校验通过 md5=$got"
        ok=1; break
      else
        echo "   ⚠️  MD5 不符 (得到 $got)，删除后续传重下"
        rm -f "$out"
      fi
    else
      echo "   ⚠️  下载中断（镜像偶发超时）"
    fi
    sleep 2
  done

  if [ "$ok" -ne 1 ]; then
    echo "   ❌ $fname 下载失败，已试 $MAX_TRIES 次" >&2
    fail=1
  fi
  echo
done

if [ "$fail" -ne 0 ]; then
  echo "❌ 有文件未成功，未执行 push" >&2
  exit 1
fi

echo "✅ 全部文件已下载并校验通过：$OUT_DIR"

if [ "$PUSH" -eq 1 ]; then
  echo
  echo "── adb push 到设备"
  if ! command -v adb >/dev/null 2>&1; then
    echo "❌ 找不到 adb，请手动 push。目标目录：$DEST_DIR_DEVICE" >&2
    exit 1
  fi
  adb shell mkdir -p "$DEST_DIR_DEVICE" || { echo "❌ 无法创建设备目录，设备是否已连接、应用是否已安装？" >&2; exit 1; }
  for rec in "${ALL_FILES[@]}"; do
    IFS='|' read -r vid relpath bytes md5 <<< "$rec"
    case " $WANT " in *" $vid "*) ;; *) continue ;; esac
    fname="$(basename "$relpath")"
    echo "   → $fname"
    adb push "$OUT_DIR/$fname" "$DEST_DIR_DEVICE/" >/dev/null || { echo "❌ push 失败: $fname" >&2; exit 1; }
  done
  echo
  echo "✅ 已推送到 $DEST_DIR_DEVICE"
  echo "   现在打开侍读郎 → Voices 页 → 选 Chinese (China) → 点选 huayan 语音"
else
  echo
  echo "下一步（手动推送）："
  echo "  adb shell mkdir -p $DEST_DIR_DEVICE"
  for rec in "${ALL_FILES[@]}"; do
    IFS='|' read -r vid relpath bytes md5 <<< "$rec"
    case " $WANT " in *" $vid "*) ;; *) continue ;; esac
    echo "  adb push $OUT_DIR/$(basename "$relpath") $DEST_DIR_DEVICE/"
  done
fi

#!/usr/bin/env bash
# 重新生成歌词字体子集（Noto Sans SC，SIL OFL 1.1）。
#
# 为什么要子集化：完整 CJK 字体单档 8MB，Medium+Bold 两档会让 APK 从 16MB
# 翻倍，而其中绝大部分字形（日文汉字、韩文谚文、生僻字）歌词永远用不到。
# 子集后单档 1.7MB。
#
# 覆盖范围：GB2312 全集（6763 汉字，覆盖现代简体中文用字）+ ASCII
# + 日文假名 + 中文标点。**子集之外的字渲染成豆腐块**，
# 要支持繁体/日文歌词必须改下面的 charset 生成逻辑重跑，改 Kotlin 没用。
#
# 用法：bash tools/subset-lyric-font.sh
set -euo pipefail

VERSION="Sans2.004"
WORK="$(mktemp -d)"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/res/font"
trap 'rm -rf "$WORK"' EXIT

echo "==> 准备 fonttools"
python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install --quiet fonttools brotli

echo "==> 下载 Noto Sans SC $VERSION"
curl -sL -o "$WORK/notosc.zip" \
  "https://github.com/notofonts/noto-cjk/releases/download/$VERSION/18_NotoSansSC.zip"
unzip -o -q "$WORK/notosc.zip" -d "$WORK" \
  NotoSansSC-Medium.otf NotoSansSC-Bold.otf LICENSE

echo "==> 生成字符集"
python3 - "$WORK/charset.txt" <<'PY'
import sys
chars = set()
# GB2312 一级+二级汉字
for b1 in range(0xB0, 0xF8):
    for b2 in range(0xA1, 0xFF):
        try: chars.add(bytes([b1, b2]).decode('gb2312'))
        except UnicodeDecodeError: pass
# GB2312 符号区
for b1 in range(0xA1, 0xAA):
    for b2 in range(0xA1, 0xFF):
        try: chars.add(bytes([b1, b2]).decode('gb2312'))
        except UnicodeDecodeError: pass
chars |= {chr(c) for c in range(0x20, 0x7F)}        # ASCII
chars |= {chr(c) for c in range(0x3040, 0x3100)}     # 平假名 + 片假名
chars |= set('　·—…‘’“”〈〉《》「」『』【】〔〕♪♫∶')
open(sys.argv[1], 'w', encoding='utf-8').write(''.join(sorted(chars)))
print(f'   字符数: {len(chars)}')
PY

echo "==> 子集化"
for w in Medium Bold; do
  "$WORK/venv/bin/pyftsubset" "$WORK/NotoSansSC-$w.otf" \
    --text-file="$WORK/charset.txt" \
    --output-file="$DEST/noto_sans_sc_$(echo "$w" | tr '[:upper:]' '[:lower:]').ttf" \
    --layout-features='*' --drop-tables+=BASE,JSTF,DSIG --no-hinting
done

cp "$WORK/LICENSE" "$(dirname "$DEST")/../assets/LICENSE_NotoSansSC.txt"
ls -la "$DEST"
echo "==> 完成"

#!/usr/bin/env python3
"""
生成 LaughBox 的各密度图标。

输入：大笑视频里的一帧（app/assets/laugh.mp4）
输出：
    app/res/mipmap-<dpi>/ic_launcher.png        传统方形图标
    app/res/mipmap-<dpi>/ic_launcher_round.png 圆形图标
    app/res/mipmap-<dpi>/ic_launcher_fg.png     自适应图标前景
    app/res/mipmap-<dpi>/ic_launcher_bg.png     自适应图标背景

抠像要点：源图是白底，直接按亮度做 key 会**留下地面投影**。
改用饱和度区分 —— 投影是低饱和灰 (sat≈5-20)，奶白肚子饱和度 ≈30，
所以 `min>198 且 饱和度<24` 能干净地去掉投影而不伤到角色。

用法：
    python3 tools/make_icons.py [帧号，默认 112]
"""

import os
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

WHITE = 198      # 亮度阈值
SAT = 24         # 饱和度阈值（投影 < 24，肚子 ≈30）
MARGIN = 0.60    # 角色占画布比例


def main():
    src = os.path.join(ROOT, "app/assets/laugh.mp4")
    frame = int(sys.argv[1]) if len(sys.argv) > 1 else 112

    tmp = tempfile.mktemp(suffix=".png")
    subprocess.run(["ffmpeg", "-v", "error", "-i", src,
                    "-vf", f"select='eq(n\\,{frame})'",
                    "-frames:v", "1", tmp, "-y"], check=True)

    im = Image.open(tmp).convert("RGB")
    os.remove(tmp)

    # 找角色包围盒（排除画面边缘的 UI 残留）
    a = np.asarray(im).astype(int)
    nz = a.sum(axis=2) < 735
    core = nz.copy()
    core[:30, :] = False
    core[-30:, :] = False
    core[:, :30] = False
    core[:, -30:] = False
    ys, xs = np.where(core)
    pad = 14
    char = im.crop((max(0, xs.min() - pad), max(0, ys.min() - pad),
                    min(im.width, xs.max() + 1 + pad),
                    min(im.height, ys.max() + 1 + pad)))

    # 抠像：低饱和的偏亮像素 = 投影/白底
    arr = np.asarray(char).astype(np.int16)
    mn = arr.min(axis=2)
    sat = arr.max(axis=2) - mn
    alpha = np.where((mn > WHITE) & (sat < SAT), 0, 255).astype(np.uint8)
    am = Image.fromarray(alpha).filter(ImageFilterBlur()).point(lambda v: 0 if v < 90 else v)
    rgba = char.convert("RGBA")
    rgba.putalpha(am)

    s = int(512 * MARGIN) / max(rgba.width, rgba.height)
    ch = rgba.resize((max(1, int(rgba.width * s)),
                      max(1, int(rgba.height * s))), Image.LANCZOS)

    # 橙色渐变背景
    bg = Image.new("RGBA", (512, 512))
    d = ImageDraw.Draw(bg)
    for y in range(512):
        t = y / 511
        d.line([(0, y), (512, y)],
               fill=(int(255 - 16 * t), int(184 - 38 * t), int(64 - 18 * t), 255))

    fg = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    fg.paste(ch, ((512 - ch.width) // 2, (512 - ch.height) // 2), ch)
    full = Image.alpha_composite(bg, fg)

    for dpi, size in LEGACY.items():
        outdir = os.path.join(ROOT, f"app/res/mipmap-{dpi}")
        os.makedirs(outdir, exist_ok=True)
        full.resize((size, size), Image.LANCZOS) \
            .save(os.path.join(outdir, "ic_launcher.png"))
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            [0, 0, size - 1, size - 1], radius=int(size * 0.19), fill=255)
        ic = full.convert("RGBA").resize((size, size), Image.LANCZOS)
        ic.putalpha(mask)
        ic.save(os.path.join(outdir, "ic_launcher_round.png"))

        a_size = ADAPTIVE[dpi]
        fg.resize((a_size, a_size), Image.LANCZOS) \
            .save(os.path.join(outdir, "ic_launcher_fg.png"))
        bg.resize((a_size, a_size), Image.LANCZOS) \
            .save(os.path.join(outdir, "ic_launcher_bg.png"))
        print(f"    mipmap-{dpi}: {size}px + adaptive {a_size}px")

    print("✅ 图标生成完毕")


# 单独抽出来方便看清
def ImageFilterBlur():
    from PIL import ImageFilter
    return ImageFilter.GaussianBlur(1.0)


if __name__ == "__main__":
    main()

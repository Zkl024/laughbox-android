#!/usr/bin/env python3
"""
从一段 iOS 录屏中提取 LaughBox 所需的素材。

输入：一段手机录屏（原 App 全屏录制），720x1280，30fps
输出：
    app/res/drawable-nodpi/hero_idle.png   待机图   720x1260
    app/assets/laugh.mp4                    大笑动画 720x1260 H.264+AAC

处理步骤：
    1. 定位 App 内容区（去掉两侧黑边）
    2. 裁成 4:7，抹掉 iOS 状态栏 / 录制红点 / 滚动条等残留
    3. 用自相关找出原动画的循环周期，取一个完整周期
    4. 待机图与大笑视频用完全相同的裁切框，保证点击切换不跳变

依赖：ffmpeg、ffprobe、numpy、Pillow
用法：
    python3 tools/extract_assets.py 录屏.mp4
"""

import os
import subprocess
import sys
import tempfile
import wave
import struct
import math

import numpy as np
from PIL import Image

# ── 裁切参数（针对 720x1280 竖屏录屏）──────────────────────────────
CROP_X, CROP_W = 66, 588          # App 内容区：两侧各有 66px 黑边
CROP_Y, CROP_H = 40, 1028
OUT_W, OUT_H = 1080, 1890         # 4:7
#   1080x1890 → 宏块 68x119 = 8092 ≤ MaxFS 8192（Level 4.0 起）
#   60fps → MBPS = 8092 x 60 = 485,520 > 4.0/4.1 的 245,760
#   → 必须 Level 4.2（MaxMBPS 522,240）。老设备硬解不了 1080p60，
#     app 内 onError 会 toast「这台设备放不出来…」。
#     若要覆盖 Android 5.0–6.0，把 OUT_W/H 降回 720x1260 且 Level 3.1。

# 静音段通常就是待机段；笑声响起点即循环动画起点
IDLE_SEC = 1.0
LOOP_FRAMES = 170                 # 扫描得到的循环周期（30fps → 5.67s）
FPS = 30
FPS_OUT = 60                      # 目标帧率，30fps 录屏靠 minterpolate 插帧


def run(cmd):
    print("  $", " ".join(cmd))
    subprocess.run(cmd, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def grab(src, t, out):
    run(["ffmpeg", "-v", "error", "-ss", f"{t}", "-i", src,
         "-frames:v", "1", "-q:v", "2", out, "-y"])


def audio_rms(src, dur=0.5):
    """逐段 RMS，用来定位待机/大笑的分界"""
    tmp = tempfile.mktemp(suffix=".wav")
    run(["ffmpeg", "-v", "error", "-i", src, "-ac", "1", "-ar", "16000",
         "-f", "wav", tmp, "-y"])
    w = wave.open(tmp)
    n, sr = w.getnframes(), w.getframerate()
    d = struct.unpack("<%dh" % n, w.readframes(n))
    os.remove(tmp)
    seg = int(sr * dur)
    out = []
    for i in range(n // seg):
        c = d[i * seg:(i + 1) * seg]
        if c:
            out.append(math.sqrt(sum(x * x for x in c) / len(c)))
    return out


def find_laugh_start(src):
    """第一段响亮音频 = 笑声响起的时刻"""
    rms = audio_rms(src)
    for i, v in enumerate(rms):
        if v > 2000:
            return i * 0.5
    return 2.0


def clean_crop(src, dst):
    """裁切 + 抹掉 UI 残留

    关键坑：ffmpeg 的 pad 默认填充 **黑色**，必须显式 color=white，
    否则画面底部会留黑边。
    """
    vf = (
        f"crop={CROP_W}:{CROP_H}:{CROP_X}:{CROP_Y},"
        f"drawbox=x=0:y=0:w={CROP_W}:h=24:color=white@1:t=fill,"      # 状态栏/红点
        f"drawbox=x=0:y=884:w=24:h=134:color=white@1:t=fill"          # 左侧滚动条
    )
    run(["ffmpeg", "-v", "error", "-i", src, "-vf", vf,
         "-c:v", "libx264", "-crf", "14", "-preset", "veryfast",
         "-pix_fmt", "yuv420p", dst, "-y"])


def verify_clean(path, times=(0.5, 2.5, 4.4, 7.0, 9.5, 12.0)):
    """确认四周没有残留的非白像素"""
    for t in times:
        p = tempfile.mktemp(suffix=".png")
        grab(path, t, p)
        a = np.asarray(Image.open(p).convert("RGB")).astype(int)
        nz = a.sum(axis=2) < 735
        top, left, bot = nz[:26].sum(), nz[:, :26].sum(), nz[-6:].sum()
        os.remove(p)
        flag = "OK" if (top == 0 and left == 0 and bot == 0) else "残留!"
        print(f"    t={t:>5}s  顶{top:<4} 左{left:<4} 底{bot:<4} {flag}")
    return True


def find_cycle(clean, start, total):
    """自相关扫描：找使 f[i]≈f[i+P] 的 P，即原动画的无缝循环周期"""
    tmp = tempfile.mkdtemp()
    run(["ffmpeg", "-v", "error", "-ss", f"{start}", "-t", f"{total}",
         "-i", clean, "-vf", f"fps={FPS},scale=72:126:flags=area",
         os.path.join(tmp, "c%04d.png"), "-y"])
    fs = sorted(os.listdir(tmp))
    F = np.stack([np.asarray(Image.open(os.path.join(tmp, f)).convert("L"),
                                dtype=np.float32) for f in fs])
    N = len(F)
    best = min(((P, np.abs(F[:N - P] - F[P:]).mean())
                for P in range(60, min(190, N))), key=lambda z: z[1])
    d = [np.abs(F[i] - F[i + best[0]]).mean() for i in range(0, N - best[0], 3)]
    s0 = int(np.argmin(d)) * 3
    for f in fs:
        os.remove(os.path.join(tmp, f))
    os.rmdir(tmp)
    return best[0], best[1], s0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    src = sys.argv[1]
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    print("▸ 分析音轨，定位笑声响起点")
    start = find_laugh_start(src)
    print(f"    笑声起点 ≈ {start}s")

    tmp = tempfile.mkdtemp()
    clean = os.path.join(tmp, "clean.mp4")

    print("▸ 裁切并抹除 UI 残留")
    clean_crop(src, clean)
    verify_clean(clean)

    print("▸ 扫描原动画的循环周期")
    P, diff, s0 = find_cycle(clean, start, 10.68)
    dur = P / FPS
    t0 = start + s0 / FPS
    print(f"    周期 P={P} 帧 = {dur:.2f}s，首尾差 {diff:.3f}")
    print(f"    起点 t={t0:.2f}s")

    print("▸ 生成待机图")
    idle_src = os.path.join(tmp, "idle.jpg")
    grab(clean, IDLE_SEC, idle_src)
    im = Image.open(idle_src).convert("RGB")
    # 必须与视频走**完全相同**的缩放目标（OUT_W x OUT_H），
    # 否则待机图比视频小 0.1%，点击切换时角色会轻微跳动。
    im = im.resize((OUT_W, OUT_H), Image.LANCZOS)
    canvas = Image.new("RGB", (OUT_W, OUT_H), (255, 255, 255))
    canvas.paste(im, (0, 0))
    idle_out = os.path.join(root, "app/res/drawable-nodpi/hero_idle.png")
    os.makedirs(os.path.dirname(idle_out), exist_ok=True)
    canvas.save(idle_out, optimize=True)
    print(f"    → {idle_out}  {OUT_W}x{OUT_H}")

    print("▸ 生成大笑视频（保留原声）")
    laugh_out = os.path.join(root, "app/assets/laugh.mp4")
    os.makedirs(os.path.dirname(laugh_out), exist_ok=True)
    # 关键坑 1：minterpolate 固定少吐 3 帧（171 帧 30fps → 339 帧而非 342），
    #          循环会短 1 帧、首尾对不齐，接缝处有顿挫。
    #          解决办法：插帧**前**用 tpad 给输入补 1 帧（克隆末帧 ≈ 起点帧），
    #          输出正好 341 帧 = 340 周期 + 1，末帧回到起点姿态。
    # 关键坑 2：不要在 minterpolate 之后再接 fps=60 去凑帧数——
    #          那会让 fps 滤镜**复制**末帧而不是插值，尾几帧被冻住。
    #          实测冻结版 wrap 差 3.85（等于原 30fps 的 frame-169 相位），
    #          tpad 版 wrap 差 2.035（噪声底噪，与 v1.0.1 的 2.041 齐平）。
    run(["ffmpeg", "-v", "error",
         "-ss", f"{t0:.3f}", "-t", f"{dur:.3f}", "-i", clean,
         "-ss", f"{t0:.3f}", "-t", f"{dur:.3f}", "-i", src,
         "-filter_complex",
         f"[0:v]scale={OUT_W}:{OUT_H}:flags=lanczos,"
         f"tpad=stop_mode=clone:stop_duration={1 / FPS:.4f},"
         f"minterpolate=fps={FPS_OUT}:mi_mode=mci:mc_mode=aobmc:"
         f"me_mode=bidir:vsbmc=1[v];"
         "[1:a]loudnorm=I=-16:TP=-1.5:LRA=11,aresample=44100[a]",
         "-map", "[v]", "-map", "[a]",
         "-c:v", "libx264", "-profile:v", "baseline", "-level", "4.2",
         "-pix_fmt", "yuv420p", "-crf", "20", "-preset", "slow",
         "-movflags", "+faststart",
         "-c:a", "aac", "-b:a", "64k", "-ar", "44100", "-ac", "1",
         laugh_out, "-y"])
    print(f"    → {laugh_out}  {os.path.getsize(laugh_out)} 字节")

    for f in os.listdir(tmp):
        os.remove(os.path.join(tmp, f))
    os.rmdir(tmp)
    print("\n✅ 完成，接着跑 ./build.sh 打包")


if __name__ == "__main__":
    main()

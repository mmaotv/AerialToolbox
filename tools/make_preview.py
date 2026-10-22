"""按 activity_main.xml 的 dp 数值 1:1 还原版式示意图，用于 README。

这是设计稿，不是运行截图：尺寸与间距按布局文件精确换算（960x540dp，即 1080p /
densityDpi 320 的电视），字形用系统微软雅黑近似（设备上由 Noto Sans CJK 渲染）。

工具箱现在支持两个上游，顶栏可以切换，卡片也随之换一套，所以各出一张：

    python tools/make_preview.py            # 两张都出
    python tools/make_preview.py aerial     # 只出 docs/screenshot-main.png
    python tools/make_preview.py cleantv    # 只出 docs/screenshot-cleantv.png
"""
import os
import re
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

W, H = 960, 540
K = 2                      # 输出放大倍数
PRIMARY = (255, 255, 255)
SECOND = (0xEB, 0xEB, 0xF5, 0x99)
TERTIARY = (0xEB, 0xEB, 0xF5, 0x4D)
FILL = (0x78, 0x78, 0x80, 0x2E)
HAIRLINE = (0xFF, 0xFF, 0xFF, 0x1A)
FOCUS = (0xF5, 0xF5, 0xF7)
ON_FOCUS = (0x1D, 0x1D, 0x1F)
BRAND = [(0x2F, 0xD0, 0xA5), (0x5B, 0x5B, 0xF0)]

CN = r"C:\Windows\Fonts\msyh.ttc"
CN_B = r"C:\Windows\Fonts\msyhbd.ttc"


def font(size, bold=False):
    return ImageFont.truetype(CN_B if bold else CN, int(size * K))


def text(d, xy, s, size, color, bold=False, anchor="la"):
    d.text((xy[0] * K, xy[1] * K), s, font=font(size, bold), fill=color, anchor=anchor)


def round_rect(d, box, radius, fill=None, outline=None, width=1):
    x0, y0, x1, y1 = [v * K for v in box]
    d.rounded_rectangle([x0, y0, x1, y1], radius=radius * K,
                        fill=fill, outline=outline, width=int(width * K))


def gradient_box(size, horizontal=True):
    w, h = size
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    t = (x / max(w - 1, 1)) if horizontal else (y / max(h - 1, 1))
    t = t[..., None]
    a, b = np.array(BRAND[0], np.float32), np.array(BRAND[1], np.float32)
    rgb = a + (b - a) * t
    alpha = np.full((h, w, 1), 255.0)
    return Image.fromarray(np.concatenate([rgb, alpha], 2).astype(np.uint8), "RGBA")


# ---------- 两个目标各自的卡片文案（对齐 MainActivity 的 actions 列表） ----------

AERIAL_CARDS = [
    ("一键安装 / 升级桌面", "内置 v1.30，离线直装"),
    ("桌面更新", "查 GitHub 新版，看日志再升级"),
    ("修复接管", "主页键失灵、守护掉线时用"),
    ("验收检查", "确认主页键是否进入 Aerial"),
    ("安装通道：标准版", "正常声明 HOME（小米推荐）"),
    ("ADB 常开：开", "待机唤醒后自动恢复 ADB"),
    ("屏保接管：开", "把系统屏保换成航拍（TCL）"),
    ("还原 / 卸载", "撤销接管，可一并卸载"),
    ("关于 / 更新工具箱", "v1.3.1 · 自更新与版本日志"),
]

CLEANTV_CARDS = [
    ("一键安装 / 升级桌面", "内置 v2.6，离线直装"),
    ("桌面更新", "查本仓库代构建的新版"),
    ("修复接管", "主页跑偏、被原桌面抢回时用"),
    ("验收检查", "确认主页归属与系统版本"),
    ("输入源键：开", "遥控器输入源键可用"),
    ("Home 键兼容：开", "小米等 Home 发成 ALL_APPS"),
    ("屏保接管：开", "换成 CleanYourTV 时钟"),
    ("还原 / 卸载", "恢复原桌面，可一并卸载"),
    ("关于 / 更新工具箱", "v1.3.1 · 自更新与版本日志"),
]

TARGETS = {
    "aerial": dict(
        out="screenshot-main.png",
        info=[("设备", "Xiaomi TV · Android 9"),
              ("桌面状态", "已安装 v1.30"),
              ("内置离线包", "v1.30（2026-10-22）")],
        cards=AERIAL_CARDS,
        log=["安装完成：1.30"],
    ),
    "cleantv": dict(
        out="screenshot-cleantv.png",
        info=[("设备", "Xiaomi TV · Android 9"),
              ("桌面状态", "已安装 v2.6"),
              ("内置离线包", "v2.6（Android 5.1+）")],
        cards=CLEANTV_CARDS,
        log=["目标桌面切换为 CleanYourTV"],
    ),
}


def render(key):
    spec = TARGETS[key]

    # ---------- 画布：纯黑 + 顶部品牌辉光 ----------
    img = Image.new("RGBA", (W * K, H * K), (0, 0, 0, 255))
    yy, xx = np.mgrid[0:H * K, 0:W * K].astype(np.float32)
    t = np.clip(yy / (H * K * 0.55), 0, 1)[..., None]
    glow = np.zeros((H * K, W * K, 4), np.uint8)
    glow[..., 0], glow[..., 1], glow[..., 2] = 0x5B, 0x5B, 0xF0
    glow[..., 3] = ((1 - t) * 26).astype(np.uint8)[..., 0]
    img = Image.alpha_composite(img, Image.fromarray(glow, "RGBA"))
    d = ImageDraw.Draw(img)

    # ---------- 顶栏 ----------
    round_rect(d, (40, 27, 78, 65), 10,
               fill=tuple(BRAND[0]) + (255,))          # 底色用品牌青，内部再叠渐变
    mark_bg = gradient_box((int(38 * K), int(38 * K)))
    mask = Image.new("L", mark_bg.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, mark_bg.size[0] - 1, mark_bg.size[1] - 1],
                                           radius=10 * K, fill=255)
    img.paste(mark_bg, (40 * K, 27 * K), mask)
    d = ImageDraw.Draw(img)
    # 拱弧 + 底座线
    arc = [(40 + 38 * 0.35, 27 + 38 * 0.70), (40 + 38 * 0.42, 27 + 38 * 0.42),
           (40 + 38 * 0.50, 27 + 38 * 0.32), (40 + 38 * 0.58, 27 + 38 * 0.42),
           (40 + 38 * 0.65, 27 + 38 * 0.70)]
    d.line([(p[0] * K, p[1] * K) for p in arc], fill=FOCUS + (255,), width=int(2.4 * K), joint="curve")
    d.line([(46.5 * K, 60.5 * K), (71.5 * K, 60.5 * K)], fill=FOCUS + (255,), width=int(2.4 * K))

    text(d, (92, 26), "桌面工具箱", 26, PRIMARY, bold=True)
    text(d, (93, 55), "安装 / 接管 / 升级 电视桌面", 11, SECOND)

    # 目标桌面切换：两个上游互斥，选中的那个是实底白字
    chip_y0, chip_y1 = 31, 65
    for label, x0, x1, on in (("Aerial", 466, 534, key == "aerial"),
                              ("CleanYourTV", 540, 648, key == "cleantv")):
        if on:
            round_rect(d, (x0, chip_y0, x1, chip_y1), 16, fill=FILL, outline=HAIRLINE, width=1)
        text(d, ((x0 + x1) / 2, 48), label, 13,
             PRIMARY if on else (0xEB, 0xEB, 0xF5, 0xB0), anchor="mm")

    # 状态胶囊
    chip_w = 150
    round_rect(d, (920 - 100 - 10 - chip_w, 31, 920 - 110, 64), 16,
               fill=FILL, outline=HAIRLINE, width=1)
    text(d, (920 - 100 - 10 - chip_w + 16, 40), "●", 13, (0x30, 0xD1, 0x58))
    text(d, (920 - 100 - 10 - chip_w + 34, 39), "调试通道已连接", 13, PRIMARY)
    # 重新连接按钮
    round_rect(d, (820, 26, 920, 66), 12, fill=FILL, outline=HAIRLINE, width=1)
    text(d, (870, 46), "重新连接", 13, PRIMARY, anchor="mm")

    # ---------- 三块信息 ----------
    info = spec["info"]
    col_w = (880 - 20) / 3
    for i, (label, value) in enumerate(info):
        x = 40 + i * (col_w + 10)
        round_rect(d, (x, 84, x + col_w, 145), 18, fill=FILL, outline=HAIRLINE, width=1)
        text(d, (x + 16, 94), label, 11, SECOND)
        text(d, (x + 16, 113), value, 15, PRIMARY)

    # ---------- 卡片网格 3 x 3 ----------
    grid_top, grid_bottom = 153, 488
    row_h = (grid_bottom - grid_top) / 3
    cw = (880 - 6 * 6) / 3
    for idx, (title, sub) in enumerate(spec["cards"]):
        row, col = idx // 3, idx % 3
        x = 40 + 6 + col * (cw + 12)
        y = grid_top + 6 + row * row_h
        focused = idx == 0
        round_rect(d, (x, y, x + cw, y + row_h - 12), 20,
                   fill=FOCUS if focused else FILL,
                   outline=None if focused else HAIRLINE, width=1)
        ty = y + 26
        fill_t = ON_FOCUS if focused else PRIMARY
        fill_s = (ON_FOCUS + (0x99,)) if focused else SECOND
        text(d, (x + 20, ty), title, 19, fill_t, bold=True)
        # 说明最多两行：中文按字断、英文单词整体不拆（与 Android TextView 一致）
        limit = 18
        tokens = re.findall(r"[A-Za-z0-9][A-Za-z0-9./+:_-]*|.", sub)
        lines, cur = [], ""
        for t in tokens:
            if len(cur) + len(t) > limit and cur:
                lines.append(cur)
                cur = ""
            cur += t
        if cur:
            lines.append(cur)
        for li, ln in enumerate(lines[:2]):
            text(d, (x + 20, ty + 32 + li * 19), ln, 13, fill_s)

    # ---------- 底部日志 ----------
    for i, line in enumerate(spec["log"]):
        text(d, (40, 500 + i * 17), line, 11, TERTIARY)

    out = os.path.join(ROOT, "docs", spec["out"])
    flat = Image.alpha_composite(Image.new("RGBA", img.size, (0, 0, 0, 255)), img)
    flat.convert("RGB").resize((W, H), Image.LANCZOS).save(out, quality=95)
    print("wrote", out)


if __name__ == "__main__":
    keys = sys.argv[1:] or ["aerial", "cleantv"]
    for k in keys:
        render(k)

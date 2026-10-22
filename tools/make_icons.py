"""生成 Aerial 工具箱的图标与 TV banner。

图形语言严格对齐上游 aerial-tv/docs/brand/icon.svg：
  45° 线性渐变 #2FD0A5 -> #5B5BF0，白色圆头拱弧 stroke 5.4/108
工具箱的差异只有一条底座横线，读作"把桌面装上去"。

用法（需要 Pillow 与 numpy）：
    python tools/make_icons.py

写成 PNG 而不是只用 vector，是因为 minSdk 21 要走各密度位图兜底；
API 26 以上由 res/mipmap-anydpi-v26/ic_launcher.xml 的自适应图标接管。
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res")
SS = 8                      # 超采样倍数
V = 108                     # 设计画布
BRAND = [(0x2F, 0xD0, 0xA5), (0x5B, 0x5B, 0xF0)]

# 上游拱弧 + 我们的底座线（均为 108 画布坐标）
ARC = dict(
    pts=[(33.75, 65.25), (40.5, 51.75), (47.25, 45.0), (54.0, 45.0),
         (54.0, 45.0), (67.5, 51.75), (74.25, 65.25)],
    width=5.4,
)
BASE = dict(a=(42.0, 78.5), b=(66.0, 78.5), width=5.4)


def bezier(p0, p1, p2, p3, n=140):
    t = np.linspace(0, 1, n)[:, None]
    p = ((1 - t) ** 3) * np.array(p0) + 3 * ((1 - t) ** 2) * t * np.array(p1) \
        + 3 * (1 - t) * t ** 2 * np.array(p2) + t ** 3 * np.array(p3)
    return [tuple(q) for q in p]


def arc_points():
    p = ARC["pts"]
    return bezier(p[0], p[1], p[2], p[3]) + bezier(p[3], p[4], p[5], p[6])[1:]


def gradient(size):
    """45° 渐变，起点 (18,18)、终点 (90,90)，与 SVG 的 gradientUnits 一致。"""
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    s = size / V
    t = ((x - 18 * s) + (y - 18 * s)) / (2 * 72 * s)
    t = np.clip(t, 0, 1)[..., None]
    a = np.array(BRAND[0], dtype=np.float32)
    b = np.array(BRAND[1], dtype=np.float32)
    rgb = a + (b - a) * t
    alpha = np.full((size, size, 1), 255.0)
    return Image.fromarray(np.concatenate([rgb, alpha], axis=2).astype(np.uint8), "RGBA")


def render_icon(size, rounded=None):
    """绘制图标主体。rounded 为圆角半径（108 坐标），None 表示直角（自适应图标前景用）。"""
    px = size * SS
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    s = px / V

    bg = gradient(px)
    if rounded is not None:
        mask = Image.new("L", (px, px), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            [0, 0, px - 1, px - 1], radius=int(rounded * s), fill=255)
        img.paste(bg, (0, 0), mask)
    else:
        img.paste(bg, (0, 0))

    d = ImageDraw.Draw(img)
    w = max(2, int(round(ARC["width"] * s)))
    pts = [(x * s, y * s) for x, y in arc_points()]
    d.line(pts, fill=(255, 255, 255, 255), width=w, joint="curve")
    for q in (pts[0], pts[-1]):          # 圆头线帽
        d.ellipse([q[0] - w / 2, q[1] - w / 2, q[0] + w / 2, q[1] + w / 2],
                  fill=(255, 255, 255, 255))

    a = (BASE["a"][0] * s, BASE["a"][1] * s)
    b = (BASE["b"][0] * s, BASE["b"][1] * s)
    d.line([a, b], fill=(255, 255, 255, 255), width=w)
    for q in (a, b):
        d.ellipse([q[0] - w / 2, q[1] - w / 2, q[0] + w / 2, q[1] + w / 2],
                  fill=(255, 255, 255, 255))

    return img.resize((size, size), Image.LANCZOS)


def write(path, img):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", path, img.size)


def banner(width=320, height=180):
    """TV 桌面横幅：黑底 + 渐变圆角标 + 英文名（Inter 不含中文，故用拉丁字标）。"""
    k = 4
    W, H = width * k, height * k
    img = Image.new("RGBA", (W, H), (0, 0, 0, 255))

    # 左侧品牌辉光：柔和的径向衰减，不能出现硬边
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    cx, cy = W * 0.16, H * 0.5
    r = np.sqrt(((xx - cx) / (W * 0.62)) ** 2 + ((yy - cy) / (H * 0.95)) ** 2)
    a = np.clip(1.0 - r, 0, 1) ** 2 * 46
    glow = np.zeros((H, W, 4), dtype=np.uint8)
    glow[..., 0], glow[..., 1], glow[..., 2] = 0x5B, 0x5B, 0xF0
    glow[..., 3] = a.astype(np.uint8)
    img = Image.alpha_composite(img, Image.fromarray(glow, "RGBA"))

    mark = render_icon(int(H * 0.52), rounded=24)
    mx, my = int(W * 0.10), (H - mark.size[1]) // 2
    img.alpha_composite(mark, (mx, my))

    font_path = os.path.join(ROOT, "app", "src", "main", "assets", "fonts", "inter.ttf")
    # Inter 的变体轴顺序是 [opsz, wght]
    f1 = ImageFont.truetype(font_path, int(H * 0.21))
    f1.set_variation_by_axes([32, 600])
    f2 = ImageFont.truetype(font_path, int(H * 0.10))
    f2.set_variation_by_axes([14, 600])

    d = ImageDraw.Draw(img)
    tx = mx + mark.size[0] + int(W * 0.055)
    d.text((tx, int(H * 0.36)), "Aerial", font=f1, fill=(255, 255, 255, 255), anchor="lm")
    d.text((tx + 2, int(H * 0.60)), "TOOLBOX", font=f2,
           fill=(0x2F, 0xD0, 0xA5, 255), anchor="lm")
    return img.resize((width, height), Image.LANCZOS)


if __name__ == "__main__":
    for folder, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                         ("xxhdpi", 144), ("xxxhdpi", 192)]:
        write(os.path.join(OUT, f"mipmap-{folder}", "ic_launcher.png"),
              render_icon(size, rounded=22))

    # 自适应图标前景用不透明白弧（渐变由 background 层提供），安全区内留足余量
    fg = 432
    f = Image.new("RGBA", (fg, fg), (0, 0, 0, 0))
    s = fg / V
    d = ImageDraw.Draw(f)
    w = max(2, int(round(ARC["width"] * s)))
    pts = [(x * s, y * s) for x, y in arc_points()]
    d.line(pts, fill=(255, 255, 255, 255), width=w, joint="curve")
    for q in (pts[0], pts[-1]):
        d.ellipse([q[0] - w / 2, q[1] - w / 2, q[0] + w / 2, q[1] + w / 2],
                  fill=(255, 255, 255, 255))
    write(os.path.join(OUT, "drawable-xxxhdpi", "ic_launcher_foreground.png"), f)

    write(os.path.join(OUT, "drawable-xhdpi", "banner.png"), banner())

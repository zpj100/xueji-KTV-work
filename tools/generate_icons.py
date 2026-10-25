#!/usr/bin/env python3
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path("/workspace/xueji-KTV")
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
BG = (255, 215, 168, 255)
FG = (92, 42, 12, 255)
ACCENT = (232, 140, 64, 255)

def font(size):
    return ImageFont.truetype(FONT, size)

def draw_two_line(size, radius=None, circle=False):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    if circle:
        draw.ellipse((0, 0, size - 1, size - 1), fill=BG)
        ring = max(2, size // 32)
        draw.ellipse((ring, ring, size - 1 - ring, size - 1 - ring), outline=ACCENT, width=ring)
    else:
        r = radius if radius is not None else max(8, size // 8)
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=r, fill=BG)
        draw.rounded_rectangle((1, 1, size - 2, size - 2), radius=r, outline=ACCENT, width=max(2, size // 48))
    title = font(int(size * 0.34))
    sub = font(int(size * 0.22))
    def center(text, y, f):
        box = draw.textbbox((0, 0), text, font=f)
        x = (size - (box[2] - box[0])) / 2
        draw.text((x, y), text, font=f, fill=FG)
    center("学记", int(size * 0.18), title)
    center("KTV", int(size * 0.58), sub)
    return img

def save_icon(path, size, circle=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    draw_two_line(size, circle=circle).save(path, "PNG")

def save_banner(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    w, h = 320, 180
    img = Image.new("RGBA", (w, h), BG)
    draw = ImageDraw.Draw(img)
    draw.rectangle((0, 0, w - 1, h - 1), outline=ACCENT, width=4)
    title = font(54)
    sub = font(36)
    t = "学记"
    box = draw.textbbox((0, 0), t, font=title)
    draw.text(((w - (box[2] - box[0])) / 2, 38), t, font=title, fill=FG)
    s = "KTV"
    box = draw.textbbox((0, 0), s, font=sub)
    draw.text(((w - (box[2] - box[0])) / 2, 108), s, font=sub, fill=FG)
    img.save(path, "PNG")

sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
res = ROOT / "app/src/main/res"
for name, px in sizes.items():
    save_icon(res / f"mipmap-{name}" / "ic_launcher.png", px, circle=False)
    save_icon(res / f"mipmap-{name}" / "ic_launcher_round.png", px, circle=True)
save_icon(res / "drawable-nodpi" / "ktv_logo.png", 512, circle=False)
save_banner(res / "drawable-xhdpi" / "tv_launcher_banner.png")
print("icons ok")

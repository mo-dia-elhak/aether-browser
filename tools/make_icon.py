"""Regenerates the Æther launcher icons (stylized lowercase 'æ'). Needs Pillow + a serif italic font."""
from PIL import Image, ImageDraw, ImageFont
import sys
FONT = sys.argv[1] if len(sys.argv) > 1 else "InstrumentSerif-Italic.ttf"
RES = "../app/src/main/res"

def glyph(size, scale, with_bg):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    if with_bg:   # legacy full icon: rounded gradient square
        bg = Image.new("RGBA", (size, size))
        px = bg.load()
        for y in range(size):
            for x in range(size):
                t = (x + y) / (2 * size)
                px[x, y] = (int(10 + 90 * t), int(132 - 40 * t), 255, 255)
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=size // 4.5, fill=255)
        img.paste(bg, (0, 0), mask)
    f = ImageFont.truetype(FONT, int(size * scale))
    d = ImageDraw.Draw(img)
    w = d.textbbox((0, 0), "æ", font=f)
    x = (size - (w[2] - w[0])) / 2 - w[0]
    y = (size - (w[3] - w[1])) / 2 - w[1]
    d.text((x, y), "æ", font=f, fill=(255, 255, 255, 255))
    return img

for dens, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    glyph(px, 0.80, True).save(f"{RES}/mipmap-{dens}/ic_launcher.png")
    fg = int(px * 108 / 48)   # adaptive foreground is 108dp; glyph kept inside the 66dp safe zone
    glyph(fg, 0.46, False).save(f"{RES}/mipmap-{dens}/ic_launcher_foreground.png")
glyph(512, 0.80, True).save("playstore_icon_512.png")

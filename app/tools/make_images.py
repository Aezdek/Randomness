#!/usr/bin/env python3
"""Draws the app icon and launch images into Resources/. Run again after changing the design."""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "Resources")
BG = (11, 11, 16)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def icon(px):
    s = 1024
    img = Image.new("RGB", (s, s))
    top, bottom = (255, 84, 120), (122, 64, 255)
    d = ImageDraw.Draw(img)
    for y in range(s):
        d.line([(0, y), (s, y)], fill=lerp(top, bottom, y / s))
    # Soft glow and a double note, like the in-app placeholder.
    glow = Image.new("L", (s, s), 0)
    ImageDraw.Draw(glow).ellipse([s * 0.18, s * 0.12, s * 0.9, s * 0.84], fill=70)
    img.paste((255, 255, 255), mask=glow.filter(ImageFilter.GaussianBlur(120)))
    d = ImageDraw.Draw(img)
    w = (255, 255, 255)
    lw = int(s * 0.07)
    def circle(cx, cy, r):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=w)
    circle(s * 0.34, s * 0.71, s * 0.115)
    circle(s * 0.68, s * 0.63, s * 0.115)
    d.line([(s * 0.44, s * 0.71), (s * 0.44, s * 0.27)], fill=w, width=lw)
    d.line([(s * 0.78, s * 0.63), (s * 0.78, s * 0.19)], fill=w, width=lw)
    d.polygon([(s * 0.405, s * 0.25), (s * 0.815, s * 0.15), (s * 0.815, s * 0.26), (s * 0.405, s * 0.36)], fill=w)
    return img.resize((px, px), Image.LANCZOS)


def launch(w, h):
    # Matches the first frame of the app: dark background with a translucent tab bar.
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, h - 98, w, h], fill=(18, 18, 22))
    return img


os.makedirs(OUT, exist_ok=True)
for pt in (29, 40, 60):
    icon(pt * 2).save(os.path.join(OUT, f"AppIcon{pt}x{pt}@2x.png"))
launch(640, 960).save(os.path.join(OUT, "Default@2x.png"))
launch(640, 1136).save(os.path.join(OUT, "Default-568h@2x.png"))

print("done")

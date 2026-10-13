#!/usr/bin/env python3
"""Draws the launch images into Resources/. The icon comes from docs/icon.svg."""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "Resources")
BG = (11, 11, 16)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def launch(w, h):
    # Matches the first frame of the app: dark background with a translucent tab bar.
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, h - 98, w, h], fill=(18, 18, 22))
    return img


os.makedirs(OUT, exist_ok=True)
# The app icon is drawn from docs/icon.svg (shared with the Wear OS apps), not here.
launch(640, 960).save(os.path.join(OUT, "Default@2x.png"))
launch(640, 1136).save(os.path.join(OUT, "Default-568h@2x.png"))

print("done")

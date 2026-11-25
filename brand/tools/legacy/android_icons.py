#!/usr/bin/env python3
"""Icone Android di Panaru derivate dall'illustrazione ufficiale (la stessa dell'icona macOS):
brand/icons/macos/source/panaru-icon-source.png.

- Icona adattiva: sfondo = crema della piastra; primo piano = il paniere estratto dalla sorgente
  (il crema diventa trasparente con bordi morbidi, quindi anche le fughe della trama). Il paniere è
  scalato al **massimo** consentito: il suo pixel più lontano dal centro cade sul bordo della zona
  visibile della maschera circolare (r = 34 dp su 108), così nessun launcher lo taglia.
- Icona a tema (Android 13+): silhouette del paniere con la trama, a un solo colore.
- Icona Google Play 512×512 e immagine del paniere per l'interfaccia.
- Icona delle notifiche: glifo semplificato (stesso di macOS) — a 24 dp la trama non è leggibile.

Uso:  python3 brand/tools/android_icons.py      (richiede Pillow)
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).parent))
import macos_icons  # noqa: E402  (geometria del glifo semplificato)

SOURCE = ROOT / "brand/icons/macos/source/panaru-icon-source.png"
RES = ROOT / "android/app/src/main/res"
BRAND_OUT = ROOT / "brand/icons/android"
EXPORTS = ROOT / "brand/exports/android"

CREAM = (252, 238, 210)            # piastra della sorgente (#FCEED2)
PLATE = macos_icons.PLATE
VISIBLE_RADIUS_DP = 34.0           # zona sicura 33 dp, maschere tipiche ≥ 36 dp
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def extract_basket() -> Image.Image:
    """Paniere su fondo trasparente: alfa dalla distanza dal crema, colore "de-miscelato"."""
    src = Image.open(SOURCE).convert("RGB").crop(PLATE)
    out = Image.new("RGBA", src.size)
    sp, op = src.load(), out.load()
    lo, hi = 10.0, 60.0
    for y in range(src.height):
        for x in range(src.width):
            r, g, b = sp[x, y]
            d = math.sqrt((r - CREAM[0]) ** 2 + (g - CREAM[1]) ** 2 + (b - CREAM[2]) ** 2)
            if min(r, g, b) > 240:      # bianco fuori dalla piastra arrotondata: è fondo
                d = 0.0
            a = min(1.0, max(0.0, (d - lo) / (hi - lo)))
            if a <= 0:
                op[x, y] = (0, 0, 0, 0)
                continue
            un = [min(255, max(0, round((c - (1 - a) * k) / a))) for c, k in zip((r, g, b), CREAM)]
            op[x, y] = (*un, round(a * 255))
    return out.crop(out.getbbox())


def max_radius(img: Image.Image) -> float:
    """Distanza massima dal centro del riquadro di un pixel visibile (alfa > 50%)."""
    a = img.getchannel("A").load()
    cx, cy = img.width / 2, img.height / 2
    best = 0.0
    for y in range(0, img.height, 2):
        for x in range(0, img.width, 2):
            if a[x, y] > 128:
                best = max(best, math.hypot(x - cx, y - cy))
    return best


def place(basket: Image.Image, canvas_px: int, radius_px: float) -> Image.Image:
    scale = radius_px / max_radius(basket)
    w, h = round(basket.width * scale), round(basket.height * scale)
    img = basket.resize((w, h), Image.LANCZOS)
    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    canvas.alpha_composite(img, ((canvas_px - w) // 2, (canvas_px - h) // 2))
    return canvas


def monochrome(img: Image.Image) -> Image.Image:
    white = Image.new("RGBA", img.size, (255, 255, 255, 0))
    white.putalpha(img.getchannel("A"))
    return white


def notification_vector() -> str:
    """Glifo semplificato in VectorDrawable (bianco: Android lo tinge)."""
    vb_x, vb_y, vb = 172, 160, 910
    items = []
    for kind, d in macos_icons.menubar_paths():
        if kind == "fill":
            items.append(f'        <path android:fillColor="#FFFFFFFF" android:pathData="{d}"/>')
        else:
            w = kind.split(":")[1]
            items.append(f'        <path android:strokeColor="#FFFFFFFF" android:strokeWidth="{w}" '
                         f'android:strokeLineCap="round" android:pathData="{d}"/>')
    s = 24 / vb
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generato da brand/tools/android_icons.py — icona notifiche (glifo semplificato del paniere). -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
        f'    <group android:scaleX="{s:.5f}" android:scaleY="{s:.5f}" '
        f'android:translateX="{-vb_x * s:.4f}" android:translateY="{-vb_y * s:.4f}">\n'
        + "\n".join(items) + "\n    </group>\n</vector>\n"
    )


def main() -> None:
    basket = extract_basket()
    BRAND_OUT.mkdir(parents=True, exist_ok=True)
    EXPORTS.mkdir(parents=True, exist_ok=True)
    basket.save(BRAND_OUT / "panaru-basket.png")

    for name, k in DENSITIES.items():
        d = RES / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)
        size = round(108 * k)
        fg = place(basket, size, VISIBLE_RADIUS_DP * k)
        fg.save(d / "ic_launcher_foreground.png", optimize=True)
        monochrome(fg).save(d / "ic_launcher_monochrome.png", optimize=True)
        print("•", d.relative_to(ROOT))

    # Paniere per l'interfaccia (schermata vuota, informazioni).
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    ui = basket.resize((360, round(basket.height * 360 / basket.width)), Image.LANCZOS)
    ui.save(nodpi / "panaru_basket.png", optimize=True)

    # Google Play: 512×512 a tutto campo (Play applica da sé la maschera).
    play = Image.new("RGBA", (512, 512), (*CREAM, 255))
    play.alpha_composite(place(basket, 512, 512 * VISIBLE_RADIUS_DP / 108))
    play.convert("RGB").save(EXPORTS / "play-store-icon-512.png", optimize=True)

    (RES / "drawable" / "ic_stat_panaru.xml").write_text(notification_vector())
    print("• icone Android generate (scala dal raggio massimo:", round(max_radius(basket)), "px nella sorgente)")


if __name__ == "__main__":
    main()

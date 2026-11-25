#!/usr/bin/env python3
"""Icone di Panaru (macOS e Android) dal logo vettoriale brand/logo/new/panaru.svg.

Una sola grafica per le due app: paniere **bianco** su piastra **arancio** (#EC5F36), con le stesse
proporzioni (il paniere occupa il 63% del lato della piastra visibile).

Genera senza dipendenze (anche in CI):
- brand/icons/app/panaru-glyph.svg            paniere bianco, viewBox stretto
- brand/icons/app/panaru-app-icon.svg         icona completa (piastra arrotondata + paniere)
- brand/icons/macos/menubar/*.svg             glifi template della barra dei menu (normale, attivo)
- android/app/src/main/res/drawable/*.xml     icona adattiva (primo piano/monocromatica),
                                              icona notifiche, simbolo per l'interfaccia

Con l'argomento <rasterizzatore> (binario compilato da rasterize.swift) genera anche i raster:
AppIcon.iconset (macOS), icona Google Play 512, export PNG. Richiede Pillow.

Uso:  python3 brand/tools/app_icons.py [rasterizzatore cartella-iconset]
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "brand/logo/new/panaru.svg"
APP_DIR = ROOT / "brand/icons/app"
MENUBAR_DIR = ROOT / "brand/icons/macos/menubar"
RES = ROOT / "android/app/src/main/res"
LINUX_ICONS = ROOT / "linux/data/icons/hicolor"
EXPORTS = ROOT / "brand/exports"

ORANGE = "#EC5F36"                  # colore dell'icona (brand/colors/tokens.json → brand.arancio)
WHITE = "#FFFFFF"
GLYPH_RATIO = 0.63                  # altezza del paniere / lato della piastra visibile

# Riquadro del paniere nelle coordinate del logo (viewBox 364×430), misurato sul rendering.
BX0, BY0, BX1, BY1 = 6.3, 46.4, 348.1, 408.9
BW, BH = BX1 - BX0, BY1 - BY0
BCX, BCY = (BX0 + BX1) / 2, (BY0 + BY1) / 2

# Freccia dello stato "trasferimento" dentro l'arco del manico (coordinate del logo).
ARROW = "M182 104L230 154H200V206H164V154H134Z"


def f(n: float) -> str:
    return f"{n:.4f}".rstrip("0").rstrip(".")


def source_paths() -> list[str]:
    """Path del logo (coordinate potrace: translate(0,430) scale(0.1,-0.1))."""
    src = SOURCE.read_text()
    return [" ".join(d.split()) for d in re.findall(r'<path d="([^"]+)"', src)]


SRC_TRANSFORM = "translate(0,430) scale(0.1,-0.1)"


def glyph_group(color: str, active: bool = False) -> str:
    body = "".join(f'    <path d="{d}"/>\n' for d in source_paths())
    arrow = f'  <path d="{ARROW}" fill="{color}"/>\n' if active else ""
    return (f'  <g transform="{SRC_TRANSFORM}" fill="{color}" stroke="none">\n{body}  </g>\n{arrow}')


def svg_doc(vb: tuple[float, float, float, float], inner: str, title: str) -> str:
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{" ".join(f(v) for v in vb)}" '
            f'width="{f(vb[2])}" height="{f(vb[3])}" role="img" aria-label="{title}">\n'
            f'  <title>{title}</title>\n<!-- Generato da brand/tools/app_icons.py -->\n{inner}</svg>\n')


def glyph_svg(color: str = WHITE, active: bool = False, margin: float = 0.0, square: bool = False) -> str:
    w, h = BW + 2 * margin, BH + 2 * margin
    if square:
        w = h = max(w, h)
    vb = (BCX - w / 2, BCY - h / 2, w, h)
    return svg_doc(vb, glyph_group(color, active), "Panaru")


def app_icon_svg(size: float = 1024, radius: float = 230) -> str:
    """Icona completa: piastra arancio a tutto campo + paniere bianco centrato."""
    s = GLYPH_RATIO * size / BH
    tx, ty = size / 2 - BCX * s, size / 2 - BCY * s
    inner = (f'  <rect width="{f(size)}" height="{f(size)}" rx="{f(radius)}" fill="{ORANGE}"/>\n'
             f'  <g transform="translate({f(tx)},{f(ty)}) scale({f(s)})">\n'
             + glyph_group(WHITE) + "  </g>\n")
    return svg_doc((0, 0, size, size), inner, "Panaru")


# --------------------------------------------------------------------------- Android (vettoriale)
def vector_drawable(size_dp: float, glyph_h_dp: float, color: str, comment: str,
                    plate: str | None = None, plate_radius: float = 0) -> str:
    """VectorDrawable con il paniere alto glyph_h_dp, centrato in un riquadro size_dp."""
    s = glyph_h_dp / BH
    tx, ty = size_dp / 2 - BCX * s, size_dp / 2 - BCY * s
    paths = "\n".join(f'            <path android:fillColor="{color}" android:pathData="{d}"/>'
                      for d in source_paths())
    bg = ""
    if plate:
        r, n = plate_radius, size_dp
        bg = (f'    <path android:fillColor="{plate}" android:pathData="M{f(r)},0H{f(n - r)}A{f(r)},{f(r)} 0 0 1 {f(n)},{f(r)}'
              f'V{f(n - r)}A{f(r)},{f(r)} 0 0 1 {f(n - r)},{f(n)}H{f(r)}A{f(r)},{f(r)} 0 0 1 0,{f(n - r)}'
              f'V{f(r)}A{f(r)},{f(r)} 0 0 1 {f(r)},0Z"/>\n')
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- Generato da brand/tools/app_icons.py — {comment} -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{f(size_dp)}dp" android:height="{f(size_dp)}dp"\n'
        f'    android:viewportWidth="{f(size_dp)}" android:viewportHeight="{f(size_dp)}">\n'
        + bg +
        f'    <group android:translateX="{f(tx)}" android:translateY="{f(ty)}" android:scaleX="{f(s)}" android:scaleY="{f(s)}">\n'
        '        <group android:translateY="430" android:scaleX="0.1" android:scaleY="-0.1">\n'
        f'{paths}\n'
        '        </group>\n    </group>\n</vector>\n'
    )


ANDROID_VISIBLE_DP = 72.0           # diametro della maschera adattiva visibile (su 108 dp)


def write_android() -> None:
    d = RES / "drawable"
    glyph_dp = GLYPH_RATIO * ANDROID_VISIBLE_DP
    (d / "ic_launcher_foreground.xml").write_text(vector_drawable(
        108, glyph_dp, "#FFFFFFFF", "primo piano dell'icona adattiva (paniere bianco)."))
    (d / "ic_launcher_monochrome.xml").write_text(vector_drawable(
        108, glyph_dp, "#FFFFFFFF", "icona a tema Android 13+ (il sistema la tinge)."))
    (d / "ic_stat_panaru.xml").write_text(vector_drawable(
        24, 22, "#FFFFFFFF", "icona delle notifiche (bianca: il sistema la tinge)."))
    (d / "panaru_basket.xml").write_text(vector_drawable(
        96, GLYPH_RATIO * 96, "#FFFFFFFF", "simbolo per l'interfaccia, identico all'icona dell'app.",
        plate="#FF" + ORANGE[1:], plate_radius=96 * 0.225))
    print("• android/app/src/main/res/drawable: ic_launcher_foreground, ic_launcher_monochrome, ic_stat_panaru, panaru_basket")


def write_svgs() -> None:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    MENUBAR_DIR.mkdir(parents=True, exist_ok=True)
    (APP_DIR / "panaru-glyph.svg").write_text(glyph_svg())
    (APP_DIR / "panaru-glyph-orange.svg").write_text(glyph_svg(ORANGE))
    (APP_DIR / "panaru-app-icon.svg").write_text(app_icon_svg())
    # Barra dei menu: nero + alfa (template), quadrato con un piccolo margine ottico.
    (MENUBAR_DIR / "panaru-menubar.svg").write_text(glyph_svg("#000000", margin=10, square=True))
    (MENUBAR_DIR / "panaru-menubar-active.svg").write_text(glyph_svg("#000000", active=True, margin=10, square=True))
    # Linux (tema icone hicolor): icona a colori e simbolica (monocromatica, tinta dal tema).
    (LINUX_ICONS / "scalable/apps").mkdir(parents=True, exist_ok=True)
    (LINUX_ICONS / "symbolic/apps").mkdir(parents=True, exist_ok=True)
    (LINUX_ICONS / "scalable/apps/app.panaru.Panaru.svg").write_text(app_icon_svg(128, 28))
    (LINUX_ICONS / "symbolic/apps/app.panaru.Panaru-symbolic.svg").write_text(glyph_svg("#241f31", margin=8, square=True))
    print("• brand/icons/app/*.svg, brand/icons/macos/menubar/*.svg, linux/data/icons")


# --------------------------------------------------------------------------- raster
def rasterize(raster: str, svg_text: str, width: int):
    from PIL import Image
    with tempfile.TemporaryDirectory() as tmp:
        s, p = Path(tmp) / "g.svg", Path(tmp) / "g.png"
        s.write_text(svg_text)
        subprocess.run([raster, str(s), str(p), str(width)], check=True)
        return Image.open(p).convert("RGBA").copy()


def hex_rgb(h: str) -> tuple[int, int, int]:
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))  # type: ignore[return-value]


def plate_icon(raster: str, size: int, plate: int, radius: float, shadow: bool):
    """Piastra arancio disegnata con Pillow (colore esatto) + paniere bianco rasterizzato."""
    from PIL import Image, ImageDraw, ImageFilter
    ss = 4
    mask = Image.new("L", (plate * ss, plate * ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, plate * ss - 1, plate * ss - 1), radius=radius * ss, fill=255)
    mask = mask.resize((plate, plate), Image.LANCZOS)
    tile = Image.new("RGBA", (plate, plate), (*hex_rgb(ORANGE), 255))
    tile.putalpha(mask)
    gh = round(GLYPH_RATIO * plate)
    glyph = rasterize(raster, glyph_svg(), round(gh * BW / BH))
    tile.alpha_composite(glyph, ((plate - glyph.width) // 2, (plate - glyph.height) // 2))
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    off = (size - plate) // 2
    if shadow:
        sh = Image.new("L", (size, size), 0)
        sh.paste(mask, (off, off + round(size * 0.012)))
        layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        layer.putalpha(sh.point(lambda a: int(a * 0.30)).filter(ImageFilter.GaussianBlur(size * 0.014)))
        canvas.alpha_composite(layer)
    canvas.alpha_composite(tile, (off, off))
    return canvas


def write_rasters(raster: str, iconset: Path) -> None:
    from PIL import Image
    # macOS: griglia ufficiale (canvas 1024, piastra 824, raggio 185, ombra morbida).
    icon = plate_icon(raster, 1024, 824, 185, shadow=True)
    (EXPORTS / "macos").mkdir(parents=True, exist_ok=True)
    icon.save(EXPORTS / "macos/panaru-macos-icon@1024.png")
    iconset.mkdir(parents=True, exist_ok=True)
    for s in (16, 32, 128, 256, 512):
        icon.resize((s, s), Image.LANCZOS).save(iconset / f"icon_{s}x{s}.png")
        icon.resize((s * 2, s * 2), Image.LANCZOS).save(iconset / f"icon_{s}x{s}@2x.png")
    print("• iconset", iconset)
    # Google Play: 512×512 a tutto campo (la maschera la applica Play).
    (EXPORTS / "android").mkdir(parents=True, exist_ok=True)
    plate_icon(raster, 512, 512, 0, shadow=False).convert("RGB").save(
        EXPORTS / "android/play-store-icon-512.png", optimize=True)
    # Export generici.
    (EXPORTS / "app").mkdir(parents=True, exist_ok=True)
    for s in (1024, 512, 180):
        plate_icon(raster, s, s, s * 0.225, shadow=False).save(EXPORTS / f"app/panaru-app-icon@{s}.png")
    white = rasterize(raster, glyph_svg(), 1024)
    white.save(EXPORTS / "app/panaru-glyph-white@1024.png")
    orange = Image.new("RGBA", white.size, (*hex_rgb(ORANGE), 255))
    orange.putalpha(white.getchannel("A"))   # tinta esatta (il rasterizzatore non è color-managed)
    orange.save(EXPORTS / "app/panaru-glyph-orange@1024.png")
    print("• brand/exports/{macos,android,app}")


def main() -> None:
    write_svgs()
    write_android()
    if len(sys.argv) > 2:
        write_rasters(sys.argv[1], Path(sys.argv[2]))


if __name__ == "__main__":
    main()

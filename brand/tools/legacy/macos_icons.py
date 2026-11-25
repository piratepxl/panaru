#!/usr/bin/env python3
"""Icone macOS di Panaru derivate dall'illustrazione ufficiale
(brand/icons/macos/source/panaru-icon-source.png).

1. AppIcon: la piastra crema della sorgente viene ritagliata e portata sulla griglia delle icone
   macOS (canvas 1024, piastra 824 con raggio 185, ombra morbida), poi esportata in .iconset.
2. Barra dei menu: glifo vettoriale *semplificato* ricavato dalle proporzioni misurate sulla
   sorgente (manico ad arco, bordo ovale con apertura, corpo a due fasce). A 16–18 pt la trama
   dell'illustrazione diventerebbe una macchia: il glifo ne conserva solo la silhouette leggibile.
   È un'immagine "template" (nero + alfa) che macOS tinge per chiaro/scuro e selezione.

Uso:  python3 brand/tools/macos_icons.py <cartella-iconset>
Richiede Pillow (solo per l'AppIcon; gli SVG della barra dei menu sono generati senza dipendenze).
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "brand/icons/macos/source/panaru-icon-source.png"
MENUBAR_DIR = ROOT / "brand/icons/macos/menubar"

# Misure ricavate dalla sorgente (pixel 1254×1254).
PLATE = (56, 64, 1198, 1196)          # piastra crema: x0, y0, x1, y1


def f(n: float) -> str:
    s = f"{n:.1f}".rstrip("0").rstrip(".")
    return s


# --------------------------------------------------------------------------- glifo barra dei menu
# Coordinate nello spazio della sorgente, per restare fedeli alle proporzioni dell'illustrazione.
CX = 627.0
HANDLE = dict(cx=CX, base=575.0, rx=342.0, ry=355.0, w=78.0)          # manico (linea mediana)
RIM = dict(cy=662.0, rx=413.0, ry=78.0, open_cy=636.0, open_rx=286.0, open_ry=33.0)
BODY_TOP, GAP_TOP, GAP_BOTTOM, KNEE, BOTTOM = 752.0, 868.0, 912.0, 968.0, 1046.0
SIDE_TOP_X, SIDE_KNEE_X = 252.0, 332.0                                 # fianco sinistro
SAG = 46.0                                                             # curvatura delle fasce


def side_x(y: float) -> float:
    """Ascissa del fianco sinistro (lineare tra il bordo e il ginocchio)."""
    return SIDE_TOP_X + (y - BODY_TOP) * (SIDE_KNEE_X - SIDE_TOP_X) / (KNEE - BODY_TOP)


def menubar_paths(active: bool = False) -> list[tuple[str, str]]:
    """(tipo, path): 'fill' o 'stroke:<larghezza>'."""
    h = HANDLE
    out: list[tuple[str, str]] = []
    # Manico: arco ellittico (linea mediana) con estremità arrotondate.
    out.append((f"stroke:{f(h['w'])}",
                f"M{f(h['cx'] - h['rx'])} {f(h['base'])}A{f(h['rx'])} {f(h['ry'])} 0 0 1 {f(h['cx'] + h['rx'])} {f(h['base'])}"))
    # Bordo: ellisse piena con apertura (percorsa al contrario → foro con nonzero).
    r = RIM
    out.append(("fill",
                f"M{f(CX - r['rx'])} {f(r['cy'])}A{f(r['rx'])} {f(r['ry'])} 0 1 1 {f(CX + r['rx'])} {f(r['cy'])}"
                f"A{f(r['rx'])} {f(r['ry'])} 0 1 1 {f(CX - r['rx'])} {f(r['cy'])}Z"
                f"M{f(CX - r['open_rx'])} {f(r['open_cy'])}A{f(r['open_rx'])} {f(r['open_ry'])} 0 1 0 {f(CX + r['open_rx'])} {f(r['open_cy'])}"
                f"A{f(r['open_rx'])} {f(r['open_ry'])} 0 1 0 {f(CX - r['open_rx'])} {f(r['open_cy'])}Z"))
    # Corpo: due fasce curve separate da una fuga.
    l0, l1 = side_x(BODY_TOP), side_x(GAP_TOP)
    out.append(("fill",
                f"M{f(l0)} {f(BODY_TOP)}Q{f(CX)} {f(BODY_TOP + 2 * SAG)} {f(2 * CX - l0)} {f(BODY_TOP)}"
                f"L{f(2 * CX - l1)} {f(GAP_TOP)}Q{f(CX)} {f(GAP_TOP + 2 * SAG)} {f(l1)} {f(GAP_TOP)}Z"))
    l2, lk = side_x(GAP_BOTTOM), SIDE_KNEE_X
    out.append(("fill",
                f"M{f(l2)} {f(GAP_BOTTOM)}Q{f(CX)} {f(GAP_BOTTOM + 2 * SAG)} {f(2 * CX - l2)} {f(GAP_BOTTOM)}"
                f"L{f(2 * CX - lk)} {f(KNEE)}Q{f(2 * CX - lk - 22)} {f(BOTTOM)} {f(2 * CX - 470)} {f(BOTTOM)}"
                f"L470 {f(BOTTOM)}Q{f(lk + 22)} {f(BOTTOM)} {f(lk)} {f(KNEE)}Z"))
    if active:
        # Stato "trasferimento in corso": freccia verso l'alto dentro l'arco del manico.
        out.append(("fill", "M627 300L742 430H668V545H586V430H512Z"))
    return out


def menubar_svg(active: bool = False, color: str = "#000000") -> str:
    # Riquadro quadrato attorno alla cesta (x 210–1045, y 180–1050) con margine ottico.
    vb = (172, 160, 910, 910)
    body = ""
    for kind, d in menubar_paths(active):
        if kind == "fill":
            body += f'  <path d="{d}" fill="{color}"/>\n'
        else:
            w = kind.split(":")[1]
            body += f'  <path d="{d}" fill="none" stroke="{color}" stroke-width="{w}" stroke-linecap="round"/>\n'
    title = "Panaru — barra dei menu" + (" (attivo)" if active else "")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{" ".join(map(str, vb))}" '
            f'width="{vb[2]}" height="{vb[3]}" role="img" aria-label="{title}">\n  <title>{title}</title>\n{body}</svg>\n')


# --------------------------------------------------------------------------- AppIcon
def app_icon_1024():
    from PIL import Image, ImageDraw, ImageFilter

    src = Image.open(SOURCE).convert("RGBA")
    plate = src.crop(PLATE).resize((824, 824), Image.LANCZOS)
    # Maschera della griglia macOS (raggio 185 su 824): più ampia del raggio della sorgente,
    # quindi elimina ogni residuo del fondo bianco attorno alla piastra.
    mask = Image.new("L", (824 * 4, 824 * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 824 * 4 - 1, 824 * 4 - 1), radius=185 * 4, fill=255)
    mask = mask.resize((824, 824), Image.LANCZOS)
    plate.putalpha(mask)

    canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    shadow = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    sh_mask = Image.new("L", (1024, 1024), 0)
    sh_mask.paste(mask, (100, 112))
    shadow.putalpha(sh_mask.point(lambda a: int(a * 0.30)).filter(ImageFilter.GaussianBlur(14)))
    canvas.alpha_composite(shadow)
    canvas.alpha_composite(plate, (100, 100))
    return canvas


def write_iconset(dest: Path) -> None:
    from PIL import Image

    dest.mkdir(parents=True, exist_ok=True)
    icon = app_icon_1024()
    icon.save(ROOT / "brand/exports/macos/panaru-macos-icon@1024.png")
    for s in (16, 32, 128, 256, 512):
        icon.resize((s, s), Image.LANCZOS).save(dest / f"icon_{s}x{s}.png")
        icon.resize((s * 2, s * 2), Image.LANCZOS).save(dest / f"icon_{s}x{s}@2x.png")


def main() -> None:
    MENUBAR_DIR.mkdir(parents=True, exist_ok=True)
    (MENUBAR_DIR / "panaru-menubar.svg").write_text(menubar_svg())
    (MENUBAR_DIR / "panaru-menubar-active.svg").write_text(menubar_svg(active=True))
    print("• brand/icons/macos/menubar/*.svg")
    if len(sys.argv) > 1:
        write_iconset(Path(sys.argv[1]))
        print("• iconset", sys.argv[1])


if __name__ == "__main__":
    main()

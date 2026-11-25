#!/usr/bin/env python3
"""Generatore degli asset vettoriali di Panaru.

Il paniere geometrico è definito qui come geometria pura (griglia 120x120),
così ogni SVG del marchio deriva dalla stessa fonte e resta coerente.

Uso:  python3 brand/tools/generate_brand.py
Scrive gli SVG in brand/logo, brand/icons e le risorse vettoriali delle app.
Nessuna dipendenza esterna (solo libreria standard).
"""
from __future__ import annotations

import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BRAND = ROOT / "brand"
TOKENS = json.loads((BRAND / "colors" / "tokens.json").read_text())
C = {k: v["value"] for k, v in TOKENS["brand"].items()}

ARANCIO = C["arancio"]
ANTRACITE = C["antracite"]
BIANCO = C["biancoCaldo"]
SABBIA = C["sabbia"]


def f(n: float) -> str:
    """Numero compatto per i path SVG."""
    s = f"{n:.3f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


# ---------------------------------------------------------------------------
# Geometria del paniere (griglia 120). Tutto è separato da un'unica "fuga" G.
# ---------------------------------------------------------------------------
CORNER = 2.0         # raggio d'angolo ottico


def rounded_polygon(pts: list[tuple[float, float]], r: float) -> str:
    """Poligono con angoli smussati (curve quadratiche): pulito e modificabile."""
    n = len(pts)
    segs = []
    for i in range(n):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        def toward(a, b):
            dx, dy = a[0] - b[0], a[1] - b[1]
            d = math.hypot(dx, dy)
            k = min(r, d / 2) / d
            return (b[0] + dx * k, b[1] + dy * k)
        a = toward(p0, p1)
        b = toward(p2, p1)
        segs.append((a, p1, b))
    d = f"M{f(segs[0][2][0])} {f(segs[0][2][1])}"
    for a, p, b in segs[1:] + segs[:1]:
        d += f"L{f(a[0])} {f(a[1])}Q{f(p[0])} {f(p[1])} {f(b[0])} {f(b[1])}"
    return d + "Z"


# ---------------------------------------------------------------------------
# Primitive ellittiche: il bordo ovale in prospettiva e le fasce curve.
# ---------------------------------------------------------------------------
def ellipse_ring(cx: float, cy: float, rx: float, ry: float, icx: float, icy: float, irx: float, iry: float) -> str:
    """Anello ellittico (bordo + apertura). L'apertura è percorsa in senso opposto:
    funziona con la regola di riempimento predefinita (nonzero) in SVG e Android."""
    return (
        f"M{f(cx - rx)} {f(cy)}A{f(rx)} {f(ry)} 0 1 1 {f(cx + rx)} {f(cy)}"
        f"A{f(rx)} {f(ry)} 0 1 1 {f(cx - rx)} {f(cy)}Z"
        f"M{f(icx - irx)} {f(icy)}A{f(irx)} {f(iry)} 0 1 0 {f(icx + irx)} {f(icy)}"
        f"A{f(irx)} {f(iry)} 0 1 0 {f(icx - irx)} {f(icy)}Z"
    )


def curved_band(cx: float, y_top: float, w_top: float, y_bot: float, w_bot: float, sag: float) -> str:
    """Fascia del corpo: lati dritti rastremati, bordi curvi paralleli (stessa freccia `sag`).
    Curve parallele = fuga costante anche ai fianchi, nessun filo sottile alle estremità."""
    return (
        f"M{f(cx - w_top)} {f(y_top)}Q{f(cx)} {f(y_top + 2 * sag)} {f(cx + w_top)} {f(y_top)}"
        f"L{f(cx + w_bot)} {f(y_bot)}Q{f(cx)} {f(y_bot + 2 * sag)} {f(cx - w_bot)} {f(y_bot)}Z"
    )


def half_ring(cx: float, cy: float, R: float, w: float, start_deg: float, sweep_deg: float = 180) -> str:
    """Settore di anello circolare (ansa). Angoli matematici (0° = destra, 90° = alto)."""
    ri = R - w
    a0, a1 = math.radians(start_deg), math.radians(start_deg + sweep_deg)
    def pt(r, a):
        return (cx + r * math.cos(a), cy - r * math.sin(a))
    o0, o1, i1, i0 = pt(R, a0), pt(R, a1), pt(ri, a1), pt(ri, a0)
    large = 1 if sweep_deg > 180 else 0
    return (f"M{f(o0[0])} {f(o0[1])}A{f(R)} {f(R)} 0 {large} 0 {f(o1[0])} {f(o1[1])}"
            f"L{f(i1[0])} {f(i1[1])}A{f(ri)} {f(ri)} 0 {large} 1 {f(i0[0])} {f(i0[1])}Z")


def arch_handle(cx: float, base_y: float, rx: float, ry: float, w: float) -> str:
    """Manico ad arco: semi-anello ellittico che poggia sul bordo."""
    return (f"M{f(cx - rx)} {f(base_y)}A{f(rx)} {f(ry)} 0 0 1 {f(cx + rx)} {f(base_y)}"
            f"H{f(cx + rx - w)}A{f(rx - w)} {f(ry - w)} 0 0 0 {f(cx - rx + w)} {f(base_y)}Z")


# Trama: montanti verticali che passano sopra una fascia sì e una no (intreccio "sopra-sotto").
# Posizioni lungo la fascia (0 = fianco sinistro, 1 = destro), alternate per riga come nel cesto reale.
WEAVE_COLUMNS = (0.2, 0.5, 0.8)
WEAVE_STRIP = 9.0   # larghezza del montante (griglia 120)


def _edge_point(cx: float, y: float, w: float, sag: float, t: float) -> tuple[float, float]:
    """Punto a parametro t sul bordo curvo di una fascia (Bezier quadratica, x lineare in t)."""
    return cx - w + 2 * w * t, y + 4 * sag * t * (1 - t)


def _edge_ctrl(cx: float, y: float, w: float, sag: float, t0: float, t1: float) -> tuple[float, float]:
    """Punto di controllo del tratto [t0, t1] dello stesso bordo (blossom della quadratica)."""
    p0, p1, p2 = (cx - w, y), (cx, y + 2 * sag), (cx + w, y)
    a, m, c = (1 - t0) * (1 - t1), (1 - t0) * t1 + t0 * (1 - t1), t0 * t1
    return a * p0[0] + m * p1[0] + c * p2[0], a * p0[1] + m * p1[1] + c * p2[1]


def _piece(cx: float, top: tuple[float, float], bot: tuple[float, float], sag: float, t0: float, t1: float) -> str:
    """Porzione di fascia tra due bordi curvi, limitata ai parametri [t0, t1]."""
    (yt, wt), (yb, wb) = top, bot
    a0, a1 = _edge_point(cx, yt, wt, sag, t0), _edge_point(cx, yt, wt, sag, t1)
    ca = _edge_ctrl(cx, yt, wt, sag, t0, t1)
    b0, b1 = _edge_point(cx, yb, wb, sag, t0), _edge_point(cx, yb, wb, sag, t1)
    cb = _edge_ctrl(cx, yb, wb, sag, t0, t1)
    return (f"M{f(a0[0])} {f(a0[1])}Q{f(ca[0])} {f(ca[1])} {f(a1[0])} {f(a1[1])}"
            f"L{f(b1[0])} {f(b1[1])}Q{f(cb[0])} {f(cb[1])} {f(b0[0])} {f(b0[1])}Z")


def body(cx: float, rim_y: float, rx: float, ry: float, bands: int, h: float, gap: float, taper: float,
         weave: bool = False, overlay_only: bool = False) -> list[str]:
    """Corpo a fasce sotto il bordo ovale. La prima fascia segue il bordo a distanza `gap`.
    Con `weave` i montanti verticali attraversano le fasce alternate: dove un montante passa
    "sopra", la fascia è interrotta da due fughe e il montante prosegue nelle fughe orizzontali
    fino alle fasce vicine, sotto cui scompare."""
    w0 = rx - 2.0
    side_y = rim_y + ry * math.sqrt(1 - (w0 / rx) ** 2) + gap   # sotto il bordo, ai fianchi
    sag = rim_y + ry + gap - side_y                              # al centro: bordo + fuga
    rows = []
    t = 0.0
    for _ in range(bands):
        rows.append(((side_y + t, w0 - t * taper), (side_y + t + h, w0 - (t + h) * taper)))
        t += h + gap
    if not weave:
        return [curved_band(cx, top[0], top[1], bot[0], bot[1], sag) for top, bot in rows]

    out, overlay = [], []
    for r, (top, bot) in enumerate(rows):
        width = top[1] + bot[1]                      # larghezza media della fascia
        hs = WEAVE_STRIP / 2 / width                 # mezza larghezza del montante, in t
        gt = gap / width                             # fuga laterale, in t
        over = [c for i, c in enumerate(WEAVE_COLUMNS) if (i + r) % 2 == 0]
        cuts, t0 = [], 0.0
        for c in over:
            cuts.append((t0, c - hs - gt))           # fascia fino alla fuga
            t0 = c + hs + gt
        cuts.append((t0, 1.0))
        out += [_piece(cx, top, bot, sag, a, b) for a, b in cuts]
        for c in over:
            # Tratto visibile del montante dentro la fascia (sicuro anche in monocromia).
            out.append(_piece(cx, top, bot, sag, c - hs, c + hs))
            # Secondo tono: il montante attraversa le fughe e sparisce sotto le fasce vicine.
            ov = 0.4                                  # leggera sovrapposizione: nessun filo di giunzione
            up = (rows[r - 1][1][0] - ov, rows[r - 1][1][1]) if r > 0 else top
            down = (rows[r + 1][0][0] + ov, rows[r + 1][0][1]) if r < len(rows) - 1 else bot
            overlay.append(_piece(cx, up, down, sag, c - hs, c + hs))
    return overlay if overlay_only else out


def body_bottom(rim_y: float, rx: float, ry: float, bands: int, h: float, gap: float) -> float:
    return rim_y + ry + gap + bands * h + (bands - 1) * gap


# ---------------------------------------------------------------------------
# I due simboli. Griglia 120; la fuga G separa bordo e fasce.
# ---------------------------------------------------------------------------
CX = 60.0
GAP = 4.0

# CESTA (simbolo principale): bassa e larga, bordo ovale, due anse laterali.
CESTA = dict(rim_y=48.0, rx=46.0, ry=12.0, open_rx=37.0, open_ry=6.2, open_dy=-1.6,
             bands=3, band_h=8.0, taper=0.28, ear_R=11.0, ear_w=5.0, ear_in=5.0)

# PANIERE (variante): manico ad arco alto, corpo più profondo.
PANIERE = dict(rim_y=64.0, rx=40.0, ry=10.0, open_rx=32.0, open_ry=5.2, open_dy=-1.3,
               bands=3, band_h=10.0, taper=0.36, handle_rx=38.0, handle_ry=52.0, handle_w=6.0)


def cesta_paths(small: bool = False, overlay: bool = False) -> list[str]:
    g = dict(CESTA)
    if small:  # taglio ottico ≤ 24 px: due fasce, anse e bordo più spessi
        g.update(bands=2, band_h=12.0, ear_w=6.5, open_rx=34.5, open_ry=4.6)
    y = g["rim_y"]
    rim = ellipse_ring(CX, y, g["rx"], g["ry"], CX, y + g["open_dy"], g["open_rx"], g["open_ry"])
    ex = g["rx"] - g["ear_in"]
    # Ansa: anello aperto ancorato in due punti — sopra il bordo e sul fianco.
    ears = [half_ring(CX - ex, y - 4.0, g["ear_R"], g["ear_w"], 20, 181),
            half_ring(CX + ex, y - 4.0, g["ear_R"], g["ear_w"], -21, 181)]
    if overlay:
        return [] if small else body(CX, y, g["rx"], g["ry"], g["bands"], g["band_h"], GAP, g["taper"], weave=True, overlay_only=True)
    bands = body(CX, y, g["rx"], g["ry"], g["bands"], g["band_h"], GAP, g["taper"], weave=not small)
    return ears + [rim] + bands


def paniere_paths(small: bool = False, overlay: bool = False) -> list[str]:
    g = dict(PANIERE)
    if small:
        g.update(bands=2, band_h=16.0, handle_w=9.0, open_rx=29.0, open_ry=4.2)
    y = g["rim_y"]
    handle = arch_handle(CX, y + 1.0, g["handle_rx"], g["handle_ry"], g["handle_w"])
    rim = ellipse_ring(CX, y, g["rx"], g["ry"], CX, y + g["open_dy"], g["open_rx"], g["open_ry"])
    if overlay:
        return [] if small else body(CX, y, g["rx"], g["ry"], g["bands"], g["band_h"], GAP, g["taper"], weave=True, overlay_only=True)
    bands = body(CX, y, g["rx"], g["ry"], g["bands"], g["band_h"], GAP, g["taper"], weave=not small)
    return [handle, rim] + bands


def _bbox(name: str) -> tuple[float, float, float, float]:
    if name == "cesta":
        g = CESTA
        ex = g["rx"] - g["ear_in"]
        x0 = min(CX - g["rx"], CX - ex - g["ear_R"])
        y0 = min(g["rim_y"] - g["ry"], g["rim_y"] - 4.0 - g["ear_R"])
    else:
        g = PANIERE
        x0 = CX - g["rx"]
        y0 = g["rim_y"] + 1.0 - g["handle_ry"]
    y1 = body_bottom(g["rim_y"], g["rx"], g["ry"], g["bands"], g["band_h"], GAP)
    return x0, 2 * CX - x0, y0, y1


SYMBOLS = {"cesta": cesta_paths, "paniere": paniere_paths}
ACTIVE = "cesta"


def symbol_paths(variant: str | None = None, overlay: bool = False) -> list[str]:
    """Path del simbolo attivo. Suffisso '-small' = taglio ottico per piccole dimensioni.
    `overlay=True` restituisce i montanti della trama per il secondo tono (versioni a colori)."""
    v = variant or ACTIVE
    small = v.endswith("-small")
    return SYMBOLS[v.removesuffix("-small")](small, overlay)


def use_variant(name: str) -> None:
    global ACTIVE, SYM_X0, SYM_X1, SYM_Y0, SYM_Y1, SYM_W, SYM_H
    ACTIVE = name
    SYM_X0, SYM_X1, SYM_Y0, SYM_Y1 = _bbox(name)
    SYM_W, SYM_H = SYM_X1 - SYM_X0, SYM_Y1 - SYM_Y0


use_variant("cesta")


# ---------------------------------------------------------------------------
# Wordmark "panaru": costruito con un solo cerchio (r=20) e un'asta.
# Altezza x = 50, tratto = 10. Gli archi di n/u riprendono il manico.
# ---------------------------------------------------------------------------
WM_STROKE = 10.0
WM_XH = 50.0
WM_DESC = 22.0


def wordmark_strokes() -> tuple[list[str], float]:
    s = WM_STROKE / 2  # metà tratto
    R = 20.0
    gap = 9.0
    paths = []
    x = 0.0
    # p
    stem = x + s
    paths.append(f"M{f(stem)} 0V{f(WM_XH + WM_DESC)}")
    cx = stem + R
    paths.append(f"M{f(cx - R)} 25A{f(R)} {f(R)} 0 1 1 {f(cx + R)} 25A{f(R)} {f(R)} 0 1 1 {f(cx - R)} 25")
    x = cx + R + s + gap
    # a, n, a, r, u
    def letter_a(x: float) -> float:
        cx = x + s + R
        paths.append(f"M{f(cx - R)} 25A{f(R)} {f(R)} 0 1 1 {f(cx + R)} 25A{f(R)} {f(R)} 0 1 1 {f(cx - R)} 25")
        paths.append(f"M{f(cx + R)} 0V{f(WM_XH)}")
        return cx + R + s + gap

    x = letter_a(x)
    stem = x + s  # n
    paths.append(f"M{f(stem)} 0V{f(WM_XH)}")
    paths.append(f"M{f(stem)} 25A{f(R)} {f(R)} 0 0 1 {f(stem + 2 * R)} 25V{f(WM_XH)}")
    x = stem + 2 * R + s + gap
    x = letter_a(x)
    stem = x + s  # r
    paths.append(f"M{f(stem)} 0V{f(WM_XH)}")
    paths.append(f"M{f(stem)} 25A{f(R)} {f(R)} 0 0 1 {f(stem + R)} 5H{f(stem + R + 4)}")
    x = stem + R + 4 + gap - 2
    stem = x + s  # u
    paths.append(f"M{f(stem)} 0V25A{f(R)} {f(R)} 0 0 0 {f(stem + 2 * R)} 25")
    paths.append(f"M{f(stem + 2 * R)} 0V{f(WM_XH)}")
    width = stem + 2 * R + s
    return paths, width


WM_PATHS, WM_W = wordmark_strokes()


# ---------------------------------------------------------------------------
# Composizione SVG
# ---------------------------------------------------------------------------
def svg(view: tuple[float, float, float, float], body: str, title: str) -> str:
    x, y, w, h = view
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{f(x)} {f(y)} {f(w)} {f(h)}" '
        f'width="{f(w)}" height="{f(h)}" role="img" aria-label="{title}">\n'
        f"  <title>{title}</title>\n{body}</svg>\n"
    )


def g_symbol(color: str, tx: float = 0, ty: float = 0, scale: float = 1, variant: str | None = None,
             ident: str = "simbolo", weave_color: str | None = None) -> str:
    """Simbolo; con `weave_color` i montanti della trama sono disegnati nel secondo tono."""
    paths = "\n".join(f'    <path d="{d}"/>' for d in symbol_paths(variant))
    if weave_color:
        paths += "\n" + "\n".join(f'    <path fill="{weave_color}" d="{d}"/>' for d in symbol_paths(variant, overlay=True))
    return (f'  <g id="{ident}" fill="{color}" transform="translate({f(tx)} {f(ty)}) scale({f(scale)})">\n'
            f"{paths}\n  </g>\n")


def g_wordmark(color: str, tx: float, ty: float, scale: float = 1) -> str:
    paths = "\n".join(f'    <path d="{d}"/>' for d in WM_PATHS)
    return (f'  <g id="wordmark" fill="none" stroke="{color}" stroke-width="{f(WM_STROKE)}" '
            f'stroke-linecap="butt" stroke-linejoin="miter" '
            f'transform="translate({f(tx)} {f(ty)}) scale({f(scale)})">\n{paths}\n  </g>\n')


def symbol_svg(color: str, variant: str | None = None, pad: float = 6, bg: str | None = None) -> str:
    view = (SYM_X0 - pad, SYM_Y0 - pad, SYM_W + 2 * pad, SYM_H + 2 * pad)
    body = ""
    if bg:
        body += f'  <rect x="{f(view[0])}" y="{f(view[1])}" width="{f(view[2])}" height="{f(view[3])}" fill="{bg}"/>\n'
    body += g_symbol(color, variant=variant)
    return svg(view, body, "Panaru — simbolo")


# Lockup orizzontale: base allineata alla linea di base del wordmark.
def hs() -> float:
    """Scala del simbolo nel lockup orizzontale: alto al massimo 1,8 × l'altezza x, largo al massimo 110."""
    return min(1.8 * WM_XH / SYM_H, 110 / SYM_W)


H_GAP = 26.0


def horizontal_svg(sym_color: str, text_color: str, bg: str | None = None, pad: float = 16) -> str:
    sym_w = SYM_W * hs()
    sym_h = SYM_H * hs()
    sx = -SYM_X0 * hs()
    sy = WM_XH - sym_h - SYM_Y0 * hs()
    width = sym_w + H_GAP + WM_W
    top = WM_XH - sym_h
    bottom = WM_XH + WM_DESC
    view = (-pad, top - pad, width + 2 * pad, bottom - top + 2 * pad)
    body = ""
    if bg:
        body += f'  <rect x="{f(view[0])}" y="{f(view[1])}" width="{f(view[2])}" height="{f(view[3])}" rx="12" fill="{bg}"/>\n'
    body += g_symbol(sym_color, sx, sy, hs())
    body += g_wordmark(text_color, sym_w + H_GAP, 0)
    return svg(view, body, "Panaru")


# Lockup principale (verticale): simbolo sopra, wordmark sotto.
def vs() -> float:
    return min(1.9, 160 / SYM_H)


V_GAP = 34.0


def stacked_svg(sym_color: str, text_color: str, bg: str | None = None, pad: float = 24) -> str:
    sym_w = SYM_W * vs()
    sym_h = SYM_H * vs()
    width = max(sym_w, WM_W)
    sx = (width - sym_w) / 2 - SYM_X0 * vs()
    sy = -SYM_Y0 * vs()
    wy = sym_h + V_GAP
    view = (-pad, -pad, width + 2 * pad, wy + WM_XH + WM_DESC + 2 * pad)
    body = ""
    if bg:
        body += f'  <rect x="{f(view[0])}" y="{f(view[1])}" width="{f(view[2])}" height="{f(view[3])}" rx="16" fill="{bg}"/>\n'
    body += g_symbol(sym_color, sx, sy, vs())
    body += g_wordmark(text_color, (width - WM_W) / 2, wy)
    return svg(view, body, "Panaru")


def app_icon_android_foreground(color: str) -> tuple[float, float, float]:
    """Trasformazione del simbolo nel canvas adattivo 108x108 (zona sicura Ø66)."""
    scale = 54 / max(SYM_W, SYM_H)
    tx = 54 - (SYM_X0 + SYM_W / 2) * scale
    ty = 54 - (SYM_Y0 + SYM_H / 2) * scale - 1.0  # leggera compensazione ottica verso l'alto
    return tx, ty, scale


def macos_icon_svg() -> str:
    # Griglia icone macOS: canvas 1024, piastra 824 con raggio ~185, ombra morbida.
    scale = 600 / max(SYM_W, SYM_H)
    tx = 512 - (SYM_X0 + SYM_W / 2) * scale
    ty = 512 - (SYM_Y0 + SYM_H / 2) * scale - 8
    body = (
        '  <defs>\n'
        '    <linearGradient id="piastra" x1="0" y1="0" x2="0" y2="1">\n'
        f'      <stop offset="0" stop-color="#C95E36"/>\n'
        f'      <stop offset="1" stop-color="{C["arancioProfondo"]}"/>\n'
        '    </linearGradient>\n'
        '    <filter id="ombra" x="-10%" y="-10%" width="120%" height="130%">\n'
        '      <feDropShadow dx="0" dy="12" stdDeviation="14" flood-color="#000" flood-opacity="0.28"/>\n'
        '    </filter>\n'
        '  </defs>\n'
        '  <rect x="100" y="100" width="824" height="824" rx="185" fill="url(#piastra)" filter="url(#ombra)"/>\n'
        '  <rect x="100.5" y="100.5" width="823" height="823" rx="184.5" fill="none" stroke="#FFFFFF" stroke-opacity="0.12"/>\n'
    )
    body += g_symbol(BIANCO, tx, ty, scale)
    return svg((0, 0, 1024, 1024), body, "Panaru — icona macOS")


def square_icon_svg(size: float = 512, radius: float = 112, sym_ratio: float = 0.64) -> str:
    scale = size * sym_ratio / max(SYM_W, SYM_H)
    tx = size / 2 - (SYM_X0 + SYM_W / 2) * scale
    ty = size / 2 - (SYM_Y0 + SYM_H / 2) * scale - size * 0.01
    body = f'  <rect width="{f(size)}" height="{f(size)}" rx="{f(radius)}" fill="{ARANCIO}"/>\n'
    body += g_symbol(BIANCO, tx, ty, scale)
    return svg((0, 0, size, size), body, "Panaru — icona")


def favicon_svg() -> str:
    # Taglio ottico piccolo + adattamento al tema del browser.
    scale = 28 / max(SYM_W, SYM_H)
    tx = 16 - (SYM_X0 + SYM_W / 2) * scale
    ty = 16 - (SYM_Y0 + SYM_H / 2) * scale
    paths = "".join(f'<path d="{d}"/>' for d in symbol_paths(f"{ACTIVE}-small"))
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">'
        f'<rect width="32" height="32" rx="7" fill="{ARANCIO}"/>'
        f'<g fill="{BIANCO}" transform="translate({f(tx)} {f(ty)}) scale({f(scale)})">{paths}</g></svg>\n'
    )


def readme_banner_svg() -> str:
    """Banner per README/sito: lockup orizzontale + payoff. Il payoff usa font di sistema."""
    w, h = 1280, 400
    scale = 2.0
    lock_w = (SYM_W * hs() + H_GAP + WM_W) * scale
    tx = (w - lock_w) / 2
    ty = 150
    sym_h = SYM_H * hs()
    body = (
        '  <style>\n'
        f'    .bg {{ fill: {BIANCO}; }} .sym {{ fill: {ARANCIO}; }} .wm {{ stroke: {ANTRACITE}; }} .pay {{ fill: {C["pietra"]}; }}\n'
        '    @media (prefers-color-scheme: dark) {\n'
        f'      .bg {{ fill: {ANTRACITE}; }} .sym {{ fill: {C["arancioVivo"]}; }} .wm {{ stroke: {BIANCO}; }} .pay {{ fill: {C["pietraChiara"]}; }}\n'
        '    }\n'
        '  </style>\n'
        f'  <rect class="bg" width="{w}" height="{h}" rx="24"/>\n'
        f'  <g transform="translate({f(tx)} {f(ty)}) scale({f(scale)})">\n'
    )
    paths = "\n".join(f'      <path d="{d}"/>' for d in symbol_paths())
    body += (f'    <g class="sym" transform="translate({f(-SYM_X0 * hs())} {f(WM_XH - sym_h - SYM_Y0 * hs())}) '
             f'scale({f(hs())})">\n{paths}\n    </g>\n')
    wm = "\n".join(f'      <path d="{d}"/>' for d in WM_PATHS)
    body += (f'    <g class="wm" fill="none" stroke-width="{f(WM_STROKE)}" '
             f'transform="translate({f(SYM_W * hs() + H_GAP)} 0)">\n{wm}\n    </g>\n  </g>\n')
    body += (f'  <text class="pay" x="{w / 2}" y="330" text-anchor="middle" '
             'font-family="-apple-system, BlinkMacSystemFont, \'Segoe UI\', Roboto, Helvetica, Arial, sans-serif" '
             'font-size="34" font-weight="500" letter-spacing="1">Files in. Links out.</text>\n')
    return svg((0, 0, w, h), body, "Panaru — Files in. Links out.")


def android_vector(paths: list[str], tx: float, ty: float, scale: float, color: str, size: float = 108,
                   name: str = "foreground") -> str:
    items = "\n".join(f'        <path android:fillColor="{color}" android:pathData="{d}"/>' for d in paths)
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- Generato da brand/tools/generate_brand.py — non modificare a mano ({name}). -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{f(size)}dp" android:height="{f(size)}dp"\n'
        f'    android:viewportWidth="{f(size)}" android:viewportHeight="{f(size)}">\n'
        f'    <group android:translateX="{f(tx)}" android:translateY="{f(ty)}"\n'
        f'        android:scaleX="{f(scale)}" android:scaleY="{f(scale)}">\n'
        f"{items}\n    </group>\n</vector>\n"
    )


def android_wordmark_vector(color: str) -> str:
    h = WM_XH + WM_DESC
    items = "\n".join(
        f'    <path android:strokeColor="{color}" android:strokeWidth="{f(WM_STROKE)}" android:pathData="{d}"/>'
        for d in WM_PATHS)
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generato da brand/tools/generate_brand.py — non modificare a mano (wordmark). -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{f(WM_W / 4)}dp" android:height="{f(h / 4)}dp"\n'
        f'    android:viewportWidth="{f(WM_W)}" android:viewportHeight="{f(h)}">\n'
        f"{items}\n</vector>\n"
    )


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)
    print("•", path.relative_to(ROOT))


def main() -> None:
    logo = BRAND / "logo"
    # 1. Logo principale (verticale) chiaro/scuro
    write(logo / "panaru-logo.svg", stacked_svg(ARANCIO, ANTRACITE))
    write(logo / "panaru-logo-on-dark.svg", stacked_svg(C["arancioVivo"], BIANCO, bg=ANTRACITE))
    # 2. Simbolo autonomo
    write(logo / "panaru-symbol.svg", symbol_svg(ARANCIO))
    write(logo / "panaru-symbol-small.svg", symbol_svg(ARANCIO, f"{ACTIVE}-small"))
    write(logo / "panaru-symbol-on-dark.svg", symbol_svg(C["arancioVivo"], bg=ANTRACITE))
    # 3. Orizzontale
    write(logo / "panaru-horizontal.svg", horizontal_svg(ARANCIO, ANTRACITE))
    write(logo / "panaru-horizontal-on-dark.svg", horizontal_svg(C["arancioVivo"], BIANCO, bg=ANTRACITE))
    # 4. Monocromatiche
    write(logo / "mono" / "panaru-logo-black.svg", stacked_svg("#000000", "#000000"))
    write(logo / "mono" / "panaru-logo-white.svg", stacked_svg("#FFFFFF", "#FFFFFF"))
    write(logo / "mono" / "panaru-horizontal-black.svg", horizontal_svg("#000000", "#000000"))
    write(logo / "mono" / "panaru-horizontal-white.svg", horizontal_svg("#FFFFFF", "#FFFFFF"))
    write(logo / "mono" / "panaru-symbol-black.svg", symbol_svg("#000000"))
    write(logo / "mono" / "panaru-symbol-white.svg", symbol_svg("#FFFFFF"))
    icons = BRAND / "icons"
    write(icons / "macos" / "panaru-macos-icon.svg", macos_icon_svg())
    write(icons / "web" / "favicon.svg", favicon_svg())
    write(icons / "web" / "panaru-app-icon.svg", square_icon_svg())
    write(icons / "web" / "readme-banner.svg", readme_banner_svg())

    # Android: le icone dell'app derivano dal logo brand/logo/new/panaru.svg (tools/app_icons.py);
    # qui si genera solo il wordmark vettoriale usato nell'interfaccia.
    res = ROOT / "android" / "app" / "src" / "main" / "res"
    write(res / "drawable" / "panaru_wordmark.xml", android_wordmark_vector("#FF24211E"))

    # Variante "paniere" (manico ad arco): set completo per il confronto.
    use_variant("paniere")
    vd = logo / "variants" / "paniere"
    write(vd / "panaru-paniere-symbol.svg", symbol_svg(ARANCIO))
    write(vd / "panaru-paniere-symbol-small.svg", symbol_svg(ARANCIO, "paniere-small"))
    write(vd / "panaru-paniere-logo.svg", stacked_svg(ARANCIO, ANTRACITE))
    write(vd / "panaru-paniere-horizontal.svg", horizontal_svg(ARANCIO, ANTRACITE))
    write(vd / "panaru-paniere-logo-on-dark.svg", stacked_svg(C["arancioVivo"], BIANCO, bg=ANTRACITE))
    write(vd / "panaru-paniere-symbol-black.svg", symbol_svg("#000000"))
    write(vd / "panaru-paniere-app-icon.svg", square_icon_svg())
    write(vd / "panaru-paniere-macos-icon.svg", macos_icon_svg())
    use_variant("cesta")

    print(f"\nSimbolo: {SYM_W:.1f}×{SYM_H:.1f} (griglia 120) — wordmark {WM_W:.1f}×{WM_XH + WM_DESC:.1f}")


if __name__ == "__main__":
    main()

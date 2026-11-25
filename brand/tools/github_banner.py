#!/usr/bin/env python3
"""Immagini per la home GitHub di Panaru (social preview 1280×640 e banner del README).

Tre direzioni sulla stessa idea — *panaru*, il paniere siciliano che raccoglie, custodisce e porta
le tue cose. Claim: «Your files. Your basket. Your rules.»
  A  «Il tuo paniere»                 fondo arancio, paniere bianco, claim bilingue
  B  «Raccogli · Custodisci · Condividi»  i tre gesti del paniere: file → paniere → link
  C  «Dizionario»                     voce pa·nà·ru: il paniere, poi Panaru

Uso:  python3 brand/tools/github_banner.py [rasterizzatore]   → brand/github/*.svg (+ PNG)
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import app_icons  # noqa: E402  (paniere del logo)

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "brand/github"
W, H = 1280, 640

ARANCIO, INTENSO, PROFONDO, TENUE = "#EC5F36", "#CD3D13", "#952D0E", "#FCDDD5"
ANTRACITE, BIANCO, PIETRA, PIETRA_CHIARA, SABBIA = "#24211E", "#FAF6EF", "#6E665D", "#B5AA9C", "#E6D5B8"
FONT = "'Avenir Next', 'Inter', 'Helvetica Neue', Arial, sans-serif"
SERIF = "'Iowan Old Style', 'Georgia', serif"

# Wordmark geometrico del brand (tratti, 322×72, x-height 50): da brand/logo/panaru-horizontal.svg.
WORDMARK = [
    "M5 0V72", "M5 25A20 20 0 1 1 45 25A20 20 0 1 1 5 25", "M64 25A20 20 0 1 1 104 25A20 20 0 1 1 64 25",
    "M104 0V50", "M123 0V50", "M123 25A20 20 0 0 1 163 25V50", "M182 25A20 20 0 1 1 222 25A20 20 0 1 1 182 25",
    "M222 0V50", "M241 0V50", "M241 25A20 20 0 0 1 261 5H265", "M277 0V25A20 20 0 0 0 317 25", "M317 0V50",
]


def wordmark(x: float, y: float, height_x: float, color: str) -> str:
    """Wordmark con x-height `height_x` px, angolo in alto a sinistra in (x, y)."""
    s = height_x / 50
    paths = "".join(f'<path d="{d}"/>' for d in WORDMARK)
    return (f'<g transform="translate({x} {y}) scale({s:.4f})" fill="none" stroke="{color}" '
            f'stroke-width="10">{paths}</g>')


def basket(cx: float, cy: float, height: float, color: str) -> str:
    """Paniere del logo centrato in (cx, cy), alto `height` px."""
    s = height / app_icons.BH
    tx, ty = cx - app_icons.BCX * s, cy - app_icons.BCY * s
    paths = "".join(f'<path d="{d}"/>' for d in app_icons.source_paths())
    return (f'<g transform="translate({tx:.2f} {ty:.2f}) scale({s:.4f})">'
            f'<g transform="{app_icons.SRC_TRANSFORM}" fill="{color}">{paths}</g></g>')


def text(x, y, s, size, color, weight=400, anchor="start", family=FONT, extra=""):
    s = s.replace("&", "&amp;").replace("<", "&lt;")
    return (f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" font-weight="{weight}" '
            f'fill="{color}" text-anchor="{anchor}" {extra}>{s}</text>')


def doc(body: str, title: str) -> str:
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" '
            f'role="img" aria-label="{title}"><title>{title}</title>{body}</svg>\n')


def file_icon(x, y, w, color, fold, label, label_color):
    """Iconcina di documento con angolo piegato ed estensione."""
    h = w * 1.25
    f = w * 0.3
    return (f'<g transform="translate({x} {y})">'
            f'<path d="M0 6Q0 0 6 0H{w - f}L{w} {f}V{h - 6}Q{w} {h} {w - 6} {h}H6Q0 {h} 0 {h - 6}Z" fill="{color}"/>'
            f'<path d="M{w - f} 0V{f - 4}Q{w - f} {f} {w - f + 4} {f}H{w}Z" fill="{fold}"/>'
            + text(w / 2, h * 0.72, label, w * 0.26, label_color, 700, "middle") + "</g>")



FILE_KINDS = {
    # tipo: (colore, etichetta)
    "pdf": ("#E2412F", "PDF"),
    "img": ("#1E9E6A", "JPG"),
    "doc": ("#2F6FD6", "DOC"),
    "zip": ("#8A6A3D", "ZIP"),
}


def typed_file(cx, cy, w, kind, angle=0):
    """Documento riconoscibile per tipo: foglio bianco con angolo piegato, simbolo e fascia colorata
    con l'estensione (PDF rosso, immagine verde, documento blu, archivio marrone)."""
    color, label = FILE_KINDS[kind]
    h = w * 1.3
    f = w * 0.32
    x0, y0 = -w / 2, -h / 2
    sheet = (f'<path d="M{x0 + 3} {y0}H{x0 + w - f}L{x0 + w} {y0 + f}V{y0 + h - 3}Q{x0 + w} {y0 + h} {x0 + w - 3} {y0 + h}'
             f'H{x0 + 3}Q{x0} {y0 + h} {x0} {y0 + h - 3}V{y0 + 3}Q{x0} {y0} {x0 + 3} {y0}Z" fill="#FFFFFF" stroke="#D9D3C7" stroke-width="{w * 0.03:.2f}"/>'
             f'<path d="M{x0 + w - f} {y0}V{y0 + f - 2}Q{x0 + w - f} {y0 + f} {x0 + w - f + 2} {y0 + f}H{x0 + w}Z" fill="#E6E0D4"/>')
    gx, gy, gw = x0 + w * 0.2, y0 + h * 0.22, w * 0.6       # area del simbolo
    if kind == "img":
        glyph = (f'<rect x="{gx}" y="{gy}" width="{gw}" height="{gw * 0.72}" rx="{w * 0.04}" fill="{color}" fill-opacity="0.16"/>'
                 f'<circle cx="{gx + gw * 0.72}" cy="{gy + gw * 0.2}" r="{gw * 0.1}" fill="#F2B33D"/>'
                 f'<path d="M{gx} {gy + gw * 0.72}L{gx + gw * 0.36} {gy + gw * 0.3}L{gx + gw * 0.6} {gy + gw * 0.55}L{gx + gw * 0.75} {gy + gw * 0.42}L{gx + gw} {gy + gw * 0.72}Z" fill="{color}"/>')
    elif kind == "pdf":
        # tre curve intrecciate, in stile Acrobat stilizzato
        cx0, cy0, r = gx + gw / 2, gy + gw * 0.36, gw * 0.34
        glyph = (f'<g fill="none" stroke="{color}" stroke-width="{w * 0.06:.2f}" stroke-linecap="round">'
                 f'<path d="M{cx0 - r} {cy0 + r * 0.9}Q{cx0} {cy0 - r * 1.6} {cx0 + r} {cy0 + r * 0.9}"/>'
                 f'<path d="M{cx0 - r * 1.05} {cy0 + r * 0.9}Q{cx0} {cy0 + r * 0.2} {cx0 + r * 1.05} {cy0 + r * 0.6}"/></g>')
    elif kind == "zip":
        glyph = "".join(f'<rect x="{gx + gw / 2 - w * 0.05 + (w * 0.05 if i % 2 else 0)}" y="{gy + i * w * 0.08}" width="{w * 0.06}" height="{w * 0.06}" fill="{color}"/>' for i in range(6))
    else:
        glyph = "".join(f'<rect x="{gx}" y="{gy + i * w * 0.13}" width="{gw * (0.6 if i == 3 else 1)}" height="{w * 0.06}" rx="{w * 0.03}" fill="{color}" fill-opacity="{0.9 if i == 0 else 0.45}"/>' for i in range(4))
    band_h = h * 0.26
    band = (f'<path d="M{x0} {y0 + h - band_h}H{x0 + w}V{y0 + h - 3}Q{x0 + w} {y0 + h} {x0 + w - 3} {y0 + h}H{x0 + 3}Q{x0} {y0 + h} {x0} {y0 + h - 3}Z" fill="{color}"/>'
            + text(0, y0 + h - band_h * 0.28, label, band_h * 0.68, "#FFFFFF", 700, "middle"))
    return (f'<g transform="translate({cx} {cy}) rotate({angle})"><g filter="url(#fs)">{sheet}</g>{glyph}{band}</g>')


FILE_SHADOW = ('<defs><filter id="fs" x="-30%" y="-30%" width="160%" height="160%">'
               '<feDropShadow dx="0" dy="1.5" stdDeviation="1.5" flood-color="#3a2a1a" flood-opacity="0.18"/></filter></defs>')

# --------------------------------------------------------------------------- A · Il tuo paniere
def concept_a() -> str:
    b = [f'<rect width="{W}" height="{H}" fill="{ARANCIO}"/>',
         f'<defs><radialGradient id="g" cx="0.5" cy="0.5" r="0.5"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.22"/>'
         f'<stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient></defs>',
         f'<circle cx="320" cy="320" r="300" fill="url(#g)"/>',
         basket(320, 318, 350, "#FFFFFF"),
         wordmark(600, 132, 80, "#FFFFFF"),
         text(602, 306, "Your files. Your basket.", 44, "#FFFFFF", 500),
         text(602, 358, "Your rules.", 44, "#FFFFFF", 700),
         text(602, 412, "I tuoi file. Il tuo paniere. Le tue regole.", 25, "#FFFFFF", 400, extra='fill-opacity="0.88"'),
         f'<line x1="602" y1="452" x2="1190" y2="452" stroke="#FFFFFF" stroke-opacity="0.35" stroke-width="2"/>',
         text(602, 494, "A Sicilian idea for sharing files your way.", 22, "#FFFFFF", 500, extra='fill-opacity="0.92"'),
         text(602, 530, "Open-source client for Gokapi · Android · macOS · Linux", 20, "#FFFFFF", 400, extra='fill-opacity="0.75"')]
    return doc("".join(b), "Panaru — Your files. Your basket. Your rules.")


# --------------------------------------------------------------------------- B · Raccogli, custodisci, condividi
def step_label(cx, en, it):
    return text(cx, 520, en, 30, ANTRACITE, 500, "middle") + text(cx, 556, it, 22, PIETRA, 400, "middle")


def concept_b() -> str:
    arrow = lambda x: (f'<path d="M{x} 380H{x + 70}M{x + 56} 366L{x + 72} 380L{x + 56} 394" stroke="{PIETRA_CHIARA}" '
                       f'stroke-width="5" stroke-linecap="round" stroke-linejoin="round" fill="none"/>')
    b = [f'<rect width="{W}" height="{H}" fill="{BIANCO}"/>',
         wordmark(80, 64, 52, ANTRACITE),
         text(1200, 100, "Your files. Your basket. Your rules.", 30, ANTRACITE, 500, "end"),
         text(1200, 136, "I tuoi file. Il tuo paniere. Le tue regole.", 21, PIETRA, 400, "end"),
         # 1 · raccogli: file sparsi
         f'<circle cx="250" cy="380" r="110" fill="{TENUE}"/>',
         FILE_SHADOW,
         typed_file(205, 372, 58, "doc", -14),
         typed_file(295, 372, 58, "img", 14),
         typed_file(250, 386, 62, "pdf", 0),
         arrow(390),
         # 2 · custodisci: il paniere sul tuo server
         f'<circle cx="640" cy="380" r="110" fill="{ARANCIO}"/>',
         basket(640, 384, 140, "#FFFFFF"),
         f'<g transform="translate(700 452)"><rect width="74" height="34" rx="17" fill="{ANTRACITE}"/>'
         + text(37, 23, "Gokapi", 15, "#FFFFFF", 500, "middle") + "</g>",
         arrow(780),
         # 3 · condividi: il link
         f'<circle cx="1030" cy="380" r="110" fill="{TENUE}"/>',
         f'<g transform="translate(930 352)"><rect width="200" height="56" rx="28" fill="{ANTRACITE}"/>'
         + text(28, 37, "🔗", 22, "#FFFFFF") + text(62, 37, "…/d?id=…", 22, "#FFFFFF", 600) + "</g>",
         step_label(250, "Collect", "Raccogli"),
         step_label(640, "Keep", "Custodisci"),
         step_label(1030, "Share", "Condividi"),
         text(640, 612, "Files in. Links out. · Open-source client for Gokapi · Android · macOS · Linux", 18, PIETRA, 400, "middle")]
    return doc("".join(b), "Panaru — Collect, keep, share")


# --------------------------------------------------------------------------- C · Dizionario
def concept_c() -> str:
    b = [f'<rect width="{W}" height="{H}" fill="{ANTRACITE}"/>',
         basket(1075, 245, 220, ARANCIO),
         text(90, 146, "pa·nà·ru", 108, BIANCO, 400, family=SERIF),
         text(94, 194, "/paˈnaːɾu/  ·  s.m., siciliano  ·  noun, Sicilian", 23, PIETRA_CHIARA, 400, family=SERIF, extra='font-style="italic"'),
         f'<line x1="94" y1="226" x2="800" y2="226" stroke="#4A443D" stroke-width="2"/>',
         text(94, 278, "1  Paniere tradizionale per raccogliere, custodire e portare le proprie cose.", 23, BIANCO, 400, family=SERIF),
         text(122, 314, "A traditional basket to gather, carry and keep things together.", 21, PIETRA_CHIARA, 400, family=SERIF, extra='font-style="italic"'),
         text(94, 374, "2  Client open source per Gokapi: raccoglie i tuoi file e li trasforma in link.", 23, BIANCO, 400, family=SERIF),
         text(122, 410, "An open-source Gokapi client: gathers your files and turns them into links.", 21, PIETRA_CHIARA, 400, family=SERIF, extra='font-style="italic"'),
         wordmark(94, 486, 40, "#EE724E"),
         text(380, 526, "Your files. Your basket. Your rules.", 24, BIANCO, 500),
         text(380, 560, "Made with care, inspired by Sicily · Android · macOS · Linux", 19, PIETRA_CHIARA, 400)]
    return doc("".join(b), "Panaru — voce di dizionario")


# --------------------------------------------------------------------------- Sintesi (A + B + C)
def mini_step(cx, cy, kind, en, it):
    """Passo compatto: cerchio con icona, verbo inglese e italiano."""
    out = f'<circle cx="{cx}" cy="{cy}" r="38" fill="{ARANCIO if kind == "keep" else TENUE}"/>'
    if kind == "collect":
        out += (typed_file(cx - 15, cy + 2, 27, "doc", -16) + typed_file(cx + 15, cy + 2, 27, "img", 16)
                + typed_file(cx, cy + 4, 29, "pdf", 0))
    elif kind == "keep":
        out += basket(cx, cy + 1, 44, "#FFFFFF")
    else:
        out += (f'<g transform="translate({cx - 26} {cy - 13})"><rect width="52" height="26" rx="13" fill="{ANTRACITE}"/>'
                + text(26, 18, "🔗", 13, "#FFFFFF", 400, "middle") + "</g>")
    out += text(cx + 54, cy - 4, en, 24, ANTRACITE, 500) + text(cx + 54, cy + 22, it, 17, PIETRA, 400)
    return out


def concept_sintesi() -> str:
    panel = 470
    arrow = lambda x: (f'<path d="M{x} 548H{x + 26}M{x + 18} 541L{x + 27} 548L{x + 18} 555" stroke="{PIETRA_CHIARA}" '
                       f'stroke-width="3" stroke-linecap="round" stroke-linejoin="round" fill="none"/>')
    b = [f'<rect width="{W}" height="{H}" fill="{BIANCO}"/>', FILE_SHADOW,
         # A · il paniere sul fondo arancio
         f'<rect width="{panel}" height="{H}" fill="{ARANCIO}"/>',
         f'<defs><radialGradient id="g" cx="0.5" cy="0.5" r="0.5"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.22"/>'
         f'<stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient></defs>',
         f'<circle cx="{panel / 2}" cy="270" r="230" fill="url(#g)"/>',
         basket(panel / 2, 262, 270, "#FFFFFF"),
         wordmark(panel / 2 - 161 * 0.8, 470, 40, "#FFFFFF"),
         text(panel / 2, 578, "Files in. Links out.", 21, "#FFFFFF", 500, "middle", extra='fill-opacity="0.9"'),
         # C · voce di dizionario
         text(530, 112, "pa·nà·ru", 72, ANTRACITE, 400, family=SERIF),
         text(820, 110, "/paˈnaːɾu/ · s.m., siciliano · noun, Sicilian", 19, PIETRA, 400, family=SERIF, extra='font-style="italic"'),
         text(532, 156, "Paniere tradizionale per raccogliere, custodire e portare le proprie cose.", 20, ANTRACITE, 400, family=SERIF),
         text(532, 184, "A traditional basket to gather, carry and keep things together.", 18, PIETRA, 400, family=SERIF, extra='font-style="italic"'),
         f'<line x1="532" y1="222" x2="1200" y2="222" stroke="{SABBIA}" stroke-width="2"/>',
         # claim
         text(530, 292, "Your files. Your basket.", 46, ANTRACITE, 500),
         text(530, 346, "Your rules.", 46, INTENSO, 700),
         text(532, 392, "I tuoi file. Il tuo paniere. Le tue regole.", 22, PIETRA, 400),
         # B · raccogli, custodisci, condividi
         mini_step(574, 540, "collect", "Collect", "Raccogli"),
         arrow(732),
         mini_step(808, 540, "keep", "Keep", "Custodisci"),
         arrow(980),
         mini_step(1056, 540, "share", "Share", "Condividi"),
         text(1200, 620, "Open-source client for Gokapi · Android · macOS · Linux", 15, PIETRA_CHIARA, 400, "end")]
    return doc("".join(b), "Panaru — Your files. Your basket. Your rules.")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("social-preview-*"):
        old.unlink()
    for name, fn in (("a-paniere", concept_a), ("b-raccogli", concept_b), ("c-dizionario", concept_c), ("sintesi", concept_sintesi)):
        svg = OUT / f"social-preview-{name}.svg"
        svg.write_text(fn())
        if len(sys.argv) > 1:
            subprocess.run([sys.argv[1], str(svg), str(svg.with_suffix(".png")), str(W)], check=True)
        print("•", svg.relative_to(ROOT))


if __name__ == "__main__":
    main()

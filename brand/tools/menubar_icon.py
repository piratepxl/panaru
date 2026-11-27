"""Glifo della barra dei menu di macOS (stile "linea", scelto dal committente).

Paniere disegnato a tratto, coerente con le icone di sistema della barra dei menu:
manico alto (circa metà dell'icona, proporzioni dell'illustrazione originale), bordo, corpo
rastremato e una croce che accenna la trama. Occupa tutta l'area dell'icona (viewBox 100×100,
il tratto tocca i bordi). È un'immagine *template*: nero + alfa, macOS la tinge.

La variante "attiva" (trasferimento in corso) aggiunge una freccia verso l'alto sotto il manico.
"""
from __future__ import annotations


def menubar_svg(active: bool = False, color: str = "#000000") -> str:
    arrow = (
        f'\n    <path d="M50 42V19M40.5 28.5L50 19L59.5 28.5" stroke-width="6.5"/>' if active else ""
    )
    title = "Panaru" + (" — trasferimento in corso" if active else "")
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100" '
        f'role="img" aria-label="{title}">\n'
        f'  <title>{title}</title>\n'
        '  <!-- Generato da brand/tools/menubar_icon.py -->\n'
        f'  <g fill="none" stroke="{color}" stroke-linecap="round" stroke-linejoin="round">\n'
        '    <path d="M15 50A35 44.5 0 0 1 85 50" stroke-width="7"/>\n'
        '    <path d="M4 50H96" stroke-width="7"/>\n'
        '    <path d="M10.5 50L18 88.5Q19.5 96.5 27.5 96.5H72.5Q80.5 96.5 82 88.5L89.5 50" stroke-width="7"/>\n'
        '    <path d="M15 72H85M50 72V96.5" stroke-width="5" stroke-linecap="butt"/>'
        f'{arrow}\n'
        '  </g>\n</svg>\n'
    )

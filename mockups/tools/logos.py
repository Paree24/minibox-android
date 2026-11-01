"""Minibox app logo options (2026-11-01): writes mockups/logos/<name>.svg and .png (512 px), and
mockups/png/logo_options.png: each option full size, in Android's round mask, and at launcher size (48 px).

    python3 mockups/tools/logos.py
"""
import os
import shutil
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "mockups", "logos")
BG = "#141518"
SURF = "#20232A"
TEXT = "#ECE8E3"
LILAC = "#B9A7F0"
RED = "#E88A8A"
MINT = "#8FD0C2"
PINK = "#E9A3B8"
GREEN = "#A9D58E"
BLUE = "#9EB9F3"
AMBER = "#E8B86A"
PEACH = "#F0A07C"


def svg(body, bg=BG):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">'
            f'<rect width="108" height="108" fill="{bg}"/>{body}</svg>')


def rect(x, y, w, h, fill, r=0, extra=""):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}" {extra}/>'


def four_square():
    """1 · Pastel mark (now): four squares (the family logo)."""
    return svg(rect(30, 30, 22, 22, RED) + rect(56, 30, 22, 22, MINT) + rect(30, 56, 22, 22, PINK) + rect(56, 56, 22, 22, GREEN))


def rounded_four():
    """2 · Rounded pads: four rounded pads, the top-left one lilac (the pattern colour)."""
    return svg(rect(29, 29, 23, 23, LILAC, 6) + rect(56, 29, 23, 23, MINT, 6) + rect(29, 56, 23, 23, PINK, 6) + rect(56, 56, 23, 23, AMBER, 6))


def pad_grid():
    """3 · Pad grid: a 4×4 grid, a beat lit across it (the app is pads first)."""
    lit = {0: BLUE, 5: MINT, 10: AMBER, 15: PINK, 3: LILAC, 12: GREEN}
    b = rect(22, 22, 64, 64, SURF, 12)
    for i in range(16):
        x, y = 27 + (i % 4) * 14.5, 27 + (i // 4) * 14.5
        b += rect(x, y, 11, 11, lit.get(i, "#2E323A"), 3)
    return svg(b)


def cube():
    """4 · Mini box: a small cube in three pastel faces."""
    top = f'<path d="M54 22 L82 37 L54 52 L26 37 Z" fill="{LILAC}"/>'
    left = f'<path d="M26 37 L54 52 L54 86 L26 71 Z" fill="{MINT}"/>'
    right = f'<path d="M82 37 L54 52 L54 86 L82 71 Z" fill="{PINK}"/>'
    return svg(top + left + right)


def four_play():
    """5 · Mark that plays: the family mark that plays."""
    b = rect(30, 30, 22, 22, RED) + rect(56, 30, 22, 22, MINT) + rect(30, 56, 22, 22, PINK) + rect(56, 56, 22, 22, LILAC)
    b += f'<path d="M62 61 L74 67 L62 73 Z" fill="{BG}"/>'
    return svg(b)


def steps():
    """6 · Steps: a row of eight sequencer steps over a playhead, in the box's frame."""
    b = rect(18, 30, 72, 48, SURF, 12)
    cols = [BLUE, "#2E323A", MINT, "#2E323A", AMBER, PINK, "#2E323A", LILAC]
    for i, c in enumerate(cols):
        b += rect(24 + i * 8.2, 40, 6, 28, c, 3)
    b += rect(24 + 4 * 8.2 - 1.5, 36, 9, 36, "none", 4, f'stroke="{TEXT}" stroke-width="1.5"')
    return svg(b)


def monogram():
    """7 · mb monogram: lowercase, the b in the pattern lilac."""
    b = (f'<text x="54" y="70" font-family="DejaVu Sans, Arial, sans-serif" font-weight="900" font-size="42" text-anchor="middle" '
         f'letter-spacing="-2"><tspan fill="{TEXT}">m</tspan><tspan fill="{LILAC}">b</tspan></text>')
    return svg(b)


def wave_box():
    """8 · Sound in a box: a pastel wave inside a rounded box, a knob's arc around it."""
    b = rect(32, 32, 44, 44, SURF, 11)
    b += (f'<path d="M38 58 C42 44, 46 44, 50 54 S57 66, 61 54 S68 42, 71 50" fill="none" stroke="{MINT}" '
          f'stroke-width="4.5" stroke-linecap="round"/>')
    b += f'<circle cx="54" cy="54" r="36" fill="none" stroke="{LILAC}" stroke-width="3.5" stroke-linecap="round" stroke-dasharray="170 56" transform="rotate(125 54 54)"/>'
    return svg(b)


OPTIONS = [("1_four_square", four_square), ("2_rounded_four", rounded_four), ("3_pad_grid", pad_grid), ("4_cube", cube),
           ("5_four_play", four_play), ("6_steps", steps), ("7_monogram", monogram), ("8_wave_box", wave_box)]


def main():
    os.makedirs(OUT, exist_ok=True)
    rsvg = shutil.which("rsvg-convert")
    pngs = []
    for name, fn in OPTIONS:
        p = os.path.join(OUT, name + ".svg")
        open(p, "w").write(fn())
        if rsvg:
            png = os.path.join(OUT, name + ".png")
            subprocess.run([rsvg, "-w", "512", "-h", "512", "-o", png, p], check=True)
            pngs.append((name, fn.__doc__.split("·")[1].strip().split(":")[0], png))
        print(os.path.relpath(p, ROOT))
    from PIL import Image, ImageDraw, ImageFont
    cell, gap = 380, 40
    sheet = Image.new("RGB", (4 * (cell + gap) + gap, 2 * (cell // 2 + 150) + gap), (28, 29, 33))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 22)
    except OSError:
        font = ImageFont.load_default()
    for i, (name, title, png) in enumerate(pngs):
        x = gap + (i % 4) * (cell + gap)
        y = gap + (i // 4) * (cell // 2 + 150)
        im = Image.open(png).convert("RGBA")
        full = im.resize((cell // 2 - 10, cell // 2 - 10))
        mask = Image.new("L", full.size, 0)
        ImageDraw.Draw(mask).ellipse((0, 0, full.size[0] - 1, full.size[1] - 1), fill=255)
        sheet.paste(full, (x, y))                            # full tile
        sheet.paste(full, (x + cell // 2 + 10, y), mask)     # in Android's round mask
        small = im.resize((48, 48))
        sm = Image.new("L", (48, 48), 0)
        ImageDraw.Draw(sm).ellipse((0, 0, 47, 47), fill=255)
        sheet.paste(small, (x, y + cell // 2 + 10), sm)      # launcher size
        d.text((x + 60, y + cell // 2 + 22), "48 px", fill=(140, 140, 140), font=font)
        d.text((x, y + cell // 2 + 80), f"{i + 1} · {title}", fill=(236, 232, 227), font=font)
    out = os.path.join(ROOT, "mockups", "png", "logo_options.png")
    sheet.save(out)
    print(os.path.relpath(out, ROOT))


if __name__ == "__main__":
    main()

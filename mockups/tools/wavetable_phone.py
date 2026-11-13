"""Wavetable on the phone (2026-11-13): the Sound tab for the bass synth, mockups before implementation.

The phone's Sound tab as for the other synths (preset bar, section tabs, one section at a time, the pads folded into
a handle), with the five macros as a strip above the tabs, where the thumb reaches them. Writes
mockups/svg/wavetable_*.svg, png/ and png/wavetable_phone.png (the screens side by side).

    python3 mockups/tools/wavetable_phone.py
"""
import math
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import build as B  # noqa: E402  (puts the desktop kit on the path)
from svgkit import T, knob, pill, segmented, well, icon_chevron, icon_dots, tw  # noqa: E402
import wavetable_mockup as WT  # noqa: E402

W, M, CW = B.W, B.M, B.CW
B.RACK[1] = ("wavetable", "Wavetable", "Foghorn Reese")   # track 2 plays the new synth


def _chip_fix(track_row):
    """The selected chip, redrawn with the longer name at a size that clears the caret."""
    def row(s, sel, y=B.TRACKS_Y):
        keep = B.RACK[sel]
        B.RACK[sel] = (keep[0], "", keep[2])
        track_row(s, sel, y)
        B.RACK[sel] = keep
        x = M + sel * (32 + 5)
        s.text(x + 36, y + 22.5, keep[1], 12, 900, T["bg"])
    return row


B.track_row = _chip_fix(B.track_row)
COLOR = B.TRACK[1]
TABS = ["Osc A", "Osc B", "Sub", "Filter", "Env", "LFO", "Dist", "Matrix", "Unison"]
MACROS = [("Wobble", .55), ("Movement", .4), ("Dirt", .62), ("Bite", .5), ("Width", .7)]


def dice(s, cx, cy, size, color):
    h = size / 2
    s.rect(cx - h, cy - h, size, size, stroke=color, r=4, sw=1.8)
    for dx, dy in ((-0.45, -0.45), (0.45, 0.45), (0, 0), (0.45, -0.45), (-0.45, 0.45)):
        s.circle(cx + dx * h * 0.9, cy + dy * h * 0.9, 1.6, color)


def preset_bar(s, y):
    """‹ name family ›, then randomize and the preset menu."""
    w = CW - 100
    s.rect(M, y, w, 40, T["control"], r=20)
    icon_chevron(s, M + 20, y + 20, 8, T["quiet"], "left", 2)
    icon_chevron(s, M + w - 20, y + 20, 8, T["quiet"], "right", 2)
    name, fam = "Foghorn Reese", "Reese"
    s.text(M + w / 2 - 24, y + 25.5, name, 14, 900, T["text"], "middle")
    pill(s, M + w / 2 + 38, y + 9, fam, 10, h=22, padx=8, fill=T["bg"], color=COLOR)
    s.circle(W - M - 70, y + 20, 20, T["control"])
    dice(s, W - M - 70, y + 20, 15, COLOR)
    s.circle(W - M - 20, y + 20, 20, T["control"])
    icon_dots(s, W - M - 20, y + 20, T["quiet"], gap=5, r=1.8)


def macro_strip(s, y, h=84):
    s.rect(M, y, CW, h, T["surface"], r=16)
    for i, (name, v) in enumerate(MACROS):
        knob(s, M + CW * (i + 0.5) / len(MACROS), y + 32, 17, v, COLOR, name, label_size=11)
    return h


def tabs(s, y, sel):
    """The section tabs scroll sideways; the selected one is kept in view."""
    widths = [tw(t, 13, 900) + 28 for t in TABS]
    x = M
    xs = []
    for wd in widths:
        xs.append(x)
        x += wd + 8
    shift = max(0, xs[sel] + widths[sel] - (W - M - 40))
    for i, t in enumerate(TABS):
        tx = xs[i] - shift
        if tx + widths[i] < 0 or tx > W:
            continue
        on = i == sel
        pill(s, tx, y, t, 13, fill=COLOR if on else T["control"], color=T["bg"] if on else T["quiet"], h=34, padx=14,
             weight=900 if on else 700, w=widths[i])


def knobs(s, x, y, w, items, cols, row_h=92, r=22):
    for i, k in enumerate(items):
        c, rr = i % cols, i // cols
        in_row = min(cols, len(items) - rr * cols)
        cw = w / cols
        x0 = x + (w - in_row * cw) / 2
        knob(s, x0 + cw * (c + 0.5), y + r + 8 + rr * row_h, r, k[1], COLOR, k[0], bipolar=len(k) > 3 and k[3], label_size=11,
             value=k[2] if len(k) > 2 else None)


def frame(sel):
    s = B.chrome("Sound", 1)
    y = B.BODY + 8
    preset_bar(s, y)
    y += 50
    y += macro_strip(s, y) + 10
    tabs(s, y, sel)
    y += 46
    s.rect(M, y, CW, B.EDIT_END - y, T["surface"], r=16)
    B.pads_drawer(s, COLOR)
    return s, y


def osc_a(out):
    s, y = frame(0)
    pill(s, M + 12, y + 12, "Reese Saw", 12, fill=T["control"], color=COLOR, h=30, padx=12, weight=900, caret=True)
    WT.wavetable(s, M + 12, y + 52, CW - 24, 150, COLOR, WT.frame_reese, .38)
    knobs(s, M + 12, y + 216, CW - 24, [("Position", .38, "38%"), ("Octave", .5, "0", True), ("Semi", .5, "0", True),
                                        ("Fine", .5, "0 ct", True), ("Phase", .0, "Free"), ("Level", .8, "80%")], 3)
    s.save(out)


def osc_b(out):
    s, y = frame(1)
    pill(s, M + 12, y + 12, "Growl", 12, fill=T["control"], color=COLOR, h=30, padx=12, weight=900, caret=True)
    segmented(s, M + 112, y + 12, CW - 124, 30, ["Off", "Sync", "FM", "Ring"], 2, COLOR, 12)
    WT.wavetable(s, M + 12, y + 52, CW - 24, 130, COLOR, WT.frame_growl, .62)
    knobs(s, M + 12, y + 192, CW - 24, [("Position", .62, "62%"), ("FM", .45, "45%"), ("Octave", .5, "0", True), ("Semi", .5, "+7", True),
                                        ("Fine", .55, "+4 ct", True), ("Phase", .25, "90°"), ("Level", .6, "60%")], 4, row_h=86, r=19)
    s.save(out)


def lfo(out):
    s, y = frame(5)
    segmented(s, M + 12, y + 12, CW - 100, 30, ["LFO 1", "LFO 2"], 0, COLOR, 12)
    pill(s, W - M - 12, y + 12, "Sync", 12, fill=COLOR, color=T["bg"], h=30, padx=14, weight=900, anchor="end")
    vy, vh = y + 54, 120
    well(s, M + 12, vy, CW - 24, vh)
    pts = [(M + 22 + i / 160 * (CW - 44), vy + vh * 0.42 - math.sin(2 * math.pi * 3 * i / 160 - 1.2) * vh * 0.28) for i in range(161)]
    s.path(s.poly(pts), stroke=COLOR, sw=2.4)
    px = M + 22
    for t, on in (("→ Cutoff", True), ("→ Position A", False), ("+", False)):
        wd = pill(s, px, vy + vh - 32, t, 11, fill=COLOR if on else T["control"], color=T["bg"] if on else T["quiet"], h=24, padx=10,
                  weight=900 if on else 700)
        px += wd + 6
    segmented(s, M + 12, vy + vh + 12, CW - 24, 30, ["Sine", "Tri", "Saw", "Square", "S&H"], 0, COLOR, 12)
    knobs(s, M + 12, vy + vh + 54, CW - 24, [("Rate", .55, "1/8"), ("Depth", .7, "70%"), ("Fade", .0, "0 ms")], 3)
    s.save(out)


def matrix(out):
    s, y = frame(7)
    rows = WT.MATRIX + [None]
    rows = [r for r in rows if r is not None][:5] + [None]
    rh = 52
    for i, m in enumerate(rows):
        ry = y + 14 + i * rh
        if m is None:
            s.rect(M + 12, ry, CW - 24, rh - 10, stroke=T["faint"], r=(rh - 10) / 2, sw=1.2, dash="4 4")
            s.text(W / 2, ry + (rh - 10) / 2 + 4, "+ Add", 12, 700, T["faint"], "middle")
            continue
        src, dst, amt = m
        s.rect(M + 12, ry, CW - 24, rh - 10, T["control"], r=(rh - 10) / 2)
        s.text(M + 28, ry + 17, src, 12, 900, T["text"])
        s.text(M + 28 + tw(src, 12, 900) + 8, ry + 17, "→", 12, 900, T["faint"])
        s.text(M + 28 + tw(src, 12, 900) + 26, ry + 17, dst, 12, 900, COLOR)
        s.text(W - M - 26, ry + 17, f"{'+' if amt > 0 else '−'}{abs(amt) * 100:.0f}%", 12, 900, T["quiet"], "end")
        bx, bw, by = M + 28, CW - 56, ry + 30
        s.rect(bx, by - 2, bw, 4, T["divider"], r=2)
        mid = bx + bw / 2
        s.line(mid, by - 5, mid, by + 5, T["faint"], 1.5)
        x1 = mid + amt * bw / 2
        s.rect(min(mid, x1), by - 2, abs(x1 - mid), 4, COLOR, r=2)
    s.save(out)


SCREENS = [("wavetable_osc_a", osc_a, "Osc A"), ("wavetable_osc_b", osc_b, "Osc B: FM from A"),
           ("wavetable_lfo", lfo, "LFO"), ("wavetable_matrix", matrix, "Mod Matrix")]


def main():
    root = os.path.dirname(os.path.dirname(HERE))
    svg_dir, png_dir = os.path.join(root, "mockups", "svg"), os.path.join(root, "mockups", "png")
    rsvg = shutil.which("rsvg-convert")
    pngs = []
    for name, fn, _ in SCREENS:
        path = os.path.join(svg_dir, name + ".svg")
        fn(path)
        if rsvg:
            p = os.path.join(png_dir, name + ".png")
            subprocess.run([rsvg, "-z", "2", "-o", p, path], check=True)
            pngs.append(p)
        print(os.path.relpath(path, root))
    if pngs:   # side by side, with a caption over each
        from PIL import Image, ImageDraw, ImageFont
        ims = [Image.open(p) for p in pngs]
        gap, cap = 40, 70
        out = Image.new("RGB", (sum(i.width for i in ims) + gap * (len(ims) + 1), ims[0].height + cap + gap), T["bg"])
        d = ImageDraw.Draw(out)
        try:
            font = ImageFont.truetype("/usr/share/fonts/TTF/Lato-Black.ttf", 34)
        except OSError:
            font = ImageFont.load_default()
        x = gap
        for im, (_, _, title) in zip(ims, SCREENS):
            d.text((x, 20), title, fill=T["quiet"], font=font)
            out.paste(im, (x, cap))
            x += im.width + gap
        out.save(os.path.join(png_dir, "wavetable_phone.png"))
        print("mockups/png/wavetable_phone.png")


if __name__ == "__main__":
    main()

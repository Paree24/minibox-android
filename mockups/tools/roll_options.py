"""Piano roll layout options (2026-11-01): four ways to give the roll more of the screen. Writes
mockups/svg/roll_option_{a..d}.svg and PNGs, plus png/roll_options.png with the four side by side.

    python3 mockups/tools/roll_options.py
"""
import os
import shutil
import subprocess

import build as B
from build import T, M, W, H, CW, BODY, NAV_Y, TRACK, segmented, pill, icon_undo, icon_play, icon_stop, icon_chevron, icon_trash

COLOR = TRACK[2]
# one octave of C minor, top to bottom, and a two-bar chord-and-line clip on it (start, length in 1/16 steps)
ROWS = ["C3", "Bb2", "Ab2", "G2", "F2", "Eb2", "D2", "C2"]
CLIP = [(0, 32, "C2", .9), (0, 32, "Eb2", .9), (0, 32, "G2", .9), (2, 2, "C3", .8), (6, 2, "Bb2", .7), (10, 4, "Ab2", .8),
        (16, 2, "G2", .7), (20, 6, "F2", .9), (28, 2, "Bb2", .7)]
SEL = {3}
STEPS = 32   # two bars in view
EDITS = ["↓", "↑", "8↓", "8↑", "Cut", "Copy", "Paste", "Q"]


def hroll(s, x, y, w, h, rows=ROWS, steps=STEPS, ruler=True):
    """A roll with time across and the scale's rows down; keys on the left."""
    kw = 40
    x0 = x + kw + 4
    gw = w - kw - 4 - 28           # room after the end (the edge-swipe zone)
    cw = gw / steps
    gy = y + (18 if ruler else 0)
    rh = (y + h - gy) / len(rows)
    if ruler:
        for b in range(steps // 4):
            if b % 2 == 0 or cw * 4 > 30:
                s.text(x0 + b * 4 * cw + 3, y + 12, f"{b // 4 + 1}.{b % 4 + 1}", 10, 700, T["faint"])
    s.rect(x0, gy, w - kw - 4, rh * len(rows), T["surface"], r=8)
    for r, n in enumerate(rows):
        ry = gy + r * rh
        root = n.startswith("C")
        if root:
            s.rect(x0, ry, w - kw - 4, rh, COLOR, opacity=0.08)
        s.rect(x, ry + 0.5, kw, rh - 1, COLOR if root else T["control"], r=4)
        s.text(x + kw - 5, ry + rh / 2 + 4, n, 10, 900, T["bg"] if root else T["quiet"], "end")
        s.line(x0, ry + rh, x0 + gw, ry + rh, T["divider"], 0.6)
    for c in range(steps + 1):
        bar, beat = c % 16 == 0, c % 4 == 0
        s.line(x0 + c * cw, gy, x0 + c * cw, gy + rh * len(rows), T["quiet"] if bar else T["faint"],
               1.6 if bar else (1.1 if beat else 0.6), opacity=0.7 if bar else (0.8 if beat else 0.3))
    for i, (st, ln, n, v) in enumerate(CLIP):
        if n not in rows:
            continue
        nx, ny = x0 + st * cw + 1, gy + rows.index(n) * rh + 2
        nw, nh = ln * cw - 2, rh - 4
        sel = i in SEL
        s.rect(nx, ny, nw, nh, COLOR, r=4, opacity=1 if sel else 0.45 + 0.45 * v)
        if sel:
            s.rect(nx - 1.5, ny - 1.5, nw + 3, nh + 3, stroke=T["text"], r=5, sw=2)
    px = x0 + 9.5 * cw
    s.line(px, y + 14, px, gy + rh * len(rows), T["pattern"], 2)


def vroll(s, x, y, w, h, rows=ROWS, steps=STEPS):
    """A roll turned on its side, like the step editor: the scale across the top as keys, time running down."""
    cols = list(reversed(rows))   # low notes on the left, as on a keyboard
    kh, rl = 40, 30               # keys row, step-number column
    gx = x + rl
    cw = (w - rl) / len(cols)
    gy = y + kh + 4
    gh = y + h - gy
    rh = gh / steps
    for c, n in enumerate(cols):
        cx = gx + c * cw
        root = n.startswith("C")
        s.rect(cx + 1, y, cw - 2, kh, COLOR if root else T["control"], r=6)
        s.text(cx + cw / 2, y + kh / 2 + 4, n, 11, 900, T["bg"] if root else T["quiet"], "middle")
        if root:
            s.rect(cx, gy, cw, gh, COLOR, opacity=0.08)
    s.rect(gx, gy, w - rl, gh, T["surface"], r=8, opacity=0.6)
    for r in range(steps + 1):
        bar, beat = r % 16 == 0, r % 4 == 0
        s.line(gx, gy + r * rh, gx + w - rl, gy + r * rh, T["quiet"] if bar else T["faint"],
               1.6 if bar else (1.1 if beat else 0.6), opacity=0.7 if bar else (0.8 if beat else 0.3))
        if beat and r < steps:
            s.text(x + rl - 6, gy + r * rh + 11, f"{r // 16 + 1}.{r // 4 % 4 + 1}", 9, 700, T["faint"], "end")
    for c in range(len(cols) + 1):
        s.line(gx + c * cw, gy, gx + c * cw, gy + gh, T["divider"], 0.6)
    for i, (st, ln, n, v) in enumerate(CLIP):
        c = cols.index(n)
        nx, ny = gx + c * cw + 3, gy + st * rh + 1
        sel = i in SEL
        s.rect(nx, ny, cw - 6, ln * rh - 2, COLOR, r=5, opacity=1 if sel else 0.45 + 0.45 * v)
        if sel:
            s.rect(nx - 1.5, ny - 1.5, cw - 3, ln * rh + 1, stroke=T["text"], r=6, sw=2)
    py = gy + 9.5 * rh
    s.line(x, py, x + w, py, T["pattern"], 2)


def edit_row(s, y, x=M, w=CW, h=32):
    gap = 4
    bw = (w - gap * (len(EDITS) - 1)) / len(EDITS)
    for i, e in enumerate(EDITS):
        s.rect(x + i * (bw + gap), y, bw, h, T["control"], r=9)
        s.text(x + i * (bw + gap) + bw / 2, y + h / 2 + 4, e, 11, 900, T["text"], "middle")


def undo_pair(s, cx, cy):
    for k, redo in enumerate((False, True)):
        s.circle(cx + k * 40, cy, 16, T["control"])
        icon_undo(s, cx + k * 40, cy, 14, T["quiet"], redo=redo)


def floating_edits(s, y):
    """The selection's edit bar, floating over the roll while notes are selected."""
    s.rect(M + 6, y, CW - 12, 50, T["bg"], r=16, opacity=0.94)
    s.rect(M + 6, y, CW - 12, 50, stroke=T["divider"], r=16, sw=1)
    s.text(M + 20, y + 30, "C3", 12, 900, COLOR)
    gap = 3
    x = M + 50
    w = CW - 12 - 50 - 70
    bw = (w - gap * 7) / 8
    for i, e in enumerate(EDITS):
        s.rect(x + i * (bw + gap), y + 9, bw, 32, T["control"], r=8)
        s.text(x + i * (bw + gap) + bw / 2, y + 29, e, 10, 900, T["text"], "middle")
    s.rect(W - M - 6 - 64, y + 9, 56, 32, T["control"], r=16)
    s.text(W - M - 6 - 36, y + 29, "Delete", 11, 900, T["record"], "middle")


def option_a(out):
    """A: one toolbar. Steps/Roll, length, Notes/Velocity, snap and undo share one row; the edits float over the roll
    only while notes are selected. The roll runs from the toolbar to the nav."""
    s = B.chrome("Seq", 2)
    y = BODY + 6
    segmented(s, M, y, 96, 32, ["Steps", "Roll"], 1, T["text"], 11)
    segmented(s, M + 102, y, 100, 32, ["Notes", "Vel"], 0, T["text"], 11)
    pill(s, M + 208, y + 2, "2 · 1/16", 11, h=28, padx=8, caret=True)
    undo_pair(s, W - M - 56, y + 16)
    top = y + 40
    hroll(s, M, top, CW, NAV_Y - 8 - top)
    floating_edits(s, NAV_Y - 8 - 60)
    s.save(out)


def option_b(out):
    """B: vertical, like the step editor. The scale runs across the top as wide keys (about 46 dp a note), time runs
    down two bars; notes are tall bars. Pinch up/down zooms time."""
    s = B.chrome("Seq", 2)
    y = BODY + 6
    segmented(s, M, y, 96, 32, ["Steps", "Roll"], 1, T["text"], 11)
    segmented(s, M + 102, y, 100, 32, ["Notes", "Vel"], 0, T["text"], 11)
    pill(s, M + 208, y + 2, "2 · 1/16", 11, h=28, padx=8, caret=True)
    undo_pair(s, W - M - 56, y + 16)
    edit_row(s, y + 40)
    top = y + 80
    vroll(s, M, top, CW, NAV_Y - 8 - top)
    s.save(out)


def option_c(out):
    """C: focus. A roll button opens the roll over everything: no app bar, tracks or nav; a slim bar with back,
    play / stop, the track, Notes/Vel and undo on top, the edits at the bottom. About 85 % of the screen is roll."""
    s = B.Svg(W, H)
    B.status_bar(s)
    y = 34
    s.circle(M + 18, y + 18, 18, T["control"])
    icon_chevron(s, M + 18, y + 18, 8, T["text"], "left", 2)
    s.circle(M + 62, y + 18, 18, T["control"])
    icon_play(s, M + 63, y + 18, 12, T["pattern"])
    s.circle(M + 102, y + 18, 18, T["control"])
    icon_stop(s, M + 102, y + 18, 10, T["text"])
    s.rect(M + 128, y + 2, 70, 32, COLOR, r=16)
    s.text(M + 163, y + 23, "3 · VA", 12, 900, T["bg"], "middle")
    segmented(s, M + 204, y + 2, 104, 32, ["Notes", "Vel"], 0, T["text"], 11)
    undo_pair(s, W - M - 56, y + 18)
    top = y + 44
    bottom = H - 16 - 44
    hroll(s, M, top, CW, bottom - 8 - top)
    edit_row(s, bottom, h=36)
    B.gesture(s)
    s.save(out)


def option_d(out):
    """D: side strip. The header shrinks to one row and the edits stack down the right edge as a 44 dp strip; the
    roll fills the height between the tracks and the nav."""
    s = B.chrome("Seq", 2)
    y = BODY + 6
    segmented(s, M, y, 96, 32, ["Steps", "Roll"], 1, T["text"], 11)
    segmented(s, M + 102, y, 100, 32, ["Notes", "Vel"], 0, T["text"], 11)
    pill(s, M + 208, y + 2, "2 · 1/16", 11, h=28, padx=8, caret=True)
    undo_pair(s, W - M - 56, y + 16)
    top = y + 42
    strip = 44
    hroll(s, M, top, CW - strip - 6, NAV_Y - 8 - top)
    sx = W - M - strip
    items = EDITS + ["Del"]
    ih = (NAV_Y - 8 - top - 18 - 4 * (len(items) - 1)) / len(items)
    for i, e in enumerate(items):
        iy = top + 18 + i * (ih + 4)
        s.rect(sx, iy, strip, ih, T["control"], r=10)
        s.text(sx + strip / 2, iy + ih / 2 + 4, e, 11, 900, T["record"] if e == "Del" else T["text"], "middle")
    s.save(out)


OPTIONS = [("roll_option_a", option_a, "A · One toolbar"), ("roll_option_b", option_b, "B · Vertical"),
           ("roll_option_c", option_c, "C · Focus (full screen)"), ("roll_option_d", option_d, "D · Side strip")]


def main():
    svg_dir = os.path.join(B.ROOT, "mockups", "svg")
    png_dir = os.path.join(B.ROOT, "mockups", "png")
    rsvg = shutil.which("rsvg-convert")
    pngs = []
    for name, fn, _ in OPTIONS:
        path = os.path.join(svg_dir, name + ".svg")
        fn(path)
        if rsvg:
            png = os.path.join(png_dir, name + ".png")
            subprocess.run([rsvg, "-z", "2", "-o", png, path], check=True)
            pngs.append(png)
        print(os.path.relpath(path, B.ROOT))
    try:   # the four side by side, labelled
        from PIL import Image, ImageDraw, ImageFont
        ims = [Image.open(p) for p in pngs]
        w, h = ims[0].size
        sheet = Image.new("RGB", (w * 4 + 60 * 3, h + 120), (18, 19, 22))
        d = ImageDraw.Draw(sheet)
        try:
            font = ImageFont.truetype("DejaVuSans-Bold.ttf", 52)
        except OSError:
            font = ImageFont.load_default()
        for i, (im, (_, _, title)) in enumerate(zip(ims, OPTIONS)):
            sheet.paste(im, (i * (w + 60), 120))
            d.text((i * (w + 60) + 20, 30), title, fill=(230, 226, 220), font=font)
        sheet.save(os.path.join(png_dir, "roll_options.png"))
        print("mockups/png/roll_options.png")
    except ImportError:
        pass


if __name__ == "__main__":
    main()

"""Synth step sequencer options (2026-11-08): the synth steps as a drum-style grid (steps × the scale's notes),
with note length, octave, accent and glide as the step's settings. Four layouts for the phone; one acid line
in C minor in all of them. Writes mockups/svg/synthseq_*.svg, PNGs, and png/synthseq_options.png side by side.

    python3 mockups/tools/synthseq_options.py
"""
import os
import shutil
import subprocess

import build as B
from build import T, M, W, CW, BODY, TRACK, segmented, pill, icon_chevron, icon_minus, icon_plus

COLOR = TRACK[1]   # the Acid track
DEG = ["C", "D", "Eb", "F", "G", "Ab", "Bb", "C"]   # one octave of C minor and the octave root
NAMES = [d + ("3" if i == 7 else "2") for i, d in enumerate(DEG)]
# step: (degree, octave, length in steps, accent, glide into the next note)
LINE = {0: (0, 0, 1, 1, 0), 2: (7, 0, 1, 0, 1), 3: (2, 0, 1, 0, 0), 4: (0, 0, 2, 1, 0), 6: (4, 0, 1, 0, 1),
        7: (6, -1, 1, 0, 0), 8: (0, 0, 3, 1, 0), 11: (3, 0, 1, 0, 1), 12: (4, 1, 1, 1, 0), 13: (2, 0, 1, 0, 0),
        14: (5, 0, 2, 0, 1)}
PLAYHEAD = 4
SEL = 8                      # the selected step (D)
GRID_BOTTOM = B.DRAWER_Y - 8


def held_by(step):
    """The note a step is still sounding (a longer note started before it), or None."""
    for st, (d, o, ln, a, g) in LINE.items():
        if st < step < st + ln:
            return st
    return None


def oct_text(o):
    return "" if o == 0 else ("+%d" % o if o > 0 else "−%d" % -o)


def deg_header(s, x0, cw, y, h=22, sel=None):
    """The scale's notes as column heads (roots tinted), as the drum grid's part chips."""
    for c, n in enumerate(DEG):
        root = c in (0, 7)
        on = c == sel
        if on or root:
            s.rect(x0 + c * cw + 1, y, cw - 2, h, COLOR, r=8, opacity=None if on else 0.22)
        s.text(x0 + c * cw + cw / 2, y + h / 2 + 4, n, 10, 900, T["bg"] if on else (COLOR if root else T["quiet"]), "middle")


def rows_bg(s, y, rh, n=16, x=M, w=CW, numbers=True):
    s.rect(x, y - 4, w, n * rh + 8, T["surface"], r=12)
    for i in range(n):
        ry = y + i * rh
        if (i // 4) % 2:
            s.rect(x + 2, ry, w - 4, rh, "#23262D")
        if numbers:
            s.text(x + 24, ry + rh / 2 + 4, str(i + 1), 11, 700, T["faint"] if i % 4 else T["quiet"], "end")


def playhead(s, y, rh, i=PLAYHEAD, x=M, w=CW):
    s.rect(x + 1, y + i * rh + 1, w - 2, rh - 2, stroke=T["pattern"], r=8, sw=1.5)


def glide_mark(s, x, y, c=None):
    s.path(f"M{x} {y + 4} q 5 -9 11 -9", stroke=c or T["bg"], sw=2.2)


def note_cells(s, x0, cw, y, rh, dim=False, tails=True, marks=True, grips=False, connectors=True):
    """Every note: its cell, the steps it rings over as a tail in its column, octave / accent / glide marks."""
    for st, (d, o, ln, a, g) in LINE.items():
        cx = x0 + d * cw + 3
        top = y + st * rh + 4
        body = (ln * rh - 8) if tails else (rh - 8)
        op = (0.25 if dim else (1.0 if a else 0.72))
        s.rect(cx, top, cw - 6, body, COLOR, r=7, opacity=op)
        if marks and not dim:
            if o:
                s.text(cx + 5, top + 14, oct_text(o), 10, 900, T["bg"])
            if a:
                s.circle(cx + cw - 13, top + 7, 3, T["bg"])
            if g:
                glide_mark(s, cx + cw - 22, top + rh - 13)
        if grips and not dim:   # drag the end down: longer
            s.rect(cx + (cw - 6) / 2 - 8, top + body - 6, 16, 3, T["bg"], r=1.5, opacity=0.8)
        if connectors and g and not dim:   # the glide: from this note into the next one
            nxt = min((k for k in LINE if k > st), default=None)
            if nxt is not None:
                nd = LINE[nxt][0]
                x1, y1 = cx + (cw - 6) / 2, top + body
                x2, y2 = x0 + nd * cw + cw / 2, y + nxt * rh + 4
                s.path(f"M{x1} {y1} C {x1} {(y1 + y2) / 2}, {x2} {(y1 + y2) / 2}, {x2} {y2}", stroke=COLOR, sw=2, opacity=0.8)


def empty_cells(s, x0, cw, y, rh, n=16):
    for i in range(n):
        for c in range(8):
            s.rect(x0 + c * cw + 3, y + i * rh + 4, cw - 6, rh - 8, T["control"], r=7, opacity=0.55)


def screen():
    s = B.chrome("Seq", 1)
    B.seq_header(s, BODY + 8, 0)
    B.pads_drawer(s, COLOR)
    return s


# ---------------------------------------------------------------- A: the drum grid, with modes
def option_a(out, mode=0):
    """A: the drum sequencer exactly: steps down, the scale's notes across. Modes as the drums' Hits / Velocity:
    Notes (tap a cell: that note on that step), Length (drag a note's tail), Octave, Accent, Glide (paint by sliding)."""
    s = screen()
    y = BODY + 50
    segmented(s, M, y, CW, 32, ["Notes", "Length", "Octave", "Accent", "Glide"], mode, T["text"], 11)
    x0 = M + 34
    cw = (CW - 38) / 8
    deg_header(s, x0, cw, y + 42)
    gy = y + 72
    rh = (GRID_BOTTOM - gy) / 16
    rows_bg(s, gy, rh)
    if mode == 2:
        for i in range(16):
            if i in LINE:
                continue
            h = held_by(i)
            for c in range(8):
                held = h is not None and LINE[h][0] == c
                s.rect(x0 + c * cw + 3, gy + i * rh + 4, cw - 6, rh - 8, COLOR if held else T["control"], r=7, opacity=0.3 if held else 0.55)
    else:
        empty_cells(s, x0, cw, gy, rh)
    if mode == 0:
        note_cells(s, x0, cw, gy, rh)
    elif mode == 1:   # Length: the notes and their tails, a grip at each end; the other cells quiet
        note_cells(s, x0, cw, gy, rh, grips=True, marks=False, connectors=False)
        for st, (d, o, ln, a, g) in LINE.items():
            s.text(x0 + d * cw + cw + 2, gy + (st + ln) * rh - 8, f"{ln}", 10, 900, T["text"])
    elif mode == 2:   # Octave: each note's step a −2 … +2 strip across the row (the note faint behind)
        for st, (d, o, ln, a, g) in LINE.items():
            ry = gy + st * rh
            seg_w = (CW - 38) / 5
            for k, lab in enumerate(["−2", "−1", "0", "+1", "+2"]):
                on = k - 2 == o
                bx = x0 + k * seg_w + 3
                s.rect(bx, ry + 5, seg_w - 6, rh - 10, COLOR if on else T["control"], r=8, opacity=None if on else 0.95)
                s.text(bx + (seg_w - 6) / 2, ry + rh / 2 + 4, lab, 11, 900, T["bg"] if on else T["quiet"], "middle")
    playhead(s, gy, rh)
    s.save(out)


# ---------------------------------------------------------------- A, chosen (2026-11-08): seven notes + an octave sequencer
DEG7 = DEG[:7]
OCTAVES = list(range(9))   # C0 … C8


def line7():
    """The line as the scale's seven notes with an absolute octave per step (the top C is C of the next octave)."""
    out = {}
    for st, (d, o, ln, a, g) in LINE.items():
        octave = 2 + o + (1 if d == 7 else 0)
        out[st] = (d % 7, octave, ln, a, g)
    return out


def held7(step, ln7):
    for st, (d, o, ln, a, g) in ln7.items():
        if st < step < st + ln:
            return st
    return None


def a_frame(mode):
    s = screen()
    y = BODY + 50
    segmented(s, M, y, CW, 32, ["Notes", "Length", "Octave", "Accent", "Glide"], mode, T["text"], 11)
    return s, y


def a_notes_grid(s, y, mode):
    """Notes, Length, Accent, Glide: steps down, the scale's seven notes across; each note shows what it plays."""
    ln7 = line7()
    x0 = M + 34
    cw = (CW - 38) / 7
    for c, n in enumerate(DEG7):
        root = c == 0
        if root:
            s.rect(x0 + c * cw + 1, y + 42, cw - 2, 22, COLOR, r=8, opacity=0.22)
        s.text(x0 + c * cw + cw / 2, y + 57, n, 10, 900, COLOR if root else T["quiet"], "middle")
    gy = y + 72
    rh = (GRID_BOTTOM - gy) / 16
    rows_bg(s, gy, rh)
    for i in range(16):
        for c in range(7):
            s.rect(x0 + c * cw + 3, gy + i * rh + 4, cw - 6, rh - 8, T["control"], r=7, opacity=0.55)
    for st, (d, o, ln, a, g) in ln7.items():
        cx, top = x0 + d * cw + 3, gy + st * rh + 4
        body = ln * rh - 8
        lit = {0: 1.0 if a else 0.75, 1: 0.85, 3: 1.0 if a else 0.3, 4: 1.0 if g else 0.3}[mode]
        s.rect(cx, top, cw - 6, body, COLOR, r=7, opacity=lit)
        if mode in (0, 1):
            s.text(cx + 6, top + 15, f"{DEG7[d]}{o}", 10, 900, T["bg"])
        if mode == 0:
            if a:
                s.circle(cx + cw - 13, top + 7, 3, T["bg"])
            if g:
                glide_mark(s, cx + cw - 22, top + rh - 13)
        if mode == 1:   # drag the end down: longer
            s.rect(cx + (cw - 6) / 2 - 8, top + body - 6, 16, 3, T["bg"], r=1.5, opacity=0.8)
            s.text(cx + cw - 2, top + body - 3, str(ln), 10, 900, T["text"])
        if mode == 3 and a:
            s.text(cx + (cw - 6) / 2, top + 15, "A", 11, 900, T["bg"], "middle")
        if mode in (0, 4) and g:   # the glide into the next note
            nxt = min((k for k in ln7 if k > st), default=None)
            if nxt is not None:
                x1, y1 = cx + (cw - 6) / 2, top + body
                x2, y2 = x0 + ln7[nxt][0] * cw + cw / 2, gy + nxt * rh + 4
                s.path(f"M{x1} {y1} C {x1} {(y1 + y2) / 2}, {x2} {(y1 + y2) / 2}, {x2} {y2}", stroke=COLOR,
                       sw=2.4 if mode == 4 else 2, opacity=0.9)
            if mode == 4:
                glide_mark(s, cx + (cw - 6) / 2 - 6, top + rh / 2 - 1)
    playhead(s, gy, rh)


def option_a2(out, mode):
    s, y = a_frame(mode)
    if mode != 2:
        a_notes_grid(s, y, mode)
        s.save(out)
        return
    # Octave: an octave sequencer. Columns are octaves 0-8 (C0-C8); a note's step lights its octave and shows the
    # note it plays there; tap another octave, or slide down a column to paint it on every note passed. Steps without
    # a note have no octave (quiet). A faint line joins the notes (the line's contour across octaves).
    ln7 = line7()
    x0 = M + 34
    cw = (CW - 38) / 9
    # a slim row: what the columns are, and every step an octave down / up at once
    s.text(M + 4, y + 60, "OCTAVE", 10, 900, T["faint"], ls=1.2)
    pill(s, W - M, y + 42, "All ›", 11, h=26, padx=10, anchor="end")
    pill(s, W - M - 66, y + 42, "‹ All", 11, h=26, padx=10, anchor="end")
    hy = y + 76
    for c in OCTAVES:
        used = any(o == c for (d, o, ln, a, g) in ln7.values())
        if used:
            s.rect(x0 + c * cw + 1, hy, cw - 2, 22, COLOR, r=8, opacity=0.22)
        s.text(x0 + c * cw + cw / 2, hy + 15, str(c), 11, 900, COLOR if used else T["quiet"], "middle")
    gy = hy + 30
    rh = (GRID_BOTTOM - gy) / 16
    rows_bg(s, gy, rh)
    for i in range(16):
        h = held7(i, ln7)
        for c in OCTAVES:
            if i in ln7:
                op = 0.55
            elif h is not None and ln7[h][1] == c:
                s.rect(x0 + c * cw + 3, gy + i * rh + 4, cw - 6, rh - 8, COLOR, r=7, opacity=0.3)
                continue
            else:
                op = 0.18
            s.rect(x0 + c * cw + 3, gy + i * rh + 4, cw - 6, rh - 8, T["control"], r=7, opacity=op)
    for st, (d, o, ln, a, g) in ln7.items():   # the step's octave, lit (the note itself is on Notes)
        s.rect(x0 + o * cw + 3, gy + st * rh + 4, cw - 6, rh - 8, COLOR, r=7)
    playhead(s, gy, rh)
    s.save(out)


# ---------------------------------------------------------------- B: everything on the row
def option_b(out):
    """B: no modes. A narrower grid of the scale's notes, and the step's settings on the same row: Length (tap:
    1 2 3 4 8, drag: any), Octave (tap +, long-press −), Accent, Glide. Painting works on the grid and the toggles."""
    s = screen()
    y = BODY + 50
    x0 = M + 30
    cw = 24
    gx1 = x0 + 8 * cw
    cols = [("Len", gx1 + 28), ("Oct", gx1 + 72), ("A", gx1 + 108), ("G", gx1 + 135)]
    deg_header(s, x0, cw, y + 4, h=22)
    for name, cx in cols:
        s.text(cx, y + 19, name.upper(), 10, 900, T["faint"], "middle", ls=1)
    gy = y + 34
    rh = (GRID_BOTTOM - gy) / 16
    rows_bg(s, gy, rh)
    for i in range(16):
        for c in range(8):
            s.rect(x0 + c * cw + 2, gy + i * rh + 5, cw - 4, rh - 10, T["control"], r=6, opacity=0.55)
    for st, (d, o, ln, a, g) in LINE.items():
        cx = x0 + d * cw + 2
        s.rect(cx, gy + st * rh + 5, cw - 4, ln * rh - 10, COLOR, r=6, opacity=1.0 if a else 0.72)
    for i in range(16):
        ry = gy + i * rh
        cy = ry + rh / 2
        if i in LINE:
            d, o, ln, a, g = LINE[i]
            pill(s, cols[0][1], cy - 11, f"{ln}", 11, fill=T["bg"], color=COLOR if ln > 1 else T["text"], h=22, w=36, anchor="middle")
            pill(s, cols[1][1], cy - 11, oct_text(o) or "0", 11, fill=COLOR if o else T["bg"], color=T["bg"] if o else T["faint"],
                 h=22, w=38, anchor="middle")
            s.circle(cols[2][1], cy, 7, COLOR if a else T["control"])
            if g:
                s.rect(cols[3][1] - 11, cy - 9, 22, 18, COLOR, r=9)
                glide_mark(s, cols[3][1] - 6, cy + 3)
            else:
                s.rect(cols[3][1] - 11, cy - 9, 22, 18, T["control"], r=9)
        elif held_by(i) is not None:
            s.text(cols[0][1], cy + 4, "╎", 12, 700, COLOR, "middle", opacity=0.7)
    playhead(s, gy, rh)
    s.save(out)


# ---------------------------------------------------------------- C: tails you drag
def option_c(out):
    """C: length is drawn, not set: a note is a bar down its column, drag its end to lengthen (or tap a step below
    it). A glide is the line into the next note (tap the gap between them); accent and octave are marks on the note
    (tap to toggle; a narrow Oct strip on the left steps it up or down). Long-press a note: all four in a popover."""
    s = screen()
    y = BODY + 50
    ox = M + 30            # the octave strip
    x0 = ox + 40
    cw = (M + CW - 4 - x0) / 8
    s.text(ox + 16, y + 19, "OCT", 10, 900, T["faint"], "middle", ls=1)
    deg_header(s, x0, cw, y + 4)
    gy = y + 34
    rh = (GRID_BOTTOM - gy) / 16
    rows_bg(s, gy, rh)
    empty_cells(s, x0, cw, gy, rh)
    note_cells(s, x0, cw, gy, rh, grips=True)
    for st, (d, o, ln, a, g) in LINE.items():
        cy = gy + st * rh + rh / 2
        if o:
            s.rect(ox, cy - 10, 32, 20, COLOR, r=10)
            s.text(ox + 16, cy + 4, oct_text(o), 10, 900, T["bg"], "middle")
        else:
            s.circle(ox + 16, cy, 2.5, T["faint"])
    playhead(s, gy, rh)
    # the popover of a long-pressed note (step 9, C2, 3 steps)
    px, py, pw, ph = M + 84, gy + 8 * rh + 36, 240, 150
    s.rect(px + 4, py + 6, pw, ph, "#000000", r=16, opacity=0.35)
    s.rect(px, py, pw, ph, T["surface"], r=16, stroke=T["divider"], sw=1)
    s.text(px + 16, py + 26, "Step 9 · C2", 13, 900)
    s.text(px + 16, py + 52, "Length", 11, 700, T["quiet"])
    for k, lab in enumerate(["1", "2", "3", "4", "8"]):
        on = lab == "3"
        s.rect(px + 72 + k * 32, py + 38, 28, 22, COLOR if on else T["control"], r=8)
        s.text(px + 86 + k * 32, py + 53, lab, 11, 900, T["bg"] if on else T["quiet"], "middle")
    s.text(px + 16, py + 84, "Octave", 11, 700, T["quiet"])
    for k, lab in enumerate(["−2", "−1", "0", "+1", "+2"]):
        on = lab == "0"
        s.rect(px + 72 + k * 32, py + 70, 28, 22, COLOR if on else T["control"], r=8)
        s.text(px + 86 + k * 32, py + 85, lab, 10, 900, T["bg"] if on else T["quiet"], "middle")
    for k, (lab, on) in enumerate((("Accent", True), ("Glide", False))):
        bx = px + 16 + k * 108
        s.rect(bx, py + 106, 100, 30, COLOR if on else T["control"], r=15)
        s.text(bx + 50, py + 126, lab, 12, 900, T["bg"] if on else T["text"], "middle")
    s.save(out)


# ---------------------------------------------------------------- D: compact grid + the step's inspector
def option_d(out):
    """D: tap a step to select it; its settings sit docked under the grid with big controls (no modes, no small
    targets): Length, Octave, Accent, Glide. The grid stays the drums' (tap a cell: the note; slide: paint)."""
    s = screen()
    y = BODY + 50
    x0 = M + 34
    cw = (CW - 38) / 8
    deg_header(s, x0, cw, y + 4, sel=LINE[SEL][0])
    gy = y + 32
    insp_h = 196
    rh = (GRID_BOTTOM - insp_h - 10 - gy) / 16
    rows_bg(s, gy, rh)
    empty_cells(s, x0, cw, gy, rh)
    for st, (d, o, ln, a, g) in LINE.items():
        cx = x0 + d * cw + 3
        s.rect(cx, gy + st * rh + 3, cw - 6, ln * rh - 6, COLOR, r=6, opacity=1.0 if a else 0.72)
        if g:
            glide_mark(s, cx + cw - 20, gy + st * rh + rh / 2 + 1)
        if o:
            s.text(cx + 4, gy + st * rh + rh / 2 + 4, oct_text(o), 9, 900, T["bg"])
    playhead(s, gy, rh)
    s.rect(M + 1, gy + SEL * rh + 1, CW - 2, rh - 2, stroke=T["text"], r=8, sw=2)
    # the inspector
    iy = GRID_BOTTOM - insp_h
    s.rect(M, iy, CW, insp_h, T["surface"], r=16)
    d, o, ln, a, g = LINE[SEL]
    s.text(M + 16, iy + 28, f"Step {SEL + 1}", 15, 900)
    s.text(M + 88, iy + 28, NAMES[d], 13, 700, COLOR)
    icon_chevron(s, W - M - 52, iy + 22, 8, T["quiet"], "left", 2)
    icon_chevron(s, W - M - 22, iy + 22, 8, T["quiet"], "right", 2)
    # length: − value +, and the usual lengths
    s.text(M + 16, iy + 62, "LENGTH", 10, 900, T["faint"], ls=1)
    s.rect(M + 16, iy + 70, 150, 40, T["control"], r=12)
    icon_minus(s, M + 38, iy + 90, 10, T["text"])
    s.text(M + 91, iy + 96, f"{ln} steps", 15, 900, T["text"], "middle")
    icon_plus(s, M + 144, iy + 90, 10, T["text"])
    for k, lab in enumerate(["1", "2", "4", "8"]):
        on = int(lab) == ln
        s.rect(M + 176 + k * 48, iy + 70, 42, 40, COLOR if on else T["control"], r=12, opacity=None)
        s.text(M + 197 + k * 48, iy + 96, lab, 14, 900, T["bg"] if on else T["quiet"], "middle")
    # octave
    s.text(M + 16, iy + 132, "OCTAVE", 10, 900, T["faint"], ls=1)
    segmented(s, M + 16, iy + 140, 200, 40, ["−2", "−1", "0", "+1", "+2"], o + 2, COLOR, 13)
    # accent, glide
    for k, (lab, on) in enumerate((("Accent", a), ("Glide", g))):
        bx = M + 228 + k * 76
        s.rect(bx, iy + 140, 70, 40, COLOR if on else T["control"], r=12)
        s.text(bx + 35, iy + 165, lab, 12, 900, T["bg"] if on else T["text"], "middle")
    s.save(out)


A_SET = [("synthseq_a2_notes", lambda p: option_a2(p, 0), "Notes"), ("synthseq_a2_length", lambda p: option_a2(p, 1), "Length"),
         ("synthseq_a2_octave", lambda p: option_a2(p, 2), "Octave"), ("synthseq_a2_accent", lambda p: option_a2(p, 3), "Accent"),
         ("synthseq_a2_glide", lambda p: option_a2(p, 4), "Glide")]

OPTIONS = [("synthseq_a_notes", lambda p: option_a(p, 0), "A · Modes: Notes"),
           ("synthseq_a_length", lambda p: option_a(p, 1), "A · Modes: Length"),
           ("synthseq_a_octave", lambda p: option_a(p, 2), "A · Modes: Octave"),
           ("synthseq_b", option_b, "B · All in a row"),
           ("synthseq_c", option_c, "C · Tails + popover"),
           ("synthseq_d", option_d, "D · Grid + inspector")]


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
    a_pngs = []
    for name, fn, _ in A_SET:
        path = os.path.join(svg_dir, name + ".svg")
        fn(path)
        if rsvg:
            png = os.path.join(png_dir, name + ".png")
            subprocess.run([rsvg, "-z", "2", "-o", png, path], check=True)
            a_pngs.append(png)
        print(os.path.relpath(path, B.ROOT))
    try:
        from PIL import Image, ImageDraw, ImageFont
        ims = [Image.open(p) for p in a_pngs]
        w, h = ims[0].size
        sheet = Image.new("RGB", (w * len(ims) + 60 * (len(ims) - 1), h + 120), (18, 19, 22))
        d = ImageDraw.Draw(sheet)
        try:
            font = ImageFont.truetype("DejaVuSans-Bold.ttf", 52)
        except OSError:
            font = ImageFont.load_default()
        for i, (im, (_, _, title)) in enumerate(zip(ims, A_SET)):
            sheet.paste(im, (i * (w + 60), 120))
            d.text((i * (w + 60) + 20, 30), "A · " + title, fill=(230, 226, 220), font=font)
        sheet.save(os.path.join(png_dir, "synthseq_option_a.png"))
        print("mockups/png/synthseq_option_a.png")
    except ImportError:
        pass
    try:   # side by side, labelled
        from PIL import Image, ImageDraw, ImageFont
        ims = [Image.open(p) for p in pngs]
        w, h = ims[0].size
        n = len(ims)
        sheet = Image.new("RGB", (w * n + 60 * (n - 1), h + 120), (18, 19, 22))
        d = ImageDraw.Draw(sheet)
        try:
            font = ImageFont.truetype("DejaVuSans-Bold.ttf", 52)
        except OSError:
            font = ImageFont.load_default()
        for i, (im, (_, _, title)) in enumerate(zip(ims, OPTIONS)):
            sheet.paste(im, (i * (w + 60), 120))
            d.text((i * (w + 60) + 20, 30), title, fill=(230, 226, 220), font=font)
        sheet.save(os.path.join(png_dir, "synthseq_options.png"))
        print("mockups/png/synthseq_options.png")
    except ImportError:
        pass


if __name__ == "__main__":
    main()

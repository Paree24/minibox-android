"""Minibox for Android: phone mockups, portrait only, 412 × 915 dp (PNG at 2×).

Pads replace the keyboard: notes, chords and drum parts are big pads, always at
the bottom of the screen (the thumb zone). The sequencer stays a sequencer.

Reuses the desktop mockup kit (theme tokens, knobs, visuals) from
../../pastel-minibox/mockups/tools so both builds draw from one source.

    python3 mockups/tools/build.py      # writes mockups/svg/ and mockups/png/
"""
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
KIT = os.environ.get("MINIBOX_KIT", os.path.join(ROOT, "..", "pastel-minibox", "mockups", "tools"))
sys.path.insert(0, os.path.abspath(KIT))

from svgkit import (T, Svg, arc_d, knob, pill, segmented, icon_play, icon_stop, icon_record, icon_chevron,  # noqa: E402
                    icon_dots, icon_plus, icon_minus, icon_metronome, icon_back, icon_copy, icon_trash, icon_save,
                    icon_share, icon_export, icon_new, icon_steps, icon_faders, icon_folder, icon_lock, icon_undo, toggle,
                    slider_h, vis_filter, vis_env, vis_wave, draw_waveform)
from specs import DEVICES  # noqa: E402

W, H = 412, 915
M = 16
CW = W - 2 * M                       # content width
STATUS, APPBAR = 28, 64
TOP = STATUS + APPBAR                # 92
TRACKS_Y = TOP + 10                  # track row
BODY = TRACKS_Y + 54                 # first body row
NAV = 72
NAV_Y = H - NAV                      # 843

TRACK = ["#9EB9F3", "#8FD0C2", "#E8B86A", "#E9A3B8", "#A8D58A", "#F0A07C", "#7FC6E8", "#D7A6E8"]
BPM = "124.0"

# Pattern A: eight tracks (track 8 empty)
RACK = [("drums", "Drums", "House"), ("acid", "Acid", "Resonant Line"), ("poly", "VA", "Breathing Pad"),
        ("fm", "FM", "Mellow Bell"), ("mono", "Bassline", "Round Sub"), ("sampler", "Sampler", "Glass Keys"),
        ("drumsampler", "Drum Sampler", "Dusty Break"), None]
PATTERNS = {  # letter: (tracks used, bars)
    "A": ([1, 1, 1, 1, 1, 1, 1, 0], 4), "B": ([1, 1, 1, 0, 1, 1, 0, 0], 4), "C": ([1, 1, 0, 1, 0, 0, 1, 0], 8),
    "D": ([1, 0, 0, 0, 1, 0, 0, 0], 2), "E": ([1, 1, 1, 1, 0, 0, 0, 0], 4), "F": ([0, 0, 1, 1, 0, 1, 0, 0], 1),
    "G": (None, 0), "H": (None, 0),
}
DRUM_PARTS = ["Kick", "Snare", "Clap", "Tom", "C Hat", "O Hat", "Cymbal", "Perc"]
FX_PADS = ["Stutter", "Glitch", "Shuffle", "Slicer", "Crush", "Filter", "Drive", "Reverse"]
MINOR = [0, 2, 3, 5, 7, 8, 10]
NOTE = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]


def scale_note(deg, base_oct=2):
    o, d = divmod(deg, 7)
    return NOTE[MINOR[d]] + str(base_oct + o), d == 0


# ---------------------------------------------------------------- chrome

def status_bar(s):
    s.text(M + 4, 19, "9:41", 12, 700, T["quiet"])
    for i in range(4):
        s.rect(W - 74 + i * 5, 15 - i * 2.5, 3, 4 + i * 2.5, T["quiet"], r=1)
    s.rect(W - 44, 9, 22, 11, stroke=T["quiet"], r=3, sw=1.2)
    s.rect(W - 42, 11, 14, 7, T["quiet"], r=1.5)
    s.rect(W - 21, 12.5, 2, 4, T["quiet"], r=1)


def pattern_tile(s, x, y, w, h, letter, state, size=15):
    """state: current | queued | idle"""
    used, _ = PATTERNS[letter]
    if state == "current":
        s.rect(x, y, w, h, T["pattern"], r=10, opacity=0.18)
        s.rect(x, y, w, h, stroke=T["pattern"], r=10, sw=1.5)
    elif state == "queued":
        s.rect(x, y, w, h, T["control"], r=10)
        s.rect(x, y, w, h, stroke=T["pattern"], r=10, sw=1.5, dash="4 3")
    else:
        s.rect(x, y, w, h, T["control"], r=10)
    s.text(x + w / 2, y + h / 2 + 1, letter, size, 900, T["pattern"] if state != "idle" else T["text"], "middle")
    if used:
        for i, u in enumerate(used):
            s.circle(x + w / 2 + (i - 3.5) * 4.2, y + h - 8, 1.5, TRACK[i] if u else T["faint"])


def app_bar(s, rec=False, progress=0.4, queued="B", pos="2.3.1"):
    y = STATUS + APPBAR / 2
    s.circle(M + 20, y, 20, T["record"] if rec else T["control"])
    icon_record(s, M + 20, y, 12, T["bg"] if rec else T["record"])
    cx = M + 70
    s.circle(cx, y, 20, T["control"])
    s.circle(cx, y, 23.5, stroke=T["divider"], sw=2.5)
    s.path(arc_d(cx, y, 23.5, -90, -90 + 360 * progress), stroke=T["pattern"], sw=2.5)
    icon_play(s, cx + 1, y, 14, T["pattern"])
    s.circle(M + 120, y, 20, T["control"])
    icon_stop(s, M + 120, y, 12, T["text"])
    # tempo + position (opens the Tempo sheet)
    tx = M + 156
    s.text(tx, y + 2, BPM, 20, 900)
    s.text(tx, y + 18, pos, 10, 700, T["record"] if rec else T["faint"])
    s.text(tx + 34, y + 18, "· 4/4", 10, 700, T["faint"])
    # current pattern (+ queued) — opens Perform
    px = W - M - 40 - 8 - 36 - 4 - 44
    pattern_tile(s, px, y - 20, 44, 40, "A", "current", 16)
    if queued:
        pattern_tile(s, px + 48, y - 20, 36, 40, queued, "queued", 13)
    s.circle(W - M - 20, y, 20, T["control"])
    icon_dots(s, W - M - 20, y, T["quiet"], gap=5, r=1.8)
    s.line(0, TOP, W, TOP, T["divider"], 1)


def track_row(s, sel, y=TRACKS_Y):
    """Eight track chips; the selected one opens into a pill with its device name."""
    d, gap = 32, 5
    sel_w = CW - 7 * d - 7 * gap
    x = M
    for i in range(8):
        dev = RACK[i]
        if i == sel:
            s.rect(x, y, sel_w, d + 4, TRACK[i], r=(d + 4) / 2)
            s.circle(x + 18, y + 18, 13, T["bg"], opacity=0.22)
            s.text(x + 18, y + 22.5, str(i + 1), 13, 900, T["bg"], "middle")
            s.text(x + 38, y + 23, dev[1], 14, 900, T["bg"])
            icon_chevron(s, x + sel_w - 18, y + 18, 7, T["bg"], "down", 1.8)
            x += sel_w + gap
        else:
            cy = y + 18
            if dev is None:
                s.circle(x + d / 2, cy, d / 2, stroke=T["faint"], sw=1.5, dash="3 3")
                icon_plus(s, x + d / 2, cy, 10, T["faint"], 1.6)
            else:
                s.circle(x + d / 2, cy, d / 2, T["control"])
                s.circle(x + d / 2, cy, d / 2 - 1, stroke=TRACK[i], sw=2, opacity=0.55)
                s.text(x + d / 2, cy + 4.5, str(i + 1), 13, 900, TRACK[i], "middle")
            x += d + gap


def nav_icon(s, name, cx, cy, c):
    if name == "Play":
        for r in range(2):
            for k in range(2):
                s.rect(cx - 9 + k * 10, cy - 9 + r * 10, 8, 8, c, r=2.5)
    elif name == "Seq":
        icon_steps(s, cx, cy, 18, c)
    elif name == "Sound":
        s.circle(cx, cy, 8, stroke=c, sw=2)
        s.line(cx, cy, cx + 4, cy - 5, c, 2)
        s.path(arc_d(cx, cy, 11.5, 135, 405), stroke=c, sw=1.5, opacity=0.6)
    elif name == "Mix":
        icon_faders(s, cx, cy, 18, c)
    elif name == "Perform":
        s.path(s.poly([(cx + 2, cy - 10), (cx - 6, cy + 1), (cx, cy + 1), (cx - 2, cy + 10), (cx + 6, cy - 1),
                       (cx, cy - 1)], close=True), fill=c)


def bottom_nav(s, active):
    s.rect(0, NAV_Y, W, NAV, T["surface"])
    items = ["Play", "Seq", "Sound", "Mix", "Perform"]
    iw = W / len(items)
    for i, name in enumerate(items):
        cx = iw * (i + 0.5)
        on = name == active
        if on:
            s.rect(cx - 28, NAV_Y + 9, 56, 30, T["control"], r=15)
        nav_icon(s, name, cx, NAV_Y + 24, T["text"] if on else T["faint"])
        s.text(cx, NAV_Y + 56, name, 11, 900 if on else 700, T["text"] if on else T["faint"], "middle")
    gesture(s)


def gesture(s):
    s.rect(W / 2 - 54, H - 8, 108, 4, T["faint"], r=2)


def chrome(active, sel=1, rec=False, tracks=True, queued="B", pos="2.3.1"):
    s = Svg(W, H)
    status_bar(s)
    app_bar(s, rec=rec, queued=queued, pos=pos)
    if tracks:
        track_row(s, sel)
    if active:
        bottom_nav(s, active)
    else:
        gesture(s)
    return s


def dim(s):
    s.rect(0, 0, W, H, "#0B0C0E", opacity=0.72)


def sheet(s, y, title=None, sub=None):
    s.rect(0, y, W, H - y + 20, T["surface"], r=24)
    s.rect(W / 2 - 20, y + 10, 40, 4, T["faint"], r=2)
    if title:
        s.text(M + 4, y + 48, title, 18, 900)
    if sub:
        s.text(M + 4 + len(title) * 10.5 + 10, y + 48, sub, 13, 700, T["quiet"])
    gesture(s)


def label(s, x, y, text):
    s.text(x, y, text.upper(), 11, 900, T["faint"], ls=1.2)


# ---------------------------------------------------------------- pads

def pad(s, x, y, w, h, color, name=None, sub=None, state="idle", big=False, r=14, playing=False):
    """state: idle | root | lit (pressed / held / on) | dim | empty"""
    if state == "lit":
        s.rect(x, y, w, h, color, r=r)
        fg, fs = T["bg"], T["bg"]
    elif state == "root":
        s.rect(x, y, w, h, T["control"], r=r)
        s.rect(x, y, w, h, color, r=r, opacity=0.28)
        fg, fs = T["text"], color
    elif state == "empty":
        s.rect(x, y, w, h, stroke=T["faint"], r=r, sw=1.5, dash="4 4")
        fg, fs = T["faint"], T["faint"]
    else:
        s.rect(x, y, w, h, T["control"], r=r)
        fg, fs = (T["quiet"] if state == "dim" else T["text"]), T["faint"]
    if playing:
        s.rect(x + 1, y + 1, w - 2, h - 2, stroke=color, r=r - 1, sw=2)
    if name:
        if big:
            s.text(x + w / 2, y + h / 2 + 7, name, 22, 900, fg, "middle")
            if sub:
                s.text(x + w / 2, y + h / 2 + 26, sub, 11, 700, fs, "middle")
        else:
            s.text(x + 12, y + 22, name, 13, 900, fg)
            if sub:
                s.text(x + 12, y + h - 12, sub, 11, 700, fs)


def grid(x, y, w, h, cols, rows, gap=8):
    pw, ph = (w - gap * (cols - 1)) / cols, (h - gap * (rows - 1)) / rows
    return [(x + c * (pw + gap), y + (rows - 1 - r) * (ph + gap), pw, ph) for r in range(rows) for c in range(cols)]


PAD = (CW - 24) / 4                    # 89 dp: square pads
PADS_H = 4 * PAD + 24
PADS_Y = NAV_Y - 12 - PADS_H           # the 4×4 sits on the nav, in the thumb zone
DRAWER_Y = NAV_Y - 12 - 40             # editors fold the pads into a handle here
EDIT_END = DRAWER_Y - 10

CHORDS = [("Cm", "i"), ("D°", "ii°"), ("Eb", "III"), ("Fm", "iv"), ("Gm", "v"), ("Ab", "VI"), ("Bb", "VII"), ("Cm", "i")]
CHORDS7 = [("Cm7", "i7"), ("Dø7", "ii7"), ("Ebmaj7", "III7"), ("Fm7", "iv7"), ("Gm7", "v7"), ("Abmaj7", "VI7"),
           ("Bb7", "VII7"), ("Cm9", "i9")]
RATE = "1/16"


def pads16(s, color, kind="notes", lit=(), playing=(), y=None):
    """The 4×4 square pads; index 0 is bottom-left."""
    cells = grid(M, PADS_Y if y is None else y, CW, PADS_H, 4, 4)
    for i, (x, yy, w, h) in enumerate(cells):
        on = i in lit
        if kind == "notes":
            name, root = scale_note((i // 4) * 3 + i % 4)        # rows climb a fourth
            pad(s, x, yy, w, h, color, name, state="lit" if on else ("root" if root else "idle"), playing=i in playing)
        elif kind == "chords":
            name, roman = (CHORDS + CHORDS7)[i]
            pad(s, x, yy, w, h, color, name, roman, big=True,
                state="lit" if on else ("root" if roman in ("i", "i7", "i9") else "idle"))
        else:   # drums: the lower 8 hit the parts, the upper 8 roll them at the rate
            roll = i >= 8
            pad(s, x, yy, w, h, color, DRUM_PARTS[i % 8], RATE if roll else None, state="lit" if on else "idle")
            if roll:
                for k in range(3):
                    s.rect(x + w - 26 + k * 6, yy + 12, 3, 12, T["bg"] if on else color, r=1.5, opacity=0.8)
            if on:      # touch height sets the velocity
                s.text(x + w - 10, yy + h - 12, "112", 11, 900, T["bg"], "end")


def pads_drawer(s, color):
    """Editors keep the pads folded into a handle; pull it up for the 4×4."""
    y = DRAWER_Y
    s.rect(M, y, CW, 40, T["control"], r=14)
    for r in range(2):
        for k in range(2):
            s.rect(M + 16 + k * 9, y + 12 + r * 9, 7, 7, color, r=2)
    s.text(M + 44, y + 25, "Pads", 13, 900, T["quiet"])
    icon_chevron(s, W - M - 22, y + 20, 8, T["quiet"], "up", 2)


def macros8(s, y, dev_key, color):
    h = PADS_Y - 12 - y
    s.rect(M, y, CW, h, T["surface"], r=16)
    for i, (name, v, val) in enumerate(DEVICES[dev_key]["macros"]):
        c, r = i % 4, i // 4
        knob(s, M + CW * (c + 0.5) / 4, y + 42 + r * h / 2, 20, v, color, name, label_size=11, value=val)


def play_header(s, y, color, mode):
    segmented(s, M, y, 196, 34, ["Notes", "Chords", "Arp"], mode, color, 12)
    pill(s, M + 206, y + 3, "C minor", 12, h=28, caret=True)
    ox = W - M - 78
    s.rect(ox, y + 3, 78, 28, T["control"], r=14)
    icon_chevron(s, ox + 16, y + 17, 7, T["quiet"], "left", 1.8)
    s.text(ox + 39, y + 22, "C2", 12, 900, T["text"], "middle")
    icon_chevron(s, ox + 62, y + 17, 7, T["quiet"], "right", 1.8)


def play_synth(s, color, dev, kind, mode, lit=(), playing=()):
    y = BODY + 8
    play_header(s, y, color, mode)
    macros8(s, y + 46, dev, color)
    pads16(s, color, kind, lit, playing)


# ---------------------------------------------------------------- screens

def s01_play_notes(out):
    s = chrome("Play", 1)
    play_synth(s, TRACK[1], "acid", "notes", 0, lit=(6,), playing=(9,))
    s.save(out)


def s02_play_chords(out):
    s = chrome("Play", 2)
    play_synth(s, TRACK[2], "poly", "chords", 1, lit=(9,))
    s.save(out)


def s03_play_drums(out):
    color = TRACK[0]
    s = chrome("Play", 0)
    y = BODY + 8
    s.text(M + 4, y + 22, "ROLL", 11, 900, T["faint"], ls=1.2)
    for i, r in enumerate(["1/8", "1/16", "1/32", "1/8T", "1/16T"]):
        on = r == RATE
        pill(s, M + 52 + i * 66, y + 3, r, 12, h=28, w=60,
             fill=T["text"] if on else T["control"], color=T["bg"] if on else T["quiet"])
    macros8(s, y + 46, "drums", color)
    pads16(s, color, "drums", lit=(0, 12))
    s.save(out)


# ---------------------------------------------------------------- Seq: step editor and piano roll

ACID_STEPS = {0: ("C2", 1, 0), 2: ("C3", 0, 1), 3: ("Eb2", 0, 0), 5: ("G2", 1, 0), 6: ("C2", 0, 0),
              8: ("Bb2", 0, 1), 9: ("C3", 1, 0), 11: ("F2", 0, 0), 13: ("G2", 0, 0), 14: ("Ab2", 1, 1)}
VEL = [1, 0, .7, .8, 0, .9, .6, 0, .7, 1, 0, .6, 0, .7, .9, 0]
GATE = [.5, 0, .9, .5, 0, .5, .3, 0, 1, .5, 0, .5, 0, .5, .8, 0]
CHANCE = {6: 75, 13: 50}
RATCHET = {11: 2}
LOCKED = {5, 9}
KICK = {0: (1, 1), 3: (.5, 1), 4: (1, 1), 8: (1, 1), 10: (.6, 2), 12: (1, 1), 15: (.4, 3)}
LANES = [[0, 3, 4, 8, 10, 12, 15], [4, 12], [12], [6, 14], [0, 2, 4, 6, 8, 10, 12, 14], [6, 14], [0], [3, 11]]
PLAYHEAD = 4


def seq_header(s, y, view, bars=4, cur=1, playing=1):
    segmented(s, M, y, 140, 34, ["Steps", "Roll"], view, T["text"], 12)
    bw = 34
    for b in range(bars):
        x = M + 150 + b * (bw + 4)
        on = b == cur
        s.rect(x, y, bw, 34, T["pattern"] if on else T["control"], r=10, opacity=0.18 if on else None)
        if on:
            s.rect(x, y, bw, 34, stroke=T["pattern"], r=10, sw=1.5)
        s.text(x + bw / 2, y + 22, str(b + 1), 13, 900, T["pattern"] if on else T["quiet"], "middle")
        if b == playing:
            s.rect(x + 9, y + 28, bw - 18, 2.5, T["pattern"], r=1.25)
    pill(s, W - M, y + 3, f"{bars} bars", 11, h=28, padx=8, caret=True, anchor="end")


def mini_bar(s, x, y, w, v, color, h=8):
    s.rect(x, y - h / 2, w, h, T["control"], r=h / 2)
    if v:
        s.rect(x, y - h / 2, w * v, h, color, r=h / 2, opacity=0.9)


def step_editor(s, y, color, held=None, row_h=34):
    """Synth steps as rows (time runs down): every step's settings in one line."""
    cols = [("Note", M + 70), ("Vel", M + 130), ("Gate", M + 222), ("%", M + 300), ("×", M + 330),
            ("A", M + 352), ("S", M + 370)]
    for name, x in cols:
        s.text(x, y + 12, name.upper(), 10, 900, T["faint"], ls=1)
    y += 22
    s.rect(M, y - 4, CW, 16 * row_h + 8, T["surface"], r=12)
    for i in range(16):
        ry = y + i * row_h
        cy = ry + row_h / 2
        if (i // 4) % 2:
            s.rect(M + 2, ry, CW - 4, row_h, "#23262D")
        st = ACID_STEPS.get(i)
        s.text(M + 26, cy + 4, str(i + 1), 11, 700, T["faint"] if i % 4 else T["quiet"], "end")
        # on/off
        s.rect(M + 34, cy - 12, 26, 24, color if st else T["control"], r=7, opacity=0.9 if st else None)
        if st:
            note, acc, slide = st
            pill(s, M + 70, cy - 12, note, 12, h=24, w=50, fill=T["bg"], color=color)
            if i in LOCKED:
                s.circle(M + 116, cy - 9, 3.5, T["pattern"])
            mini_bar(s, M + 130, cy, 80, VEL[i], color)
            mini_bar(s, M + 222, cy, 64, GATE[i], color)
            s.text(M + 318, cy + 4, str(CHANCE.get(i, 100)), 11, 700, T["text"] if i in CHANCE else T["faint"], "end")
            s.text(M + 336, cy + 4, str(RATCHET.get(i, 1)), 11, 700, T["text"] if i in RATCHET else T["faint"], "middle")
            s.circle(M + 356, cy, 5, color if acc else T["control"])
            if slide:
                s.path(f"M{M + 368} {cy + 4} q 5 -9 10 -9", stroke=color, sw=2.2)
            else:
                s.circle(M + 373, cy, 2, T["control"])
        else:
            s.text(M + 95, cy + 4, "–", 12, 700, T["faint"], "middle")
        if i == PLAYHEAD:
            s.rect(M + 1, ry + 1, CW - 2, row_h - 2, stroke=T["pattern"], r=8, sw=1.5)
        if i == held:
            s.rect(M + 1, ry + 1, CW - 2, row_h - 2, stroke=T["text"], r=8, sw=2)


def drum_editor(s, y, color, sel=0, row_h=34):
    """Drum steps as rows, parts as columns."""
    x0 = M + 34
    cw = (CW - 38) / 8
    for c, part in enumerate(["Kick", "Snare", "Clap", "Tom", "CH", "OH", "Cym", "Perc"]):
        on = c == sel
        if on:
            s.rect(x0 + c * cw + 1, y - 4, cw - 2, 22, color, r=8)
        s.text(x0 + c * cw + cw / 2, y + 11, part, 10, 900, T["bg"] if on else T["quiet"], "middle")
    y += 22
    s.rect(M, y - 4, CW, 16 * row_h + 8, T["surface"], r=12)
    for i in range(16):
        ry = y + i * row_h
        if (i // 4) % 2:
            s.rect(M + 2, ry, CW - 4, row_h, "#23262D")
        s.text(M + 26, ry + row_h / 2 + 4, str(i + 1), 11, 700, T["faint"] if i % 4 else T["quiet"], "end")
        for c in range(8):
            cx = x0 + c * cw + 3
            if i in LANES[c]:
                v, rat = KICK[i] if (c == 0 and i in KICK) else (0.8, 1)
                s.rect(cx, ry + 4, cw - 6, row_h - 8, color, r=7, opacity=0.45 + 0.55 * v)
                for k in range(rat if rat > 1 else 0):
                    s.rect(cx + 6 + k * 6, ry + row_h - 12, 3, 3, T["bg"], r=1)
            else:
                s.rect(cx, ry + 4, cw - 6, row_h - 8, T["control"], r=7, opacity=0.6)
        if i == PLAYHEAD:
            s.rect(M + 1, ry + 1, CW - 2, row_h - 2, stroke=T["pattern"], r=8, sw=1.5)


def s04_steps(out, held=None):
    s = chrome("Seq", 1)
    y = BODY + 8
    seq_header(s, y, 0)
    step_editor(s, y + 44, TRACK[1], held=held)
    pads_drawer(s, TRACK[1])
    return s


def s04(out):
    s04_steps(out).save(out)


def s05_steps_drums(out):
    s = chrome("Seq", 0)
    y = BODY + 8
    seq_header(s, y, 0)
    drum_editor(s, y + 48, TRACK[0])
    pads_drawer(s, TRACK[0])
    s.save(out)


# clip notes: (start step, length in steps, note, velocity) — free lengths and off-grid starts
CLIP = [(0, 3, "C2", 1), (3, 1, "C3", .7), (4.5, 1.5, "Eb2", .8), (6, 2, "G2", .9), (8, 4, "Bb2", .6),
        (10, 1, "C3", 1), (12, 2, "F2", .7), (12, 2, "Ab2", .7), (14, 2, "G2", .9)]
SELECTED = 4
CHROM = ["D3", "C#3", "C3", "B2", "Bb2", "A2", "Ab2", "G2", "F#2", "F2", "E2", "Eb2", "D2", "C#2", "C2", "B1"]


def piano_roll(s, y, h, color, rows, lane=True):
    kw = 40
    x0 = M + kw + 4
    cw = (W - M - x0) / 16
    rh = (h - 18 - (70 if lane else 0)) / len(rows)
    # ruler
    for b in range(4):
        s.text(x0 + b * 4 * cw + 3, y + 11, f"2.{b + 1}", 10, 700, T["faint"])
    gy = y + 18
    gh = rh * len(rows)
    s.rect(x0, gy, W - M - x0, gh, T["surface"])
    for r, nme in enumerate(rows):
        black = "#" in nme or nme[:2] in ("Bb", "Eb", "Ab")
        ry = gy + r * rh
        if black:
            s.rect(x0, ry, W - M - x0, rh, "#1B1D22")
        # keys
        s.rect(M, ry + 0.5, kw, rh - 1, "#2E323A" if black else "#C9C4BE", r=3)
        if nme.startswith("C") and "#" not in nme:
            s.text(M + kw - 5, ry + rh / 2 + 4, nme, 10, 900, T["bg"], "end")
        s.line(x0, ry + rh, W - M, ry + rh, T["divider"], 0.6)
    for c in range(17):
        s.line(x0 + c * cw, gy, x0 + c * cw, gy + gh, T["faint"] if c % 4 == 0 else T["divider"],
               1 if c % 4 == 0 else 0.6, opacity=0.6 if c % 4 == 0 else None)
    for i, (st, ln, note, v) in enumerate(CLIP):
        if note not in rows:
            continue
        nx, ny = x0 + st * cw + 1, gy + rows.index(note) * rh + 2
        nw, nh = ln * cw - 2, rh - 4
        sel = i == SELECTED
        s.rect(nx, ny, nw, nh, color, r=4, opacity=1 if sel else 0.45 + 0.45 * v)
        if sel:
            s.rect(nx - 1.5, ny - 1.5, nw + 3, nh + 3, stroke=T["text"], r=5, sw=2)
            for hx in (nx + 4, nx + nw - 4):      # resize handles
                s.rect(hx - 1.5, ny + 4, 3, nh - 8, T["bg"], r=1.5)
            pill(s, nx, ny - 30, "Bb2 · 96 · 1/4", 11, h=24, fill=T["text"], color=T["bg"], padx=10)
    px = x0 + 4.25 * cw
    s.line(px, y + 14, px, gy + gh, T["pattern"], 2)
    if lane:   # note velocities
        ly = gy + gh + 10
        s.rect(M, ly, CW, 60, T["surface"], r=12)
        s.text(M + 10, ly + 34, "VEL", 10, 900, T["faint"], ls=1)
        for i, (st, ln, note, v) in enumerate(CLIP):
            sx = x0 + st * cw + 2
            top = ly + 8 + (44 * (1 - v))
            s.line(sx, ly + 52, sx, top, color if i == SELECTED else T["quiet"], 2, opacity=1 if i == SELECTED else 0.6)
            s.circle(sx, top, 3.5, color if i == SELECTED else T["quiet"])


def roll_tools(s, y):
    segmented(s, M, y, 150, 32, ["Draw", "Select"], 0, T["text"], 12)
    pill(s, M + 160, y + 2, "1/16", 12, h=28, caret=True)
    for k, redo in enumerate((False, True)):
        cx = W - M - 60 + k * 44
        s.circle(cx, y + 16, 16, T["control"])
        icon_undo(s, cx, y + 16, 14, T["quiet"], redo=redo)


def s15_roll(out):
    color = TRACK[1]
    s = chrome("Seq", 1)
    y = BODY + 8
    seq_header(s, y, 1)
    roll_tools(s, y + 44)
    piano_roll(s, y + 86, EDIT_END - (y + 86), color, CHROM)
    pads_drawer(s, color)
    s.save(out)


def s16_roll_pads(out):
    color = TRACK[1]
    s = chrome("Seq", 1)
    y = BODY + 8
    seq_header(s, y, 1)
    piano_roll(s, y + 42, PADS_Y - 16 - (y + 42), color, CHROM[2:10], lane=False)
    s.rect(W / 2 - 20, PADS_Y - 9, 40, 4, T["faint"], r=2)        # fold handle
    pads16(s, color, "notes", lit=(7,))
    s.save(out)


def choice_pads(s, x, y, w, opts, sel, color, h=44):
    gap = 8
    pw = (w - gap * (len(opts) - 1)) / len(opts)
    for i, o in enumerate(opts):
        on = i == sel
        s.rect(x + i * (pw + gap), y, pw, h, color if on else T["control"], r=12)
        s.text(x + i * (pw + gap) + pw / 2, y + h / 2 + 5, o, 14, 900, T["bg"] if on else T["quiet"], "middle")


def bar_slider(s, x, y, w, v, color, name, value, h=44):
    """A whole-width drag bar: the fill is the value."""
    s.rect(x, y, w, h, T["control"], r=12)
    s.rect(x, y, w * v, h, color, r=12, opacity=0.35)
    s.rect(x + w * v - 3, y + 8, 3, h - 16, color, r=1.5)
    s.text(x + 14, y + h / 2 + 5, name, 13, 900, T["text"])
    s.text(x + w - 14, y + h / 2 + 5, value, 13, 700, T["quiet"], "end")


def s06_step_sheet(out):
    color = TRACK[1]
    s = s04_steps(out, held=5)
    dim(s)
    y0 = 360
    sheet(s, y0, "Step 6", "G2")
    icon_copy(s, W - M - 70, y0 + 42, 18, T["quiet"])
    icon_trash(s, W - M - 26, y0 + 42, 18, T["quiet"])
    y = y0 + 72
    bar_slider(s, M, y, CW, .76, color, "Velocity", "96")
    bar_slider(s, M, y + 54, CW, .5, color, "Gate", "50%")
    bar_slider(s, M, y + 108, CW, .5, color, "Micro", "0", )
    s.line(M + CW / 2, y + 116, M + CW / 2, y + 144, T["faint"], 1.5)
    y += 170
    label(s, M + 4, y, "Chance")
    choice_pads(s, M, y + 10, CW, ["100", "75", "50", "25"], 0, color)
    y += 72
    label(s, M + 4, y, "Ratchet")
    choice_pads(s, M, y + 10, CW, ["1", "2", "3", "4"], 0, color)
    y += 72
    half = (CW - 8) / 2
    pad(s, M, y, half, 48, color, None, state="lit", r=12)
    s.text(M + half / 2, y + 30, "Accent", 14, 900, T["bg"], "middle")
    pad(s, M + half + 8, y, half, 48, color, None, r=12)
    s.text(M + half * 1.5 + 8, y + 30, "Slide", 14, 900, T["quiet"], "middle")
    y += 62
    lw = pill(s, M, y, "Cutoff  1.2 kHz", 12, fill=T["pattern"], color=T["bg"], h=30, padx=30)
    icon_lock(s, M + 16, y + 15, 11, T["bg"])
    pill(s, M + lw + 8, y, "+ Lock", 12, h=30, color=T["quiet"])
    s.save(out)


def preset_bar(s, y, color, name, family):
    w = CW - 52
    s.rect(M, y, w, 40, T["control"], r=20)
    icon_chevron(s, M + 20, y + 20, 8, T["quiet"], "left", 2)
    icon_chevron(s, M + w - 20, y + 20, 8, T["quiet"], "right", 2)
    s.text(M + w / 2 - 22, y + 25.5, name, 14, 900, T["text"], "middle")
    pill(s, M + w / 2 + 34, y + 9, family, 10, h=22, padx=8, fill=T["bg"], color=color)
    s.circle(W - M - 20, y + 20, 20, T["control"])
    icon_dots(s, W - M - 20, y + 20, T["quiet"], gap=5, r=1.8)


def section_tabs(s, y, names, sel, color):
    x = M
    for i, nme in enumerate(names):
        on = i == sel
        w = pill(s, x, y, nme, 13, fill=color if on else T["control"], color=T["bg"] if on else T["quiet"],
                 h=34, padx=14, weight=900 if on else 700)
        x += w + 8
        if x > W:
            break


def knob_grid(s, y, knobs, color, cols=3, row_h=104):
    for i, (nme, v, val) in enumerate(knobs):
        c, r = i % cols, i // cols
        knob(s, M + CW * (c + 0.5) / cols, y + 36 + r * row_h, 25, v, color, nme, label_size=12, value=val)


def s07_sound(out):
    sel, color = 1, TRACK[1]
    s = chrome("Sound", sel)
    y = BODY + 8
    preset_bar(s, y, color, "Resonant Line", "Classic")
    section_tabs(s, y + 54, ["Osc", "Stack", "Filter", "Accent", "Slide", "Amp", "LFO"], 2, color)
    cy = y + 102
    s.rect(M, cy, CW, EDIT_END - cy, T["surface"], r=16)
    vis_filter(s, M + 12, cy + 12, CW - 24, 190, color, cut=.42, res=.72)
    s.circle(M + 12 + (CW - 24) * .42 + 8, cy + 12 + 190 * .2, 9, color)          # drag node: cutoff × reso
    s.circle(M + 12 + (CW - 24) * .42 + 8, cy + 12 + 190 * .2, 15, stroke=color, sw=1.5, opacity=.4)
    segmented(s, M + 12, cy + 214, CW - 24, 34, ["Ladder", "Diode"], 1, color, 12)
    knob_grid(s, cy + 264, [("Cutoff", .42, "640 Hz"), ("Reso", .72, "72%"), ("Env Mod", .6, "60%"),
                            ("Decay", .45, "310 ms"), ("Key", .3, "30%"), ("Bass Comp", .5, "50%")], color)
    pads_drawer(s, color)
    s.save(out)


def s08_sound_drums(out):
    sel, color = 0, TRACK[0]
    s = chrome("Sound", sel)
    y = BODY + 8
    preset_bar(s, y, color, "House", "Kits")
    # part pads choose which drum the editor shows (and play it)
    cells = grid(M, y + 52, CW, 96, 4, 2, gap=6)
    cells = [cells[(1 - i // 4) * 4 + i % 4] for i in range(8)]
    for i, (x, yy, w, h) in enumerate(cells):
        on = i == 0
        pad(s, x, yy, w, h, color, None, state="lit" if on else "idle", r=10)
        s.text(x + w / 2, yy + h / 2 + 5, DRUM_PARTS[i], 12, 900, T["bg"] if on else T["quiet"], "middle")
    section_tabs(s, y + 162, ["Kick", "Shape", "Mix", "Resonator", "Kit", "Reverb"], 0, color)
    cy = y + 210
    s.rect(M, cy, CW, EDIT_END - cy, T["surface"], r=16)
    vis_env(s, M + 12, cy + 12, CW - 24, 120, color, kind="pitch", d=.12)
    s.circle(M + 12 + (CW - 24) * .18, cy + 12 + 120 * .62, 9, color)
    segmented(s, M + 12, cy + 144, CW - 24, 34, ["Classic", "Punch", "Box", "Ring", "Layer"], 3, color, 12)
    knob_grid(s, cy + 184, [("Pitch", .35, "A1"), ("Bend", .6, "+24 st"), ("Bend Time", .3, "40 ms"),
                            ("Decay", .55, "420 ms"), ("Tone", .4, "40%"), ("Click", .5, "50%")], color, row_h=96)
    pads_drawer(s, color)
    s.save(out)


def s09_mix(out):
    s = chrome("Mix", sel=1, tracks=False)
    y = TOP + 16
    levels = [.78, .7, .55, .6, .72, .5, .64, 0]
    meters = [.62, .58, .4, .35, .55, .2, .5, 0]
    gap = 6
    cw = (CW - 7 * gap) / 8
    colh = 380
    for i in range(8):
        x = M + i * (cw + gap)
        dev = RACK[i]
        col = TRACK[i]
        if dev is None:
            s.rect(x, y, cw, colh, stroke=T["faint"], r=12, sw=1.5, dash="4 4")
            icon_plus(s, x + cw / 2, y + colh / 2, 12, T["faint"])
        else:
            mute = i == 3
            s.rect(x, y, cw, colh, T["control"], r=12)
            lv = levels[i]
            s.rect(x, y + colh * (1 - lv), cw, colh * lv, col, r=12, opacity=0.18 if mute else 0.32)
            s.rect(x + 6, y + colh * (1 - lv) - 2, cw - 12, 4, T["text"] if not mute else T["faint"], r=2)
            m = meters[i]
            s.rect(x + cw / 2 - 2, y + colh * (1 - m) + 6, 4, colh * m - 12, col, r=2, opacity=0.0 if mute else 0.9)
            s.circle(x + cw / 2, y + 22, 13, col)
            s.text(x + cw / 2, y + 26.5, str(i + 1), 12, 900, T["bg"], "middle")
        # mute / solo pads
        by = y + colh + 10
        mute, solo = i == 3, i == 1
        s.rect(x, by, cw, 44, T["mute"] if mute else T["control"], r=10)
        s.text(x + cw / 2, by + 27, "M", 13, 900, T["bg"] if mute else T["quiet"], "middle")
        s.rect(x, by + 50, cw, 44, T["text"] if solo else T["control"], r=10)
        s.text(x + cw / 2, by + 77, "S", 13, 900, T["bg"] if solo else T["quiet"], "middle")
    # master
    my = y + colh + 10 + 94 + 16
    s.rect(M, my, CW, 210, T["surface"], r=16)
    label(s, M + 16, my + 26, "Master")
    pill(s, W - M - 12, my + 10, "Effects", 12, h=28, anchor="end", caret=True)
    for i, c in enumerate(["#E88A8A", "#8FD0C2", "#E9A3B8", "#A9D58E", "#9EB9F3"]):
        s.circle(W - M - 128 + i * 9, my + 24, 3, c)
    for i, (nme, v, val) in enumerate([("Delay", .45, "3/16"), ("Reverb", .35, "Plate"), ("Glue", .4, "2.1 dB"),
                                       ("Limit", .8, "−0.3 dB")]):
        knob(s, M + CW * (i + 0.5) / 4, my + 82, 22, v, T["pattern"], nme, label_size=12, value=val)
    bar_slider(s, M + 12, my + 150, CW - 24, .74, T["pattern"], "Volume", "−6.2 dB", h=46)
    s.save(out)


def s10_perform(out):
    s = chrome("Perform", tracks=False)
    y = TOP + 18
    label(s, M + 4, y + 10, "Patterns")
    pill(s, W - M, y - 6, "Bar", 12, h=28, caret=True, anchor="end")
    cells = grid(M, y + 30, CW, 220, 4, 2)
    cells = [cells[(1 - i // 4) * 4 + i % 4] for i in range(8)]
    for i, (x, yy, w, h) in enumerate(cells):
        letter = "ABCDEFGH"[i]
        used, bars = PATTERNS[letter]
        if used is None:
            s.rect(x, yy, w, h, stroke=T["faint"], r=14, sw=1.5, dash="4 4")
            s.text(x + w / 2, yy + h / 2 + 9, letter, 26, 900, T["faint"], "middle")
            continue
        state = "current" if letter == "A" else ("queued" if letter == "B" else "idle")
        if state == "current":
            s.rect(x, yy, w, h, T["pattern"], r=14, opacity=0.22)
            s.rect(x, yy, w, h, stroke=T["pattern"], r=14, sw=2)
            s.path(arc_d(x + w - 18, yy + 18, 8, -90, 144), stroke=T["pattern"], sw=2.5)   # progress
        elif state == "queued":
            s.rect(x, yy, w, h, T["control"], r=14)
            s.rect(x, yy, w, h, stroke=T["pattern"], r=14, sw=2, dash="5 4")
            s.text(x + w - 12, yy + 22, "2", 12, 900, T["pattern"], "end")
        else:
            s.rect(x, yy, w, h, T["control"], r=14)
        s.text(x + 14, yy + 40, letter, 28, 900, T["pattern"] if state != "idle" else T["text"])
        for k, u in enumerate(used):
            s.circle(x + 16 + k * 8, yy + h - 30, 2.5, TRACK[k] if u else T["faint"])
        s.text(x + 14, yy + h - 11, f"{bars} bars" if bars > 1 else "1 bar", 11, 700, T["faint"])
    y += 30 + 220 + 26
    label(s, M + 4, y + 10, "FX")
    y += 30
    cells = grid(M, y, CW, 290, 4, 2)
    cells = [cells[(1 - i // 4) * 4 + i % 4] for i in range(8)]
    for i, (x, yy, w, h) in enumerate(cells):
        held = i in (0, 5)
        if held:
            s.rect(x, yy, w, h, T["pattern"], r=14)
        else:
            s.rect(x, yy, w, h, T["control"], r=14)
        s.text(x + 12, yy + 24, FX_PADS[i], 13, 900, T["bg"] if held else T["text"])
        s.text(x + w - 12, yy + h - 12, str(i + 1), 11, 700, T["bg"] if held else T["faint"], "end")
        if i == 5:   # Filter: finger position sweeps it
            s.circle(x + w * .4, yy + h * .45, 16, stroke=T["bg"], sw=2)
            s.circle(x + w * .4, yy + h * .45, 4, T["bg"])
    y += 290 + 16
    bar_slider(s, M, y, CW, .74, T["pattern"], "Volume", "−6.2 dB", h=46)
    s.save(out)


def device_glyph(s, cx, cy, key, color, size=44):
    h = size * 0.28
    if key == "mono":
        s.path(f"M{cx - h} {cy + h * 0.6} V{cy - h * 0.6} H{cx} V{cy + h * 0.6} H{cx + h} V{cy - h * 0.6}", stroke=color, sw=2.4)
    elif key == "poly":
        for i in range(3):
            s.rect(cx - h + i * h * 0.75, cy - h * 0.7 + i * 3, h * 0.45, h * 1.4 - i * 3, color, r=1.5)
    elif key == "acid":
        s.path(f"M{cx - h} {cy - h * 0.2} H{cx - h * 0.1} Q{cx + h * 0.2} {cy - h} {cx + h * 0.4} {cy + h * 0.7} H{cx + h}",
               stroke=color, sw=2.4)
    elif key == "fm":
        s.rect(cx - h * 0.45, cy - h, h * 0.9, h * 0.7, stroke=color, sw=2, r=2)
        s.rect(cx - h * 0.45, cy + h * 0.3, h * 0.9, h * 0.7, color, r=2)
        s.line(cx, cy - h * 0.3, cx, cy + h * 0.3, color, 2)
    elif key == "sampler":
        for i in range(7):
            a = [0.3, 0.8, 1, 0.6, 0.9, 0.4, 0.2][i]
            s.line(cx - h + i * h / 3, cy - h * a, cx - h + i * h / 3, cy + h * a, color, 2.4)
    elif key in ("drums", "drumsampler"):
        g = h * 0.15
        for i in range(4):
            s.rect(cx - h + (i % 2) * (h + g), cy - h + (i // 2) * (h + g), h - g, h - g,
                   color if (i == 0 or key == "drumsampler" and i == 3) else T["faint"], r=2)
    else:
        icon_minus(s, cx, cy, h * 1.4, T["faint"])


def s11_device_picker(out):
    s = chrome("Play", sel=1)
    dim(s)
    y0 = 380
    sheet(s, y0, "Track 8", "Pattern A")
    devs = [("mono", "Bassline", "Mono"), ("poly", "VA", "Poly · 6 voices"), ("acid", "Acid", "Bass line"),
            ("fm", "FM", "6 operators"), ("sampler", "Sampler", "Chromatic"), ("drums", "Drums", "Drum synth"),
            ("drumsampler", "Drum Sampler", "8 pads · slicer"), (None, "Empty", "")]
    cells = grid(M, y0 + 72, CW, 400, 2, 4)
    cells = [cells[(3 - i // 2) * 2 + i % 2] for i in range(8)]
    col = TRACK[7]
    for i, (x, yy, w, h) in enumerate(cells):
        key, nme, sub = devs[i]
        if key is None:
            s.rect(x, yy, w, h, stroke=T["faint"], r=14, sw=1.5, dash="4 4")
            s.text(x + w / 2, yy + h / 2 + 5, nme, 14, 900, T["faint"], "middle")
            continue
        on = i == 1
        s.rect(x, yy, w, h, T["control"], r=14)
        if on:
            s.rect(x, yy, w, h, stroke=col, r=14, sw=2)
        device_glyph(s, x + 34, yy + h / 2, key, col if on else T["quiet"])
        s.text(x + 62, yy + h / 2 - 2, nme, 15, 900)
        s.text(x + 62, yy + h / 2 + 16, sub, 11, 700, T["faint"])
    s.save(out)


def s12_tempo(out):
    s = chrome("Play", sel=1)
    dim(s)
    y0 = 300
    sheet(s, y0, "Tempo")
    y = y0 + 76
    s.rect(M, y, 72, 72, T["control"], r=16)
    icon_minus(s, M + 36, y + 36, 18, T["text"], 2.5)
    s.rect(W - M - 72, y, 72, 72, T["control"], r=16)
    icon_plus(s, W - M - 36, y + 36, 18, T["text"], 2.5)
    s.text(W / 2, y + 48, BPM, 44, 900, T["text"], "middle")
    s.text(W / 2, y + 68, "BPM", 11, 900, T["faint"], "middle", ls=1.2)
    y += 92
    s.rect(M, y, CW, 104, T["pattern"], r=18, opacity=.18)
    s.rect(M, y, CW, 104, stroke=T["pattern"], r=18, sw=1.5)
    s.text(W / 2, y + 60, "TAP", 22, 900, T["pattern"], "middle", ls=3)
    y += 128
    label(s, M + 4, y, "Time")
    choice_pads(s, M, y + 10, CW, ["3/4", "4/4", "5/4", "6/8", "7/8"], 1, T["text"])
    y += 76
    bar_slider(s, M, y, CW, .24, T["text"], "Swing", "56%", h=48)
    y += 64
    half = (CW - 8) / 2
    for k, (nme, on) in enumerate([("Click", True), ("Count-in", False)]):
        x = M + k * (half + 8)
        s.rect(x, y, half, 56, T["control"], r=14)
        if k == 0:
            icon_metronome(s, x + 26, y + 28, 18, T["text"])
        s.text(x + (46 if k == 0 else 16), y + 33, nme, 14, 900, T["text"] if on else T["quiet"])
        toggle(s, x + half - 54, y + 17, on, T["text"])
    s.save(out)


def s13_record(out):
    s = chrome("Play", sel=1, rec=True, queued=None, pos="1.1.1")
    # count-in over the pads
    play_synth(s, TRACK[1], "acid", "notes", 0)
    dim(s)
    y0 = 430
    sheet(s, y0, "Record")
    s.circle(W - M - 22, y0 + 42, 22, T["record"])
    s.text(W - M - 22, y0 + 50, "3", 22, 900, T["bg"], "middle")
    y = y0 + 76
    choice_pads(s, M, y, CW, ["Live", "Step"], 0, T["record"], h=52)
    y += 74
    label(s, M + 4, y, "Takes")
    choice_pads(s, M, y + 10, CW, ["Extend", "Overdub"], 0, T["text"])
    y += 76
    label(s, M + 4, y, "Quantize")
    choice_pads(s, M, y + 10, CW, ["Off", "50", "75", "100"], 3, T["text"])
    y += 76
    half = (CW - 8) / 2
    for k, (nme, on) in enumerate([("Count-in", True), ("Knobs", True)]):
        x = M + k * (half + 8)
        s.rect(x, y, half, 56, T["control"], r=14)
        s.text(x + 16, y + 33, nme, 14, 900)
        toggle(s, x + half - 54, y + 17, on, T["text"])
    s.save(out)


def s14_project(out):
    s = Svg(W, H)
    status_bar(s)
    y = STATUS + 10
    s.circle(M + 20, y + 22, 20, T["control"])
    icon_back(s, M + 20, y + 22, 14, T["text"])
    s.text(M + 54, y + 29, "Night Market", 20, 900)
    s.circle(M + 54 + 134, y + 23, 3.5, T["mute"])
    y += 66
    acts = [("New", icon_new), ("Open", icon_folder), ("Save", icon_save), ("Export", icon_export),
            ("Share", icon_share)]
    cells = grid(M, y, CW, 76, 5, 1, gap=8)
    for (x, yy, w, h), (nme, fn) in zip(cells, acts):
        s.rect(x, yy, w, h, T["control"], r=14)
        fn(s, x + w / 2, yy + 28, 20, T["text"])
        s.text(x + w / 2, yy + 60, nme, 11, 700, T["quiet"], "middle")
    y += 96
    # audio
    s.rect(M, y, CW, 206, T["surface"], r=16)
    label(s, M + 16, y + 28, "Audio")
    pill(s, W - M - 12, y + 12, "Low latency", 11, h=24, fill=T["bg"], color="#A8D58A", anchor="end")
    s.text(M + 16, y + 74, "8.4", 40, 900)
    s.text(M + 84, y + 74, "ms", 15, 700, T["quiet"])
    s.text(M + 16, y + 98, "AAudio · exclusive · 48 kHz · 192 frames", 12, 700, T["quiet"])
    # buffer steps (bursts)
    label(s, M + 16, y + 132, "Buffer")
    for i, b in enumerate(["1×", "2×", "3×", "4×"]):
        on = i == 1
        x = M + 92 + i * 66
        s.rect(x, y + 114, 58, 30, T["text"] if on else T["control"], r=15)
        s.text(x + 29, y + 134, b, 12, 900, T["bg"] if on else T["quiet"], "middle")
    s.text(M + 16, y + 180, "Output", 13, 700, T["quiet"])
    s.text(W - M - 16, y + 180, "Phone speaker", 13, 900, T["text"], "end")
    y += 222
    # midi
    s.rect(M, y, CW, 100, T["surface"], r=16)
    label(s, M + 16, y + 28, "MIDI")
    s.circle(M + 22, y + 60, 4, "#A8D58A")
    s.text(M + 36, y + 65, "KeyLab Essential 49 mk2", 14, 900)
    s.text(W - M - 16, y + 65, "USB", 12, 700, T["quiet"], "end")
    s.circle(M + 22, y + 84, 4, T["faint"])
    s.text(M + 36, y + 89, "Bluetooth MIDI", 13, 700, T["quiet"])
    y += 116
    rows = [("Pattern switch", "Bar"), ("Pad velocity", "Touch height"), ("Haptics", None), ("Keep playing in background", None)]
    s.rect(M, y, CW, 56 * len(rows), T["surface"], r=16)
    for i, (k, v) in enumerate(rows):
        yy = y + i * 56
        if i:
            s.line(M + 16, yy, W - M - 16, yy, T["divider"], 1)
        s.text(M + 16, yy + 34, k, 14, 700)
        if v:
            s.text(W - M - 34, yy + 34, v, 13, 700, T["quiet"], "end")
            icon_chevron(s, W - M - 20, yy + 29, 7, T["faint"], "right", 1.8)
        else:
            toggle(s, W - M - 56, yy + 17, True, T["text"])
    gesture(s)
    s.save(out)


SCREENS = [
    ("01_play_notes", s01_play_notes),
    ("02_play_chords", s02_play_chords),
    ("03_play_drums", s03_play_drums),
    ("04_seq_steps", s04),
    ("05_seq_drums", s05_steps_drums),
    ("06_step_sheet", s06_step_sheet),
    ("07_sound", s07_sound),
    ("08_sound_drums", s08_sound_drums),
    ("09_mix", s09_mix),
    ("10_perform", s10_perform),
    ("11_device_picker", s11_device_picker),
    ("12_tempo", s12_tempo),
    ("13_record", s13_record),
    ("14_project", s14_project),
    ("15_seq_roll", s15_roll),
    ("16_seq_roll_pads", s16_roll_pads),
]


def main():
    svg_dir = os.path.join(ROOT, "mockups", "svg")
    png_dir = os.path.join(ROOT, "mockups", "png")
    os.makedirs(svg_dir, exist_ok=True)
    os.makedirs(png_dir, exist_ok=True)
    rsvg = shutil.which("rsvg-convert")
    for name, fn in SCREENS:
        path = os.path.join(svg_dir, name + ".svg")
        fn(path)
        if rsvg:
            subprocess.run([rsvg, "-z", "2", "-o", os.path.join(png_dir, name + ".png"), path], check=True)
        print(os.path.relpath(path, ROOT))


if __name__ == "__main__":
    main()

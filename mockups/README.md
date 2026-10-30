# Minibox for Android: mockups

These are phone screens: portrait only, 412 × 915 dp, with PNGs rendered at 2×. The SVGs are the source of truth.

```
python3 mockups/tools/build.py      # writes svg/ and png/ (needs rsvg-convert for the PNGs)
```

The generator reuses the desktop kit (`../pastel-minibox/mockups/tools/svgkit.py`, `specs.py`) for the theme tokens,
knobs and visuals. Set `MINIBOX_KIT` to point at it if the folders move.

## Principles

- **Pads replace the keyboard:** always **16 square pads (4×4) at the bottom** of the screen, sitting on the nav where
  thumbs rest. Controls (mode, scale, 8 macros) sit above them. On the editors (Seq, Sound) the pads fold into a
  **Pads** handle in the same place; pull it up and the same 4×4 appears under the editor (`16_seq_roll_pads`).
- **Dedicated editors:** the Seq tab switches between **Steps** (a step editor panel) and **Roll** (a piano roll panel),
  each using the full height.
- **One hero per screen**, with a thumb-sized control budget. Everything else is behind a long-press, a tap on a value, or ⋯.
- **The app bar is always there:** record, play, stop, tempo and position, the current and queued pattern, and ⋯.
  Playing is never more than one tap away.
- **Colour:** the track colour is the only accent, lilac is for patterns and the playhead, and red is for record only.
  There is no instructional text.

## Screens

| File | Screen |
|---|---|
| `01_play_notes` | Play, synth: Notes/Chords/Arp, scale, octave and 8 macros on top; 4×4 in-scale pads at the bottom (rows a fourth apart, roots tinted, sequenced note outlined) |
| `02_play_chords` | Play, Chords mode: diatonic triads (lower 8) and sevenths (upper 8) |
| `03_play_drums` | Play, drums: roll rate and 8 macros on top; 4×4 pads: lower 8 hit the parts (Kick bottom-left), upper 8 roll the same parts at the rate; touch height = velocity |
| `04_seq_steps` | Seq → Steps, synth: the step editor panel, one row per step (on, note, velocity, gate, chance, ratchet, accent, slide; lilac dot = parameter locks), playhead row, Pads handle |
| `05_seq_drums` | Seq → Steps, drums: steps as rows × 8 parts as columns (brightness = velocity, dots = ratchet), Pads handle |
| `06_step_sheet` | Long-press a step row: velocity, gate, micro, chance, ratchet, accent/slide, parameter locks |
| `07_sound` | Sound, Acid Filter tab: preset bar, section tabs, draggable filter node, choice, 6 knobs, Pads handle |
| `08_sound_drums` | Sound, Drums: part buttons pick the drum, then the Kick tab with engine choice and pitch envelope |
| `09_mix` | Mix: 8 fader columns (drag anywhere; meter inside), M/S, master (effects chain, sends, glue, limit, volume) |
| `10_perform` | Perform: A–H pattern pads (current, queued with a countdown, empty), quantize, 8 hold-to-play FX pads, volume |
| `11_device_picker` | Track sheet: 7 devices plus Empty, for the current pattern |
| `12_tempo` | Tempo sheet: −/+, big TAP, time signature, swing, click, count-in |
| `13_record` | Long-press record: Live/Step, Extend/Overdub, quantize, count-in (counting 3), knob recording |
| `14_project` | ⋯: file actions, the audio latency readout (AAudio exclusive, buffer in bursts), MIDI devices, settings |
| `15_seq_roll` | Seq → Roll: the piano roll panel. Piano keys, bar.beat ruler, free-length and off-grid notes, chords, the selected note with resize handles and its note · velocity · length, playhead, Draw/Select, snap, undo/redo, velocity lane, Pads handle |
| `16_seq_roll_pads` | The Roll with the pads pulled up: the roll shrinks above the 4×4 for step input and auditioning |

## Gestures

| Gesture | Where | Does |
|---|---|---|
| Touch down | Play pads, FX pads | Sounds immediately (never waits for tap-up). Finger height sets the velocity. |
| Tap | Step row's on button / drum cell | Toggle the step; drag down the column to paint |
| Drag sideways | Step row's velocity / gate bar | Set it; drag the note pill up or down to change the note |
| Tap / drag | Piano roll | Draw: add a note, drag to set its length. Select: move, resize from either end, box-select |
| Pull up / down | Pads handle | Show or fold the 4×4 under the Seq or Sound editor |
| Long-press | Step row / record / track chip | Step sheet / record sheet / device picker |
| Hold a step row + turn a macro | Seq → Play macros | Parameter lock on that step |
| Drag vertically | Knob, fader column | Value; double-tap resets |
| Tap | Tempo / pattern tile in the app bar | Tempo sheet / Perform tab |

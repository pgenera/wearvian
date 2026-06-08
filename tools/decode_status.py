#!/usr/bin/env python3
"""Decode Rivian CHAR_VEHICLE_STATUS (0x1c) frames out of a wearvian debug log.

We are still reverse-engineering the plaintext 0x1c status frame (SoC, range, cabin
temp, charge state/power) against ground truth, and we'll be chasing the remaining
fields for a while. This tool pulls every 0x1c frame out of a captured watch debug
log and prints the decoded fields as a table, so a capture can be correlated against
what the official app showed without re-counting hex by hand.

It is the Python mirror of `wear/app/.../service/VehicleStatus.kt`. Keep the two in
sync: the byte layout in `decode_status()` and the `CHARGE_KW_DIVISOR` constant must
match the Kotlin parser. The self-tests in `test_decode_status.py` pin both against
real captured frames.

No third-party deps.

Usage:
    tools/decode_status.py LOG [LOG ...]      # decoded table (one row per frame)
    tools/decode_status.py LOG --unique       # collapse consecutive identical frames
    tools/decode_status.py LOG --raw          # also print the raw frame hex
    tools/decode_status.py LOG --power         # only frames that report charge power

------------------------------------------------------------------------------
Capturing on the watch
------------------------------------------------------------------------------
The app logs each received 0x1c frame to its in-app debug console (swipe left), e.g.
    dbg: ← PK/0x1c  VEHICLE_STATUS 0100000010ffac0f0031181bdd00005078000000  20B
Export that console to a file (the *.log captures in the repo root are gitignored —
they can carry VIN/MAC/nonce material, so they stay out of git).

------------------------------------------------------------------------------
Frame layout (decoded on-vehicle 2026-06-06..06-08; see VehicleStatus.kt)
------------------------------------------------------------------------------
Frame = [le32 counter] ‖ [16-byte status]; some log lines omit the counter, so we
anchor on the 16-byte status and treat any leading bytes as the counter.

  status[0] bit0      asleep
  status[1] hi-nibble locked; lo-nibble doors  (0x0f = all closed)
  status[2] hi-nibble locked; 0x08 frunk, 0x04 liftgate  (1 = closed)
  status[3] lo-nibble windows  (0x0f = all closed)
  status[5]           state of charge, integer %
  status[6] lo-nibble charge-state enum  (1 unplugged, 2 starting, 3 charging,
                                          5 plugged-idle, 7 fault)
  status[7]           cabin temperature, °C
  status[8]           estimated range, km (LOW byte only; high byte unlocated)
  status[9..10]       live charge power, LE16, in 1/CHARGE_KW_DIVISOR kW per count
  status[11..15]      config tail, constant 50 78 00 00 00 (used as the frame anchor)
"""

import argparse
import re
import sys

# --- decode constants (keep in sync with VehicleStatus.kt) -------------------

# Charge power is status[9..10] (LE16) in 1/DIVISOR kW per count. Pinned to ~70 by
# two captures matched against the official app: raw 644 -> 9.1 kW, raw 692 -> 9.9 kW
# (an earlier /64 overshot by ~9%, reading 10.8 where the app showed 9.9).
CHARGE_KW_DIVISOR = 70

# status[11..15] are a constant config tail; we anchor frame extraction on it so the
# 16-byte status is located whether or not a counter prefixes it in the log line.
CONFIG_TAIL = "5078000000"

CHARGE_STATES = {1: "unplugged", 2: "starting", 3: "charging", 5: "plugged-idle", 7: "fault"}


def extract_frames(text):
    """Yield raw status frames (16-byte `bytes`) found in a log's text.

    Primary path keys off our log marker (`VEHICLE_STATUS <hex>`); the fallback scans
    any hex run for a 16-byte window ending in the config tail, so a reformatted or
    marker-less dump still decodes. If the *log* format changes, update MARKER_RE.
    """
    seen_spans = set()
    # 1) canonical marker emitted by the watch's debug log.
    marker_re = re.compile(r"VEHICLE_STATUS\s+([0-9a-fA-F]{32,})")
    for m in marker_re.finditer(text):
        frame = _status_from_hex(m.group(1))
        if frame is not None:
            seen_spans.add(m.span())
            yield frame
    # 2) fallback: any hex run carrying a status (config-tail-anchored), for
    #    captures that don't use the marker. Skip runs already matched above.
    run_re = re.compile(r"[0-9a-fA-F]{32,}")
    for m in run_re.finditer(text):
        if any(s[0] <= m.start() < s[1] for s in seen_spans):
            continue
        frame = _status_from_hex(m.group(0))
        if frame is not None:
            yield frame


def _status_from_hex(hexstr):
    """Return the 16-byte status from a frame hex string, or None if it isn't one.

    Anchors on the config tail: the status is the 32 hex chars ending at the tail.
    Any leading hex is the le32 counter (ignored for decoding)."""
    hexstr = hexstr.lower()
    idx = hexstr.rfind(CONFIG_TAIL)
    if idx < 0:
        return None
    end = idx + len(CONFIG_TAIL)
    start = end - 32  # status is 16 bytes
    if start < 0:
        return None
    try:
        return bytes.fromhex(hexstr[start:end])
    except ValueError:
        return None


def decode_status(s):
    """Decode a 16-byte status into a dict. Mirrors VehicleStatus.update()."""
    raw_power = s[9] | (s[10] << 8)
    return {
        "asleep": bool(s[0] & 0x01),
        "locked": bool(s[2] & 0xF0),
        "frunk_open": (s[2] & 0x08) == 0,
        "hatch_open": (s[2] & 0x04) == 0,
        "door_open": (s[1] & 0x0F) != 0x0F,
        "window_open": (s[3] & 0x0F) != 0x0F,
        "soc_pct": s[5],
        "charge_state": CHARGE_STATES.get(s[6] & 0x0F, "?0x%x" % (s[6] & 0x0F)),
        "cabin_c": s[7],
        "range_km": s[8],
        "power_raw": raw_power,
        "power_kw": raw_power / CHARGE_KW_DIVISOR,
    }


def _closures(d):
    bits = []
    if d["door_open"]:
        bits.append("door")
    if d["window_open"]:
        bits.append("window")
    if d["frunk_open"]:
        bits.append("frunk")
    if d["hatch_open"]:
        bits.append("hatch")
    return ",".join(bits) if bits else "-"


def main(argv=None):
    ap = argparse.ArgumentParser(description="Decode 0x1c VEHICLE_STATUS frames from wearvian logs.")
    ap.add_argument("logs", nargs="+", help="log file(s) to scan")
    ap.add_argument("--unique", action="store_true", help="collapse consecutive identical decodes")
    ap.add_argument("--raw", action="store_true", help="also print the raw status hex")
    ap.add_argument("--power", action="store_true", help="only frames reporting charge power > 0")
    args = ap.parse_args(argv)

    hdr = "%-6s %-4s %-12s %-5s %-6s %-6s %-9s %s" % (
        "lock", "soc", "charge", "cabC", "rngkm", "kW", "raw", "closures")
    print(hdr)
    print("-" * len(hdr))

    last = None
    total = 0
    for path in args.logs:
        try:
            with open(path, "r", errors="replace") as f:
                text = f.read()
        except OSError as e:
            print("! %s: %s" % (path, e), file=sys.stderr)
            continue
        for s in extract_frames(text):
            d = decode_status(s)
            if args.power and d["power_raw"] == 0:
                continue
            key = s.hex()
            if args.unique and key == last:
                continue
            last = key
            total += 1
            row = "%-6s %-4d %-12s %-5d %-6d %-6.2f %-9d %s" % (
                "locked" if d["locked"] else "open",
                d["soc_pct"], d["charge_state"], d["cabin_c"], d["range_km"],
                d["power_kw"], d["power_raw"], _closures(d))
            if args.raw:
                row += "  " + key
            print(row)
    print("-" * len(hdr))
    print("%d frame(s)" % total)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

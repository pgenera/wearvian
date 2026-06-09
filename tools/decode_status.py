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
    tools/decode_status.py LOG --raw          # also print the 16 raw status bytes
    tools/decode_status.py LOG --power        # only frames that report charge power
    tools/decode_status.py LOG --div 255      # recompute kW with a different divisor
    tools/decode_status.py LOG --summary      # per-byte value summary (find the mode bit)

------------------------------------------------------------------------------
Open investigation: the charge-power scale is mode-dependent
------------------------------------------------------------------------------
`[9..10]` (LE16) is NOT a single-scale power field. AC sessions fit raw/70 kW
(raw 644->9.1, 692->9.9), but a later session read raw ~1295 while the app showed
5.1 kW (raw/255). Same field, two scales -> a charge-MODE selector (AC vs DC, or
phase/unit) lives in another byte. `--summary` exists to find it: run it on an
AC-session log and a DC-session log and compare which byte(s) flip between them
(prime suspects: [6] charge-state, [4], or the high bits of [10]). `--div` lets you
test a candidate scale without editing code. Until the selector is known, the
default divisor stays 70 (correct for AC); do NOT retune it from one number.

------------------------------------------------------------------------------
Capturing on the watch
------------------------------------------------------------------------------
The app logs each received 0x1c frame to its in-app debug console (swipe left), e.g.
    dbg: <- PK/0x1c  VEHICLE_STATUS 0100000010ffac0f0031181bdd00005078000000  20B
Export that console to a file (the *.log captures in the repo root are gitignored --
they can carry VIN/MAC/nonce material, so they stay out of git).

------------------------------------------------------------------------------
Frame layout (decoded on-vehicle 2026-06-06..06-08; see VehicleStatus.kt)
------------------------------------------------------------------------------
Frame = [le32 counter] || [16-byte status]; some log lines omit the counter, so we
anchor on the 16-byte status and treat any leading bytes as the counter.

  status[0] bit0      asleep
  status[1] hi-nibble locked; lo-nibble doors  (0x0f = all closed)
  status[2] hi-nibble locked; 0x08 frunk, 0x04 liftgate  (1 = closed)
  status[3] lo-nibble windows  (0x0f = all closed)
  status[5]           state of charge, integer %
  status[6] lo-nibble charge-state enum  (1 unplugged, 2 starting, 3 charging,
                                          5 plugged-idle, 7 fault)
  status[7]           cabin temperature, deg C
  status[8]           estimated range, km (LOW byte only; high byte unlocated)
  status[9..10]       live charge power, LE16, in 1/CHARGE_KW_DIVISOR kW per count
                      (AC only -- scale is mode-dependent; see note above)
  status[11..15]      config tail, constant 50 78 00 00 00 (used as the frame anchor)
"""

import argparse
import re
import sys

# --- decode constants (keep in sync with VehicleStatus.kt) -------------------

# Charge power is status[9..10] (LE16) in 1/DIVISOR kW per count, for AC charging.
# Pinned to ~70 by two AC captures matched against the official app: raw 644 -> 9.1 kW,
# raw 692 -> 9.9 kW. A later session fit raw/255 (~5.1 kW) -> the scale is mode-dependent
# (see the module docstring); this default is the AC scale.
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


def decode_status(s, divisor=CHARGE_KW_DIVISOR):
    """Decode a 16-byte status into a dict. Mirrors VehicleStatus.update().

    `divisor` scales the raw charge-power count to kW (default = the AC scale)."""
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
        "power_kw": raw_power / divisor,
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


def summarize(frames, div):
    """Print a per-byte value summary across all collected frames.

    `frames` is a list of (path, 16-byte status). Constant bytes are flagged; varying
    ones list their value set. This is the tool for finding the AC/DC mode selector:
    summarize an AC-session log and a DC-session log, then compare which byte flips.
    """
    print()
    print("=== per-byte summary over %d frame(s) ===" % len(frames))
    allvals = [set() for _ in range(16)]
    for _, b in frames:
        for i in range(16):
            allvals[i].add(b[i])
    for i in range(16):
        vals = sorted(allvals[i])
        tag = "CONST" if len(vals) == 1 else "VARIES"
        shown = " ".join("%02x" % v for v in vals[:16]) + (" ..." if len(vals) > 16 else "")
        print("  [%2d] %-6s %s" % (i, tag, shown))
    # Power-specific view: the raw [9..10] range and what it implies under candidate scales.
    powers = sorted({(b[9] | (b[10] << 8)) for _, b in frames if (b[9] | (b[10] << 8)) > 0})
    if powers:
        lo, hi = powers[0], powers[-1]
        print("  charge power raw [9..10]: %d distinct, range %d..%d" % (len(powers), lo, hi))
        print("    under /%d (AC): %.2f..%.2f kW" % (div, lo / div, hi / div))
        print("    under /255    : %.2f..%.2f kW   (the suspected DC/alt scale)" % (lo / 255, hi / 255))


def main(argv=None):
    ap = argparse.ArgumentParser(description="Decode 0x1c VEHICLE_STATUS frames from wearvian logs.")
    ap.add_argument("logs", nargs="+", help="log file(s) to scan")
    ap.add_argument("--unique", action="store_true", help="collapse consecutive identical decodes")
    ap.add_argument("--raw", action="store_true", help="also print the 16 raw status bytes")
    ap.add_argument("--power", action="store_true", help="only frames reporting charge power > 0")
    ap.add_argument("--div", type=int, default=CHARGE_KW_DIVISOR,
                    help="charge-power divisor (kW = raw/div); default %(default)s (AC scale)")
    ap.add_argument("--summary", action="store_true",
                    help="after the table, print a per-byte value summary (finds the mode selector)")
    args = ap.parse_args(argv)

    hdr = "%-6s %-4s %-12s %-5s %-6s %-7s %-6s %s" % (
        "lock", "soc", "charge", "cabC", "rngkm", "kW", "raw", "closures")
    print(hdr)
    print("-" * len(hdr))

    last = None
    total = 0
    collected = []  # (path, status) for --summary
    for path in args.logs:
        try:
            with open(path, "r", errors="replace") as f:
                text = f.read()
        except OSError as e:
            print("! %s: %s" % (path, e), file=sys.stderr)
            continue
        for s in extract_frames(text):
            d = decode_status(s, args.div)
            if args.power and d["power_raw"] == 0:
                continue
            key = s.hex()
            if args.unique and key == last:
                continue
            last = key
            total += 1
            collected.append((path, s))
            row = "%-6s %-4d %-12s %-5d %-6d %-7.2f %-6d %s" % (
                "locked" if d["locked"] else "open",
                d["soc_pct"], d["charge_state"], d["cabin_c"], d["range_km"],
                d["power_kw"], d["power_raw"], _closures(d))
            if args.raw:
                row += "  " + " ".join("%02x" % b for b in s)
            print(row)
    print("-" * len(hdr))
    print("%d frame(s)  (kW divisor=%d)" % (total, args.div))
    if args.summary and collected:
        summarize(collected, args.div)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Decode Rivian CHAR_VEHICLE_STATUS (0x1c) frames out of a wearvian debug log.

We are still reverse-engineering the plaintext 0x1c status frame (SoC, range, cabin
temp, charge state/power) against ground truth, and we'll be chasing the remaining
fields for a while. This tool pulls every 0x1c frame out of a captured watch debug
log and prints the decoded fields as a table, so a capture can be correlated against
what the official app showed without re-counting hex by hand.

It is the Python mirror of `wear/app/.../service/VehicleStatus.kt`. Keep the two in
sync: the byte layout in `decode_status()` must match the Kotlin parser. The self-tests
in `test_decode_status.py` pin both against real captured frames.

No third-party deps.

Usage:
    tools/decode_status.py LOG [LOG ...]      # decoded table (one row per frame)
    tools/decode_status.py LOG --unique       # collapse consecutive identical frames
    tools/decode_status.py LOG --raw          # also print the 16 raw status bytes
    tools/decode_status.py LOG --charging     # only frames that are actively charging
    tools/decode_status.py LOG --summary      # per-byte value summary

------------------------------------------------------------------------------
RESOLVED 2026-06-09: `[9..10]` is charge TIME-to-complete, not power
------------------------------------------------------------------------------
A fixed-SoC (50%) amperage sweep settled it: 20A->1483, 28A->1042, 44A->654, i.e.
the field FALLS as current rises and raw*current is ~constant (~29k) -- the signature
of time ∝ 1/power. Power must RISE with current, so the field can't be power. The
old "raw/70 kW" fit and the "raw/255 = 5.1 kW" anomaly were the same illusion: both
captures sat in a power band where the time value numerically resembled the kW the app
showed, so power and time were degenerate. There is NO AC/DC mode selector to find --
true charge POWER is simply not in this 16-byte frame (no other byte tracks current).
The unit/target of the time field (minutes? to 100% or to the limit?) is still
unconfirmed; capture this raw alongside the app's "time to charge complete" to pin it.

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
  status[9..10]       charge TIME-to-complete, LE16 (∝ 1/power; NOT power -- see above)
  status[11..15]      config tail; only [12..13]=78 00 is constant ([11],[14],[15] vary)
"""

import argparse
import re
import sys

# --- decode constants (keep in sync with VehicleStatus.kt) -------------------

# status[12..13] == 0x78,0x00 is the only part of the [11..15] tail that has held across
# every capture ([11], [14], [15] vary); the marker-less fallback anchors frame extraction
# on it. (The marker path doesn't need it — it just takes the trailing 16 bytes.)
CONFIG_ANCHOR = "7800"

CHARGE_STATES = {1: "unplugged", 2: "starting", 3: "charging", 5: "plugged-idle", 7: "fault"}


def extract_frames(text):
    """Yield raw status frames (16-byte `bytes`) found in a log's text.

    Primary path keys off our log marker (`VEHICLE_STATUS <hex>`); the fallback scans
    any hex run for a 16-byte window ending in the config tail, so a reformatted or
    marker-less dump still decodes. If the *log* format changes, update MARKER_RE.
    """
    seen_spans = set()
    # 1) canonical marker emitted by the watch's debug log. The marker carries the whole
    #    frame ([le32 counter][16B status]), so the status is just the trailing 16 bytes —
    #    no content anchor needed (the [11..15] tail is NOT constant across captures).
    marker_re = re.compile(r"VEHICLE_STATUS\s+([0-9a-fA-F]{32,})")
    for m in marker_re.finditer(text):
        frame = _status_trailing(m.group(1))
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


def _status_trailing(hexstr):
    """Return the 16-byte status from a marker frame, or None if it can't be one.

    The marker always carries `[le32 counter][16B status]`, so the status is the last
    16 bytes; any leading hex is the counter (ignored). Robust to the [11..15] tail
    varying between captures (it does)."""
    hexstr = hexstr.lower()
    if len(hexstr) < 32 or len(hexstr) % 2:
        return None
    try:
        return bytes.fromhex(hexstr[-32:])
    except ValueError:
        return None


def _status_from_hex(hexstr):
    """Return the 16-byte status from a marker-less hex run, or None if it isn't one.

    Anchors on status[12..13] == 0x78,0x00 (the one part of the [11..15] tail that has
    held across every capture — [11], [14], [15] all vary). The status is the 16 bytes
    whose byte 12 is that anchor; any leading hex is the le32 counter. Used only by the
    fallback scan, which needs a *content* anchor to reject non-status hex runs."""
    hexstr = hexstr.lower()
    idx = hexstr.rfind(CONFIG_ANCHOR)
    if idx < 0:
        return None
    start = idx - 24  # status[12] sits 12 bytes (24 hex) into the 16-byte status
    if start < 0:
        return None
    try:
        return bytes.fromhex(hexstr[start:start + 32])
    except ValueError:
        return None


def decode_status(s):
    """Decode a 16-byte status into a dict. Mirrors VehicleStatus.update()."""
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
        "charge_time_raw": s[9] | (s[10] << 8),  # ETA-to-limit (∝ 1/power), not power
        "eta_min": (s[9] | (s[10] << 8)) * 16 / 60,  # ~16 s per count (pinned vs app "5h18m")
    }


def _eta(minutes):
    m = int(round(minutes))
    return "%dh%02dm" % (m // 60, m % 60) if m >= 60 else "%dm" % m


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


def summarize(frames):
    """Print a per-byte value summary across all collected frames.

    `frames` is a list of (path, 16-byte status). Constant bytes are flagged; varying
    ones list their value set -- handy for spotting which byte tracks a condition.
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
    times = sorted({(b[9] | (b[10] << 8)) for _, b in frames if (b[9] | (b[10] << 8)) > 0})
    if times:
        print("  charge-time raw [9..10]: %d distinct, range %d..%d (∝ 1/power, not power)"
              % (len(times), times[0], times[-1]))


def main(argv=None):
    ap = argparse.ArgumentParser(description="Decode 0x1c VEHICLE_STATUS frames from wearvian logs.")
    ap.add_argument("logs", nargs="+", help="log file(s) to scan")
    ap.add_argument("--unique", action="store_true", help="collapse consecutive identical decodes")
    ap.add_argument("--raw", action="store_true", help="also print the 16 raw status bytes")
    ap.add_argument("--charging", action="store_true", help="only frames that are actively charging")
    ap.add_argument("--summary", action="store_true",
                    help="after the table, print a per-byte value summary")
    args = ap.parse_args(argv)

    hdr = "%-6s %-4s %-12s %-5s %-6s %-8s %-8s %s" % (
        "lock", "soc", "charge", "cabC", "rngkm", "t_raw", "eta", "closures")
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
            d = decode_status(s)
            if args.charging and d["charge_state"] != "charging":
                continue
            key = s.hex()
            if args.unique and key == last:
                continue
            last = key
            total += 1
            collected.append((path, s))
            row = "%-6s %-4d %-12s %-5d %-6d %-8d %-8s %s" % (
                "locked" if d["locked"] else "open",
                d["soc_pct"], d["charge_state"], d["cabin_c"], d["range_km"],
                d["charge_time_raw"], _eta(d["eta_min"]) if d["charge_time_raw"] else "-",
                _closures(d))
            if args.raw:
                row += "  " + " ".join("%02x" % b for b in s)
            print(row)
    print("-" * len(hdr))
    print("%d frame(s)" % total)
    if args.summary and collected:
        summarize(collected)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

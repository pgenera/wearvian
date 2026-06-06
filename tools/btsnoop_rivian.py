#!/usr/bin/env python3
"""Decode a Rivian phone-key BLE session from a btsnoop HCI capture.

This is the tool we use to reverse-engineer / sanity-check the BLE protocol from
captures of the *official* Rivian app (or our own watch app). It turns a raw
`btsnoop_hci.log` into a readable timeline of the GATT traffic on the Rivian
phone-key service, classifying each frame by its role in the protocol.

Requires `tshark` (Wireshark CLI) on PATH. No Python deps.

Usage:
    tools/btsnoop_rivian.py CAPTURE.log                # full timeline
    tools/btsnoop_rivian.py CAPTURE.log --from 13 --to 35   # time window (s)
    tools/btsnoop_rivian.py CAPTURE.log --heartbeats   # include 0x1b heartbeats
    tools/btsnoop_rivian.py bugreport.zip              # extracts btsnoop itself

Capturing on the phone: Developer options -> Enable Bluetooth HCI snoop log
(set to "Enabled", not "Filtered"), toggle Bluetooth off/on, perform the
actions, then `adb bugreport`. The snoop lands at
  FS/data/misc/bluetooth/logs/btsnoop_hci.log
inside the bugreport zip.

----------------------------------------------------------------------------
What the protocol looks like (Gen-1 R1S, decoded 2026-06-06)
----------------------------------------------------------------------------
GATT value handles on the phone-key service double as Rivian's 16-bit short
ids (value handle 0x00NN carries the characteristic whose short id is 0xNN):

  0x12  CHAR_PHONE_ID   phone writes 16B phoneId; vehicle echoes 16B vehicle-id
  0x15  CHAR_NONCE      phone writes 48B pNonce;  vehicle returns 48B vNonce
  0x18  RIV_MOBKEY_WRITE  present but UNUSED for commands (old M1 red herring)
  0x1b  CHAR_READ       phone streams 37B authenticated heartbeats (~12 Hz)
  0x20  CHAR_ACTIVE     the command + status channel (see below)

The 0x20 channel is bidirectional and frames are tagged by their first 2 bytes:
  16 01 ...  COMMAND   phone -> vehicle, 64B  = [16 01][12B IV][AES-128-GCM ct+tag]
  17 01 ...  CMD ACK   vehicle -> phone, 67B  (encrypted CommandReturnValue)
  18 01 ...  STATUS    vehicle -> phone, 114B (encrypted vehicle/closure state)
  01 01 ..   SensorInformation (small, plaintext-ish handshake control)
  03 01      short control / session marker

37B heartbeat layout: [le32 counter][1B flag][32B keyed payload]. The le32
counter is plaintext and increments per authenticated message; it resets to 0
on each (re)connection. Commands share this running counter (the HMAC preimage
binds le32(counter)), so a command sent mid-session must use the *current*
counter, not a hardcoded 0 -- a one-shot counter=0 is only valid as the first
message of a fresh connection.
----------------------------------------------------------------------------
"""

import argparse
import os
import subprocess
import sys
import tempfile
import zipfile

# value handle (== Rivian short id) -> human name
CHAR_NAMES = {
    "0x0012": "PHONE_ID",
    "0x0015": "NONCE",
    "0x0017": "MOBKEY_WRITE(0x18)",
    "0x001b": "HEARTBEAT",
    "0x0020": "ACTIVE",
}

# 0x20-channel frame tags (first 2 bytes) -> role
FRAME_TAGS = {
    "1601": "COMMAND  ->veh",
    "1701": "CMD-ACK  <-veh",
    "1801": "STATUS   <-veh",
    "0101": "SensorInfo",
    "0301": "control",
}

BTSNOOP_IN_BUGREPORT = "FS/data/misc/bluetooth/logs/btsnoop_hci.log"


def extract_if_zip(path):
    """If path is a bugreport zip, pull the btsnoop out to a temp file."""
    if not zipfile.is_zipfile(path):
        return path, None
    tmp = tempfile.NamedTemporaryFile(prefix="btsnoop_", suffix=".log", delete=False)
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if n.endswith("btsnoop_hci.log")]
        if not names:
            sys.exit(f"no btsnoop_hci.log found inside {path}")
        name = BTSNOOP_IN_BUGREPORT if BTSNOOP_IN_BUGREPORT in names else names[0]
        with z.open(name) as src:
            tmp.write(src.read())
    tmp.close()
    return tmp.name, tmp.name


def run_tshark(snoop):
    fields = ["frame.time_relative", "btatt.opcode", "btatt.handle", "btatt.value"]
    cmd = ["tshark", "-r", snoop, "-Y", "btatt", "-T", "fields"]
    for f in fields:
        cmd += ["-e", f]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"tshark failed:\n{out.stderr}")
    rows = []
    for line in out.stdout.splitlines():
        parts = (line.split("\t") + ["", "", "", ""])[:4]
        rows.append(parts)
    return rows


def clean_hex(v):
    """Return clean hex string, or None if this value column isn't a byte blob
    (e.g. a comma-separated handle list from a discovery response)."""
    v = v.replace(":", "")
    if "," in v or not v:
        return None
    try:
        bytes.fromhex(v)
    except ValueError:
        return None
    return v


def classify(handle, opcode, hexval):
    """Return (label, detail) for one ATT frame, or None to skip."""
    n = len(hexval) // 2
    char = CHAR_NAMES.get(handle, handle)
    is_write = opcode in ("0x12", "0x52")
    is_notify = opcode in ("0x1b", "0x1d")
    if not (is_write or is_notify):
        return None

    if handle == "0x0020":
        # 6B `07xx....0100` notifications are the vehicle's per-heartbeat
        # ranging/RSSI responses -- high volume, bucket them.
        if is_notify and n == 6 and hexval.startswith("07"):
            return ("RANGING  <-veh", f"{n:3d}B {hexval}")
        tag = FRAME_TAGS.get(hexval[:4], f"tag={hexval[:4]}")
        return (tag, f"{n:3d}B {hexval}")
    if handle == "0x001b" and n == 37:
        counter = int.from_bytes(bytes.fromhex(hexval[:8]), "little")
        return ("HEARTBEAT", f"ctr={counter:<4d} {hexval}")
    if handle in ("0x0012", "0x0015"):
        dirn = "->veh" if is_write else "<-veh"
        return (f"{char} {dirn}", f"{n:3d}B {hexval}")
    return (f"{char} {'W' if is_write else 'N'}", f"{n:3d}B {hexval}")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("capture", help="btsnoop_hci.log or an adb bugreport .zip")
    ap.add_argument("--from", dest="t0", type=float, default=None, help="start time (s)")
    ap.add_argument("--to", dest="t1", type=float, default=None, help="end time (s)")
    ap.add_argument("--heartbeats", action="store_true",
                    help="include 0x1b heartbeat frames (noisy; off by default)")
    args = ap.parse_args()

    snoop, tmp = extract_if_zip(args.capture)
    try:
        rows = run_tshark(snoop)
    finally:
        if tmp:
            os.unlink(tmp)

    counts = {}
    for t, opcode, handle, value in rows:
        hexval = clean_hex(value)
        if hexval is None:
            continue
        res = classify(handle, opcode, hexval)
        if res is None:
            continue
        label, detail = res
        counts[label] = counts.get(label, 0) + 1
        if label == "HEARTBEAT" and not args.heartbeats:
            continue
        try:
            rt = float(t)
        except ValueError:
            continue
        if args.t0 is not None and rt < args.t0:
            continue
        if args.t1 is not None and rt > args.t1:
            continue
        marker = ">>>" if label.startswith("COMMAND") else "   "
        print(f"{rt:9.3f} {marker} {label:16s} {detail}")

    print("\n=== frame counts ===")
    for label in sorted(counts, key=lambda k: -counts[k]):
        print(f"  {counts[label]:5d}  {label}")


if __name__ == "__main__":
    main()

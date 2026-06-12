#!/usr/bin/env python3
"""Tests for tools/decode_status.py.

Pure-logic tests, no deps. The frame samples are real bytes from the on-vehicle
0x1c captures (sunday-status.log, monday-status-plugged-in-and-charging.log) and
mirror the Kotlin KATs in VehicleStatusTest.kt, so the Python tool and the app
parser stay in agreement. Run:

    python3 tools/test_decode_status.py
"""

import importlib.util
import os
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "decode_status", os.path.join(_HERE, "decode_status.py"))
ds = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ds)


class ExtractTest(unittest.TestCase):
    def test_marker_line(self):
        line = ("06-08 18:25:06.238  8187  8200 I wearvian: dbg: "
                "← PK/0x1c  VEHICLE_STATUS 0100000010ffac0f0031181bdd00005078000000  20B")
        frames = list(ds.extract_frames(line))
        self.assertEqual(1, len(frames))
        # 16-byte status, counter stripped.
        self.assertEqual("10ffac0f0031181bdd00005078000000", frames[0].hex())

    def test_markerless_fallback(self):
        # A bare hex run (no VEHICLE_STATUS marker) still decodes via the [12..13]=7800 anchor.
        frames = list(ds.extract_frames("blah 100f0c0f0030111edc00005078000000 blah"))
        self.assertEqual(1, len(frames))

    def test_marker_decodes_varied_tail(self):
        # Real 44A charging frame whose [11..15] tail is 14 78 00 80 0c (not the old 50 78 00 00 00);
        # the marker path takes the trailing 16 bytes, so the tail variation doesn't matter.
        line = "VEHICLE_STATUS 0900000011ffac0f0032131be39002147800800c"
        frames = list(ds.extract_frames(line))
        self.assertEqual(1, len(frames))
        self.assertEqual("11ffac0f0032131be39002147800800c", frames[0].hex())

    def test_marker_not_double_counted(self):
        # The marker path and the fallback scan must not both yield the same frame.
        line = "VEHICLE_STATUS 0100000010ffac0f0031181bdd00005078000000"
        self.assertEqual(1, len(list(ds.extract_frames(line))))

    def test_non_status_hex_ignored(self):
        # An encrypted/other frame without the config tail is skipped.
        self.assertEqual([], list(ds.extract_frames("1801d8018696afca8f0e88ecef203b9b")))


class DecodeTest(unittest.TestCase):
    def test_sunday_telemetry(self):
        # sunday-status.log ground truth: SoC 48%, range 137mi(=220km), cabin 86F(=30C), unplugged.
        s = bytes.fromhex("100f0c0f0030111edc00005078000000")
        d = ds.decode_status(s)
        self.assertEqual(48, d["soc_pct"])
        self.assertEqual(30, d["cabin_c"])
        self.assertEqual(220, d["range_km"])
        self.assertEqual("unplugged", d["charge_state"])
        self.assertEqual(0, d["charge_time_raw"])

    def test_charge_time_amperage_sweep(self):
        # Fixed-SoC (50%) 2026-06-09 sweep: raw [9..10] falls as current rises, and raw*current
        # is ~constant (~29k) -- proving the field is time-to-complete (∝ 1/power), not power.
        a20 = ds.decode_status(bytes.fromhex("10ffac0f0032131be3d005147800800c"))  # 20A
        a44 = ds.decode_status(bytes.fromhex("11ffac0f0032131be39002147800800c"))  # 44A
        self.assertEqual(50, a20["soc_pct"])
        self.assertEqual("charging", a20["charge_state"])
        self.assertEqual(0x05d0, a20["charge_time_raw"])  # 1488
        self.assertEqual(0x0290, a44["charge_time_raw"])  # 656
        self.assertGreater(a20["charge_time_raw"], a44["charge_time_raw"])  # lower current -> more time
        # While charging, range is the 9-bit field [8]+bit0([9]): 0xe3d0 & 0x1ff = 0x0e3 = 227.
        self.assertEqual(227, a20["range_km"])
        self.assertEqual(227, a44["range_km"])

    def test_range_nine_bits_while_charging(self):
        # mileage-charging.log 2026-06-12: SoC 68%, charging, app showed 193 mi. Range shares [9]
        # with the ETA: 0xcd36 & 0x1ff = 0x136 = 310 km = 193 mi; old [8]-only read 0x36 = 54 km
        # = 34 mi (the bug). ETA = [9..10] = 0x00cd = 205, unaffected by the range mask.
        d = ds.decode_status(bytes.fromhex("100f0c0f0044131c36cd005078000000"))
        self.assertEqual(68, d["soc_pct"])
        self.assertEqual("charging", d["charge_state"])
        self.assertEqual(310, d["range_km"])
        self.assertEqual(0xcd, d["charge_time_raw"])

    def test_range_high_byte_when_not_charging(self):
        # mileage.log 2026-06-10: SoC 62%, unplugged, app showed 173 mi. [8..9]=18 01 = 0x0118 =
        # 280 km = 174 mi; the old [8]-only parse read 0x18 = 24 km ~= 15 mi (the bug).
        d = ds.decode_status(bytes.fromhex("100f0c0f003e111c1801005078000000"))
        self.assertEqual(62, d["soc_pct"])
        self.assertEqual("unplugged", d["charge_state"])
        self.assertEqual(280, d["range_km"])
        self.assertEqual(0, d["charge_time_raw"])

    def test_closure_bits(self):
        # [1]=0x0f unlocked + doors closed; [2]=0x04 frunk open, liftgate closed.
        d = ds.decode_status(bytes.fromhex("000f04000000000000005078000000"))
        self.assertFalse(d["locked"])
        self.assertTrue(d["frunk_open"])
        self.assertFalse(d["hatch_open"])


if __name__ == "__main__":
    unittest.main()

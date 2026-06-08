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
        # A bare hex run (no VEHICLE_STATUS marker) still decodes via the config-tail anchor.
        frames = list(ds.extract_frames("blah 100f0c0f0030111edc00005078000000 blah"))
        self.assertEqual(1, len(frames))

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
        self.assertEqual(0, d["power_raw"])

    def test_charge_power_divisor_is_seventy(self):
        # monday raw 692 -> 9.9 kW (the app's reading); sunday raw 644 -> ~9.2 kW. Divisor ~70.
        s = bytes.fromhex("11ffac0f00311318ddb4025078000000")  # raw 0x02b4 = 692
        d = ds.decode_status(s)
        self.assertEqual(692, d["power_raw"])
        self.assertAlmostEqual(9.886, d["power_kw"], places=2)
        self.assertEqual("charging", d["charge_state"])

    def test_closure_bits(self):
        # [1]=0x0f unlocked + doors closed; [2]=0x04 frunk open, liftgate closed.
        d = ds.decode_status(bytes.fromhex("000f04000000000000005078000000"))
        self.assertFalse(d["locked"])
        self.assertTrue(d["frunk_open"])
        self.assertFalse(d["hatch_open"])


if __name__ == "__main__":
    unittest.main()

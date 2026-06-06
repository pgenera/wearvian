#!/usr/bin/env python3
"""Tests for tools/btsnoop_rivian.py.

Pure-logic tests only -- no tshark required (so they run anywhere). The frame
samples below are real bytes lifted from the 2026-06-06 sleeping-truck capture
(official app: unlock + charge-port + windows). Run:

    python3 tools/test_btsnoop_rivian.py
"""

import importlib.util
import io
import os
import tempfile
import unittest
import zipfile

# Load the hyphen-free sibling module by path so the test runs from any cwd.
_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "btsnoop_rivian", os.path.join(_HERE, "btsnoop_rivian.py"))
btsnoop = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(btsnoop)

# Real frame samples (handle, opcode, value) from the capture.
WRITE_REQ, WRITE_CMD, NOTIFY = "0x12", "0x52", "0x1b"
READ_REQ = "0x0a"

CMD_FRAME = ("1601ece9d92332d0d2b63e408a371cc3ee2a6896ca2381b4137b408c467cf41"
             "a9ae1c849bc276c27710b9c2808a70c4f520cee400a822456099a01ed4bc71373")  # 64B
ACK_FRAME = ("1701c4b2a71ae381e3c523941c25fe6a43e40408f51b6fcdd8531b22329a9ad2c3"
             "699dd82aa6f601bf5d4f6a9760580f0de6984e3b47c6c4a5ee037bfaba8c19cb0665")  # 67B
STATUS_FRAME = ("1801aa9fce1540e2e05bbe181db04613c26eb175698e2f810e13907e8aa845f"
                "cc4f2cfa7dd903f0b6e55b5249cea708ad005c154e04b7f209ac6f473612cbda"
                "968b7c6f069398e2b2da79b8ecdef578a0cb4db848e77a002f483a6be5757af3"
                "6ca5916b880f299685a2c009cc2a59f87f767")  # 114B
RANGING_FRAME = "077f91060100"  # 6B
SENSORINFO = "010102070157034339b874"  # 11B
HB_CTR0 = "0000000080011a6018b5167332d1186356ccdd7ddd8ed22be9648e8450c2f16932e3288db0"
HB_CTR4 = "04000000ba8d0d49dc339f7dbc44d9806d1f6b64c24ddef0db5414501db682b9d37e9f0117"


class CleanHexTests(unittest.TestCase):
    def test_plain_hex(self):
        self.assertEqual(btsnoop.clean_hex("1601ab"), "1601ab")

    def test_strips_colons(self):
        self.assertEqual(btsnoop.clean_hex("16:01:ab"), "1601ab")

    def test_handle_list_rejected(self):
        # discovery responses put a comma-separated handle list in the value col
        self.assertIsNone(btsnoop.clean_hex("0x0001,0x000c,0x000d"))

    def test_empty_rejected(self):
        self.assertIsNone(btsnoop.clean_hex(""))

    def test_non_hex_rejected(self):
        self.assertIsNone(btsnoop.clean_hex("nothex"))


class ClassifyTests(unittest.TestCase):
    def label(self, handle, opcode, hexval):
        res = btsnoop.classify(handle, opcode, hexval)
        return res[0] if res else None

    def test_command_frame(self):
        self.assertEqual(self.label("0x0020", WRITE_REQ, CMD_FRAME), "COMMAND  ->veh")

    def test_command_via_write_command_opcode(self):
        self.assertEqual(self.label("0x0020", WRITE_CMD, CMD_FRAME), "COMMAND  ->veh")

    def test_cmd_ack(self):
        self.assertEqual(self.label("0x0020", NOTIFY, ACK_FRAME), "CMD-ACK  <-veh")

    def test_status(self):
        self.assertEqual(self.label("0x0020", NOTIFY, STATUS_FRAME), "STATUS   <-veh")

    def test_ranging_bucketed(self):
        self.assertEqual(self.label("0x0020", NOTIFY, RANGING_FRAME), "RANGING  <-veh")

    def test_sensorinfo(self):
        self.assertEqual(self.label("0x0020", NOTIFY, SENSORINFO), "SensorInfo")

    def test_unknown_0x20_tag_passes_through(self):
        self.assertEqual(self.label("0x0020", NOTIFY, "030199"), "control")
        self.assertEqual(self.label("0x0020", WRITE_REQ, "abcd00"), "tag=abcd")

    def test_heartbeat_counter_parsed_little_endian(self):
        label, detail = btsnoop.classify("0x001b", WRITE_REQ, HB_CTR0)
        self.assertEqual(label, "HEARTBEAT")
        self.assertIn("ctr=0", detail)
        _, detail4 = btsnoop.classify("0x001b", WRITE_REQ, HB_CTR4)
        self.assertIn("ctr=4", detail4)

    def test_phone_id_direction(self):
        self.assertEqual(self.label("0x0012", WRITE_REQ, "aa" * 16), "PHONE_ID ->veh")
        self.assertEqual(self.label("0x0012", NOTIFY, "bb" * 16), "PHONE_ID <-veh")

    def test_nonce(self):
        self.assertEqual(self.label("0x0015", WRITE_REQ, "cc" * 48), "NONCE ->veh")

    def test_read_request_skipped(self):
        # only writes/notifications/indications are interesting
        self.assertIsNone(btsnoop.classify("0x0020", READ_REQ, CMD_FRAME))


class ExtractIfZipTests(unittest.TestCase):
    def test_plain_file_passthrough(self):
        with tempfile.NamedTemporaryFile(suffix=".log", delete=False) as f:
            f.write(b"not a zip")
            path = f.name
        try:
            out, tmp = btsnoop.extract_if_zip(path)
            self.assertEqual(out, path)
            self.assertIsNone(tmp)
        finally:
            os.unlink(path)

    def test_extracts_btsnoop_from_zip(self):
        payload = b"BTSNOOP-FAKE-BYTES"
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("FS/some/other.txt", b"junk")
            z.writestr(btsnoop.BTSNOOP_IN_BUGREPORT, payload)
        with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as f:
            f.write(buf.getvalue())
            zpath = f.name
        tmp = None
        try:
            out, tmp = btsnoop.extract_if_zip(zpath)
            self.assertIsNotNone(tmp)
            with open(out, "rb") as g:
                self.assertEqual(g.read(), payload)
        finally:
            os.unlink(zpath)
            if tmp and os.path.exists(tmp):
                os.unlink(tmp)


if __name__ == "__main__":
    unittest.main(verbosity=2)

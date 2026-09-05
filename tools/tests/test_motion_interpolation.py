import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "blender"))
from prepare_model import normalize_motion


def motion(interpolation):
    return b"".join((
        b"Vocaloid Motion Data 0002".ljust(30, b"\0"),
        b"model".ljust(20, b"\0"),
        struct.pack("<I", 1),
        struct.pack("<15sI7f", b"bone", 30, 1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 1.0),
        bytes(interpolation),
        struct.pack("<5I", 0, 0, 0, 0, 0),
    ))


class MotionInterpolationTest(unittest.TestCase):
    def test_normalizes_compact_curves_without_changing_other_tracks(self):
        compact = bytes(range(1, 17)) + bytes(48)
        original = motion(compact)
        with tempfile.TemporaryDirectory(prefix="libmmd-vmd-") as directory:
            source = Path(directory) / "source.vmd"
            exported = Path(directory) / "normalized.vmd"
            source.write_bytes(original)
            self.assertEqual(normalize_motion(source, exported), 1)
            normalized = exported.read_bytes()
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual(normalized[:101], original[:101])
            self.assertEqual(normalized[165:], original[165:])
            for channel in range(4):
                self.assertEqual(normalized[101 + channel * 16:117 + channel * 16:4], compact[channel:16:4])
            repeated = Path(directory) / "repeated.vmd"
            self.assertEqual(normalize_motion(exported, repeated), 0)
            self.assertEqual(repeated.read_bytes(), normalized)

    def test_preserves_extended_layout_byte_for_byte(self):
        original = motion(bytes(range(64)))
        with tempfile.TemporaryDirectory(prefix="libmmd-vmd-") as directory:
            source = Path(directory) / "source.vmd"
            exported = Path(directory) / "normalized.vmd"
            source.write_bytes(original)
            self.assertEqual(normalize_motion(source, exported), 0)
            self.assertEqual(exported.read_bytes(), original)

    def test_rejects_invalid_input_and_existing_output(self):
        with tempfile.TemporaryDirectory(prefix="libmmd-vmd-") as directory:
            source = Path(directory) / "source.vmd"
            exported = Path(directory) / "normalized.vmd"
            for invalid in (b"", bytes(54), motion(bytes(64))[:164]):
                source.write_bytes(invalid)
                with self.assertRaises(ValueError):
                    normalize_motion(source, exported)
                self.assertFalse(exported.exists())
            original = motion(bytes(64))
            source.write_bytes(original)
            with self.assertRaises(FileExistsError):
                normalize_motion(source, source)
            self.assertEqual(source.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()

import os
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path


def pmx_with_texture(texture):
    def text(value):
        encoded = value.encode("utf-8")
        return struct.pack("<i", len(encoded)) + encoded

    return b"".join((
        b"PMX ", struct.pack("<f", 2.0), bytes((8, 1, 0, 4, 1, 1, 1, 1, 1)),
        text("模型"), text(""), text(""), text(""),
        struct.pack("<i8fBbf", 1, *([0.0] * 8), 0, 0, 1.0),
        struct.pack("<i3I", 3, 0, 0, 0),
        struct.pack("<i", 1), text(texture),
        struct.pack("<i", 1), text("材质"), text(""),
        struct.pack("<11fB5fbbBBB", *([0.0] * 11), 0, *([0.0] * 5), 0, -1, 0, 1, 0),
        text(""), struct.pack("<i", 3),
        struct.pack("<i", 1), text("骨骼"), text(""),
        struct.pack("<3fbiH3f", 0.0, 0.0, 0.0, -1, 0, 0, 0.0, 0.0, 0.0),
        struct.pack("<4i", 0, 0, 0, 0),
    ))


class CompilerPathTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        executable = "libmmdc.exe" if os.name == "nt" else "libmmdc"
        default = Path(__file__).resolve().parents[2] / "bazel-bin/native/libmmd" / executable
        cls.compiler = Path(os.environ.get("LIBMMDC", default)).resolve(strict=True)

    def run_compiler(self, *args):
        return subprocess.run([self.compiler, *args], capture_output=True, text=True, encoding="utf-8", check=False)

    def test_packs_unicode_paths_and_texture_names(self):
        with tempfile.TemporaryDirectory(prefix="libmmd-") as directory:
            model_directory = Path(directory) / "模型 目录"
            textures = model_directory / "纹理"
            textures.mkdir(parents=True)
            (textures / "皮肤.JPG").write_bytes(b"texture fixture")
            model = model_directory / "初音.pmx"
            model.write_bytes(pmx_with_texture("纹理\\皮肤.jpg"))
            inspected = self.run_compiler("inspect", model)
            self.assertEqual(inspected.returncode, 0, inspected.stderr)
            self.assertIn("missing_textures=0", inspected.stdout)
            pack = model_directory / "导出.mmdpack"
            result = self.run_compiler("pack", model, pack)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(pack.is_file())
            self.assertIn("format=MMDPACK", result.stdout)
            self.assertIn("纹理/皮肤.JPG".encode("utf-8"), pack.read_bytes())

    def test_missing_texture_fails_without_creating_pack(self):
        with tempfile.TemporaryDirectory(prefix="libmmd-") as directory:
            model = Path(directory) / "模型.pmx"
            model.write_bytes(pmx_with_texture("缺失.png"))
            pack = Path(directory) / "输出.mmdpack"
            result = self.run_compiler("pack", model, pack)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("missing texture: 缺失.png", result.stderr)
            self.assertFalse(pack.exists())


if __name__ == "__main__":
    unittest.main()

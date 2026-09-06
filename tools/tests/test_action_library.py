import collections
import math
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from motion import vmd
from motion.build import write_archive, write_clips, write_legacy_archive
from motion.clips import build_clips, mirror


class ActionLibraryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.clips = build_clips()
        cls.by_name = {clip.name: clip for clip in cls.clips}

    def test_movement_use_and_weapon_coverage(self):
        self.assertEqual(len(self.clips), len(self.by_name))
        for name in ("idle", "walk", "run", "sneak_idle", "sneak_walk", "jump_start", "jump_air",
                     "jump_land", "fall", "swim", "crawl", "glide", "fly", "climb", "climb_down",
                     "climb_idle", "ride", "sleep", "riptide", "boat", "mount", "dismount"):
            self.assertIn("move_" + name, self.by_name)
        for name in ("item", "eat", "drink", "block", "bow", "trident", "crossbow_charge", "crossbow_hold",
                     "spyglass", "toot_horn", "brush", "bundle", "spear"):
            for prefix in ("", "fp_"):
                self.assertIn(prefix + "use_" + name, self.by_name)
                self.assertIn(prefix + "use_" + name + "_left", self.by_name)
        for weapon in ("sword", "axe", "spear", "trident", "mace", "tool", "unarmed"):
            for suffix in ("light_1", "light_2", "light_3", "heavy", "charge", "air_1", "air_2"):
                self.assertIn(f"combat_{weapon}_{suffix}", self.by_name)
                self.assertIn(f"combat_{weapon}_{suffix}_left", self.by_name)
        for direction in ("forward", "back", "left", "right"):
            self.assertIn("combat_dodge_" + direction, self.by_name)

    def test_keyframes_are_finite_unique_and_loop_seams_match(self):
        for clip in self.clips:
            with self.subTest(clip=clip.name):
                self.assertTrue(clip.bone_keys)
                tracks = collections.defaultdict(dict)
                for key in clip.bone_keys:
                    self.assertNotIn(key.frame, tracks[key.bone])
                    self.assertGreaterEqual(key.frame, 0)
                    self.assertLessEqual(key.frame, clip.duration)
                    self.assertTrue(all(math.isfinite(value) for value in (*key.translation, *key.rotation)))
                    self.assertAlmostEqual(sum(value * value for value in key.rotation), 1.0, places=5)
                    tracks[key.bone][key.frame] = key
                for track in tracks.values():
                    self.assertIn(0, track)
                    self.assertIn(clip.duration, track)
                    if clip.loop:
                        self.assertEqual(track[0].translation, track[clip.duration].translation)
                        self.assertAlmostEqual(abs(sum(a * b for a, b in zip(
                            track[0].rotation, track[clip.duration].rotation))), 1.0, places=5)

    def test_codec_preserves_coordinates_and_all_four_curves(self):
        for clip in self.clips:
            payload = vmd.write(clip.bone_keys, clip.morph_keys)
            _, keys, morphs = vmd.read(payload)
            self.assertEqual(len(keys), len(clip.bone_keys))
            self.assertEqual(len(morphs), len(clip.morph_keys))
            expected = sorted(clip.bone_keys, key=lambda key: (key.bone, key.frame))
            for actual, original in zip(keys, expected):
                self.assertEqual((actual.bone, actual.frame, actual.curve), (original.bone, original.frame, original.curve))
                for first, second in zip((*actual.translation, *actual.rotation), (*original.translation, *original.rotation)):
                    self.assertAlmostEqual(first, second, places=5)
                encoded = vmd.interpolation_bytes(original.curve)
                for channel in range(4):
                    self.assertEqual(tuple(encoded[channel * 16 + offset] for offset in (0, 4, 8, 12)), original.curve)

    def test_mirroring_is_involutive_and_first_person_has_no_root_motion(self):
        for clip in self.clips:
            if clip.hand == "right":
                mirrored = mirror(mirror(clip, "left"), "right")
                self.assertEqual(mirrored.bone_keys, clip.bone_keys)
            if clip.view == "first_person":
                self.assertFalse(any(key.bone in ("全ての親", "センター", "頭", "下半身") for key in clip.bone_keys))

    def test_upper_body_archive_never_overrides_root_legs_or_ik(self):
        archive_path = Path(__file__).resolve().parents[2] / "src/main/resources/assets/libmmd/actions.zip"
        forbidden = {"全ての親", "センター", "グルーブ", "腰", "下半身"}
        with zipfile.ZipFile(archive_path) as archive:
            for clip in self.clips:
                eligible = clip.view == "third_person" and clip.category in ("attack", "use", "interact", "combat") and not clip.name.startswith("combat_dodge")
                entry = "upper/" + clip.name + ".vmd"
                self.assertEqual(eligible, entry in archive.namelist(), clip.name)
                if not eligible:
                    continue
                _, keys, morphs = vmd.read(archive.read(entry))
                self.assertTrue(keys, clip.name)
                self.assertFalse(morphs)
                self.assertTrue(all(key.bone not in forbidden and "足" not in key.bone and "ひざ" not in key.bone for key in keys), clip.name)

    def test_archive_is_reproducible_and_index_covers_every_clip(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            written = write_clips(output / "clips", self.clips)
            first, second = output / "first.zip", output / "second.zip"
            write_archive(first, output / "clips", written)
            write_archive(second, output / "clips", written)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                rows = archive.read("index.tsv").decode("utf-8").splitlines()
                self.assertEqual(len(rows), len(self.clips))
                for row in rows:
                    name, looping, duration, category, view = row.split("\t")
                    self.assertIn(name + ".vmd", archive.namelist())
                    self.assertEqual(int(duration), self.by_name[name].duration)
                    self.assertEqual(looping, str(self.by_name[name].loop).lower())
            with self.assertRaises(ValueError):
                write_legacy_archive(first, first, written)
            self.assertTrue(first.is_file())


if __name__ == "__main__":
    unittest.main()

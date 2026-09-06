"""Generate the VMD animation set, its manifest and an optional legacy archive."""
import argparse
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from motion import rig, vmd  # noqa: E402
from motion.clips import FRAME_RATE, build_clips  # noqa: E402

MANIFEST_VERSION = 1


def manifest_entry(clip):
    return {
        "name": clip.name,
        "file": clip.name + ".vmd",
        "category": clip.category,
        "description": clip.description,
        "duration_frames": clip.duration,
        "duration_seconds": round(clip.duration / FRAME_RATE, 4),
        "loop": clip.loop,
        "hand": clip.hand,
        "cycle_distance_units": clip.cycle_distance,
        "grounded": clip.grounded,
        "matrix": list(clip.matrix),
        "legacy_names": list(clip.legacy),
        "mirrored_from": clip.mirrored_from,
        "view": clip.view,
        "visual_validation": "pending",
        "bones": clip.bones(),
        "morphs": sorted({key.morph for key in clip.morph_keys}),
    }


def write_clips(output, clips):
    if len({clip.name for clip in clips}) != len(clips):
        raise ValueError("duplicate clip names")
    output.mkdir(parents=True, exist_ok=True)
    written = []
    for clip in clips:
        data = vmd.write(clip.bone_keys, clip.morph_keys)
        (output / (clip.name + ".vmd")).write_bytes(data)
        written.append((clip, data))
    manifest = {
        "version": MANIFEST_VERSION,
        "frame_rate": FRAME_RATE,
        "units": "PMX 模型单位；运行时默认 1 单位 = 0.08 m，渲染缩放另行处理",
        "authoring_frame": "右手系：+X 为角色左侧，+Y 向上，+Z 为角色正面；文件内已转换为 MMD 左手系",
        "reference_skeleton": {
            "source": "普通版「冰饭式初音未来」PMX 2.0（370 骨骼）绑定位置，仅用于肘轴与腿部可达性",
            "leg_length_units": round(rig.leg_length(), 4),
        },
        "ik": "所有片段保持足 IK 启用；FK 腿部片段同时写入与 FK 一致的 IK 目标",
        "clips": [manifest_entry(clip) for clip in clips],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return written


def write_archive(path, output, written):
    path.parent.mkdir(parents=True, exist_ok=True)
    entries = {clip.name + ".vmd": payload for clip, payload in written}
    entries["manifest.json"] = (output / "manifest.json").read_bytes()
    settings = json.loads(Path(__file__).with_name("playback.json").read_text(encoding="utf-8"))
    entries["settings.properties"] = "".join(f"{key}={value}\n" for key, value in sorted(settings.items())).encode("ascii")
    entries["index.tsv"] = ("\n".join(
        f"{clip.name}\t{str(clip.loop).lower()}\t{clip.duration}\t{clip.category}\t{clip.view}"
        for clip, _ in written) + "\n").encode("utf-8")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, payload in sorted(entries.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, payload)


def write_legacy_archive(path, source, written):
    if path.resolve() == source.resolve():
        raise ValueError("legacy output must not overwrite the original archive")
    replacements = {}
    for clip, data in written:
        for legacy_name in clip.legacy:
            replacements[legacy_name] = data
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for info in original.infolist():
            archive.writestr(info.filename, replacements.get(info.filename, original.read(info)))
    return sorted(replacements)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("animations"))
    parser.add_argument("--archive", type=Path, help="write the complete, reproducible action archive")
    parser.add_argument("--legacy-zip", type=Path,
                        help="write a default-animation.zip whose matching legacy entries are replaced")
    parser.add_argument("--legacy-source", type=Path,
                        default=Path("src/main/resources/assets/libmmd/default-animation.zip"))
    args = parser.parse_args()
    clips = build_clips()
    written = write_clips(args.output, clips)
    print(f"wrote {len(written)} clips to {args.output}")
    if args.archive:
        write_archive(args.archive, args.output, written)
    if args.legacy_zip:
        replaced = write_legacy_archive(args.legacy_zip, args.legacy_source, written)
        print(f"legacy archive {args.legacy_zip} replaced: {', '.join(replaced)}")


if __name__ == "__main__":
    main()

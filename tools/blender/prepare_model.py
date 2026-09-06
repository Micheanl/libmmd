import argparse
import hashlib
import importlib
import json
import logging
import sys
from pathlib import Path

import bpy


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def model_summary(model):
    return {
        "counts": {
            key: len(getattr(model, key))
            for key in ("vertices", "faces", "bones", "materials", "morphs", "rigids", "joints")
        },
        "ik": [
            {
                "controller": bone.name,
                "target": model.bones[bone.target].name,
                "links": [
                    {
                        "bone": model.bones[link.target].name,
                        "lower": [round(value, 5) for value in link.minimumAngle] if link.minimumAngle else None,
                        "upper": [round(value, 5) for value in link.maximumAngle] if link.maximumAngle else None,
                    }
                    for link in bone.ik_links
                ],
                "iterations": bone.loopCount,
                "angle_limit": round(bone.rotationConstraint, 5),
            }
            for bone in model.bones
            if bone.isIK
        ],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--motion", type=Path)
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:])
    source = args.model.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    source_digest = digest(source)
    package = "bl_ext.user_default.mmd_tools"
    if package not in bpy.context.preferences.addons:
        bpy.ops.preferences.addon_enable(module=package)
    pmx = importlib.import_module(package + ".core.pmx")
    logging.getLogger().setLevel(logging.ERROR)
    original = model_summary(pmx.load(str(source)))
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    bpy.ops.mmd_tools.import_model(
        filepath=str(source),
        scale=0.08,
        clean_model=False,
        remove_doubles=False,
        fix_bone_order=False,
        rename_bones=False,
        log_level="ERROR",
        save_log=False,
    )
    roots = [obj for obj in bpy.context.scene.objects if obj.mmd_type == "ROOT"]
    if len(roots) != 1:
        raise RuntimeError("Expected one imported MMD model")
    root = roots[0]
    armature = next(obj for obj in root.children if obj.type == "ARMATURE")
    armature.show_in_front = True
    armature.data.display_type = "STICK"
    bpy.context.view_layer.objects.active = root
    bpy.ops.object.select_all(action="DESELECT")
    root.select_set(True)
    exported_path = output / "roundtrip.pmx"
    bpy.ops.mmd_tools.export_pmx(
        filepath=str(exported_path),
        scale=12.5,
        copy_textures_mode="SKIP_EXISTING",
        log_level="ERROR",
        save_log=False,
    )
    if not exported_path.is_file():
        raise RuntimeError("PMX export did not produce a file")
    exported = model_summary(pmx.load(str(exported_path)))
    if original != exported:
        (output / "roundtrip-difference.json").write_text(
            json.dumps({"source": original, "export": exported}, ensure_ascii=False, indent=2) + "\n"
        )
        raise RuntimeError("PMX roundtrip changed model counts or IK chains")
    bpy.context.scene.render.fps = 30
    bpy.context.scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=str(output / "model.blend"))
    motion_output = None
    if args.motion:
        bpy.context.view_layer.objects.active = root
        bpy.ops.mmd_tools.import_vmd(
            filepath=str(args.motion.resolve(strict=True)),
            scale=0.08,
            bone_mapper="PMX",
            log_level="ERROR",
            save_log=False,
        )
        motion_output = output / "motion-roundtrip.vmd"
        bpy.ops.mmd_tools.export_vmd(
            filepath=str(motion_output),
            scale=12.5,
            log_level="ERROR",
            save_log=False,
        )
        if not motion_output.is_file() or motion_output.stat().st_size <= 54:
            raise RuntimeError("VMD export did not produce animation data")
        bpy.ops.wm.save_as_mainfile(filepath=str(output / "motion-preview.blend"))
    if digest(source) != source_digest:
        raise RuntimeError("Source PMX changed")
    missing_images = sorted({
        image.filepath for image in bpy.data.images
        if image.source == "FILE" and not image.packed_file
        and not Path(bpy.path.abspath(image.filepath)).is_file()
    })
    report = {
        "blender": bpy.app.version_string,
        "source": str(source),
        "source_sha256": source_digest,
        "source_unchanged": True,
        "roundtrip_counts_and_ik_equal": True,
        "model": original,
        "missing_images": missing_images,
        "blender_ik_constraints": [
            {"bone": bone.name, "target": constraint.subtarget, "chain_length": constraint.chain_count}
            for bone in armature.pose.bones for constraint in bone.constraints if constraint.type == "IK"
        ],
        "motion_output": str(motion_output) if motion_output else None,
        "visual_validation": "pending",
    }
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print("LIBMMD_PREPARE_OK " + str(output / "report.json"))


if __name__ == "__main__":
    main()

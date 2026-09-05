import argparse
import hashlib
import importlib
import json
import logging
import struct
import sys
from pathlib import Path


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def normalize_motion(source, destination):
    payload = bytearray(source.read_bytes())
    if len(payload) < 54:
        raise ValueError("VMD file is truncated")
    if not payload.startswith(b"Vocaloid Motion Data 0002"):
        raise ValueError("VMD signature is unsupported")
    frame_count = struct.unpack_from("<I", payload, 50)[0]
    if frame_count > (len(payload) - 54) // 111:
        raise ValueError("VMD bone keyframes are truncated")
    normalized_frames = 0
    for frame_index in range(frame_count):
        interpolation_offset = 54 + frame_index * 111 + 47
        interpolation = payload[interpolation_offset:interpolation_offset + 64]
        if any(interpolation[16:]):
            continue
        normalized = bytearray(64)
        for channel in range(4):
            base = channel * 16
            normalized[base] = interpolation[channel]
            normalized[base + 4] = interpolation[channel + 4]
            normalized[base + 8] = interpolation[channel + 8]
            normalized[base + 12] = interpolation[channel + 12]
        payload[interpolation_offset:interpolation_offset + 64] = normalized
        normalized_frames += 1
    with destination.open("xb") as stream:
        stream.write(payload)
    return normalized_frames


def model_summary(model):
    def name_at(items, index):
        return items[index].name if index is not None and 0 <= index < len(items) else None

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
        "bones": [
            {
                "index": index,
                "name": bone.name,
                "parent": bone.parent,
                "parent_name": name_at(model.bones, bone.parent),
            }
            for index, bone in enumerate(model.bones)
        ],
        "rigid_bodies": [
            {
                "index": index,
                "name": rigid.name,
                "bone": rigid.bone,
                "bone_name": name_at(model.bones, rigid.bone),
                "shape": rigid.type,
                "mode": rigid.mode,
                "collision_group": rigid.collision_group_number,
                "collision_mask": rigid.collision_group_mask,
            }
            for index, rigid in enumerate(model.rigids)
        ],
        "joints": [
            {
                "index": index,
                "name": joint.name,
                "type": joint.mode,
                "first_rigid_body": joint.src_rigid,
                "first_rigid_body_name": name_at(model.rigids, joint.src_rigid),
                "second_rigid_body": joint.dest_rigid,
                "second_rigid_body_name": name_at(model.rigids, joint.dest_rigid),
            }
            for index, joint in enumerate(model.joints)
        ],
    }


def main():
    import bpy

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
    root.mmd_root.show_armature = True
    root.mmd_root.show_rigid_bodies = True
    root.mmd_root.show_joints = True
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
            json.dumps({"source": original, "export": exported}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        raise RuntimeError("PMX roundtrip changed counts, bone hierarchy, physics bindings or IK chains")
    bpy.context.scene.render.fps = 30
    bpy.context.scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=str(output / "model.blend"))
    motion_output = None
    normalized_motion_frames = 0
    if args.motion:
        normalized_motion = output / "motion-normalized.vmd"
        normalized_motion_frames = normalize_motion(args.motion.resolve(strict=True), normalized_motion)
        bpy.context.view_layer.objects.active = root
        bpy.ops.mmd_tools.import_vmd(
            filepath=str(normalized_motion),
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
        "mmd_tools": ".".join(map(str, importlib.import_module(package).bl_info["version"])),
        "source": str(source),
        "source_sha256": source_digest,
        "source_unchanged": True,
        "roundtrip_counts_and_ik_equal": True,
        "roundtrip_bone_and_physics_bindings_equal": True,
        "model": original,
        "missing_images": missing_images,
        "blender_ik_constraints": [
            {"bone": bone.name, "target": constraint.subtarget, "chain_length": constraint.chain_count}
            for bone in armature.pose.bones for constraint in bone.constraints if constraint.type == "IK"
        ],
        "diagnostics_visible": ["armature", "rigid_bodies", "joints"],
        "motion_output": str(motion_output) if motion_output else None,
        "normalized_motion_frames": normalized_motion_frames,
        "visual_validation": "pending",
    }
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("LIBMMD_PREPARE_OK " + str(output / "report.json"))


if __name__ == "__main__":
    main()

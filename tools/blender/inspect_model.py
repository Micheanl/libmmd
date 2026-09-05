import argparse
import json
import sys
from pathlib import Path

import bpy


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=("bone", "rigid_body", "joint"))
    parser.add_argument("index", type=int)
    parser.add_argument("output", type=Path)
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:])
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(output)
    if output.suffix != ".blend":
        raise ValueError("Output must be a .blend file")
    if args.index < 0:
        raise ValueError("Index must be non-negative")
    bpy.ops.preferences.addon_enable(module="bl_ext.user_default.mmd_tools")
    roots = [obj for obj in bpy.context.scene.objects if obj.mmd_type == "ROOT"]
    if len(roots) != 1:
        raise ValueError("Expected one MMD model in the opened project")
    root = roots[0]
    root.mmd_root.show_armature = True
    root.mmd_root.show_rigid_bodies = args.kind != "bone"
    root.mmd_root.show_joints = args.kind == "joint"
    if bpy.context.object and bpy.context.object.mode != "OBJECT":
        bpy.ops.object.mode_set(mode="OBJECT")
    bpy.ops.object.select_all(action="DESELECT")
    if args.kind == "bone":
        armature = next(obj for obj in root.children if obj.type == "ARMATURE")
        matches = [
            bone for bone in armature.pose.bones
            if not bone.is_mmd_shadow_bone and bone.mmd_bone.bone_id == args.index
        ]
        if len(matches) != 1:
            raise ValueError(f"Expected one bone with PMX index {args.index}, found {len(matches)}")
        bone = matches[0]
        armature.select_set(True)
        bpy.context.view_layer.objects.active = armature
        bpy.ops.object.mode_set(mode="POSE")
        bpy.ops.pose.select_all(action="DESELECT")
        bone.bone.hide = False
        bone.select = True
        for collection in bone.bone.collections:
            collection.is_visible = True
        armature.data.bones.active = bone.bone
        selected_name = bone.name
        source_name = bone.mmd_bone.name_j
    else:
        mmd_type = "RIGID_BODY" if args.kind == "rigid_body" else "JOINT"
        prefix = f"{args.index:03d}_"
        matches = [obj for obj in root.children_recursive if obj.mmd_type == mmd_type and obj.name.startswith(prefix)]
        if len(matches) != 1:
            raise ValueError(f"Expected one {args.kind} with PMX index {args.index}, found {len(matches)}")
        selected = matches[0]
        for obj in root.children_recursive:
            if obj.mmd_type in {"RIGID_BODY", "JOINT"}:
                obj.hide_set(obj != selected)
        if args.kind == "joint":
            constraint = selected.rigid_body_constraint
            for body in (constraint.object1, constraint.object2):
                if body:
                    body.hide_set(False)
        selected.show_in_front = True
        selected.show_name = True
        selected.select_set(True)
        bpy.context.view_layer.objects.active = selected
        selected_name = selected.name
        source_name = selected.mmd_rigid.name_j if args.kind == "rigid_body" else selected.mmd_joint.name_j
    for window in bpy.context.window_manager.windows:
        for area in window.screen.areas:
            if area.type == "VIEW_3D":
                region = next(region for region in area.regions if region.type == "WINDOW")
                with bpy.context.temp_override(window=window, area=area, region=region):
                    bpy.ops.view3d.view_selected()
    output.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=str(output))
    print("LIBMMD_INSPECT_OK " + json.dumps({
        "kind": args.kind,
        "index": args.index,
        "blender_name": selected_name,
        "source_name": source_name,
        "output": str(output),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()

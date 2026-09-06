import argparse
import runpy
import sys
from pathlib import Path

import bpy


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("motion", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--frame", type=int, default=1)
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:])
    if args.output.exists():
        raise FileExistsError(args.output)
    bpy.ops.preferences.addon_enable(module="bl_ext.user_default.mmd_tools")
    roots = [obj for obj in bpy.context.scene.objects if obj.mmd_type == "ROOT"]
    if len(roots) != 1:
        raise ValueError("Expected one imported MMD model")
    bpy.ops.object.select_all(action="DESELECT")
    root = roots[0]
    root.select_set(True)
    bpy.context.view_layer.objects.active = root
    bpy.ops.mmd_tools.import_vmd(filepath=str(args.motion.resolve(strict=True)),
        scale=0.08, bone_mapper="PMX", log_level="ERROR", save_log=False)
    bpy.context.scene.render.fps = 30
    sys.argv = [sys.argv[0], "--", str(args.output.resolve()), "--frame", str(args.frame)]
    runpy.run_path(str(Path(__file__).with_name("render_preview.py")), run_name="__main__")


if __name__ == "__main__":
    main()

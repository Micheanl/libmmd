import argparse
import sys
from pathlib import Path

import bpy
from mathutils import Vector


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--frame", type=int, default=1)
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:])
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    scene = bpy.context.scene
    scene.frame_set(args.frame)
    meshes = [obj for obj in scene.objects if obj.type == "MESH" and obj.mmd_type == "NONE" and not obj.hide_render]
    points = [obj.matrix_world @ Vector(corner) for obj in meshes for corner in obj.bound_box]
    minimum = Vector(tuple(min(point[axis] for point in points) for axis in range(3)))
    maximum = Vector(tuple(max(point[axis] for point in points) for axis in range(3)))
    center = (minimum + maximum) * 0.5
    height = maximum.z - minimum.z
    bpy.ops.object.camera_add(location=center + Vector((0, -height * 3, height * 0.08)))
    camera = bpy.context.object
    camera.rotation_euler = (center - camera.location).to_track_quat("-Z", "Y").to_euler()
    camera.data.type = "ORTHO"
    camera.data.ortho_scale = max(height * 1.2, (maximum.x - minimum.x) * 1.6)
    scene.camera = camera
    for name, offset, power, color in (
        ("Key", (-1, -1.5, 1.5), 250, (1.0, 0.91, 0.85)),
        ("Fill", (1, -1, 0.7), 150, (0.82, 0.9, 1.0)),
        ("Rim", (0, 1, 1.2), 300, (1.0, 1.0, 1.0)),
    ):
        bpy.ops.object.light_add(type="AREA", location=center + Vector(offset) * height)
        light = bpy.context.object
        light.name = name
        light.data.energy = power * height * height
        light.data.shape = "DISK"
        light.data.size = height
        light.data.color = color
        light.rotation_euler = (center - light.location).to_track_quat("-Z", "Y").to_euler()
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs[0].default_value = (0.13, 0.16, 0.2, 1.0)
    scene.world.node_tree.nodes["Background"].inputs[1].default_value = 0.5
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = 24
    scene.cycles.use_denoising = True
    scene.render.resolution_x = 768
    scene.render.resolution_y = 960
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.filepath = str(output)
    bpy.ops.render.render(write_still=True)
    print("LIBMMD_PREVIEW_OK " + str(output))


if __name__ == "__main__":
    main()

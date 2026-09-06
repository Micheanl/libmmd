package com.micheanl.libmmd.client.render;

import com.micheanl.libmmd.runtime.NativeRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

final class ArmMesh {
    record Range(boolean left, int texture, int first, int count) {}
    record Selection(ByteBuffer indices, List<Range> ranges, float eyeHeight) {}

    static Selection select(NativeRuntime.Model model) {
        var mesh = model.renderMesh();
        var bones = new int[model.info().boneCount()];
        var eyeHeight = Float.NaN;
        for (int i = 0; i < bones.length; i++) {
            var bone = model.bone(i);
            var name = bone.name();
            if (name.equals("頭")) eyeHeight = bone.position().y();
            if (name.startsWith("左腕") || name.startsWith("左ひじ") || name.startsWith("左手") ||
                name.startsWith("左親指") || name.startsWith("左人指") || name.startsWith("左中指") ||
                name.startsWith("左薬指") || name.startsWith("左小指")) bones[i] = 1;
            if (name.startsWith("右腕") || name.startsWith("右ひじ") || name.startsWith("右手") ||
                name.startsWith("右親指") || name.startsWith("右人指") || name.startsWith("右中指") ||
                name.startsWith("右薬指") || name.startsWith("右小指")) bones[i] = -1;
        }
        var skinning = mesh.skinning().asByteBuffer().order(ByteOrder.nativeOrder());
        var arms = new int[mesh.vertexCount()];
        for (int vertex = 0; vertex < arms.length; vertex++) {
            float left = 0, right = 0;
            for (int influence = 0; influence < 4; influence++) {
                int offset = vertex * mesh.skinningStride();
                int index = skinning.getInt(offset + influence * Integer.BYTES) - 1;
                float weight = skinning.getFloat(offset + 16 + influence * Float.BYTES);
                if (index >= 0 && index < bones.length) {
                    if (bones[index] == 1) left += weight;
                    if (bones[index] == -1) right += weight;
                }
            }
            arms[vertex] = left > 0.5f ? 1 : right > 0.5f ? -1 : 0;
        }
        var original = mesh.indices().asByteBuffer().order(ByteOrder.nativeOrder());
        var selected = ByteBuffer.allocateDirect(mesh.indexCount() * mesh.indexStride()).order(ByteOrder.nativeOrder());
        var ranges = new ArrayList<Range>();
        for (int materialIndex = 0; materialIndex < model.info().materialCount(); materialIndex++) {
            var material = model.material(materialIndex);
            for (int side : new int[] {1, -1}) {
                int first = selected.position() / mesh.indexStride();
                for (int index = material.firstIndex(); index < material.firstIndex() + material.indexCount(); index += 3) {
                    int a = index(original, index, mesh.indexStride());
                    int b = index(original, index + 1, mesh.indexStride());
                    int c = index(original, index + 2, mesh.indexStride());
                    if (arms[a] != side || arms[b] != side || arms[c] != side) continue;
                    for (int vertex : new int[] {a, b, c}) {
                        if (mesh.indexStride() == 2) selected.putShort((short) vertex);
                        else selected.putInt(vertex);
                    }
                }
                int count = selected.position() / mesh.indexStride() - first;
                if (count > 0) ranges.add(new Range(side == 1, material.textureIndex(), first, count));
            }
        }
        return new Selection(selected.flip(), List.copyOf(ranges), eyeHeight);
    }

    private static int index(ByteBuffer indices, int index, int stride) {
        return stride == 2 ? Short.toUnsignedInt(indices.getShort(index * stride)) : indices.getInt(index * stride);
    }
}

package com.micheanl.libmmd.client.render;

import com.micheanl.libmmd.runtime.NativeRuntime;
import com.micheanl.libmmd.runtime.SceneRuntime;

import java.util.ArrayList;
import java.util.List;

final class RenderNodeFactory {
    private RenderNodeFactory() {}

    static List<RenderNode> create(
        SceneRuntime.RenderPacket packet,
        NativeRuntime.Model model,
        GpuBuffers buffers,
        GpuTextures textures
    ) {
        var materials = model.info().materialCount();
        var nodes = new ArrayList<RenderNode>(Math.max(1, materials));
        for (var materialIndex = 0; materialIndex < materials; materialIndex++) {
            var material = model.material(materialIndex);
            if (material.indexCount() > 0) {
                nodes.add(new RenderNode(
                    buffers,
                    textures.get(material.textureIndex()),
                    material.indexCount(),
                    packet.indexStride(),
                    material.firstIndex(),
                    1
                ));
            }
        }
        if (nodes.isEmpty()) nodes.add(new RenderNode(buffers, textures.get(-1), packet.indexCount(), packet.indexStride()));
        return List.copyOf(nodes);
    }
}

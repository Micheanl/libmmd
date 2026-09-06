package com.micheanl.libmmd.client.model;

import com.micheanl.libmmd.runtime.NativeRuntime;

import java.nio.ByteOrder;

final class ModelSizer {
    private static final float PLAYER_HEIGHT = 1.8f;

    private ModelSizer() {}

    static Result fit(NativeRuntime.Model model) {
        var mesh = model.renderMesh();
        var vertices = mesh.vertices().asByteBuffer().order(ByteOrder.nativeOrder());
        var minimumY = Float.POSITIVE_INFINITY;
        var maximumY = Float.NEGATIVE_INFINITY;
        for (var index = 0; index < mesh.vertexCount(); index++) {
            var y = vertices.getFloat(index * mesh.vertexStride() + Float.BYTES);
            minimumY = Math.min(minimumY, y);
            maximumY = Math.max(maximumY, y);
        }
        var height = maximumY - minimumY;
        if (!Float.isFinite(height) || height <= 0.0f) {
            throw new IllegalStateException("Model vertex height is invalid");
        }
        var scale = PLAYER_HEIGHT / height;
        return new Result(scale, -minimumY * scale);
    }

    record Result(float scale, float verticalOffset) {}
}

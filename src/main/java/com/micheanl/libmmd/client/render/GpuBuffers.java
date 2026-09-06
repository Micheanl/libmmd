package com.micheanl.libmmd.client.render;

import com.micheanl.libmmd.runtime.SceneRuntime;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Environment(EnvType.CLIENT)
public final class GpuBuffers implements AutoCloseable {
    static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
        .build();
    static final VertexFormat SKINNING_FORMAT = VertexFormat.builder(0)
        .addAttribute("BoneIndices", GpuFormat.RGBA32_SINT)
        .addAttribute("BoneWeights", GpuFormat.RGBA32_FLOAT)
        .build();
    static final VertexFormat INSTANCE_FORMAT = VertexFormat.builder(1)
        .addAttribute("InstanceModel0", GpuFormat.RGBA32_FLOAT)
        .addAttribute("InstanceModel1", GpuFormat.RGBA32_FLOAT)
        .addAttribute("InstanceModel2", GpuFormat.RGBA32_FLOAT)
        .addAttribute("InstanceModel3", GpuFormat.RGBA32_FLOAT)
        .build();
    private final GpuBuffer vertices;
    private final GpuBuffer skinning;
    private final GpuBuffer indices;
    private final GpuBuffer instances;
    private final GpuBuffer matrices;
    private final GpuBuffer morphOffsets;
    private final GpuBuffer softBodyOffsets;

    private GpuBuffers(GpuBuffer vertices, GpuBuffer skinning, GpuBuffer indices, GpuBuffer instances, GpuBuffer matrices, GpuBuffer morphOffsets, GpuBuffer softBodyOffsets) {
        this.vertices = vertices;
        this.skinning = skinning;
        this.indices = indices;
        this.instances = instances;
        this.matrices = matrices;
        this.morphOffsets = morphOffsets;
        this.softBodyOffsets = softBodyOffsets;
    }

    public static GpuBuffers upload(SceneRuntime.RenderPacket packet) {
        return upload(packet, packet.indices().asByteBuffer());
    }

    static GpuBuffers upload(SceneRuntime.RenderPacket packet, ByteBuffer selectedIndices) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice();
        var vertices = device.createBuffer(() -> "libmmd vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, packet.vertices().asByteBuffer());
        var skinning = device.createBuffer(() -> "libmmd skinning", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, packet.skinning().asByteBuffer());
        var indices = device.createBuffer(() -> "libmmd indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, selectedIndices);
        var instances = device.createBuffer(() -> "libmmd instances", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, instanceBytes(packet));
        var matrices = device.createBuffer(() -> "libmmd matrices", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, matrixBytes(packet));
        var morphOffsets = device.createBuffer(() -> "libmmd morph offsets", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, zeroBytes(Math.multiplyExact((long) packet.vertexCount(), 32L)));
        var softBodyOffsets = device.createBuffer(() -> "libmmd soft body offsets", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, zeroBytes(Math.multiplyExact((long) packet.vertexCount(), 16L)));
        return new GpuBuffers(vertices, skinning, indices, instances, matrices, morphOffsets, softBodyOffsets);
    }

    public void updateMatrices(SceneRuntime.RenderPacket packet) {
        updateMatrices(packet, instanceBytes(packet));
    }

    void updateMatrices(SceneRuntime.RenderPacket packet, ByteBuffer transform) {
        RenderSystem.assertOnRenderThread();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(matrices.slice(), matrixBytes(packet));
        encoder.writeToBuffer(instances.slice(), transform);
        encoder.submit();
    }

    GpuBuffer vertices() {
        return vertices;
    }

    GpuBuffer skinning() {
        return skinning;
    }

    GpuBuffer indices() {
        return indices;
    }

    GpuBuffer instances() {
        return instances;
    }

    GpuBuffer matrices() {
        return matrices;
    }

    GpuBuffer morphOffsets() {
        return morphOffsets;
    }

    GpuBuffer softBodyOffsets() {
        return softBodyOffsets;
    }

    private static ByteBuffer matrixBytes(SceneRuntime.RenderPacket packet) {
        var source = packet.matrices().asByteBuffer();
        var output = ByteBuffer.allocateDirect(source.remaining() + 16 * Float.BYTES).order(source.order());
        output.putFloat(1.0f).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
        output.putFloat(0.0f).putFloat(1.0f).putFloat(0.0f).putFloat(0.0f);
        output.putFloat(0.0f).putFloat(0.0f).putFloat(1.0f).putFloat(0.0f);
        output.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).putFloat(1.0f);
        output.put(source).flip();
        return output;
    }

    private static ByteBuffer instanceBytes(SceneRuntime.RenderPacket packet) {
        var transform = packet.transform();
        var position = transform.position();
        var rotation = transform.rotation();
        var scale = transform.scale();
        var x = rotation.x();
        var y = rotation.y();
        var z = rotation.z();
        var w = rotation.w();
        var xx = x * x;
        var yy = y * y;
        var zz = z * z;
        var xy = x * y;
        var xz = x * z;
        var yz = y * z;
        var wx = w * x;
        var wy = w * y;
        var wz = w * z;
        return ByteBuffer.allocateDirect(16 * Float.BYTES)
            .order(ByteOrder.nativeOrder())
            .putFloat((1.0f - 2.0f * (yy + zz)) * scale.x()).putFloat((2.0f * (xy + wz)) * scale.x()).putFloat((2.0f * (xz - wy)) * scale.x()).putFloat(0.0f)
            .putFloat((2.0f * (xy - wz)) * scale.y()).putFloat((1.0f - 2.0f * (xx + zz)) * scale.y()).putFloat((2.0f * (yz + wx)) * scale.y()).putFloat(0.0f)
            .putFloat((2.0f * (xz + wy)) * scale.z()).putFloat((2.0f * (yz - wx)) * scale.z()).putFloat((1.0f - 2.0f * (xx + yy)) * scale.z()).putFloat(0.0f)
            .putFloat(position.x()).putFloat(position.y()).putFloat(position.z()).putFloat(1.0f)
            .flip();
    }

    private static ByteBuffer zeroBytes(long size) {
        return ByteBuffer.allocateDirect(Math.toIntExact(size));
    }

    @Override
    public void close() {
        vertices.close();
        skinning.close();
        indices.close();
        instances.close();
        matrices.close();
        morphOffsets.close();
        softBodyOffsets.close();
    }
}

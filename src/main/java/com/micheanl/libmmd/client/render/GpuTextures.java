package com.micheanl.libmmd.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.micheanl.libmmd.runtime.NativeRuntime;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class GpuTextures implements AutoCloseable {
    private final Map<Integer, Binding> bindings;
    private final GpuSampler sampler;

    private GpuTextures(Map<Integer, Binding> bindings, GpuSampler sampler) {
        this.bindings = bindings;
        this.sampler = sampler;
    }

    public static GpuTextures load(NativeRuntime.Model model, Path packPath) {
        RenderSystem.assertOnRenderThread();
        var sampler = RenderSystem.getDevice().createSampler(
            AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, 1, java.util.OptionalDouble.empty()
        );
        var bindings = new HashMap<Integer, Binding>();
        try {
            bindings.put(-1, new Binding(uploadWhite(packPath), null, sampler));
            for (var index = 0; index < model.info().textureCount(); index++) {
                var texturePath = resolveTexturePath(packPath, model.texture(index));
                bindings.put(index, new Binding(upload(texturePath), null, sampler));
            }
            for (var entry : bindings.entrySet()) {
                entry.setValue(new Binding(
                    entry.getValue().texture(),
                    RenderSystem.getDevice().createTextureView(entry.getValue().texture()),
                    sampler
                ));
            }
            return new GpuTextures(bindings, sampler);
        } catch (RuntimeException failure) {
            for (var binding : bindings.values()) {
                if (binding.view() != null) binding.view().close();
                binding.texture().close();
            }
            sampler.close();
            throw failure;
        }
    }

    private static GpuTexture upload(Path path) {
        if (!Files.isRegularFile(path)) return uploadWhite(path);
        try (var input = Files.newInputStream(path); var image = NativeImage.read(input)) {
            var texture = RenderSystem.getDevice().createTexture(
                () -> "libmmd texture " + path.getFileName(),
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1
            );
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToTexture(texture, image);
            encoder.submit();
            return texture;
        } catch (IOException failure) {
            return uploadWhite(path);
        }
    }

    private static Path resolveTexturePath(Path packPath, String textureName) {
        var root = packPath.toAbsolutePath().normalize().getParent();
        var relative = Path.of(textureName.replace('\\', '/'));
        var resolved = root.resolve(relative).normalize();
        return resolved.startsWith(root) ? resolved : root.resolve("missing-texture");
    }

    private static GpuTexture uploadWhite(Path path) {
        try (var image = whitePixel()) {
            var texture = RenderSystem.getDevice().createTexture(
                () -> "libmmd missing texture " + path.getFileName(),
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, 1, 1, 1, 1
            );
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToTexture(texture, image);
            encoder.submit();
            return texture;
        }
    }

    private static NativeImage whitePixel() {
        var image = new NativeImage(1, 1, false);
        image.setPixel(0, 0, 0xFFFFFFFF);
        return image;
    }

    public Binding get(int textureIndex) {
        return bindings.getOrDefault(textureIndex, bindings.get(-1));
    }

    public GpuSampler sampler() {
        return sampler;
    }

    @Override
    public void close() {
        for (var binding : bindings.values()) {
            binding.view().close();
            binding.texture().close();
        }
        sampler.close();
    }

    public record Binding(GpuTexture texture, GpuTextureView view, GpuSampler sampler) {}
}

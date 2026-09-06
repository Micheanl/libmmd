package com.micheanl.libmmd.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SceneRuntimeCompatibilityTest {
    @Test
    void supportsLegacyScenesWithoutModelPhysicsSymbols(@TempDir Path directory) throws IOException {
        var pmx = ByteBuffer.allocate(69).order(ByteOrder.LITTLE_ENDIAN);
        pmx.put(new byte[] {'P', 'M', 'X', ' '}).putFloat(2.0f);
        pmx.put(new byte[] {8, 1, 0, 1, 1, 1, 1, 1, 1});
        for (var textField = 0; textField < 4; textField++) pmx.putInt(0);
        for (var section = 0; section < 9; section++) pmx.putInt(0);
        var modelPath = directory.resolve("empty.pmx");
        Files.write(modelPath, pmx.array());
        var library = NativeLibrary.resolve();
        try (var arena = Arena.ofConfined();
             var runtime = NativeRuntime.open(library, 1);
             var model = runtime.loadPmx(modelPath)) {
            var symbols = SymbolLookup.libraryLookup(library, arena);
            var physicsSymbols = Set.of("libmmd_scene_create_with_physics", "libmmd_model_instance_reset_physics");
            for (var name : physicsSymbols) assertTrue(symbols.find(name).isPresent());
            SymbolLookup legacySymbols = name -> physicsSymbols.contains(name) ? Optional.empty() : symbols.find(name);
            var scenes = new SceneRuntime(runtime, Linker.nativeLinker(), legacySymbols);
            var creationFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> scenes.create(0.25f, SceneRuntime.PhysicsConfig.defaults())
            );
            assertTrue(creationFailure.getMessage().contains("libmmd_scene_create_with_physics"));
            assertFalse(scenes.hasOpenScenes());
            try (var scene = scenes.create();
                 var instance = scene.createInstance(model)) {
                assertEquals(1, scene.update(1.0f / 60.0f).instanceCount());
                assertTrue(instance.state().visible());
                var resetFailure = assertThrows(UnsupportedOperationException.class, instance::resetPhysics);
                assertTrue(resetFailure.getMessage().contains("libmmd_model_instance_reset_physics"));
                assertEquals(2, scene.update(1.0f / 60.0f).frameIndex());
            }
            try (var scene = scenes.create(0.1f)) {
                assertEquals(0.1f, scene.update(0.2f).deltaSeconds());
            }
            assertFalse(scenes.hasOpenScenes());
        }
    }
}

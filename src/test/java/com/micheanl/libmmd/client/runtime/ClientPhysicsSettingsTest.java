package com.micheanl.libmmd.client.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class ClientPhysicsSettingsTest {
    @Test
    void defaultsEnablePhysicsAndAllowPartialOverrides(@TempDir Path directory) throws Exception {
        var defaults = ClientPhysicsSettings.load(directory);
        assertTrue(defaults.enabled());
        assertTrue(defaults.physics().fixedStepSeconds() * defaults.physics().maximumSubsteps() >= 1.0f / 20.0f);
        Files.writeString(directory.resolve("libmmd-physics.properties"), "enabled=false\nsolver_iterations=16\n");
        var overrides = ClientPhysicsSettings.load(directory);
        assertFalse(overrides.enabled());
        assertEquals(16, overrides.physics().solverIterations());
        assertEquals(defaults.physics().gravity(), overrides.physics().gravity());
    }

    @Test
    void rejectsInvalidSettingsInsteadOfSilentlyDisablingPhysics(@TempDir Path directory) throws Exception {
        for (String invalid : new String[] {"enabled=yes", "gravity_y=NaN", "fixed_step_seconds=0",
            "maximum_substeps=0", "solver_iterations=-1", "meters_per_unit=-0.1", "reset_distance=Infinity"}) {
            Files.writeString(directory.resolve("libmmd-physics.properties"), invalid);
            assertThrows(IllegalStateException.class, () -> ClientPhysicsSettings.load(directory), invalid);
        }
    }
}

package com.micheanl.libmmd.client.runtime;

import com.micheanl.libmmd.runtime.NativeRuntime;
import com.micheanl.libmmd.runtime.SceneRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ClientPhysicsSettings(boolean enabled, float maximumDeltaSeconds,
                                    float resetDistance, SceneRuntime.PhysicsConfig physics) {
    public static ClientPhysicsSettings load(Path configDirectory) {
        var properties = new Properties();
        try (var defaults = ClientPhysicsSettings.class.getResourceAsStream("/assets/libmmd/physics.properties")) {
            if (defaults == null) throw new IOException("Missing physics defaults");
            properties.load(defaults);
            var path = configDirectory.resolve("libmmd-physics.properties");
            if (Files.exists(path)) {
                try (var input = Files.newInputStream(path)) { properties.load(input); }
            }
            var enabled = properties.getProperty("enabled");
            if (!enabled.equals("true") && !enabled.equals("false")) throw new IllegalArgumentException("enabled must be true or false");
            var maximumDelta = positive(properties, "maximum_delta_seconds");
            var resetDistance = positive(properties, "reset_distance");
            return new ClientPhysicsSettings(Boolean.parseBoolean(enabled), maximumDelta, resetDistance,
                new SceneRuntime.PhysicsConfig(new NativeRuntime.Vector3(number(properties, "gravity_x"),
                    number(properties, "gravity_y"), number(properties, "gravity_z")),
                    positive(properties, "meters_per_unit"), positive(properties, "fixed_step_seconds"),
                    Integer.parseInt(properties.getProperty("maximum_substeps")),
                    Integer.parseInt(properties.getProperty("solver_iterations"))));
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid libmmd physics configuration", failure);
        }
    }

    private static float number(Properties properties, String name) {
        float value = Float.parseFloat(properties.getProperty(name));
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        return value;
    }

    private static float positive(Properties properties, String name) {
        float value = number(properties, name);
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}

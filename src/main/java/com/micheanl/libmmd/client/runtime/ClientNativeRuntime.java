package com.micheanl.libmmd.client.runtime;

import com.micheanl.libmmd.runtime.NativeRuntime;
import com.micheanl.libmmd.runtime.SceneRuntime;

public final class ClientNativeRuntime implements AutoCloseable {
    private final NativeRuntime runtime;
    private final SceneRuntime.Scene scene;
    private final SceneRuntime.Scene firstPersonScene;
    private final ClientPhysicsSettings settings;

    private ClientNativeRuntime(NativeRuntime runtime, SceneRuntime.Scene scene,
                                SceneRuntime.Scene firstPersonScene, ClientPhysicsSettings settings) {
        this.runtime = runtime;
        this.scene = scene;
        this.firstPersonScene = firstPersonScene;
        this.settings = settings;
    }

    public static ClientNativeRuntime open(ClientPhysicsSettings settings) {
        var runtime = NativeRuntime.open();
        try {
            var bodyScene = settings.enabled()
                ? runtime.scenes().create(settings.maximumDeltaSeconds(), settings.physics())
                : runtime.scenes().create(settings.maximumDeltaSeconds());
            return new ClientNativeRuntime(runtime, bodyScene, runtime.scenes().create(settings.maximumDeltaSeconds()), settings);
        } catch (RuntimeException failure) {
            runtime.close();
            throw failure;
        }
    }

    public NativeRuntime runtime() {
        return runtime;
    }

    public SceneRuntime.Scene scene() {
        return scene;
    }

    public SceneRuntime.Scene firstPersonScene() { return firstPersonScene; }

    public ClientPhysicsSettings settings() { return settings; }

    public void update(float deltaSeconds) {
        scene.update(deltaSeconds);
        firstPersonScene.update(deltaSeconds);
    }

    @Override
    public void close() {
        firstPersonScene.close();
        scene.close();
        runtime.close();
    }
}

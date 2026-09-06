package com.micheanl.libmmd.client.runtime;

import com.micheanl.libmmd.runtime.NativeRuntime;
import com.micheanl.libmmd.runtime.SceneRuntime;

public final class ClientNativeRuntime implements AutoCloseable {
    private final NativeRuntime runtime;
    private final SceneRuntime.Scene scene;

    private ClientNativeRuntime(NativeRuntime runtime, SceneRuntime.Scene scene) {
        this.runtime = runtime;
        this.scene = scene;
    }

    public static ClientNativeRuntime open() {
        var runtime = NativeRuntime.open();
        try {
            return new ClientNativeRuntime(runtime, runtime.scenes().create());
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

    public void update(float deltaSeconds) {
        scene.update(deltaSeconds);
    }

    @Override
    public void close() {
        scene.close();
        runtime.close();
    }
}

package com.micheanl.libmmd.client.model;

import com.micheanl.libmmd.runtime.NativeRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ModelController implements AutoCloseable {
    private final NativeRuntime runtime;
    private NativeRuntime.Model model;
    private NativeRuntime.Motion motion;
    private Path packPath;
    private ModelSizer.Result size = new ModelSizer.Result(1.0f, 0.0f);

    public ModelController(NativeRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public synchronized void load(Path path) {
        Objects.requireNonNull(path, "path");
        var normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Model file does not exist: " + normalized);
        }
        var nextModel = runtime.loadPack(normalized);
        NativeRuntime.Motion nextMotion = null;
        ModelSizer.Result nextSize;
        try {
            nextMotion = nextModel.loadMotion(DefaultAnimationReader.read("idle.vmd"));
            nextSize = ModelSizer.fit(nextModel);
        } catch (RuntimeException failure) {
            if (nextMotion != null) nextMotion.close();
            nextModel.close();
            throw failure;
        }
        var previousModel = model;
        var previousMotion = motion;
        try {
            if (previousMotion != null) previousMotion.close();
            if (previousModel != null) previousModel.close();
        } catch (RuntimeException failure) {
            nextMotion.close();
            nextModel.close();
            throw failure;
        }
        model = nextModel;
        motion = nextMotion;
        packPath = normalized;
        size = nextSize;
    }

    public synchronized void unload() {
        if (motion != null) {
            motion.close();
            motion = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
        packPath = null;
        size = new ModelSizer.Result(1.0f, 0.0f);
    }

    public synchronized boolean hasModel() {
        return model != null;
    }

    public synchronized NativeRuntime.Model model() {
        if (model == null) throw new IllegalStateException("No libmmd model is loaded");
        return model;
    }

    public synchronized NativeRuntime.Motion motion() {
        if (motion == null) throw new IllegalStateException("No libmmd animation is loaded");
        return motion;
    }

    public synchronized Path packPath() {
        if (packPath == null) throw new IllegalStateException("No libmmd model is loaded");
        return packPath;
    }

    public synchronized float scale() {
        if (model == null) throw new IllegalStateException("No libmmd model is loaded");
        return size.scale();
    }

    public synchronized float verticalOffset() {
        if (model == null) throw new IllegalStateException("No libmmd model is loaded");
        return size.verticalOffset();
    }

    @Override
    public synchronized void close() {
        unload();
    }
}

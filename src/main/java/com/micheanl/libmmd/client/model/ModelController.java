package com.micheanl.libmmd.client.model;

import com.micheanl.libmmd.runtime.NativeRuntime;
import com.micheanl.libmmd.client.animation.ActionCatalog;
import com.micheanl.libmmd.client.animation.ActionLibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ModelController implements AutoCloseable {
    private final NativeRuntime runtime;
    private NativeRuntime.Model model;
    private NativeRuntime.Motion motion;
    private ActionLibrary actions;
    private String previewAction;
    private long previewRevision;
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
        var catalog = ActionCatalog.bundled();
        var nextModel = runtime.loadPack(normalized);
        NativeRuntime.Motion nextMotion = null;
        ActionLibrary nextActions = null;
        ModelSizer.Result nextSize;
        try {
            nextMotion = nextModel.loadMotion(DefaultAnimationReader.read("idle.vmd"));
            nextActions = new ActionLibrary(nextModel, catalog);
            nextSize = ModelSizer.fit(nextModel);
        } catch (RuntimeException failure) {
            if (nextActions != null) nextActions.close();
            if (nextMotion != null) nextMotion.close();
            nextModel.close();
            throw failure;
        }
        var previousModel = model;
        var previousMotion = motion;
        try {
            if (actions != null) actions.close();
            if (previousMotion != null) previousMotion.close();
            if (previousModel != null) previousModel.close();
        } catch (RuntimeException failure) {
            nextActions.close();
            nextMotion.close();
            nextModel.close();
            throw failure;
        }
        model = nextModel;
        motion = nextMotion;
        actions = nextActions;
        previewAction = null;
        previewRevision++;
        packPath = normalized;
        size = nextSize;
    }

    public synchronized void unload() {
        if (actions != null) {
            actions.close();
            actions = null;
        }
        if (motion != null) {
            motion.close();
            motion = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
        packPath = null;
        previewAction = null;
        previewRevision++;
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

    public synchronized ActionLibrary actions() {
        if (actions == null) throw new IllegalStateException("No libmmd model is loaded");
        return actions;
    }

    public synchronized void previewAction(String name) {
        actions().motion(name);
        previewAction = name;
        previewRevision++;
    }

    public synchronized void stopPreview() {
        previewAction = null;
        previewRevision++;
    }

    public synchronized String previewAction() { return previewAction; }
    public synchronized long previewRevision() { return previewRevision; }

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

package com.micheanl.libmmd.client.animation;

import com.micheanl.libmmd.runtime.NativeRuntime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ActionLibrary implements AutoCloseable {
    private final NativeRuntime.Model model;
    private final ActionCatalog catalog;
    private final Map<String, NativeRuntime.Motion> motions = new LinkedHashMap<>();
    private boolean isClosed;

    public ActionLibrary(NativeRuntime.Model model, ActionCatalog catalog) {
        this.model = Objects.requireNonNull(model, "model");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public ActionCatalog catalog() {
        return catalog;
    }

    public synchronized NativeRuntime.Motion motion(String name) {
        if (isClosed) throw new IllegalStateException("Action library is closed");
        catalog.definition(name);
        return motions.computeIfAbsent(name, key -> {
            var motion = model.loadMotion(catalog.bytes(key));
            if (motion.info().boundBoneCount() == 0) {
                motion.close();
                throw new IllegalArgumentException("Action has no matching model bones: " + key);
            }
            return motion;
        });
    }

    @Override
    public synchronized void close() {
        if (isClosed) return;
        for (var motion : motions.values()) motion.close();
        motions.clear();
        isClosed = true;
    }
}

package com.micheanl.libmmd.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class SceneRuntime {
    public static final int OPENGL = 0x00000001;
    public static final int VULKAN = 0x00000002;
    private static final MemoryLayout CONFIG_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_INT
    );
    private static final MemoryLayout PHYSICS_CONFIG_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT, JAVA_INT, MemoryLayout.sequenceLayout(3, JAVA_FLOAT), JAVA_FLOAT, JAVA_FLOAT, JAVA_INT, JAVA_INT
    );
    private static final MemoryLayout UPDATE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT
    );
    private static final MemoryLayout TRANSFORM_LAYOUT = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        MemoryLayout.sequenceLayout(4, JAVA_FLOAT),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT)
    );
    private static final MemoryLayout STATE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT,
        JAVA_INT,
        TRANSFORM_LAYOUT,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT,
        JAVA_FLOAT,
        JAVA_FLOAT
    );
    private static final MemoryLayout MATRIX_VIEW_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT
    );
    private static final MemoryLayout RENDER_PACKET_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT,
        TRANSFORM_LAYOUT,
        ADDRESS,
        JAVA_LONG,
        JAVA_INT,
        JAVA_INT,
        ADDRESS,
        JAVA_LONG,
        JAVA_INT,
        JAVA_INT,
        ADDRESS,
        JAVA_LONG,
        JAVA_INT,
        JAVA_INT,
        ADDRESS,
        JAVA_LONG,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT
    );

    private final NativeRuntime owner;
    private final MethodHandle createScene;
    private final MethodHandle createSceneWithPhysics;
    private final MethodHandle destroyScene;
    private final MethodHandle updateScene;
    private final MethodHandle createInstance;
    private final MethodHandle destroyInstance;
    private final MethodHandle setTransform;
    private final MethodHandle setVisible;
    private final MethodHandle play;
    private final MethodHandle stop;
    private final MethodHandle resetPhysics;
    private final MethodHandle getState;
    private final MethodHandle getMatrices;
    private final MethodHandle getRenderPacket;
    private final Set<Scene> scenes = new LinkedHashSet<>();

    SceneRuntime(NativeRuntime owner, Linker linker, SymbolLookup symbols) {
        this.owner = owner;
        createScene = downcall(linker, symbols, "libmmd_scene_create", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
        createSceneWithPhysics = optionalDowncall(
            linker,
            symbols,
            "libmmd_scene_create_with_physics",
            JAVA_INT,
            ADDRESS,
            ADDRESS,
            ADDRESS,
            ADDRESS
        );
        destroyScene = downcallVoid(linker, symbols, "libmmd_scene_destroy", ADDRESS);
        updateScene = downcall(linker, symbols, "libmmd_scene_update", JAVA_INT, ADDRESS, JAVA_FLOAT, ADDRESS);
        createInstance = downcall(
            linker,
            symbols,
            "libmmd_model_instance_create",
            JAVA_INT,
            ADDRESS,
            ADDRESS,
            ADDRESS
        );
        destroyInstance = downcallVoid(linker, symbols, "libmmd_model_instance_destroy", ADDRESS);
        setTransform = downcall(
            linker,
            symbols,
            "libmmd_model_instance_set_transform",
            JAVA_INT,
            ADDRESS,
            ADDRESS
        );
        setVisible = downcall(
            linker,
            symbols,
            "libmmd_model_instance_set_visible",
            JAVA_INT,
            ADDRESS,
            JAVA_INT
        );
        play = downcall(
            linker,
            symbols,
            "libmmd_model_instance_play",
            JAVA_INT,
            ADDRESS,
            ADDRESS,
            JAVA_INT,
            JAVA_FLOAT
        );
        stop = downcall(
            linker,
            symbols,
            "libmmd_model_instance_stop",
            JAVA_INT,
            ADDRESS,
            JAVA_FLOAT
        );
        resetPhysics = optionalDowncall(linker, symbols, "libmmd_model_instance_reset_physics", JAVA_INT, ADDRESS);
        getState = downcall(
            linker,
            symbols,
            "libmmd_model_instance_get_state",
            JAVA_INT,
            ADDRESS,
            ADDRESS
        );
        getMatrices = downcall(
            linker,
            symbols,
            "libmmd_model_instance_get_matrices",
            JAVA_INT,
            ADDRESS,
            ADDRESS
        );
        getRenderPacket = downcall(
            linker,
            symbols,
            "libmmd_model_instance_get_render_packet",
            JAVA_INT,
            ADDRESS,
            ADDRESS
        );
    }

    public Scene create() {
        return create(0.25f);
    }

    public synchronized Scene create(float maximumDeltaSeconds) {
        return createScene(maximumDeltaSeconds, null);
    }

    public synchronized Scene create(float maximumDeltaSeconds, PhysicsConfig physicsConfig) {
        return createScene(maximumDeltaSeconds, Objects.requireNonNull(physicsConfig, "physicsConfig"));
    }

    private Scene createScene(float maximumDeltaSeconds, PhysicsConfig physicsConfig) {
        owner.ensureOpen();
        requireFinitePositive(maximumDeltaSeconds, "maximumDeltaSeconds");
        if (physicsConfig != null && createSceneWithPhysics == null) {
            throw new UnsupportedOperationException("Native library does not provide libmmd_scene_create_with_physics");
        }
        try (var arena = Arena.ofConfined()) {
            var config = arena.allocate(CONFIG_LAYOUT);
            header(config, CONFIG_LAYOUT);
            config.set(JAVA_FLOAT, 8, maximumDeltaSeconds);
            var output = arena.allocate(ADDRESS);
            final int status;
            if (physicsConfig == null) {
                status = (int) createScene.invokeExact(owner.handle(), config, output);
            } else {
                var physics = arena.allocate(PHYSICS_CONFIG_LAYOUT);
                header(physics, PHYSICS_CONFIG_LAYOUT);
                writeVector(physics, 8, physicsConfig.gravity());
                physics.set(JAVA_FLOAT, 20, physicsConfig.metersPerUnit());
                physics.set(JAVA_FLOAT, 24, physicsConfig.fixedStepSeconds());
                physics.set(JAVA_INT, 28, physicsConfig.maximumSubsteps());
                physics.set(JAVA_INT, 32, physicsConfig.solverIterations());
                status = (int) createSceneWithPhysics.invokeExact(owner.handle(), config, physics, output);
            }
            check(status, "Unable to create native scene");
            var handle = output.get(ADDRESS, 0);
            if (handle.equals(MemorySegment.NULL)) throw new IllegalStateException("libmmd returned a null scene");
            var scene = new Scene(this, handle);
            scenes.add(scene);
            return scene;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native scene creation", failure);
        }
    }

    synchronized boolean hasOpenScenes() {
        return !scenes.isEmpty();
    }

    private synchronized void closeScene(Scene scene, MemorySegment handle) {
        try {
            destroyScene.invokeExact(handle);
            scenes.remove(scene);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to destroy native scene", failure);
        }
    }

    private UpdateInfo update(MemorySegment scene, float deltaSeconds) {
        requireFiniteNonNegative(deltaSeconds, "deltaSeconds");
        try (var arena = Arena.ofConfined()) {
            var output = arena.allocate(UPDATE_LAYOUT);
            header(output, UPDATE_LAYOUT);
            var status = (int) updateScene.invokeExact(scene, deltaSeconds, output);
            check(status, "Unable to update native scene");
            return new UpdateInfo(
                output.get(JAVA_LONG, 8),
                output.get(JAVA_INT, 16),
                output.get(JAVA_INT, 20),
                output.get(JAVA_INT, 24) != 0,
                output.get(JAVA_FLOAT, 32),
                output.get(JAVA_FLOAT, 36)
            );
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native scene update", failure);
        }
    }

    private MemorySegment createInstance(MemorySegment scene, NativeRuntime.Model model) {
        try (var arena = Arena.ofConfined()) {
            var output = arena.allocate(ADDRESS);
            var status = (int) createInstance.invokeExact(scene, model.nativeHandle(), output);
            check(status, "Unable to create native model instance");
            var handle = output.get(ADDRESS, 0);
            if (handle.equals(MemorySegment.NULL)) throw new IllegalStateException("libmmd returned a null model instance");
            return handle;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native model instance creation", failure);
        }
    }

    private void destroyInstance(MemorySegment instance) {
        try {
            destroyInstance.invokeExact(instance);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to destroy native model instance", failure);
        }
    }

    private void setTransform(MemorySegment instance, Transform transform) {
        try (var arena = Arena.ofConfined()) {
            var value = arena.allocate(TRANSFORM_LAYOUT);
            writeVector(value, 0, transform.position());
            writeQuaternion(value, 12, transform.rotation());
            writeVector(value, 28, transform.scale());
            var status = (int) setTransform.invokeExact(instance, value);
            check(status, "Unable to set native model transform");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native model transform update", failure);
        }
    }

    private void setVisible(MemorySegment instance, boolean visible) {
        try {
            var status = (int) setVisible.invokeExact(instance, visible ? 1 : 0);
            check(status, "Unable to set native model visibility");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native model visibility update", failure);
        }
    }

    private void play(MemorySegment instance, NativeRuntime.Motion motion, boolean looping, float fadeSeconds) {
        requireFiniteNonNegative(fadeSeconds, "fadeSeconds");
        try {
            var status = (int) play.invokeExact(instance, motion.nativeHandle(), looping ? 1 : 0, fadeSeconds);
            check(status, "Unable to play native animation");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native animation playback", failure);
        }
    }

    private void stop(MemorySegment instance, float fadeSeconds) {
        requireFiniteNonNegative(fadeSeconds, "fadeSeconds");
        try {
            var status = (int) stop.invokeExact(instance, fadeSeconds);
            check(status, "Unable to stop native animation");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native animation stop", failure);
        }
    }

    private void resetPhysics(MemorySegment instance) {
        if (resetPhysics == null) {
            throw new UnsupportedOperationException("Native library does not provide libmmd_model_instance_reset_physics");
        }
        try {
            var status = (int) resetPhysics.invokeExact(instance);
            check(status, "Unable to reset native model physics");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native model physics reset", failure);
        }
    }

    private InstanceState state(MemorySegment instance) {
        try (var arena = Arena.ofConfined()) {
            var output = arena.allocate(STATE_LAYOUT);
            header(output, STATE_LAYOUT);
            var status = (int) getState.invokeExact(instance, output);
            check(status, "Unable to query native model instance");
            return new InstanceState(
                new Transform(vector(output, 8), quaternion(output, 20), vector(output, 36)),
                output.get(JAVA_INT, 48) != 0,
                output.get(JAVA_INT, 52) != 0,
                output.get(JAVA_INT, 56) != 0,
                output.get(JAVA_FLOAT, 64),
                output.get(JAVA_FLOAT, 68)
            );
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native model instance query", failure);
        }
    }

    private MatrixView matrices(ModelInstance instance, MemorySegment handle) {
        try (var arena = Arena.ofConfined()) {
            var output = arena.allocate(MATRIX_VIEW_LAYOUT);
            header(output, MATRIX_VIEW_LAYOUT);
            var status = (int) getMatrices.invokeExact(handle, output);
            check(status, "Unable to query native model matrices");
            var floatCount = output.get(JAVA_LONG, 16);
            var boneCount = output.get(JAVA_INT, 24);
            var matrixStride = output.get(JAVA_INT, 28);
            if (floatCount != Math.multiplyExact((long) boneCount, matrixStride) || matrixStride != 16) {
                throw new IllegalStateException("libmmd returned an inconsistent model matrix view");
            }
            var data = output.get(ADDRESS, 8).reinterpret(Math.multiplyExact(floatCount, Float.BYTES)).asReadOnly();
            return new MatrixView(instance, data, boneCount, matrixStride);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native model matrix query", failure);
        }
    }

    private RenderPacket renderPacket(ModelInstance instance, MemorySegment handle) {
        try (var arena = Arena.ofConfined()) {
            var output = arena.allocate(RENDER_PACKET_LAYOUT);
            header(output, RENDER_PACKET_LAYOUT);
            var status = (int) getRenderPacket.invokeExact(handle, output);
            check(status, "Unable to query native render packet");
            var vertexSize = output.get(JAVA_LONG, 64);
            var vertexCount = output.get(JAVA_INT, 72);
            var vertexStride = output.get(JAVA_INT, 76);
            var skinningSize = output.get(JAVA_LONG, 88);
            var skinningStride = output.get(JAVA_INT, 96);
            var indexSize = output.get(JAVA_LONG, 112);
            var indexCount = output.get(JAVA_INT, 120);
            var indexStride = output.get(JAVA_INT, 124);
            var matrixFloatCount = output.get(JAVA_LONG, 136);
            var boneCount = output.get(JAVA_INT, 144);
            var matrixStride = output.get(JAVA_INT, 148);
            if (vertexSize != Math.multiplyExact((long) vertexCount, vertexStride) ||
                skinningSize != Math.multiplyExact((long) vertexCount, skinningStride) ||
                indexSize != Math.multiplyExact((long) indexCount, indexStride) ||
                matrixFloatCount != Math.multiplyExact((long) boneCount, matrixStride) ||
                vertexStride != 28 || skinningStride != 32 || matrixStride != 16 ||
                (indexStride != Short.BYTES && indexStride != Integer.BYTES)) {
                throw new IllegalStateException("libmmd returned an inconsistent render packet");
            }
            return new RenderPacket(
                instance,
                output.get(JAVA_INT, 8),
                output.get(JAVA_INT, 12) != 0,
                new Transform(vector(output, 16), quaternion(output, 28), vector(output, 44)),
                output.get(ADDRESS, 56).reinterpret(vertexSize).asReadOnly(),
                vertexCount,
                vertexStride,
                output.get(ADDRESS, 80).reinterpret(skinningSize).asReadOnly(),
                skinningStride,
                output.get(ADDRESS, 104).reinterpret(indexSize).asReadOnly(),
                indexCount,
                indexStride,
                output.get(ADDRESS, 128).reinterpret(Math.multiplyExact(matrixFloatCount, Float.BYTES)).asReadOnly(),
                boneCount,
                matrixStride,
                output.get(JAVA_INT, 152)
            );
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native render packet query", failure);
        }
    }

    private void check(int status, String operation) throws Throwable {
        if (status == 0) return;
        var message = owner.errorMessage(operation, status);
        throw switch (status) {
            case 1 -> new IllegalArgumentException(message);
            case 6 -> new UnsupportedOperationException(message);
            default -> new IllegalStateException(message);
        };
    }

    private static void header(MemorySegment segment, MemoryLayout layout) {
        segment.set(JAVA_INT, 0, NativeRuntime.ABI_VERSION);
        segment.set(JAVA_INT, 4, Math.toIntExact(layout.byteSize()));
    }

    private static void writeVector(MemorySegment segment, long offset, NativeRuntime.Vector3 value) {
        Objects.requireNonNull(value, "value");
        segment.set(JAVA_FLOAT, offset, value.x());
        segment.set(JAVA_FLOAT, offset + 4, value.y());
        segment.set(JAVA_FLOAT, offset + 8, value.z());
    }

    private static void writeQuaternion(MemorySegment segment, long offset, NativeRuntime.Quaternion value) {
        Objects.requireNonNull(value, "value");
        segment.set(JAVA_FLOAT, offset, value.x());
        segment.set(JAVA_FLOAT, offset + 4, value.y());
        segment.set(JAVA_FLOAT, offset + 8, value.z());
        segment.set(JAVA_FLOAT, offset + 12, value.w());
    }

    private static NativeRuntime.Vector3 vector(MemorySegment segment, long offset) {
        return new NativeRuntime.Vector3(
            segment.get(JAVA_FLOAT, offset),
            segment.get(JAVA_FLOAT, offset + 4),
            segment.get(JAVA_FLOAT, offset + 8)
        );
    }

    private static NativeRuntime.Quaternion quaternion(MemorySegment segment, long offset) {
        return new NativeRuntime.Quaternion(
            segment.get(JAVA_FLOAT, offset),
            segment.get(JAVA_FLOAT, offset + 4),
            segment.get(JAVA_FLOAT, offset + 8),
            segment.get(JAVA_FLOAT, offset + 12)
        );
    }

    private static void requireFinitePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0f) throw new IllegalArgumentException(name + " must be finite and positive");
    }

    private static void requireFiniteNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) throw new IllegalArgumentException(name + " must be finite and non-negative");
    }

    private static MethodHandle downcall(
        Linker linker,
        SymbolLookup symbols,
        String name,
        MemoryLayout result,
        MemoryLayout... arguments
    ) {
        var symbol = symbols.find(name).orElseThrow(() -> new IllegalStateException("Missing native symbol " + name));
        return linker.downcallHandle(symbol, FunctionDescriptor.of(result, arguments));
    }

    private static MethodHandle optionalDowncall(
        Linker linker,
        SymbolLookup symbols,
        String name,
        MemoryLayout result,
        MemoryLayout... arguments
    ) {
        return symbols.find(name)
            .map(symbol -> linker.downcallHandle(symbol, FunctionDescriptor.of(result, arguments)))
            .orElse(null);
    }

    private static MethodHandle downcallVoid(
        Linker linker,
        SymbolLookup symbols,
        String name,
        MemoryLayout... arguments
    ) {
        var symbol = symbols.find(name).orElseThrow(() -> new IllegalStateException("Missing native symbol " + name));
        return linker.downcallHandle(symbol, FunctionDescriptor.ofVoid(arguments));
    }

    public record PhysicsConfig(
        NativeRuntime.Vector3 gravity,
        float metersPerUnit,
        float fixedStepSeconds,
        int maximumSubsteps,
        int solverIterations
    ) {
        public PhysicsConfig {
            Objects.requireNonNull(gravity, "gravity");
            if (!Float.isFinite(gravity.x()) || !Float.isFinite(gravity.y()) || !Float.isFinite(gravity.z())) {
                throw new IllegalArgumentException("gravity must be finite");
            }
            requireFinitePositive(metersPerUnit, "metersPerUnit");
            requireFinitePositive(fixedStepSeconds, "fixedStepSeconds");
            if (maximumSubsteps <= 0) throw new IllegalArgumentException("maximumSubsteps must be positive");
            if (solverIterations <= 0) throw new IllegalArgumentException("solverIterations must be positive");
        }

        public static PhysicsConfig defaults() {
            return new PhysicsConfig(new NativeRuntime.Vector3(0.0f, -9.81f, 0.0f), 0.08f, 1.0f / 120.0f, 8, 10);
        }
    }

    public record Transform(
        NativeRuntime.Vector3 position,
        NativeRuntime.Quaternion rotation,
        NativeRuntime.Vector3 scale
    ) {
        public static final Transform IDENTITY = new Transform(
            new NativeRuntime.Vector3(0.0f, 0.0f, 0.0f),
            NativeRuntime.Quaternion.IDENTITY,
            new NativeRuntime.Vector3(1.0f, 1.0f, 1.0f)
        );

        public Transform {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(scale, "scale");
        }
    }

    public record UpdateInfo(
        long frameIndex,
        int instanceCount,
        int animatedInstanceCount,
        boolean droppedTime,
        float deltaSeconds,
        float totalSeconds
    ) {}

    public record InstanceState(
        Transform transform,
        boolean visible,
        boolean playing,
        boolean looping,
        float playbackSeconds,
        float transitionWeight
    ) {}

    public static final class MatrixView {
        private final ModelInstance owner;
        private final MemorySegment data;
        private final int boneCount;
        private final int matrixStride;

        private MatrixView(ModelInstance owner, MemorySegment data, int boneCount, int matrixStride) {
            this.owner = owner;
            this.data = data;
            this.boneCount = boneCount;
            this.matrixStride = matrixStride;
        }

        public MemorySegment data() {
            owner.ensureOpen();
            return data;
        }

        public int boneCount() {
            owner.ensureOpen();
            return boneCount;
        }

        public int matrixStride() {
            owner.ensureOpen();
            return matrixStride;
        }
    }

    public static final class RenderPacket {
        private final ModelInstance owner;
        private final int backendMask;
        private final boolean visible;
        private final Transform transform;
        private final MemorySegment vertices;
        private final int vertexCount;
        private final int vertexStride;
        private final MemorySegment skinning;
        private final int skinningStride;
        private final MemorySegment indices;
        private final int indexCount;
        private final int indexStride;
        private final MemorySegment matrices;
        private final int boneCount;
        private final int matrixStride;
        private final int drawCount;

        private RenderPacket(
            ModelInstance owner,
            int backendMask,
            boolean visible,
            Transform transform,
            MemorySegment vertices,
            int vertexCount,
            int vertexStride,
            MemorySegment skinning,
            int skinningStride,
            MemorySegment indices,
            int indexCount,
            int indexStride,
            MemorySegment matrices,
            int boneCount,
            int matrixStride,
            int drawCount
        ) {
            this.owner = owner;
            this.backendMask = backendMask;
            this.visible = visible;
            this.transform = transform;
            this.vertices = vertices;
            this.vertexCount = vertexCount;
            this.vertexStride = vertexStride;
            this.skinning = skinning;
            this.skinningStride = skinningStride;
            this.indices = indices;
            this.indexCount = indexCount;
            this.indexStride = indexStride;
            this.matrices = matrices;
            this.boneCount = boneCount;
            this.matrixStride = matrixStride;
            this.drawCount = drawCount;
        }

        public int backendMask() {
            owner.ensureOpen();
            return backendMask;
        }

        public boolean visible() {
            owner.ensureOpen();
            return visible;
        }

        public Transform transform() {
            owner.ensureOpen();
            return transform;
        }

        public MemorySegment vertices() {
            owner.ensureOpen();
            return vertices;
        }

        public int vertexCount() {
            owner.ensureOpen();
            return vertexCount;
        }

        public int vertexStride() {
            owner.ensureOpen();
            return vertexStride;
        }

        public MemorySegment skinning() {
            owner.ensureOpen();
            return skinning;
        }

        public int skinningStride() {
            owner.ensureOpen();
            return skinningStride;
        }

        public MemorySegment indices() {
            owner.ensureOpen();
            return indices;
        }

        public int indexCount() {
            owner.ensureOpen();
            return indexCount;
        }

        public int indexStride() {
            owner.ensureOpen();
            return indexStride;
        }

        public MemorySegment matrices() {
            owner.ensureOpen();
            return matrices;
        }

        public int boneCount() {
            owner.ensureOpen();
            return boneCount;
        }

        public int matrixStride() {
            owner.ensureOpen();
            return matrixStride;
        }

        public int drawCount() {
            owner.ensureOpen();
            return drawCount;
        }
    }

    public static final class Scene implements AutoCloseable {
        private final SceneRuntime owner;
        private final Set<ModelInstance> instances = new LinkedHashSet<>();
        private MemorySegment handle;

        private Scene(SceneRuntime owner, MemorySegment handle) {
            this.owner = owner;
            this.handle = handle;
        }

        public synchronized ModelInstance createInstance(NativeRuntime.Model model) {
            ensureOpen();
            Objects.requireNonNull(model, "model");
            if (model.runtime() != owner.owner) throw new IllegalArgumentException("Model and scene must share a runtime");
            model.retainInstance();
            try {
                var instance = new ModelInstance(this, model, owner.createInstance(handle, model));
                instances.add(instance);
                return instance;
            } catch (RuntimeException failure) {
                model.releaseInstance();
                throw failure;
            }
        }

        public synchronized UpdateInfo update(float deltaSeconds) {
            ensureOpen();
            var info = owner.update(handle, deltaSeconds);
            for (var instance : instances) instance.refreshMotion();
            return info;
        }

        public boolean isClosed() {
            return handle.equals(MemorySegment.NULL);
        }

        @Override
        public synchronized void close() {
            if (isClosed()) return;
            for (var instance : List.copyOf(instances)) instance.close();
            owner.closeScene(this, handle);
            handle = MemorySegment.NULL;
        }

        private synchronized void remove(ModelInstance instance) {
            instances.remove(instance);
        }

        private void ensureOpen() {
            if (isClosed()) throw new IllegalStateException("libmmd scene is closed");
            owner.owner.ensureOpen();
        }
    }

    public static final class ModelInstance implements AutoCloseable {
        private final Scene scene;
        private final NativeRuntime.Model model;
        private MemorySegment handle;
        private NativeRuntime.Motion motion;
        private boolean looping;

        private ModelInstance(Scene scene, NativeRuntime.Model model, MemorySegment handle) {
            this.scene = scene;
            this.model = model;
            this.handle = handle;
        }

        public void setTransform(Transform transform) {
            ensureOpen();
            scene.owner.setTransform(handle, Objects.requireNonNull(transform, "transform"));
        }

        public void setVisible(boolean visible) {
            ensureOpen();
            scene.owner.setVisible(handle, visible);
        }

        public synchronized void play(NativeRuntime.Motion motion, boolean looping, float fadeSeconds) {
            ensureOpen();
            Objects.requireNonNull(motion, "motion");
            if (motion.model() != model) throw new IllegalArgumentException("Motion and model instance must share a model");
            motion.retainInstance();
            try {
                scene.owner.play(handle, motion, looping, fadeSeconds);
            } catch (RuntimeException failure) {
                motion.releaseInstance();
                throw failure;
            }
            releaseMotion();
            this.motion = motion;
            this.looping = looping;
        }

        public synchronized void stop(float fadeSeconds) {
            ensureOpen();
            scene.owner.stop(handle, fadeSeconds);
            releaseMotion();
        }

        public void resetPhysics() {
            ensureOpen();
            scene.owner.resetPhysics(handle);
        }

        public synchronized InstanceState state() {
            ensureOpen();
            var state = scene.owner.state(handle);
            if (!state.playing()) releaseMotion();
            return state;
        }

        public MatrixView matrices() {
            ensureOpen();
            return scene.owner.matrices(this, handle);
        }

        public RenderPacket renderPacket() {
            ensureOpen();
            return scene.owner.renderPacket(this, handle);
        }

        public boolean isClosed() {
            return handle.equals(MemorySegment.NULL);
        }

        @Override
        public synchronized void close() {
            if (isClosed()) return;
            scene.owner.destroyInstance(handle);
            handle = MemorySegment.NULL;
            releaseMotion();
            model.releaseInstance();
            scene.remove(this);
        }

        private synchronized void refreshMotion() {
            if (motion != null && !looping && !scene.owner.state(handle).playing()) releaseMotion();
        }

        private void releaseMotion() {
            if (motion == null) return;
            motion.releaseInstance();
            motion = null;
            looping = false;
        }

        private void ensureOpen() {
            if (isClosed()) throw new IllegalStateException("libmmd model instance is closed");
            scene.ensureOpen();
            model.nativeHandle();
        }
    }
}

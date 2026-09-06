package com.micheanl.libmmd;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class NativeRuntime implements AutoCloseable {
    public static final int ABI_VERSION = 3;
    private static final long MAX_PACK_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_MOTION_BYTES = 64L * 1024 * 1024;

    private static final MemoryLayout CONFIG_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("worker_threads"),
        JAVA_INT.withName("flags")
    );
    private static final MemoryLayout MODEL_INFO_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("vertex_count"),
        JAVA_INT.withName("index_count"),
        JAVA_INT.withName("texture_count"),
        JAVA_INT.withName("material_count"),
        JAVA_INT.withName("bone_count"),
        JAVA_INT.withName("morph_count"),
        JAVA_INT.withName("rigid_body_count"),
        JAVA_INT.withName("joint_count"),
        JAVA_LONG.withName("source_hash")
    );
    private static final MemoryLayout PACK_VIEW_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_size"),
        ADDRESS.withName("data"),
        JAVA_LONG.withName("size")
    );
    private static final MemoryLayout MESH_VIEW_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_size"),
        ADDRESS.withName("vertex_data"),
        JAVA_LONG.withName("vertex_size"),
        JAVA_INT.withName("vertex_count"),
        JAVA_INT.withName("vertex_stride"),
        ADDRESS.withName("index_data"),
        JAVA_LONG.withName("index_size"),
        JAVA_INT.withName("index_count"),
        JAVA_INT.withName("index_stride")
    );
    private static final MemoryLayout RENDER_MESH_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_size"),
        ADDRESS.withName("vertex_data"),
        JAVA_LONG.withName("vertex_size"),
        JAVA_INT.withName("vertex_count"),
        JAVA_INT.withName("vertex_stride"),
        ADDRESS.withName("skinning_data"),
        JAVA_LONG.withName("skinning_size"),
        JAVA_INT.withName("skinning_stride"),
        JAVA_INT.withName("reserved"),
        ADDRESS.withName("index_data"),
        JAVA_LONG.withName("index_size"),
        JAVA_INT.withName("index_count"),
        JAVA_INT.withName("index_stride")
    );
    private static final MemoryLayout BONE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_size"),
        ADDRESS.withName("name"),
        JAVA_LONG.withName("name_size"),
        ADDRESS.withName("english_name"),
        JAVA_LONG.withName("english_name_size"),
        JAVA_INT.withName("parent_index"),
        JAVA_INT.withName("deform_layer"),
        JAVA_INT.withName("flags"),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("position"),
        JAVA_INT.withName("connection_index"),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("connection_offset"),
        JAVA_INT.withName("inheritance_index"),
        JAVA_FLOAT.withName("inheritance_weight"),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("fixed_axis"),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("local_x_axis"),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("local_z_axis"),
        JAVA_INT.withName("external_parent_key"),
        JAVA_INT.withName("ik_target_index"),
        JAVA_INT.withName("ik_iteration_count"),
        JAVA_FLOAT.withName("ik_angle_limit"),
        JAVA_INT.withName("ik_link_count")
    );
    private static final MemoryLayout IK_LINK_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("bone_index"),
        JAVA_INT.withName("limited"),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("lower_limit"),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("upper_limit")
    );
    private static final MemoryLayout MATRIX_VIEW_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_size"),
        ADDRESS.withName("data"),
        JAVA_LONG.withName("float_count"),
        JAVA_INT.withName("bone_count"),
        JAVA_INT.withName("matrix_stride")
    );
    private static final MemoryLayout MOTION_INFO_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("duration_frames"),
        JAVA_INT.withName("bound_bone_count"),
        JAVA_INT.withName("bound_ik_count"),
        JAVA_INT.withName("reserved")
    );

    private final Arena arena;
    private final MethodHandle destroy;
    private final MethodHandle workerThreads;
    private final MethodHandle loadModel;
    private final MethodHandle loadPmx;
    private final MethodHandle destroyModel;
    private final MethodHandle getModelInfo;
    private final MethodHandle getPackView;
    private final MethodHandle getMeshView;
    private final MethodHandle getRenderMeshView;
    private final MethodHandle getBone;
    private final MethodHandle getIkLink;
    private final MethodHandle createPose;
    private final MethodHandle destroyPose;
    private final MethodHandle resetPose;
    private final MethodHandle setPoseTransform;
    private final MethodHandle evaluatePose;
    private final MethodHandle getPoseMatrices;
    private final MethodHandle createMotion;
    private final MethodHandle destroyMotion;
    private final MethodHandle getMotionInfo;
    private final MethodHandle applyMotion;
    private final MethodHandle lastError;
    private final SceneRuntime scenes;
    private final PhysicsRuntime physics;
    private MemorySegment handle;
    private int openModels;

    private NativeRuntime(
        Arena arena,
        MethodHandle destroy,
        MethodHandle workerThreads,
        MethodHandle loadModel,
        MethodHandle loadPmx,
        MethodHandle destroyModel,
        MethodHandle getModelInfo,
        MethodHandle getPackView,
        MethodHandle getMeshView,
        MethodHandle getRenderMeshView,
        MethodHandle getBone,
        MethodHandle getIkLink,
        MethodHandle createPose,
        MethodHandle destroyPose,
        MethodHandle resetPose,
        MethodHandle setPoseTransform,
        MethodHandle evaluatePose,
        MethodHandle getPoseMatrices,
        MethodHandle createMotion,
        MethodHandle destroyMotion,
        MethodHandle getMotionInfo,
        MethodHandle applyMotion,
        MethodHandle lastError,
        Linker linker,
        SymbolLookup symbols,
        MemorySegment handle
    ) {
        this.arena = arena;
        this.destroy = destroy;
        this.workerThreads = workerThreads;
        this.loadModel = loadModel;
        this.loadPmx = loadPmx;
        this.destroyModel = destroyModel;
        this.getModelInfo = getModelInfo;
        this.getPackView = getPackView;
        this.getMeshView = getMeshView;
        this.getRenderMeshView = getRenderMeshView;
        this.getBone = getBone;
        this.getIkLink = getIkLink;
        this.createPose = createPose;
        this.destroyPose = destroyPose;
        this.resetPose = resetPose;
        this.setPoseTransform = setPoseTransform;
        this.evaluatePose = evaluatePose;
        this.getPoseMatrices = getPoseMatrices;
        this.createMotion = createMotion;
        this.destroyMotion = destroyMotion;
        this.getMotionInfo = getMotionInfo;
        this.applyMotion = applyMotion;
        this.lastError = lastError;
        this.handle = handle;
        this.scenes = new SceneRuntime(this, linker, symbols);
        this.physics = new PhysicsRuntime(this, linker, symbols);
    }

    public static NativeRuntime open(Path library) {
        return open(library, 0);
    }

    public static NativeRuntime open() {
        return open(NativeLibrary.resolve(), 0);
    }

    public static NativeRuntime open(int workerThreads) {
        return open(NativeLibrary.resolve(), workerThreads);
    }

    public static NativeRuntime open(Path library, int workerThreads) {
        Objects.requireNonNull(library, "library");
        if (workerThreads < 0) {
            throw new IllegalArgumentException("workerThreads must not be negative");
        }
        var arena = Arena.ofShared();
        try {
            var linker = Linker.nativeLinker();
            var symbols = SymbolLookup.libraryLookup(library.toAbsolutePath(), arena);
            var abiVersion = downcall(linker, symbols, "libmmd_abi_version", FunctionDescriptor.of(JAVA_INT));
            var create = downcall(
                linker,
                symbols,
                "libmmd_runtime_create",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
            );
            var destroy = downcall(
                linker,
                symbols,
                "libmmd_runtime_destroy",
                FunctionDescriptor.ofVoid(ADDRESS)
            );
            var workerCount = downcall(
                linker,
                symbols,
                "libmmd_runtime_worker_threads",
                FunctionDescriptor.of(JAVA_INT, ADDRESS)
            );
            var lastError = downcall(
                linker,
                symbols,
                "libmmd_last_error",
                FunctionDescriptor.of(ADDRESS, ADDRESS)
            );
            var loadModel = downcall(
                linker,
                symbols,
                "libmmd_model_load_pack",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)
            );
            var loadPmx = downcall(
                linker,
                symbols,
                "libmmd_model_load_pmx",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)
            );
            var destroyModel = downcall(
                linker,
                symbols,
                "libmmd_model_destroy",
                FunctionDescriptor.ofVoid(ADDRESS)
            );
            var getModelInfo = downcall(
                linker,
                symbols,
                "libmmd_model_get_info",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
            );
            var getPackView = downcall(
                linker,
                symbols,
                "libmmd_model_get_pack_view",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
            );
            var getMeshView = downcall(
                linker,
                symbols,
                "libmmd_model_get_mesh_view",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
            );
            var getRenderMeshView = downcall(
                linker,
                symbols,
                "libmmd_model_get_render_mesh_view",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
            );
            var getBone = downcall(
                linker,
                symbols,
                "libmmd_model_get_bone",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
            );
            var getIkLink = downcall(
                linker,
                symbols,
                "libmmd_model_get_ik_link",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS)
            );
            var createPose = downcall(
                linker,
                symbols,
                "libmmd_pose_create",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
            );
            var destroyPose = downcall(
                linker,
                symbols,
                "libmmd_pose_destroy",
                FunctionDescriptor.ofVoid(ADDRESS)
            );
            var resetPose = downcall(
                linker,
                symbols,
                "libmmd_pose_reset",
                FunctionDescriptor.ofVoid(ADDRESS)
            );
            var setPoseTransform = downcall(
                linker,
                symbols,
                "libmmd_pose_set_local_transform",
                FunctionDescriptor.of(
                    JAVA_INT,
                    ADDRESS,
                    JAVA_INT,
                    JAVA_FLOAT,
                    JAVA_FLOAT,
                    JAVA_FLOAT,
                    JAVA_FLOAT,
                    JAVA_FLOAT,
                    JAVA_FLOAT,
                    JAVA_FLOAT
                )
            );
            var evaluatePose = downcall(
                linker,
                symbols,
                "libmmd_pose_evaluate",
                FunctionDescriptor.of(JAVA_INT, ADDRESS)
            );
            var getPoseMatrices = downcall(
                linker,
                symbols,
                "libmmd_pose_get_matrices",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
            );
            var createMotion = downcall(
                linker,
                symbols,
                "libmmd_motion_create_vmd",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)
            );
            var destroyMotion = downcall(
                linker,
                symbols,
                "libmmd_motion_destroy",
                FunctionDescriptor.ofVoid(ADDRESS)
            );
            var getMotionInfo = downcall(
                linker,
                symbols,
                "libmmd_motion_get_info",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
            );
            var applyMotion = downcall(
                linker,
                symbols,
                "libmmd_motion_apply",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_INT, ADDRESS)
            );
            var nativeAbi = (int) abiVersion.invokeExact();
            if (nativeAbi != ABI_VERSION) {
                throw new IllegalStateException("libmmd ABI mismatch: expected " + ABI_VERSION + ", got " + nativeAbi);
            }
            var config = arena.allocate(CONFIG_LAYOUT);
            config.set(JAVA_INT, 0, ABI_VERSION);
            config.set(JAVA_INT, 4, Math.toIntExact(CONFIG_LAYOUT.byteSize()));
            config.set(JAVA_INT, 8, workerThreads);
            config.set(JAVA_INT, 12, 0);
            var output = arena.allocate(ADDRESS);
            var status = (int) create.invokeExact(config, output);
            if (status != 0) {
                var message = (MemorySegment) lastError.invokeExact(MemorySegment.NULL);
                throw new IllegalStateException("libmmd runtime creation failed: " + message.reinterpret(4096).getString(0));
            }
            var handle = output.get(ADDRESS, 0);
            if (handle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("libmmd returned a null runtime");
            }
            return new NativeRuntime(
                arena,
                destroy,
                workerCount,
                loadModel,
                loadPmx,
                destroyModel,
                getModelInfo,
                getPackView,
                getMeshView,
                getRenderMeshView,
                getBone,
                getIkLink,
                createPose,
                destroyPose,
                resetPose,
                setPoseTransform,
                evaluatePose,
                getPoseMatrices,
                createMotion,
                destroyMotion,
                getMotionInfo,
                applyMotion,
                lastError,
                linker,
                symbols,
                handle
            );
        } catch (Throwable failure) {
            arena.close();
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException("Unable to initialize libmmd", failure);
        }
    }

    public int workerThreads() {
        ensureOpen();
        try {
            return (int) workerThreads.invokeExact(handle);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to query libmmd runtime", failure);
        }
    }

    public boolean isClosed() {
        return handle.equals(MemorySegment.NULL);
    }

    public PhysicsRuntime physics() {
        ensureOpen();
        return physics;
    }

    public SceneRuntime scenes() {
        ensureOpen();
        return scenes;
    }

    public synchronized Model loadPack(Path path) {
        Objects.requireNonNull(path, "path");
        ensureOpen();
        final byte[] bytes;
        try {
            var size = Files.size(path);
            if (size <= 0 || size > MAX_PACK_BYTES) {
                throw new IllegalArgumentException("mmdpack size must be between 1 and " + MAX_PACK_BYTES + " bytes");
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read mmdpack " + path, failure);
        }
        try (var callArena = Arena.ofConfined()) {
            var data = callArena.allocate(bytes.length);
            data.copyFrom(MemorySegment.ofArray(bytes));
            var output = callArena.allocate(ADDRESS);
            var status = (int) loadModel.invokeExact(handle, data, (long) bytes.length, output);
            if (status != 0) {
                throw new IllegalArgumentException(errorMessage("Unable to load mmdpack", status));
            }
            var modelHandle = output.get(ADDRESS, 0);
            if (modelHandle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("libmmd returned a null model");
            }
            openModels++;
            return new Model(this, modelHandle);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd model loader", failure);
        }
    }

    public synchronized Model loadPmx(Path path) {
        Objects.requireNonNull(path, "path");
        ensureOpen();
        final byte[] bytes;
        try {
            var size = Files.size(path);
            if (size <= 0 || size > MAX_PACK_BYTES) {
                throw new IllegalArgumentException("PMX size must be between 1 and " + MAX_PACK_BYTES + " bytes");
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read PMX " + path, failure);
        }
        try (var callArena = Arena.ofConfined()) {
            var data = callArena.allocate(bytes.length);
            data.copyFrom(MemorySegment.ofArray(bytes));
            var output = callArena.allocate(ADDRESS);
            var status = (int) loadPmx.invokeExact(handle, data, (long) bytes.length, output);
            if (status != 0) {
                throw new IllegalArgumentException(errorMessage("Unable to load PMX", status));
            }
            var modelHandle = output.get(ADDRESS, 0);
            if (modelHandle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("libmmd returned a null model");
            }
            openModels++;
            return new Model(this, modelHandle);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd PMX loader", failure);
        }
    }

    @Override
    public synchronized void close() {
        if (isClosed()) {
            return;
        }
        if (scenes.hasOpenScenes()) {
            throw new IllegalStateException("Close all libmmd scenes before closing the runtime");
        }
        if (openModels != 0) {
            throw new IllegalStateException("Close all libmmd models before closing the runtime");
        }
        physics.closeWorlds();
        try {
            destroy.invokeExact(handle);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to destroy libmmd runtime", failure);
        } finally {
            handle = MemorySegment.NULL;
            arena.close();
        }
    }

    void ensureOpen() {
        if (isClosed()) {
            throw new IllegalStateException("libmmd runtime is closed");
        }
    }

    private synchronized ModelInfo modelInfo(MemorySegment modelHandle) {
        ensureOpen();
        try (var callArena = Arena.ofConfined()) {
            var output = callArena.allocate(MODEL_INFO_LAYOUT);
            output.set(JAVA_INT, 0, ABI_VERSION);
            output.set(JAVA_INT, 4, Math.toIntExact(MODEL_INFO_LAYOUT.byteSize()));
            var status = (int) getModelInfo.invokeExact(modelHandle, output);
            if (status != 0) {
                throw new IllegalStateException("Unable to query libmmd model: status " + status);
            }
            return new ModelInfo(
                output.get(JAVA_INT, 8),
                output.get(JAVA_INT, 12),
                output.get(JAVA_INT, 16),
                output.get(JAVA_INT, 20),
                output.get(JAVA_INT, 24),
                output.get(JAVA_INT, 28),
                output.get(JAVA_INT, 32),
                output.get(JAVA_INT, 36),
                output.get(JAVA_LONG, 40)
            );
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd model query", failure);
        }
    }

    private synchronized void closeModel(MemorySegment modelHandle) {
        ensureOpen();
        try {
            destroyModel.invokeExact(modelHandle);
            openModels--;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to destroy libmmd model", failure);
        }
    }

    private synchronized PackView packView(Model model, MemorySegment modelHandle) {
        ensureOpen();
        try (var callArena = Arena.ofConfined()) {
            var output = callArena.allocate(PACK_VIEW_LAYOUT);
            output.set(JAVA_INT, 0, ABI_VERSION);
            output.set(JAVA_INT, 4, Math.toIntExact(PACK_VIEW_LAYOUT.byteSize()));
            var status = (int) getPackView.invokeExact(modelHandle, output);
            if (status != 0) throw new IllegalStateException("Unable to query libmmd pack: status " + status);
            var size = output.get(JAVA_LONG, 16);
            var address = output.get(ADDRESS, 8);
            if (size <= 0 || size > MAX_PACK_BYTES || address.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("libmmd returned an invalid pack view");
            }
            return new PackView(model, address.reinterpret(size).asReadOnly());
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd pack query", failure);
        }
    }

    private synchronized MeshView meshView(Model model, MemorySegment modelHandle) {
        ensureOpen();
        try (var callArena = Arena.ofConfined()) {
            var output = callArena.allocate(MESH_VIEW_LAYOUT);
            output.set(JAVA_INT, 0, ABI_VERSION);
            output.set(JAVA_INT, 4, Math.toIntExact(MESH_VIEW_LAYOUT.byteSize()));
            var status = (int) getMeshView.invokeExact(modelHandle, output);
            if (status != 0) {
                throw new IllegalStateException("Unable to query libmmd mesh: status " + status);
            }
            var vertexSize = output.get(JAVA_LONG, 16);
            var vertexCount = output.get(JAVA_INT, 24);
            var vertexStride = output.get(JAVA_INT, 28);
            var indexSize = output.get(JAVA_LONG, 40);
            var indexCount = output.get(JAVA_INT, 48);
            var indexStride = output.get(JAVA_INT, 52);
            if (vertexSize != Math.multiplyExact((long) vertexCount, vertexStride) ||
                indexSize != Math.multiplyExact((long) indexCount, indexStride)) {
                throw new IllegalStateException("libmmd returned inconsistent mesh buffer sizes");
            }
            var vertices = output.get(ADDRESS, 8).reinterpret(vertexSize).asReadOnly();
            var indices = output.get(ADDRESS, 32).reinterpret(indexSize).asReadOnly();
            return new MeshView(model, vertices, vertexCount, vertexStride, indices, indexCount, indexStride);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd mesh query", failure);
        }
    }

    private synchronized RenderMesh renderMesh(Model model, MemorySegment modelHandle) {
        ensureOpen();
        try (var callArena = Arena.ofConfined()) {
            var output = callArena.allocate(RENDER_MESH_LAYOUT);
            output.set(JAVA_INT, 0, ABI_VERSION);
            output.set(JAVA_INT, 4, Math.toIntExact(RENDER_MESH_LAYOUT.byteSize()));
            var status = (int) getRenderMeshView.invokeExact(modelHandle, output);
            if (status != 0) {
                throw new IllegalStateException("Unable to query libmmd render mesh: status " + status);
            }
            var vertexSize = output.get(JAVA_LONG, 16);
            var vertexCount = output.get(JAVA_INT, 24);
            var vertexStride = output.get(JAVA_INT, 28);
            var skinningSize = output.get(JAVA_LONG, 40);
            var skinningStride = output.get(JAVA_INT, 48);
            var indexSize = output.get(JAVA_LONG, 64);
            var indexCount = output.get(JAVA_INT, 72);
            var indexStride = output.get(JAVA_INT, 76);
            if (vertexSize != Math.multiplyExact((long) vertexCount, vertexStride) ||
                skinningSize != Math.multiplyExact((long) vertexCount, skinningStride) ||
                indexSize != Math.multiplyExact((long) indexCount, indexStride) ||
                (indexStride != Short.BYTES && indexStride != Integer.BYTES)) {
                throw new IllegalStateException("libmmd returned an inconsistent render mesh");
            }
            return new RenderMesh(
                model,
                output.get(ADDRESS, 8).reinterpret(vertexSize).asReadOnly(),
                vertexCount,
                vertexStride,
                output.get(ADDRESS, 32).reinterpret(skinningSize).asReadOnly(),
                skinningStride,
                output.get(ADDRESS, 56).reinterpret(indexSize).asReadOnly(),
                indexCount,
                indexStride
            );
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd render mesh query", failure);
        }
    }

    private synchronized Bone bone(MemorySegment modelHandle, int boneIndex) {
        ensureOpen();
        if (boneIndex < 0) throw new IndexOutOfBoundsException("boneIndex must not be negative");
        try (var callArena = Arena.ofConfined()) {
            var output = callArena.allocate(BONE_LAYOUT);
            output.set(JAVA_INT, 0, ABI_VERSION);
            output.set(JAVA_INT, 4, Math.toIntExact(BONE_LAYOUT.byteSize()));
            var status = (int) getBone.invokeExact(modelHandle, boneIndex, output);
            if (status != 0) throw new IndexOutOfBoundsException("Bone index is outside the model: " + boneIndex);
            return new Bone(
                nativeString(output.get(ADDRESS, 8), output.get(JAVA_LONG, 16)),
                nativeString(output.get(ADDRESS, 24), output.get(JAVA_LONG, 32)),
                output.get(JAVA_INT, 40),
                output.get(JAVA_INT, 44),
                output.get(JAVA_INT, 48),
                vector(output, 52),
                output.get(JAVA_INT, 64),
                vector(output, 68),
                output.get(JAVA_INT, 80),
                output.get(JAVA_FLOAT, 84),
                vector(output, 88),
                vector(output, 100),
                vector(output, 112),
                output.get(JAVA_INT, 124),
                output.get(JAVA_INT, 128),
                output.get(JAVA_INT, 132),
                output.get(JAVA_FLOAT, 136),
                output.get(JAVA_INT, 140)
            );
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd bone query", failure);
        }
    }

    private synchronized IkLink ikLink(MemorySegment modelHandle, int boneIndex, int linkIndex) {
        ensureOpen();
        if (boneIndex < 0 || linkIndex < 0) throw new IndexOutOfBoundsException("Bone and IK link indices must not be negative");
        try (var callArena = Arena.ofConfined()) {
            var output = callArena.allocate(IK_LINK_LAYOUT);
            output.set(JAVA_INT, 0, ABI_VERSION);
            output.set(JAVA_INT, 4, Math.toIntExact(IK_LINK_LAYOUT.byteSize()));
            var status = (int) getIkLink.invokeExact(modelHandle, boneIndex, linkIndex, output);
            if (status != 0) throw new IndexOutOfBoundsException("IK link index is outside the model");
            return new IkLink(
                output.get(JAVA_INT, 8),
                output.get(JAVA_INT, 12) != 0,
                vector(output, 16),
                vector(output, 28)
            );
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd IK link query", failure);
        }
    }

    private synchronized MemorySegment createPose(MemorySegment modelHandle) {
        ensureOpen();
        try (var callArena = Arena.ofConfined()) {
            var output = callArena.allocate(ADDRESS);
            var status = (int) createPose.invokeExact(modelHandle, output);
            if (status != 0) throw new IllegalStateException(errorMessage("Unable to create libmmd pose", status));
            var poseHandle = output.get(ADDRESS, 0);
            if (poseHandle.equals(MemorySegment.NULL)) throw new IllegalStateException("libmmd returned a null pose");
            return poseHandle;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd pose creation", failure);
        }
    }

    private synchronized void destroyPose(MemorySegment poseHandle) {
        ensureOpen();
        try {
            destroyPose.invokeExact(poseHandle);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to destroy libmmd pose", failure);
        }
    }

    private synchronized void resetPose(MemorySegment poseHandle) {
        ensureOpen();
        try {
            resetPose.invokeExact(poseHandle);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to reset libmmd pose", failure);
        }
    }

    private synchronized void setPoseTransform(
        MemorySegment poseHandle,
        int boneIndex,
        Vector3 translation,
        Quaternion rotation
    ) {
        ensureOpen();
        try {
            var status = (int) setPoseTransform.invokeExact(
                poseHandle,
                boneIndex,
                translation.x(),
                translation.y(),
                translation.z(),
                rotation.x(),
                rotation.y(),
                rotation.z(),
                rotation.w()
            );
            if (status != 0) throw new IllegalArgumentException("Invalid local pose transform for bone " + boneIndex);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd pose update", failure);
        }
    }

    private synchronized void evaluatePose(MemorySegment poseHandle) {
        ensureOpen();
        try {
            var status = (int) evaluatePose.invokeExact(poseHandle);
            if (status != 0) throw new IllegalStateException("Unable to evaluate libmmd pose: status " + status);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd pose evaluation", failure);
        }
    }

    private synchronized MatrixView poseMatrices(Pose pose, MemorySegment poseHandle) {
        ensureOpen();
        try (var callArena = Arena.ofConfined()) {
            var output = callArena.allocate(MATRIX_VIEW_LAYOUT);
            output.set(JAVA_INT, 0, ABI_VERSION);
            output.set(JAVA_INT, 4, Math.toIntExact(MATRIX_VIEW_LAYOUT.byteSize()));
            var status = (int) getPoseMatrices.invokeExact(poseHandle, output);
            if (status != 0) throw new IllegalStateException("Unable to query libmmd pose matrices: status " + status);
            var floatCount = output.get(JAVA_LONG, 16);
            var boneCount = output.get(JAVA_INT, 24);
            var matrixStride = output.get(JAVA_INT, 28);
            if (floatCount != Math.multiplyExact((long) boneCount, matrixStride) || matrixStride != 16) {
                throw new IllegalStateException("libmmd returned an inconsistent matrix view");
            }
            var data = output.get(ADDRESS, 8).reinterpret(Math.multiplyExact(floatCount, Float.BYTES)).asReadOnly();
            return new MatrixView(pose, data, boneCount, matrixStride);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd matrix query", failure);
        }
    }

    private synchronized MemorySegment createMotion(MemorySegment modelHandle, byte[] bytes) {
        ensureOpen();
        if (bytes.length == 0 || bytes.length > MAX_MOTION_BYTES) {
            throw new IllegalArgumentException("VMD size must be between 1 and " + MAX_MOTION_BYTES + " bytes");
        }
        try (var callArena = Arena.ofConfined()) {
            var data = callArena.allocate(bytes.length);
            data.copyFrom(MemorySegment.ofArray(bytes));
            var output = callArena.allocate(ADDRESS);
            var status = (int) createMotion.invokeExact(modelHandle, data, (long) bytes.length, output);
            if (status != 0) throw new IllegalArgumentException(errorMessage("Unable to load VMD", status));
            var motionHandle = output.get(ADDRESS, 0);
            if (motionHandle.equals(MemorySegment.NULL)) throw new IllegalStateException("libmmd returned a null motion");
            return motionHandle;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd motion loader", failure);
        }
    }

    private synchronized MotionInfo motionInfo(MemorySegment motionHandle) {
        ensureOpen();
        try (var callArena = Arena.ofConfined()) {
            var output = callArena.allocate(MOTION_INFO_LAYOUT);
            output.set(JAVA_INT, 0, ABI_VERSION);
            output.set(JAVA_INT, 4, Math.toIntExact(MOTION_INFO_LAYOUT.byteSize()));
            var status = (int) getMotionInfo.invokeExact(motionHandle, output);
            if (status != 0) throw new IllegalStateException("Unable to query libmmd motion: status " + status);
            return new MotionInfo(
                output.get(JAVA_INT, 8),
                output.get(JAVA_INT, 12),
                output.get(JAVA_INT, 16)
            );
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd motion query", failure);
        }
    }

    private synchronized void applyMotion(MemorySegment motionHandle, float timeSeconds, boolean looping, MemorySegment poseHandle) {
        ensureOpen();
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            throw new IllegalArgumentException("timeSeconds must be finite and non-negative");
        }
        try {
            var status = (int) applyMotion.invokeExact(motionHandle, timeSeconds, looping ? 1 : 0, poseHandle);
            if (status != 0) throw new IllegalArgumentException("Unable to apply libmmd motion: status " + status);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call libmmd motion evaluator", failure);
        }
    }

    private synchronized void destroyMotion(MemorySegment motionHandle) {
        ensureOpen();
        try {
            destroyMotion.invokeExact(motionHandle);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to destroy libmmd motion", failure);
        }
    }

    private static Vector3 vector(MemorySegment segment, long offset) {
        return new Vector3(
            segment.get(JAVA_FLOAT, offset),
            segment.get(JAVA_FLOAT, offset + Float.BYTES),
            segment.get(JAVA_FLOAT, offset + 2L * Float.BYTES)
        );
    }

    private static String nativeString(MemorySegment address, long size) {
        if (size == 0) return "";
        if (size < 0 || size > Integer.MAX_VALUE || address.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("libmmd returned an invalid string view");
        }
        return new String(address.reinterpret(size).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
    }

    String errorMessage(String operation, int status) throws Throwable {
        var message = (MemorySegment) lastError.invokeExact(handle);
        if (message.equals(MemorySegment.NULL)) {
            return operation + ": status " + status;
        }
        return operation + ": " + message.reinterpret(4096).getString(0) + " (status " + status + ")";
    }

    MemorySegment handle() {
        ensureOpen();
        return handle;
    }

    private static MethodHandle downcall(
        Linker linker,
        SymbolLookup symbols,
        String name,
        FunctionDescriptor descriptor
    ) {
        var symbol = symbols.find(name).orElseThrow(() -> new IllegalStateException("Missing native symbol " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    public record ModelInfo(
        int vertexCount,
        int indexCount,
        int textureCount,
        int materialCount,
        int boneCount,
        int morphCount,
        int rigidBodyCount,
        int jointCount,
        long sourceHash
    ) {}

    public record Vector3(float x, float y, float z) {}

    public record Quaternion(float x, float y, float z, float w) {
        public static final Quaternion IDENTITY = new Quaternion(0.0f, 0.0f, 0.0f, 1.0f);
    }

    public record Bone(
        String name,
        String englishName,
        int parentIndex,
        int deformLayer,
        int flags,
        Vector3 position,
        int connectionIndex,
        Vector3 connectionOffset,
        int inheritanceIndex,
        float inheritanceWeight,
        Vector3 fixedAxis,
        Vector3 localXAxis,
        Vector3 localZAxis,
        int externalParentKey,
        int ikTargetIndex,
        int ikIterationCount,
        float ikAngleLimit,
        int ikLinkCount
    ) {}

    public record IkLink(
        int boneIndex,
        boolean limited,
        Vector3 lowerLimit,
        Vector3 upperLimit
    ) {}

    public record MotionInfo(
        int durationFrames,
        int boundBoneCount,
        int boundIkCount
    ) {}

    public static final class MeshView {
        private final Model owner;
        private final MemorySegment vertices;
        private final int vertexCount;
        private final int vertexStride;
        private final MemorySegment indices;
        private final int indexCount;
        private final int indexStride;

        private MeshView(
            Model owner,
            MemorySegment vertices,
            int vertexCount,
            int vertexStride,
            MemorySegment indices,
            int indexCount,
            int indexStride
        ) {
            this.owner = owner;
            this.vertices = vertices;
            this.vertexCount = vertexCount;
            this.vertexStride = vertexStride;
            this.indices = indices;
            this.indexCount = indexCount;
            this.indexStride = indexStride;
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
    }

    public static final class PackView {
        private final Model owner;
        private final MemorySegment data;

        private PackView(Model owner, MemorySegment data) {
            this.owner = owner;
            this.data = data;
        }

        public MemorySegment data() {
            owner.ensureOpen();
            return data;
        }

        public long size() {
            owner.ensureOpen();
            return data.byteSize();
        }
    }

    public static final class RenderMesh {
        private final Model owner;
        private final MemorySegment vertices;
        private final int vertexCount;
        private final int vertexStride;
        private final MemorySegment skinning;
        private final int skinningStride;
        private final MemorySegment indices;
        private final int indexCount;
        private final int indexStride;

        private RenderMesh(
            Model owner,
            MemorySegment vertices,
            int vertexCount,
            int vertexStride,
            MemorySegment skinning,
            int skinningStride,
            MemorySegment indices,
            int indexCount,
            int indexStride
        ) {
            this.owner = owner;
            this.vertices = vertices;
            this.vertexCount = vertexCount;
            this.vertexStride = vertexStride;
            this.skinning = skinning;
            this.skinningStride = skinningStride;
            this.indices = indices;
            this.indexCount = indexCount;
            this.indexStride = indexStride;
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
    }

    public static final class MatrixView {
        private final Pose owner;
        private final MemorySegment data;
        private final int boneCount;
        private final int matrixStride;

        private MatrixView(Pose owner, MemorySegment data, int boneCount, int matrixStride) {
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

    public static final class Pose implements AutoCloseable {
        private final Model owner;
        private MemorySegment handle;

        private Pose(Model owner, MemorySegment handle) {
            this.owner = owner;
            this.handle = handle;
        }

        public void reset() {
            ensureOpen();
            owner.owner.resetPose(handle);
        }

        public void setLocalTransform(int boneIndex, Vector3 translation, Quaternion rotation) {
            ensureOpen();
            owner.owner.setPoseTransform(
                handle,
                boneIndex,
                Objects.requireNonNull(translation, "translation"),
                Objects.requireNonNull(rotation, "rotation")
            );
        }

        public void evaluate() {
            ensureOpen();
            owner.owner.evaluatePose(handle);
        }

        public MatrixView matrices() {
            ensureOpen();
            return owner.owner.poseMatrices(this, handle);
        }

        public boolean isClosed() {
            return handle.equals(MemorySegment.NULL);
        }

        @Override
        public synchronized void close() {
            if (isClosed()) return;
            owner.closePose(handle);
            handle = MemorySegment.NULL;
        }

        private void ensureOpen() {
            if (isClosed()) throw new IllegalStateException("libmmd pose is closed");
            owner.ensureOpen();
        }
    }

    public static final class Motion implements AutoCloseable {
        private final Model owner;
        private MemorySegment handle;
        private int openInstances;

        private Motion(Model owner, MemorySegment handle) {
            this.owner = owner;
            this.handle = handle;
        }

        public MotionInfo info() {
            ensureOpen();
            return owner.owner.motionInfo(handle);
        }

        public void apply(float timeSeconds, boolean looping, Pose pose) {
            ensureOpen();
            Objects.requireNonNull(pose, "pose").ensureOpen();
            if (pose.owner != owner) throw new IllegalArgumentException("Motion and pose must belong to the same model");
            owner.owner.applyMotion(handle, timeSeconds, looping, pose.handle);
        }

        public boolean isClosed() {
            return handle.equals(MemorySegment.NULL);
        }

        @Override
        public synchronized void close() {
            if (isClosed()) return;
            if (openInstances != 0) {
                throw new IllegalStateException("Stop this motion on all model instances before closing it");
            }
            owner.closeMotion(handle);
            handle = MemorySegment.NULL;
        }

        synchronized void retainInstance() {
            ensureOpen();
            openInstances++;
        }

        synchronized void releaseInstance() {
            openInstances--;
        }

        MemorySegment nativeHandle() {
            ensureOpen();
            return handle;
        }

        Model model() {
            ensureOpen();
            return owner;
        }

        private void ensureOpen() {
            if (isClosed()) throw new IllegalStateException("libmmd motion is closed");
            owner.ensureOpen();
        }
    }

    public static final class Model implements AutoCloseable {
        private final NativeRuntime owner;
        private MemorySegment handle;
        private int openPoses;
        private int openMotions;
        private int openInstances;

        private Model(NativeRuntime owner, MemorySegment handle) {
            this.owner = owner;
            this.handle = handle;
        }

        public ModelInfo info() {
            ensureOpen();
            return owner.modelInfo(handle);
        }

        public PackView pack() {
            ensureOpen();
            return owner.packView(this, handle);
        }

        public MeshView mesh() {
            ensureOpen();
            return owner.meshView(this, handle);
        }

        public RenderMesh renderMesh() {
            ensureOpen();
            return owner.renderMesh(this, handle);
        }

        public Bone bone(int boneIndex) {
            ensureOpen();
            return owner.bone(handle, boneIndex);
        }

        public IkLink ikLink(int boneIndex, int linkIndex) {
            ensureOpen();
            return owner.ikLink(handle, boneIndex, linkIndex);
        }

        public synchronized Pose createPose() {
            ensureOpen();
            var pose = new Pose(this, owner.createPose(handle));
            openPoses++;
            return pose;
        }

        public Motion loadMotion(Path path) {
            Objects.requireNonNull(path, "path");
            final byte[] bytes;
            try {
                var size = Files.size(path);
                if (size <= 0 || size > MAX_MOTION_BYTES) {
                    throw new IllegalArgumentException("VMD size must be between 1 and " + MAX_MOTION_BYTES + " bytes");
                }
                bytes = Files.readAllBytes(path);
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to read VMD " + path, failure);
            }
            return loadMotion(bytes);
        }

        public synchronized Motion loadMotion(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            ensureOpen();
            var motion = new Motion(this, owner.createMotion(handle, bytes));
            openMotions++;
            return motion;
        }

        public boolean isClosed() {
            return handle.equals(MemorySegment.NULL);
        }

        @Override
        public synchronized void close() {
            if (isClosed()) {
                return;
            }
            if (openPoses != 0 || openMotions != 0 || openInstances != 0) {
                throw new IllegalStateException("Close all poses, motions, and model instances before closing the model");
            }
            owner.closeModel(handle);
            handle = MemorySegment.NULL;
        }

        private synchronized void closePose(MemorySegment poseHandle) {
            ensureOpen();
            owner.destroyPose(poseHandle);
            openPoses--;
        }

        private synchronized void closeMotion(MemorySegment motionHandle) {
            ensureOpen();
            owner.destroyMotion(motionHandle);
            openMotions--;
        }

        synchronized void retainInstance() {
            ensureOpen();
            openInstances++;
        }

        synchronized void releaseInstance() {
            openInstances--;
        }

        MemorySegment nativeHandle() {
            ensureOpen();
            return handle;
        }

        NativeRuntime runtime() {
            ensureOpen();
            return owner;
        }

        private void ensureOpen() {
            if (isClosed()) {
                throw new IllegalStateException("libmmd model is closed");
            }
        }
    }
}

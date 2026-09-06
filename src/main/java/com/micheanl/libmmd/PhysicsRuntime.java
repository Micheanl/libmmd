package com.micheanl.libmmd;

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

public final class PhysicsRuntime {
    public static final int ENABLE_CCD = 0x00000001;
    public static final int ENHANCED_DETERMINISM = 0x00000002;
    public static final int BODY_ENABLE_CCD = 0x00000001;
    public static final int BODY_DISABLE_GRAVITY = 0x00000002;
    public static final int JOINT_COLLISION = 0x00000001;

    private static final MemoryLayout TRANSFORM = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        MemoryLayout.sequenceLayout(4, JAVA_FLOAT)
    );
    private static final MemoryLayout WORLD_CONFIG = MemoryLayout.structLayout(
        JAVA_INT,
        JAVA_INT,
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        JAVA_FLOAT,
        JAVA_FLOAT,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT
    );
    private static final MemoryLayout DEVICE_INFO = MemoryLayout.structLayout(
        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT
    );
    private static final MemoryLayout BODY_DESC = MemoryLayout.structLayout(
        JAVA_INT,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT,
        TRANSFORM,
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        JAVA_FLOAT,
        JAVA_FLOAT,
        JAVA_FLOAT,
        JAVA_FLOAT,
        JAVA_FLOAT,
        JAVA_FLOAT,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT,
        JAVA_INT
    );
    private static final MemoryLayout BODY_STATE = MemoryLayout.structLayout(
        JAVA_INT,
        JAVA_INT,
        TRANSFORM,
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        JAVA_INT,
        JAVA_INT
    );
    private static final MemoryLayout STEP_INFO = MemoryLayout.structLayout(
        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT
    );
    private static final MemoryLayout RAYCAST_HIT = MemoryLayout.structLayout(
        JAVA_INT,
        JAVA_INT,
        ADDRESS,
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        JAVA_FLOAT,
        JAVA_INT
    );
    private static final MemoryLayout JOINT_DESC = MemoryLayout.structLayout(
        JAVA_INT,
        JAVA_INT,
        ADDRESS,
        ADDRESS,
        TRANSFORM,
        TRANSFORM,
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        MemoryLayout.sequenceLayout(3, JAVA_FLOAT),
        JAVA_FLOAT,
        JAVA_FLOAT,
        JAVA_FLOAT,
        JAVA_FLOAT,
        JAVA_INT,
        MemoryLayout.paddingLayout(4)
    );

    private final NativeRuntime owner;
    private final MethodHandle createWorld;
    private final MethodHandle destroyWorld;
    private final MethodHandle getDeviceInfo;
    private final MethodHandle stepWorld;
    private final MethodHandle raycast;
    private final MethodHandle createBody;
    private final MethodHandle destroyBody;
    private final MethodHandle getBodyState;
    private final MethodHandle setBodyTransform;
    private final MethodHandle setKinematicTarget;
    private final MethodHandle setVelocity;
    private final MethodHandle addImpulse;
    private final MethodHandle createJoint;
    private final MethodHandle destroyJoint;
    private final Set<World> worlds = new LinkedHashSet<>();

    PhysicsRuntime(NativeRuntime owner, Linker linker, SymbolLookup symbols) {
        this.owner = owner;
        createWorld = downcall(linker, symbols, "libmmd_physics_world_create", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
        destroyWorld = downcallVoid(linker, symbols, "libmmd_physics_world_destroy", ADDRESS);
        getDeviceInfo = downcall(linker, symbols, "libmmd_physics_world_get_device_info", JAVA_INT, ADDRESS, ADDRESS);
        stepWorld = downcall(linker, symbols, "libmmd_physics_world_step", JAVA_INT, ADDRESS, JAVA_FLOAT, ADDRESS);
        raycast = downcall(
            linker,
            symbols,
            "libmmd_physics_world_raycast",
            JAVA_INT,
            ADDRESS,
            ADDRESS,
            ADDRESS,
            JAVA_FLOAT,
            JAVA_INT,
            ADDRESS
        );
        createBody = downcall(linker, symbols, "libmmd_physics_body_create", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
        destroyBody = downcallVoid(linker, symbols, "libmmd_physics_body_destroy", ADDRESS);
        getBodyState = downcall(
            linker,
            symbols,
            "libmmd_physics_body_get_state",
            JAVA_INT,
            ADDRESS,
            JAVA_FLOAT,
            ADDRESS
        );
        setBodyTransform = downcall(
            linker,
            symbols,
            "libmmd_physics_body_set_transform",
            JAVA_INT,
            ADDRESS,
            ADDRESS,
            JAVA_INT
        );
        setKinematicTarget = downcall(
            linker,
            symbols,
            "libmmd_physics_body_set_kinematic_target",
            JAVA_INT,
            ADDRESS,
            ADDRESS
        );
        setVelocity = downcall(
            linker,
            symbols,
            "libmmd_physics_body_set_velocity",
            JAVA_INT,
            ADDRESS,
            ADDRESS,
            ADDRESS
        );
        addImpulse = downcall(linker, symbols, "libmmd_physics_body_add_impulse", JAVA_INT, ADDRESS, ADDRESS);
        createJoint = downcall(
            linker,
            symbols,
            "libmmd_physics_joint_create_d6",
            JAVA_INT,
            ADDRESS,
            ADDRESS,
            ADDRESS
        );
        destroyJoint = downcallVoid(linker, symbols, "libmmd_physics_joint_destroy", ADDRESS);
    }

    public synchronized World createWorld(WorldConfig config) {
        owner.ensureOpen();
        Objects.requireNonNull(config, "config");
        try (var arena = Arena.ofConfined()) {
            var nativeConfig = arena.allocate(WORLD_CONFIG);
            header(nativeConfig, WORLD_CONFIG);
            writeVector(nativeConfig, 8, config.gravity());
            nativeConfig.set(JAVA_FLOAT, 20, config.fixedStepSeconds());
            nativeConfig.set(JAVA_FLOAT, 24, config.maximumFrameSeconds());
            nativeConfig.set(JAVA_INT, 28, config.maximumSubsteps());
            nativeConfig.set(JAVA_INT, 32, config.workerThreads());
            nativeConfig.set(JAVA_INT, 36, config.preferredProcessor().value);
            nativeConfig.set(JAVA_INT, 40, config.allowCpuFallback() ? 1 : 0);
            nativeConfig.set(JAVA_INT, 44, config.flags());
            var output = arena.allocate(ADDRESS);
            var status = (int) createWorld.invokeExact(owner.handle(), nativeConfig, output);
            check(status, "Unable to create native physics world");
            var handle = output.get(ADDRESS, 0);
            if (handle.equals(MemorySegment.NULL)) throw new IllegalStateException("libmmd returned a null physics world");
            var world = new World(this, handle);
            worlds.add(world);
            return world;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to call native physics world creation", failure);
        }
    }

    synchronized void closeWorlds() {
        for (var world : List.copyOf(worlds)) world.close();
    }

    private RuntimeException failure(String operation, int status) {
        final String message;
        try {
            message = owner.errorMessage(operation, status);
        } catch (Throwable ignored) {
            return new IllegalStateException(operation + ": status " + status);
        }
        return switch (status) {
            case 1 -> new IllegalArgumentException(message);
            case 6 -> new UnsupportedOperationException(message);
            default -> new IllegalStateException(message);
        };
    }

    private void check(int status, String operation) {
        if (status != 0) throw failure(operation, status);
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

    private static MethodHandle downcallVoid(
        Linker linker,
        SymbolLookup symbols,
        String name,
        MemoryLayout... arguments
    ) {
        var symbol = symbols.find(name).orElseThrow(() -> new IllegalStateException("Missing native symbol " + name));
        return linker.downcallHandle(symbol, FunctionDescriptor.ofVoid(arguments));
    }

    private static void header(MemorySegment segment, MemoryLayout layout) {
        segment.set(JAVA_INT, 0, NativeRuntime.ABI_VERSION);
        segment.set(JAVA_INT, 4, Math.toIntExact(layout.byteSize()));
    }

    private static void writeVector(MemorySegment segment, long offset, NativeRuntime.Vector3 value) {
        segment.set(JAVA_FLOAT, offset, value.x());
        segment.set(JAVA_FLOAT, offset + 4, value.y());
        segment.set(JAVA_FLOAT, offset + 8, value.z());
    }

    private static NativeRuntime.Vector3 readVector(MemorySegment segment, long offset) {
        return new NativeRuntime.Vector3(
            segment.get(JAVA_FLOAT, offset),
            segment.get(JAVA_FLOAT, offset + 4),
            segment.get(JAVA_FLOAT, offset + 8)
        );
    }

    private static void writeTransform(MemorySegment segment, long offset, Transform value) {
        writeVector(segment, offset, value.position());
        var rotation = value.rotation();
        segment.set(JAVA_FLOAT, offset + 12, rotation.x());
        segment.set(JAVA_FLOAT, offset + 16, rotation.y());
        segment.set(JAVA_FLOAT, offset + 20, rotation.z());
        segment.set(JAVA_FLOAT, offset + 24, rotation.w());
    }

    private static Transform readTransform(MemorySegment segment, long offset) {
        return new Transform(
            readVector(segment, offset),
            new NativeRuntime.Quaternion(
                segment.get(JAVA_FLOAT, offset + 12),
                segment.get(JAVA_FLOAT, offset + 16),
                segment.get(JAVA_FLOAT, offset + 20),
                segment.get(JAVA_FLOAT, offset + 24)
            )
        );
    }

    public enum Processor {
        AUTO(0),
        CPU(1),
        CUDA(2);

        private final int value;

        Processor(int value) {
            this.value = value;
        }

        private static Processor from(int value) {
            return switch (value) {
                case 0 -> AUTO;
                case 1 -> CPU;
                case 2 -> CUDA;
                default -> throw new IllegalStateException("Unknown native physics processor " + value);
            };
        }
    }

    public enum BodyType {
        STATIC(0),
        DYNAMIC(1),
        KINEMATIC(2);

        private final int value;

        BodyType(int value) {
            this.value = value;
        }
    }

    public enum Shape {
        SPHERE(0),
        BOX(1),
        CAPSULE(2);

        private final int value;

        Shape(int value) {
            this.value = value;
        }
    }

    public record Transform(NativeRuntime.Vector3 position, NativeRuntime.Quaternion rotation) {
        public static final Transform IDENTITY = new Transform(
            new NativeRuntime.Vector3(0.0f, 0.0f, 0.0f),
            NativeRuntime.Quaternion.IDENTITY
        );

        public Transform {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(rotation, "rotation");
        }
    }

    public record WorldConfig(
        NativeRuntime.Vector3 gravity,
        float fixedStepSeconds,
        float maximumFrameSeconds,
        int maximumSubsteps,
        int workerThreads,
        Processor preferredProcessor,
        boolean allowCpuFallback,
        int flags
    ) {
        public WorldConfig {
            Objects.requireNonNull(gravity, "gravity");
            Objects.requireNonNull(preferredProcessor, "preferredProcessor");
        }

        public static WorldConfig defaults() {
            return new WorldConfig(
                new NativeRuntime.Vector3(0.0f, -9.81f, 0.0f),
                1.0f / 120.0f,
                0.25f,
                8,
                Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)),
                Processor.AUTO,
                true,
                ENABLE_CCD | ENHANCED_DETERMINISM
            );
        }
    }

    public record DeviceInfo(
        Processor requestedProcessor,
        Processor activeProcessor,
        boolean gpuAvailable,
        int bodyCount,
        int jointCount
    ) {}

    public record StepInfo(int substeps, boolean droppedTime, float interpolationAlpha, float simulatedSeconds) {}

    public record BodyConfig(
        BodyType type,
        Shape shape,
        Transform transform,
        NativeRuntime.Vector3 dimensions,
        float mass,
        float staticFriction,
        float dynamicFriction,
        float restitution,
        float linearDamping,
        float angularDamping,
        int collisionGroupBits,
        int collisionMask,
        int solverPositionIterations,
        int solverVelocityIterations,
        int flags
    ) {
        public BodyConfig {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(dimensions, "dimensions");
        }
    }

    public record BodyState(
        Transform transform,
        NativeRuntime.Vector3 linearVelocity,
        NativeRuntime.Vector3 angularVelocity,
        boolean sleeping
    ) {}

    public record RaycastHit(Body body, NativeRuntime.Vector3 position, NativeRuntime.Vector3 normal, float distance) {}

    public record JointConfig(
        Body bodyA,
        Body bodyB,
        Transform localFrameA,
        Transform localFrameB,
        NativeRuntime.Vector3 linearLower,
        NativeRuntime.Vector3 linearUpper,
        NativeRuntime.Vector3 angularLower,
        NativeRuntime.Vector3 angularUpper,
        float stiffness,
        float damping,
        float breakForce,
        float breakTorque,
        int flags
    ) {
        public JointConfig {
            if (bodyA == null && bodyB == null) throw new IllegalArgumentException("A joint needs at least one body");
            Objects.requireNonNull(localFrameA, "localFrameA");
            Objects.requireNonNull(localFrameB, "localFrameB");
            Objects.requireNonNull(linearLower, "linearLower");
            Objects.requireNonNull(linearUpper, "linearUpper");
            Objects.requireNonNull(angularLower, "angularLower");
            Objects.requireNonNull(angularUpper, "angularUpper");
        }
    }

    public static final class World implements AutoCloseable {
        private final PhysicsRuntime runtime;
        private final Set<Body> bodies = new LinkedHashSet<>();
        private final Set<Joint> joints = new LinkedHashSet<>();
        private MemorySegment handle;

        private World(PhysicsRuntime runtime, MemorySegment handle) {
            this.runtime = runtime;
            this.handle = handle;
        }

        public synchronized DeviceInfo deviceInfo() {
            ensureOpen();
            try (var arena = Arena.ofConfined()) {
                var output = arena.allocate(DEVICE_INFO);
                header(output, DEVICE_INFO);
                var status = (int) runtime.getDeviceInfo.invokeExact(handle, output);
                runtime.check(status, "Unable to query native physics device");
                return new DeviceInfo(
                    Processor.from(output.get(JAVA_INT, 8)),
                    Processor.from(output.get(JAVA_INT, 12)),
                    output.get(JAVA_INT, 16) != 0,
                    output.get(JAVA_INT, 20),
                    output.get(JAVA_INT, 24)
                );
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to call native physics device query", failure);
            }
        }

        public synchronized StepInfo step(float deltaSeconds) {
            ensureOpen();
            try (var arena = Arena.ofConfined()) {
                var output = arena.allocate(STEP_INFO);
                header(output, STEP_INFO);
                var status = (int) runtime.stepWorld.invokeExact(handle, deltaSeconds, output);
                runtime.check(status, "Unable to step native physics world");
                return new StepInfo(
                    output.get(JAVA_INT, 8),
                    output.get(JAVA_INT, 12) != 0,
                    output.get(JAVA_FLOAT, 16),
                    output.get(JAVA_FLOAT, 20)
                );
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to call native physics step", failure);
            }
        }

        public synchronized Body createBody(BodyConfig config) {
            ensureOpen();
            Objects.requireNonNull(config, "config");
            try (var arena = Arena.ofConfined()) {
                var desc = arena.allocate(BODY_DESC);
                header(desc, BODY_DESC);
                desc.set(JAVA_INT, 8, config.type().value);
                desc.set(JAVA_INT, 12, config.shape().value);
                writeTransform(desc, 16, config.transform());
                writeVector(desc, 44, config.dimensions());
                desc.set(JAVA_FLOAT, 56, config.mass());
                desc.set(JAVA_FLOAT, 60, config.staticFriction());
                desc.set(JAVA_FLOAT, 64, config.dynamicFriction());
                desc.set(JAVA_FLOAT, 68, config.restitution());
                desc.set(JAVA_FLOAT, 72, config.linearDamping());
                desc.set(JAVA_FLOAT, 76, config.angularDamping());
                desc.set(JAVA_INT, 80, config.collisionGroupBits());
                desc.set(JAVA_INT, 84, config.collisionMask());
                desc.set(JAVA_INT, 88, config.solverPositionIterations());
                desc.set(JAVA_INT, 92, config.solverVelocityIterations());
                desc.set(JAVA_INT, 96, config.flags());
                var output = arena.allocate(ADDRESS);
                var status = (int) runtime.createBody.invokeExact(handle, desc, output);
                runtime.check(status, "Unable to create native physics body");
                var bodyHandle = output.get(ADDRESS, 0);
                if (bodyHandle.equals(MemorySegment.NULL)) throw new IllegalStateException("libmmd returned a null body");
                var body = new Body(this, bodyHandle);
                bodies.add(body);
                return body;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to call native physics body creation", failure);
            }
        }

        public synchronized Joint createJoint(JointConfig config) {
            ensureOpen();
            Objects.requireNonNull(config, "config");
            if ((config.bodyA() != null && config.bodyA().world != this) ||
                (config.bodyB() != null && config.bodyB().world != this)) {
                throw new IllegalArgumentException("Joint bodies must belong to this world");
            }
            try (var arena = Arena.ofConfined()) {
                var desc = arena.allocate(JOINT_DESC);
                header(desc, JOINT_DESC);
                desc.set(ADDRESS, 8, config.bodyA() == null ? MemorySegment.NULL : config.bodyA().handle);
                desc.set(ADDRESS, 16, config.bodyB() == null ? MemorySegment.NULL : config.bodyB().handle);
                writeTransform(desc, 24, config.localFrameA());
                writeTransform(desc, 52, config.localFrameB());
                writeVector(desc, 80, config.linearLower());
                writeVector(desc, 92, config.linearUpper());
                writeVector(desc, 104, config.angularLower());
                writeVector(desc, 116, config.angularUpper());
                desc.set(JAVA_FLOAT, 128, config.stiffness());
                desc.set(JAVA_FLOAT, 132, config.damping());
                desc.set(JAVA_FLOAT, 136, config.breakForce());
                desc.set(JAVA_FLOAT, 140, config.breakTorque());
                desc.set(JAVA_INT, 144, config.flags());
                var output = arena.allocate(ADDRESS);
                var status = (int) runtime.createJoint.invokeExact(handle, desc, output);
                runtime.check(status, "Unable to create native D6 joint");
                var jointHandle = output.get(ADDRESS, 0);
                if (jointHandle.equals(MemorySegment.NULL)) throw new IllegalStateException("libmmd returned a null joint");
                var joint = new Joint(this, jointHandle, config.bodyA(), config.bodyB());
                joints.add(joint);
                return joint;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to call native D6 joint creation", failure);
            }
        }

        public synchronized RaycastHit raycast(
            NativeRuntime.Vector3 origin,
            NativeRuntime.Vector3 direction,
            float maximumDistance,
            int collisionMask
        ) {
            ensureOpen();
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(direction, "direction");
            try (var arena = Arena.ofConfined()) {
                var nativeOrigin = arena.allocate(3L * Float.BYTES, Float.BYTES);
                var nativeDirection = arena.allocate(3L * Float.BYTES, Float.BYTES);
                writeVector(nativeOrigin, 0, origin);
                writeVector(nativeDirection, 0, direction);
                var output = arena.allocate(RAYCAST_HIT);
                header(output, RAYCAST_HIT);
                var status = (int) runtime.raycast.invokeExact(
                    handle,
                    nativeOrigin,
                    nativeDirection,
                    maximumDistance,
                    collisionMask,
                    output
                );
                runtime.check(status, "Unable to raycast native physics world");
                var bodyHandle = output.get(ADDRESS, 8);
                if (bodyHandle.equals(MemorySegment.NULL)) return null;
                var body = bodies.stream()
                    .filter(candidate -> candidate.handle.equals(bodyHandle))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Native raycast returned an unknown body"));
                return new RaycastHit(
                    body,
                    readVector(output, 16),
                    readVector(output, 28),
                    output.get(JAVA_FLOAT, 40)
                );
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to call native physics raycast", failure);
            }
        }

        public boolean isClosed() {
            return handle.equals(MemorySegment.NULL);
        }

        @Override
        public synchronized void close() {
            if (isClosed()) return;
            for (var joint : List.copyOf(joints)) joint.close();
            for (var body : List.copyOf(bodies)) body.close();
            try {
                runtime.destroyWorld.invokeExact(handle);
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to destroy native physics world", failure);
            } finally {
                handle = MemorySegment.NULL;
                runtime.worlds.remove(this);
            }
        }

        private void ensureOpen() {
            runtime.owner.ensureOpen();
            if (isClosed()) throw new IllegalStateException("Native physics world is closed");
        }
    }

    public static final class Body implements AutoCloseable {
        private final World world;
        private MemorySegment handle;

        private Body(World world, MemorySegment handle) {
            this.world = world;
            this.handle = handle;
        }

        public BodyState state(float interpolationAlpha) {
            ensureOpen();
            try (var arena = Arena.ofConfined()) {
                var output = arena.allocate(BODY_STATE);
                header(output, BODY_STATE);
                var status = (int) world.runtime.getBodyState.invokeExact(handle, interpolationAlpha, output);
                world.runtime.check(status, "Unable to query native physics body");
                return new BodyState(
                    readTransform(output, 8),
                    readVector(output, 36),
                    readVector(output, 48),
                    output.get(JAVA_INT, 60) != 0
                );
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to call native physics body query", failure);
            }
        }

        public void setTransform(Transform transform, boolean resetVelocity) {
            ensureOpen();
            Objects.requireNonNull(transform, "transform");
            try (var arena = Arena.ofConfined()) {
                var value = arena.allocate(TRANSFORM);
                writeTransform(value, 0, transform);
                var status = (int) world.runtime.setBodyTransform.invokeExact(
                    handle,
                    value,
                    resetVelocity ? 1 : 0
                );
                world.runtime.check(status, "Unable to set native physics transform");
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to call native physics transform update", failure);
            }
        }

        public void setKinematicTarget(Transform transform) {
            ensureOpen();
            Objects.requireNonNull(transform, "transform");
            try (var arena = Arena.ofConfined()) {
                var value = arena.allocate(TRANSFORM);
                writeTransform(value, 0, transform);
                var status = (int) world.runtime.setKinematicTarget.invokeExact(handle, value);
                world.runtime.check(status, "Unable to set native kinematic target");
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to call native kinematic update", failure);
            }
        }

        public void setVelocity(NativeRuntime.Vector3 linear, NativeRuntime.Vector3 angular) {
            ensureOpen();
            Objects.requireNonNull(linear, "linear");
            Objects.requireNonNull(angular, "angular");
            try (var arena = Arena.ofConfined()) {
                var nativeLinear = arena.allocate(3L * Float.BYTES, Float.BYTES);
                var nativeAngular = arena.allocate(3L * Float.BYTES, Float.BYTES);
                writeVector(nativeLinear, 0, linear);
                writeVector(nativeAngular, 0, angular);
                var status = (int) world.runtime.setVelocity.invokeExact(handle, nativeLinear, nativeAngular);
                world.runtime.check(status, "Unable to set native physics velocity");
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to call native velocity update", failure);
            }
        }

        public void addImpulse(NativeRuntime.Vector3 impulse) {
            ensureOpen();
            Objects.requireNonNull(impulse, "impulse");
            try (var arena = Arena.ofConfined()) {
                var value = arena.allocate(3L * Float.BYTES, Float.BYTES);
                writeVector(value, 0, impulse);
                var status = (int) world.runtime.addImpulse.invokeExact(handle, value);
                world.runtime.check(status, "Unable to add native physics impulse");
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to call native impulse", failure);
            }
        }

        public boolean isClosed() {
            return handle.equals(MemorySegment.NULL);
        }

        @Override
        public synchronized void close() {
            if (isClosed()) return;
            for (var joint : List.copyOf(world.joints)) {
                if (joint.bodyA == this || joint.bodyB == this) joint.close();
            }
            try {
                world.runtime.destroyBody.invokeExact(handle);
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to destroy native physics body", failure);
            } finally {
                handle = MemorySegment.NULL;
                world.bodies.remove(this);
            }
        }

        private void ensureOpen() {
            world.ensureOpen();
            if (isClosed()) throw new IllegalStateException("Native physics body is closed");
        }
    }

    public static final class Joint implements AutoCloseable {
        private final World world;
        private final Body bodyA;
        private final Body bodyB;
        private MemorySegment handle;

        private Joint(World world, MemorySegment handle, Body bodyA, Body bodyB) {
            this.world = world;
            this.handle = handle;
            this.bodyA = bodyA;
            this.bodyB = bodyB;
        }

        public boolean isClosed() {
            return handle.equals(MemorySegment.NULL);
        }

        @Override
        public synchronized void close() {
            if (isClosed()) return;
            try {
                world.runtime.destroyJoint.invokeExact(handle);
            } catch (Throwable failure) {
                throw new IllegalStateException("Unable to destroy native physics joint", failure);
            } finally {
                handle = MemorySegment.NULL;
                world.joints.remove(this);
            }
        }
    }
}

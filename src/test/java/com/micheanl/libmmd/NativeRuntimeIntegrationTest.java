package com.micheanl.libmmd;

import com.micheanl.libmmd.runtime.NativeRuntime;
import com.micheanl.libmmd.runtime.PhysicsRuntime;
import com.micheanl.libmmd.runtime.SceneRuntime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipInputStream;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class NativeRuntimeIntegrationTest {
    @Test
    void simulatesModelPhysicsThroughFfm(@TempDir Path directory) throws IOException {
        var pmx = directory.resolve("dynamic-sphere.pmx");
        Files.write(pmx, dynamicSpherePmx());
        try (var runtime = NativeRuntime.open(1);
             var model = runtime.loadPmx(pmx);
             var animatedScene = runtime.scenes().create();
             var physicalScene = runtime.scenes().create(0.25f, SceneRuntime.PhysicsConfig.defaults());
             var animatedInstance = animatedScene.createInstance(model);
             var physicalInstance = physicalScene.createInstance(model)) {
            assertEquals(1, model.info().rigidBodyCount());
            assertThrows(
                IllegalArgumentException.class,
                () -> runtime.scenes().create(0.0f, SceneRuntime.PhysicsConfig.defaults())
            );
            assertThrows(
                IllegalArgumentException.class,
                () -> runtime.scenes().create(Float.NaN, SceneRuntime.PhysicsConfig.defaults())
            );
            assertThrows(NullPointerException.class, () -> runtime.scenes().create(0.25f, null));
            var matrices = physicalInstance.matrices();
            var packet = physicalInstance.renderPacket();
            var restMatrices = matrices.data().toArray(JAVA_FLOAT);
            assertEquals(1, matrices.boneCount());
            assertEquals(16, matrices.matrixStride());
            assertEquals(3, packet.vertexCount());
            assertArrayEquals(restMatrices, animatedInstance.matrices().data().toArray(JAVA_FLOAT), 0.0001f);

            for (var frame = 0; frame < 30; frame++) {
                animatedScene.update(1.0f / 60.0f);
                physicalScene.update(1.0f / 60.0f);
            }
            var fallenMatrices = matrices.data().toArray(JAVA_FLOAT);
            assertTrue(fallenMatrices[13] < -10.0f && fallenMatrices[13] > -20.0f);
            assertEquals(0.0f, fallenMatrices[12], 0.0001f);
            assertEquals(0.0f, fallenMatrices[14], 0.0001f);
            assertArrayEquals(fallenMatrices, packet.matrices().toArray(JAVA_FLOAT), 0.0001f);
            assertArrayEquals(restMatrices, animatedInstance.matrices().data().toArray(JAVA_FLOAT), 0.0001f);

            physicalInstance.resetPhysics();
            assertArrayEquals(restMatrices, matrices.data().toArray(JAVA_FLOAT), 0.0001f);
            assertArrayEquals(restMatrices, physicalInstance.renderPacket().matrices().toArray(JAVA_FLOAT), 0.0001f);
            physicalInstance.setVisible(false);
            for (var frame = 0; frame < 30; frame++) physicalScene.update(1.0f / 60.0f);
            assertFalse(physicalInstance.state().visible());
            var hiddenPacket = physicalInstance.renderPacket();
            assertFalse(hiddenPacket.visible());
            assertTrue(matrices.data().getAtIndex(JAVA_FLOAT, 13) < -10.0f);
            assertArrayEquals(matrices.data().toArray(JAVA_FLOAT), hiddenPacket.matrices().toArray(JAVA_FLOAT), 0.0001f);

            physicalScene.close();
            assertTrue(physicalInstance.isClosed());
            assertThrows(IllegalStateException.class, physicalInstance::resetPhysics);
            assertThrows(IllegalStateException.class, matrices::data);
            assertThrows(IllegalStateException.class, packet::matrices);
            assertThrows(IllegalStateException.class, hiddenPacket::vertices);
        }
    }

    @Test
    void rejectsInvalidModelPhysicsConfiguration() {
        var defaults = SceneRuntime.PhysicsConfig.defaults();
        assertEquals(new NativeRuntime.Vector3(0.0f, -9.81f, 0.0f), defaults.gravity());
        assertEquals(0.08f, defaults.metersPerUnit());
        assertEquals(1.0f / 120.0f, defaults.fixedStepSeconds());
        assertEquals(8, defaults.maximumSubsteps());
        assertEquals(10, defaults.solverIterations());
        assertThrows(NullPointerException.class, () -> new SceneRuntime.PhysicsConfig(null, 0.08f, 0.01f, 8, 10));
        for (var invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            for (var gravity : new NativeRuntime.Vector3[] {
                new NativeRuntime.Vector3(invalid, 0.0f, 0.0f),
                new NativeRuntime.Vector3(0.0f, invalid, 0.0f),
                new NativeRuntime.Vector3(0.0f, 0.0f, invalid)
            }) {
                assertThrows(
                    IllegalArgumentException.class,
                    () -> new SceneRuntime.PhysicsConfig(gravity, 0.08f, 0.01f, 8, 10)
                );
            }
        }
        for (var invalid : new float[] {0.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(
                IllegalArgumentException.class,
                () -> new SceneRuntime.PhysicsConfig(defaults.gravity(), invalid, 0.01f, 8, 10)
            );
            assertThrows(
                IllegalArgumentException.class,
                () -> new SceneRuntime.PhysicsConfig(defaults.gravity(), 0.08f, invalid, 8, 10)
            );
        }
        for (var invalid : new int[] {0, -1}) {
            assertThrows(
                IllegalArgumentException.class,
                () -> new SceneRuntime.PhysicsConfig(defaults.gravity(), 0.08f, 0.01f, invalid, 10)
            );
            assertThrows(
                IllegalArgumentException.class,
                () -> new SceneRuntime.PhysicsConfig(defaults.gravity(), 0.08f, 0.01f, 8, invalid)
            );
        }
    }

    @Test
    void simulatesPhysicsThroughFfm() {
        try (var runtime = NativeRuntime.open(2);
             var world = runtime.physics().createWorld(PhysicsRuntime.WorldConfig.defaults())) {
            var identity = NativeRuntime.Quaternion.IDENTITY;
            var ground = world.createBody(new PhysicsRuntime.BodyConfig(
                PhysicsRuntime.BodyType.STATIC,
                PhysicsRuntime.Shape.BOX,
                new PhysicsRuntime.Transform(new NativeRuntime.Vector3(0.0f, -0.5f, 0.0f), identity),
                new NativeRuntime.Vector3(10.0f, 0.5f, 10.0f),
                1.0f,
                0.6f,
                0.5f,
                0.0f,
                0.0f,
                0.0f,
                1,
                -1,
                8,
                2,
                0
            ));
            var sphere = world.createBody(new PhysicsRuntime.BodyConfig(
                PhysicsRuntime.BodyType.DYNAMIC,
                PhysicsRuntime.Shape.SPHERE,
                new PhysicsRuntime.Transform(new NativeRuntime.Vector3(0.0f, 3.0f, 0.0f), identity),
                new NativeRuntime.Vector3(0.5f, 0.0f, 0.0f),
                1.0f,
                0.6f,
                0.5f,
                0.0f,
                0.05f,
                0.1f,
                1,
                -1,
                8,
                2,
                PhysicsRuntime.BODY_ENABLE_CCD
            ));
            var device = world.deviceInfo();
            assertTrue(device.activeProcessor() == PhysicsRuntime.Processor.CPU);
            assertTrue(device.bodyCount() == 2);
            PhysicsRuntime.StepInfo step = null;
            for (var frame = 0; frame < 180; frame++) step = world.step(1.0f / 60.0f);
            assertTrue(step != null && step.substeps() == 2);
            var state = sphere.state(step.interpolationAlpha());
            assertTrue(state.transform().position().y() > 0.45f);
            assertTrue(state.transform().position().y() < 0.6f);
            var hit = world.raycast(
                new NativeRuntime.Vector3(0.0f, 5.0f, 0.0f),
                new NativeRuntime.Vector3(0.0f, -1.0f, 0.0f),
                10.0f,
                -1
            );
            assertTrue(hit != null && hit.body() == sphere);
            ground.close();
            assertTrue(world.deviceInfo().bodyCount() == 1);
        }

        try (var runtime = NativeRuntime.open(1)) {
            var defaults = PhysicsRuntime.WorldConfig.defaults();
            var cuda = new PhysicsRuntime.WorldConfig(
                defaults.gravity(),
                defaults.fixedStepSeconds(),
                defaults.maximumFrameSeconds(),
                defaults.maximumSubsteps(),
                defaults.workerThreads(),
                PhysicsRuntime.Processor.CUDA,
                false,
                defaults.flags()
            );
            assertThrows(UnsupportedOperationException.class, () -> runtime.physics().createWorld(cuda));
        }
    }

    @Test
    void loadsCompiledModelThroughFfm() {
        var packValue = System.getenv("LIBMMD_TEST_PACK");
        assumeTrue(packValue != null);
        var pack = Path.of(packValue);
        assumeTrue(Files.isRegularFile(pack));

        try (var runtime = NativeRuntime.open(2)) {
            assertTrue(runtime.workerThreads() == 2);
            var model = runtime.loadPack(pack);
            var info = model.info();
            var packView = model.pack();
            assertTrue(packView.size() > 72);
            assertTrue(info.vertexCount() > 0);
            assertTrue(info.indexCount() > 0);
            assertTrue(info.materialCount() > 0);
            assertTrue(info.boneCount() > 0);
            assertTrue(info.morphCount() > 0);
            assertTrue(info.rigidBodyCount() > 0);
            assertTrue(info.jointCount() > 0);
            for (var textureIndex = 0; textureIndex < info.textureCount(); textureIndex++) {
                assertTrue(model.texture(textureIndex) != null);
            }
            var coveredIndices = 0;
            for (var materialIndex = 0; materialIndex < info.materialCount(); materialIndex++) {
                var material = model.material(materialIndex);
                assertTrue(material.firstIndex() == coveredIndices);
                assertTrue(material.indexCount() >= 0 && material.indexCount() % 3 == 0);
                coveredIndices += material.indexCount();
            }
            assertTrue(coveredIndices == info.indexCount());
            var mesh = model.mesh();
            assertTrue(mesh.vertexCount() == info.vertexCount());
            assertTrue(mesh.vertexStride() == 68);
            assertTrue(mesh.vertices().byteSize() == (long) mesh.vertexCount() * mesh.vertexStride());
            assertTrue(mesh.indexCount() == info.indexCount());
            assertTrue(mesh.indexStride() == Integer.BYTES);
            assertTrue(mesh.indices().byteSize() == (long) mesh.indexCount() * mesh.indexStride());
            var renderMesh = model.renderMesh();
            assertTrue(renderMesh.vertexCount() == info.vertexCount());
            assertTrue(renderMesh.vertexStride() == 28);
            assertTrue(renderMesh.vertices().byteSize() == (long) renderMesh.vertexCount() * renderMesh.vertexStride());
            assertTrue(renderMesh.skinningStride() == 32);
            assertTrue(renderMesh.skinning().byteSize() == (long) renderMesh.vertexCount() * renderMesh.skinningStride());
            assertTrue(renderMesh.indexCount() == info.indexCount());
            assertTrue(renderMesh.indexStride() == Short.BYTES || renderMesh.indexStride() == Integer.BYTES);
            var firstBone = model.bone(0);
            assertFalse(firstBone.name().isEmpty());
            assertTrue(firstBone.parentIndex() >= -1 && firstBone.parentIndex() < info.boneCount());
            var foundIk = false;
            for (var boneIndex = 0; boneIndex < info.boneCount(); boneIndex++) {
                var bone = model.bone(boneIndex);
                if (bone.ikLinkCount() > 0) {
                    var link = model.ikLink(boneIndex, 0);
                    assertTrue(link.boneIndex() >= 0 && link.boneIndex() < info.boneCount());
                    foundIk = true;
                    break;
                }
            }
            assertTrue(foundIk);
            assertThrows(IndexOutOfBoundsException.class, () -> model.bone(info.boneCount()));
            var pose = model.createPose();
            var motion = model.loadMotion(defaultMotion("idle.vmd"));
            var motionInfo = motion.info();
            assertTrue(motionInfo.durationFrames() > 0);
            assertTrue(motionInfo.boundBoneCount() > 0);
            motion.apply(0.5f, true, pose);
            pose.setLocalTransform(
                0,
                new NativeRuntime.Vector3(0.25f, 0.0f, 0.0f),
                NativeRuntime.Quaternion.IDENTITY
            );
            pose.evaluate();
            var matrices = pose.matrices();
            assertTrue(matrices.boneCount() == info.boneCount());
            assertTrue(matrices.matrixStride() == 16);
            assertTrue(matrices.data().byteSize() == (long) info.boneCount() * 16 * Float.BYTES);
            assertThrows(
                IllegalArgumentException.class,
                () -> pose.setLocalTransform(
                    0,
                    new NativeRuntime.Vector3(Float.NaN, 0.0f, 0.0f),
                    NativeRuntime.Quaternion.IDENTITY
                )
            );
            pose.reset();
            try (var scene = runtime.scenes().create(0.1f);
                 var instance = scene.createInstance(model)) {
                instance.setTransform(new SceneRuntime.Transform(
                    new NativeRuntime.Vector3(1.0f, 2.0f, 3.0f),
                    new NativeRuntime.Quaternion(0.0f, 0.0f, 0.0f, 2.0f),
                    new NativeRuntime.Vector3(0.1f, 0.1f, 0.1f)
                ));
                instance.play(motion, true, 0.2f);
                var update = scene.update(0.15f);
                assertTrue(update.frameIndex() == 1);
                assertTrue(update.instanceCount() == 1);
                assertTrue(update.animatedInstanceCount() == 1);
                assertTrue(update.droppedTime());
                assertTrue(Math.abs(update.deltaSeconds() - 0.1f) < 0.0001f);
                var state = instance.state();
                assertTrue(state.visible());
                assertTrue(state.playing());
                assertTrue(state.looping());
                assertTrue(state.transform().rotation().w() == 1.0f);
                assertTrue(instance.matrices().boneCount() == info.boneCount());
                var packet = instance.renderPacket();
                assertTrue(packet.backendMask() == (SceneRuntime.OPENGL | SceneRuntime.VULKAN));
                assertTrue(packet.visible());
                assertTrue(packet.vertexCount() == info.vertexCount());
                assertTrue(packet.vertices().byteSize() == (long) packet.vertexCount() * packet.vertexStride());
                assertTrue(packet.skinning().byteSize() == (long) packet.vertexCount() * packet.skinningStride());
                assertTrue(packet.indexCount() == info.indexCount());
                assertTrue(packet.indices().byteSize() == (long) packet.indexCount() * packet.indexStride());
                assertTrue(packet.boneCount() == info.boneCount());
                assertTrue(packet.matrices().byteSize() == (long) packet.boneCount() * packet.matrixStride() * Float.BYTES);
                assertTrue(packet.drawCount() == info.materialCount());
                assertThrows(IllegalStateException.class, motion::close);
                instance.stop(0.1f);
            }
            assertThrows(IllegalStateException.class, model::close);
            motion.close();
            assertTrue(motion.isClosed());
            assertThrows(IllegalStateException.class, motion::info);
            pose.close();
            assertTrue(pose.isClosed());
            assertThrows(IllegalStateException.class, matrices::data);
            assertFalse(model.isClosed());
            assertThrows(IllegalStateException.class, runtime::close);
            model.close();
            assertTrue(model.isClosed());
            assertThrows(IllegalStateException.class, mesh::vertices);
            assertThrows(IllegalStateException.class, renderMesh::vertices);
            assertThrows(IllegalStateException.class, packView::data);
            var pmxValue = System.getenv("LIBMMD_TEST_PMX");
            if (pmxValue != null && Files.isRegularFile(Path.of(pmxValue))) {
                var sourceModel = runtime.loadPmx(Path.of(pmxValue));
                var sourceInfo = sourceModel.info();
                assertTrue(sourceInfo.vertexCount() == info.vertexCount());
                assertTrue(sourceInfo.indexCount() == info.indexCount());
                assertTrue(sourceInfo.boneCount() == info.boneCount());
                sourceModel.close();
            }
        }
    }

    private static byte[] dynamicSpherePmx() {
        var pmx = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        pmx.put(new byte[] {'P', 'M', 'X', ' '}).putFloat(2.0f);
        pmx.put(new byte[] {8, 1, 0, 1, 1, 1, 1, 1, 1});
        putText(pmx, "dynamic-sphere");
        putText(pmx, "");
        putText(pmx, "");
        putText(pmx, "");
        pmx.putInt(3);
        for (var position : new float[][] {{0.0f, 0.0f, 0.0f}, {1.0f, 0.0f, 0.0f}, {0.0f, 1.0f, 0.0f}}) {
            putFloats(pmx, position);
            putFloats(pmx, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f);
            pmx.put((byte) 0).put((byte) 0).putFloat(1.0f);
        }
        pmx.putInt(3).put(new byte[] {0, 1, 2});
        pmx.putInt(0);
        pmx.putInt(1);
        putText(pmx, "material");
        putText(pmx, "");
        putFloats(pmx, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f);
        pmx.put((byte) 0);
        putFloats(pmx, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f);
        pmx.put(new byte[] {-1, -1, 0, 1, 0});
        putText(pmx, "");
        pmx.putInt(3);
        pmx.putInt(1);
        putText(pmx, "root");
        putText(pmx, "");
        putFloats(pmx, 0.0f, 0.0f, 0.0f);
        pmx.put((byte) -1).putInt(0).putShort((short) 0);
        putFloats(pmx, 0.0f, 1.0f, 0.0f);
        pmx.putInt(0);
        pmx.putInt(0);
        pmx.putInt(1);
        putText(pmx, "sphere");
        putText(pmx, "");
        pmx.put((byte) 0).put((byte) 0).putShort((short) 0).put((byte) 0);
        putFloats(pmx, 0.5f, 0.0f, 0.0f);
        putFloats(pmx, 0.0f, 3.0f, 0.0f);
        putFloats(pmx, 0.0f, 0.0f, 0.0f);
        putFloats(pmx, 1.0f, 0.0f, 0.0f, 0.0f, 0.5f);
        pmx.put((byte) 1);
        pmx.putInt(0);
        return Arrays.copyOf(pmx.array(), pmx.position());
    }

    private static void putText(ByteBuffer pmx, String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        pmx.putInt(bytes.length).put(bytes);
    }

    private static void putFloats(ByteBuffer pmx, float... values) {
        for (var value : values) pmx.putFloat(value);
    }

    private static byte[] defaultMotion(String name) {
        var resource = NativeRuntimeIntegrationTest.class.getResourceAsStream("/assets/libmmd/default-animation.zip");
        if (resource == null) throw new IllegalStateException("Missing default animation archive");
        try (var zip = new ZipInputStream(resource)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) return zip.readAllBytes();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to read default animation " + name, failure);
        }
        throw new IllegalArgumentException("Default animation not found: " + name);
    }
}

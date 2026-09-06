package com.micheanl.libmmd;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class NativeRuntimeIntegrationTest {
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

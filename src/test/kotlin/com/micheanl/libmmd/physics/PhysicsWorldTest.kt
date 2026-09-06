package com.micheanl.libmmd.physics

import com.micheanl.libmmd.animation.Pose
import com.micheanl.libmmd.animation.Skeleton
import com.micheanl.libmmd.format.pmx.PmxBone
import com.micheanl.libmmd.format.pmx.PmxBoneFlags
import com.micheanl.libmmd.format.pmx.PmxBoneTail
import com.micheanl.libmmd.format.pmx.PmxFormat
import com.micheanl.libmmd.format.pmx.PmxGeometry
import com.micheanl.libmmd.format.pmx.PmxIndexLayout
import com.micheanl.libmmd.format.pmx.PmxIndexWidth
import com.micheanl.libmmd.format.pmx.PmxModel
import com.micheanl.libmmd.format.pmx.PmxModelDescriptor
import com.micheanl.libmmd.format.pmx.PmxModelMetadata
import com.micheanl.libmmd.format.pmx.PmxRigidBody
import com.micheanl.libmmd.format.pmx.PmxRigidBodyMode
import com.micheanl.libmmd.format.pmx.PmxRigidBodyShape
import com.micheanl.libmmd.format.pmx.PmxSkinning
import com.micheanl.libmmd.format.pmx.PmxTextEncoding
import com.micheanl.libmmd.format.pmx.PmxVector3
import com.micheanl.libmmd.format.pmx.PmxVersion
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhysicsWorldTest {
	@Test
	fun `reports GPU fallback instead of silently pretending CUDA is active`() {
		PhysicsWorld(
			PhysicsConfiguration(preferredProcessor = PhysicsProcessor.GPU, allowCpuFallback = true),
		).use { world ->
			assertEquals(PhysicsProcessor.GPU, world.device.requestedProcessor)
			assertEquals(PhysicsProcessor.CPU, world.device.activeProcessor)
			assertTrue(world.device.fallbackReason?.contains("CUDA") == true)
		}
		assertFailsWith<IllegalStateException> {
			PhysicsWorld(PhysicsConfiguration(preferredProcessor = PhysicsProcessor.GPU, allowCpuFallback = false))
		}
	}

	@Test
	fun `loads native PhysX and synchronizes a falling body to its bone`() {
		val model = model()
		val pose = Pose(Skeleton.from(model.bones))
		PhysicsWorld(workerThreads = 1).use { world ->
			val rig = requireNotNull(world.attach(model, pose, Matrix4f()))
			assertEquals(PhysicsVersion(5, 6, 1), world.version)
			assertEquals(1, world.rigCount)

			world.step(0.2f)

			assertTrue(rig.rigidBodyTransform(0).m31() < 2f)
			assertTrue(pose.globalMatrix(0).getTranslation(Vector3f()).y < 0f)
		}
	}

	@Test
	fun `collides with static boxes and raycasts dynamic bodies`() {
		val model = model()
		val pose = Pose(Skeleton.from(model.bones))
		PhysicsWorld(workerThreads = 1).use { world ->
			val rig = requireNotNull(world.attach(model, pose, Matrix4f()))
			world.addStaticBox(Vector3f(0f, -0.5f, 0f), Vector3f(10f, 0.5f, 10f))

			repeat(180) { world.step(1f / 60f) }

			assertEquals(0.5f, rig.rigidBodyTransform(0).m31(), 0.02f)
			val hit = requireNotNull(world.raycast(Vector3f(0f, 3f, 0f), Vector3f(0f, -1f, 0f), 5f))
			assertTrue(hit.rig === rig)
			assertEquals(0, hit.rigidBodyIndex)
			assertEquals(2f, hit.distance, 0.05f)

			world.grab(hit).use { grab ->
				assertEquals(1, world.grabCount)
				grab.moveTo(Vector3f(0f, 2f, 0f))
				repeat(30) { world.step(1f / 60f) }
				assertTrue(rig.rigidBodyTransform(0).m31() > 1f)
			}
			assertEquals(0, world.grabCount)
		}
	}

	@Test
	fun `ccd prevents fast bodies from tunneling through thin geometry`() {
		val model = model()
		val pose = Pose(Skeleton.from(model.bones))
		PhysicsWorld(workerThreads = 1).use { world ->
			val rig = requireNotNull(world.attach(model, pose, Matrix4f()))
			world.addStaticBox(Vector3f(0f, -0.025f, 0f), Vector3f(10f, 0.025f, 10f))
			rig.applyImpulse(0, Vector3f(0f, -100f, 0f))

			repeat(20) { world.step(1f / 60f) }

			assertTrue(rig.rigidBodyTransform(0).m31() >= 0.48f)
		}
	}

	@Test
	fun `reports scaled rigid body geometry for visualization`() {
		val model = model()
		val pose = Pose(Skeleton.from(model.bones))
		PhysicsWorld(workerThreads = 1).use { world ->
			val rig = requireNotNull(world.attach(model, pose, Matrix4f().scale(2f)))
			val snapshot = rig.snapshot(0)

			assertEquals(0, snapshot.index)
			assertEquals("Body", snapshot.name)
			assertEquals(BodyGeometry.Sphere(1f), snapshot.geometry)
			assertTrue(snapshot.dynamic)
			assertEquals(4f, snapshot.transform.m31(), 0.001f)
			assertEquals(listOf(snapshot.geometry), rig.snapshots().map(RigidBodySnapshot::geometry))
		}
	}

	@Test
	fun `accepts zero dimensions unused by sphere geometry`() {
		val base = model()
		val body = base.rigidBodies.single().copy(size = PmxVector3(0.5f, 0f, 0f))
		val model = base.copy(rigidBodies = listOf(body))
		val pose = Pose(Skeleton.from(model.bones))
		PhysicsWorld(workerThreads = 1).use { world ->
			val rig = requireNotNull(world.attach(model, pose, Matrix4f()))

			assertEquals(BodyGeometry.Sphere(0.5f), rig.snapshot(0).geometry)
		}
	}

	@Test
	fun `generates a body capsule when an avatar has no authored collider`() {
		val base = model()
		val hips = base.bones.single().copy(name = "下半身", englishName = "Hips")
		val spine = hips.copy(
			name = "上半身",
			englishName = "Spine",
			position = PmxVector3(0f, 1f, 0f),
			parentBoneIndex = 0,
		)
		val model = base.copy(bones = listOf(hips, spine), rigidBodies = emptyList())
		val pose = Pose(Skeleton.from(model.bones))

		PhysicsWorld(workerThreads = 1).use { world ->
			val rig = requireNotNull(world.attach(
				model,
				pose,
				Matrix4f(),
				PhysicsAssetConfiguration(autoGenerateBodyColliders = true),
			))

			assertEquals(1, rig.rigidBodyCount)
			assertEquals(PhysicsBodyRole.BODY, rig.snapshot(0).role)
			assertTrue(rig.snapshot(0).geometry is BodyGeometry.Capsule)
		}
	}

	@Test
	fun `applies apparent wind to moving skirt bodies`() {
		val base = model()
		val skirt = base.rigidBodies.single().copy(name = "qunzi_0", englishName = "Skirt")
		val model = base.copy(rigidBodies = listOf(skirt))
		val pose = Pose(Skeleton.from(model.bones))
		PhysicsWorld(Vector3f(), 1).use { world ->
			val rig = requireNotNull(world.attach(model, pose, Matrix4f()))
			repeat(30) {
				rig.updateCharacterMotion(Vector3f(4f, 0f, 0f), 1f / 60f)
				world.step(1f / 60f)
			}

			assertTrue(rig.rigidBodyTransform(0).m30() < -0.02f)
		}
	}

	@Test
	fun `expands leg contact prediction while its kinematic target moves`() {
		val base = model()
		val leg = base.rigidBodies.single().copy(
			name = "左足",
			englishName = "LeftLeg",
			shape = PmxRigidBodyShape.CAPSULE,
			size = PmxVector3(0.2f, 0.8f, 0f),
			mode = PmxRigidBodyMode.FOLLOW_BONE,
		)
		val model = base.copy(rigidBodies = listOf(leg))
		val pose = Pose(Skeleton.from(model.bones))
		PhysicsWorld(workerThreads = 1).use { world ->
			val rig = requireNotNull(world.attach(model, pose, Matrix4f()))
			world.step(1f / 20f)
			val baseOffset = rig.snapshot(0).contactOffset

			rig.follow(Matrix4f().translation(1f, 0f, 0f))
			world.step(1f / 20f)

			assertTrue(rig.snapshot(0).contactOffset > baseOffset)
		}
	}

	@Test
	fun `uses hysteresis while reducing and suspending secondary physics`() {
		val base = model()
		val skirt = base.rigidBodies.single().copy(name = "qunzi_0", englishName = "Skirt")
		val model = base.copy(rigidBodies = listOf(skirt))
		val pose = Pose(Skeleton.from(model.bones))
		val lod = SecondaryPhysicsLodConfiguration(
			hysteresis = 1f,
			skirtReducedDistance = 5f,
			skirtSuspendedDistance = 10f,
			hairReducedDistance = 5f,
			hairSuspendedDistance = 10f,
		)
		PhysicsWorld(workerThreads = 1).use { world ->
			val rig = requireNotNull(world.attach(model, pose, Matrix4f(), PhysicsAssetConfiguration(lod = lod)))

			rig.setViewerDistance(7f)
			assertEquals(PhysicsDetailLevel.REDUCED, rig.snapshot(0).detailLevel)
			rig.setViewerDistance(12f)
			assertEquals(PhysicsDetailLevel.SUSPENDED, rig.snapshot(0).detailLevel)
			assertTrue(!rig.snapshot(0).dynamic)
			rig.setViewerDistance(9.5f)
			assertEquals(PhysicsDetailLevel.SUSPENDED, rig.snapshot(0).detailLevel)
			rig.setViewerDistance(8f)
			assertEquals(PhysicsDetailLevel.REDUCED, rig.snapshot(0).detailLevel)
			rig.setViewerDistance(3f)
			assertEquals(PhysicsDetailLevel.FULL, rig.snapshot(0).detailLevel)
		}
	}

	@Test
	fun `preserves actor transforms while following a moving model root`() {
		val model = model()
		val pose = Pose(Skeleton.from(model.bones))
		PhysicsWorld(workerThreads = 1).use { world ->
			val rig = requireNotNull(world.attach(model, pose, Matrix4f().translation(1f, 2f, 3f)))
			val before = rig.rigidBodyTransform(0).getTranslation(Vector3f())

			rig.follow(Matrix4f().translation(4f, 6f, 8f))

			val after = rig.rigidBodyTransform(0).getTranslation(Vector3f())
			assertEquals(before.x + 3f, after.x, 0.0001f)
			assertEquals(before.y + 4f, after.y, 0.0001f)
			assertEquals(before.z + 5f, after.z, 0.0001f)
		}
	}

	@Test
	fun `converts physics results back through a translated model root`() {
		val model = model()
		val pose = Pose(Skeleton.from(model.bones))
		PhysicsWorld(workerThreads = 1).use { world ->
			requireNotNull(world.attach(model, pose, Matrix4f().translation(10f, 0f, 0f)))

			world.step(1f / 60f)

			assertEquals(0f, pose.globalMatrix(0).m30(), 0.0001f)
		}
	}

	private fun model(): PmxModel {
		val bone = PmxBone(
			name = "Root",
			englishName = "Root",
			position = PmxVector3(0f, 0f, 0f),
			parentBoneIndex = -1,
			deformLayer = 0,
			flags = PmxBoneFlags(0),
			tail = PmxBoneTail.Offset(PmxVector3(0f, 1f, 0f)),
			inheritance = null,
			fixedAxis = null,
			localAxes = null,
			externalParentKey = null,
			inverseKinematics = null,
		)
		return PmxModel(
			descriptor = descriptor(),
			geometry = PmxGeometry(
				positions = floatArrayOf(),
				normals = floatArrayOf(),
				textureCoordinates = floatArrayOf(),
				additionalTextureCoordinates = floatArrayOf(),
				additionalUvChannels = 0,
				skinning = PmxSkinning(byteArrayOf(), intArrayOf(), floatArrayOf(), null),
				edgeScales = floatArrayOf(),
				triangleIndices = intArrayOf(),
			),
			texturePaths = emptyList(),
			materials = emptyList(),
			bones = listOf(bone),
			morphs = emptyList(),
			displayFrames = emptyList(),
			rigidBodies = listOf(
				PmxRigidBody(
					name = "Body",
					englishName = "Body",
					boneIndex = 0,
					collisionGroup = 0,
					collisionExclusionMask = 0,
					shape = PmxRigidBodyShape.SPHERE,
					size = PmxVector3(0.5f, 0.5f, 0.5f),
					position = PmxVector3(0f, 2f, 0f),
					rotation = PmxVector3(0f, 0f, 0f),
					mass = 1f,
					linearDamping = 0f,
					angularDamping = 0f,
					restitution = 0f,
					friction = 0.5f,
					mode = PmxRigidBodyMode.PHYSICS,
				),
			),
			joints = emptyList(),
			softBodies = emptyList(),
		)
	}

	private fun descriptor(): PmxModelDescriptor = PmxModelDescriptor(
		format = PmxFormat(
			version = PmxVersion.V2_1,
			textEncoding = PmxTextEncoding.UTF_8,
			additionalUvChannels = 0,
			indices = PmxIndexLayout(
				vertex = PmxIndexWidth.ONE,
				texture = PmxIndexWidth.ONE,
				material = PmxIndexWidth.ONE,
				bone = PmxIndexWidth.ONE,
				morph = PmxIndexWidth.ONE,
				rigidBody = PmxIndexWidth.ONE,
			),
		),
		metadata = PmxModelMetadata("Test", "Test", "", ""),
	)
}

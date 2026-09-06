package com.micheanl.libmmd.animation

import com.micheanl.libmmd.format.pmx.PmxDrawFlags
import com.micheanl.libmmd.format.pmx.PmxBone
import com.micheanl.libmmd.format.pmx.PmxBoneFlags
import com.micheanl.libmmd.format.pmx.PmxBoneMorphOffset
import com.micheanl.libmmd.format.pmx.PmxBoneTail
import com.micheanl.libmmd.format.pmx.PmxFlipMorphOffset
import com.micheanl.libmmd.format.pmx.PmxGroupMorphOffset
import com.micheanl.libmmd.format.pmx.PmxMaterial
import com.micheanl.libmmd.format.pmx.PmxMaterialMorphOffset
import com.micheanl.libmmd.format.pmx.PmxMaterialMorphOperation
import com.micheanl.libmmd.format.pmx.PmxMorph
import com.micheanl.libmmd.format.pmx.PmxMorphOffset
import com.micheanl.libmmd.format.pmx.PmxMorphPanel
import com.micheanl.libmmd.format.pmx.PmxMorphType
import com.micheanl.libmmd.format.pmx.PmxRgb
import com.micheanl.libmmd.format.pmx.PmxRgba
import com.micheanl.libmmd.format.pmx.PmxSphereMode
import com.micheanl.libmmd.format.pmx.PmxToonTexture
import com.micheanl.libmmd.format.pmx.PmxUvMorphOffset
import com.micheanl.libmmd.format.pmx.PmxVector3
import com.micheanl.libmmd.format.pmx.PmxVector4
import com.micheanl.libmmd.format.pmx.PmxVertexMorphOffset
import com.micheanl.libmmd.format.vmd.VmdMorphKeyframe
import com.micheanl.libmmd.format.vmd.VmdMotion
import java.nio.ByteOrder
import org.joml.Vector3f
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MorphClipTest {
	@Test
	fun `binds VMD morph tracks and converts vertex coordinates`() {
		val set = morphSet(
			morph("笑い", "Smile", PmxVertexMorphOffset(0, PmxVector3(2f, 4f, 6f))),
		)
		val clip = MorphClip.bind(
			motion(
				VmdMorphKeyframe("笑い", 0, 0f),
				VmdMorphKeyframe("笑い", 30, 1f),
			),
			set,
		)
		val weights = MorphWeights(set)

		clip.apply(0.5f, weights)
		val offsets = weights.vertexOffsetBytes().order(ByteOrder.nativeOrder())

		assertEquals(0.5f, weights[0])
		assertEquals(1f, offsets.getFloat(0))
		assertEquals(2f, offsets.getFloat(4))
		assertEquals(-3f, offsets.getFloat(8))
		assertEquals(0f, offsets.getFloat(12))
		assertEquals(0f, offsets.getFloat(16))
		assertEquals(1, clip.boundTargetCount)
		assertEquals(1f, clip.durationSeconds)
	}

	@Test
	fun `accumulates multiple morph targets per vertex`() {
		val set = morphSet(
			morph("A", offsets = arrayOf(PmxVertexMorphOffset(0, PmxVector3(2f, 0f, 0f)))),
			morph("B", offsets = arrayOf(PmxVertexMorphOffset(0, PmxVector3(0f, 4f, 0f)))),
		)
		val weights = MorphWeights(set)
			.set(0, 0.5f)
			.set(1, 0.25f)

		val offsets = weights.vertexOffsetBytes().order(ByteOrder.nativeOrder())

		assertEquals(1f, offsets.getFloat(0))
		assertEquals(1f, offsets.getFloat(4))
		assertEquals(0f, offsets.getFloat(8))
	}

	@Test
	fun `resolves nested groups and flip selection`() {
		val targets = listOf(
			morph("A", offsets = arrayOf(PmxVertexMorphOffset(0, PmxVector3(2f, 0f, 0f)))),
			morph("B", offsets = arrayOf(PmxVertexMorphOffset(0, PmxVector3(0f, 4f, 0f)))),
			composedMorph("Inner", PmxMorphType.GROUP, PmxGroupMorphOffset(0, 0.5f)),
			composedMorph("Outer", PmxMorphType.GROUP, PmxGroupMorphOffset(2, 0.25f)),
			composedMorph(
				"Selector",
				PmxMorphType.FLIP,
				PmxFlipMorphOffset(0, 0.25f),
				PmxFlipMorphOffset(1, 0.75f),
			),
		)
		val set = MorphSet.from(1, targets)
		val weights = MorphWeights(set).set(3, 0.8f)

		var offsets = weights.vertexOffsetBytes().order(ByteOrder.nativeOrder())
		assertEquals(0.2f, offsets.getFloat(0), 0.0001f)

		weights.reset().set(0, 0.5f).set(4, 0.5f)
		offsets = weights.vertexOffsetBytes().order(ByteOrder.nativeOrder())
		assertEquals(0.5f, offsets.getFloat(0), 0.0001f)
		assertEquals(0f, offsets.getFloat(4))

		weights.reset().set(4, 1f)
		offsets = weights.vertexOffsetBytes().order(ByteOrder.nativeOrder())
		assertEquals(0f, offsets.getFloat(0))
		assertEquals(3f, offsets.getFloat(4), 0.0001f)
	}

	@Test
	fun `applies UV and material morphs`() {
		val uv = composedMorph(
			"UV",
			PmxMorphType.UV,
			PmxUvMorphOffset(0, 0, PmxVector4(0.2f, -0.4f, 0f, 0f)),
		)
		val color = composedMorph(
			"Color",
			PmxMorphType.MATERIAL,
			materialOffset(),
		)
		val set = MorphSet.from(1, listOf(material()), listOf(uv, color))
		val weights = MorphWeights(set).set(0, 0.5f).set(1, 0.5f)

		val offsets = weights.vertexOffsetBytes().order(ByteOrder.nativeOrder())
		val materialColor = weights.materialColor(0, textured = true)

		assertEquals(0.1f, offsets.getFloat(12), 0.0001f)
		assertEquals(-0.2f, offsets.getFloat(16), 0.0001f)
		assertEquals(0.5f, materialColor.x, 0.0001f)
		assertEquals(0.55f, materialColor.y, 0.0001f)
		assertEquals(0.6f, materialColor.z, 0.0001f)
		assertEquals(0.9f, materialColor.w, 0.0001f)
	}

	@Test
	fun `applies weighted bone morph translation and rotation`() {
		val halfRoot = sqrt(0.5f)
		val target = composedMorph(
			"Bone",
			PmxMorphType.BONE,
			PmxBoneMorphOffset(0, PmxVector3(2f, 0f, 0f), PmxVector4(0f, 0f, halfRoot, halfRoot)),
		)
		val set = MorphSet.from(0, emptyList(), 1, listOf(target))
		val skeleton = Skeleton.from(listOf(rootBone()))
		val pose = Pose(skeleton)
		MorphWeights(set).set(0, 0.5f).applyBones(pose)

		val matrix = pose.globalMatrix(0)
		assertEquals(1f, matrix.m30(), 0.0001f)
		val direction = matrix.transformDirection(Vector3f(1f, 0f, 0f))
		assertEquals(halfRoot, direction.x, 0.0001f)
		assertEquals(halfRoot, direction.y, 0.0001f)
	}

	@Test
	fun `multiplies every material when index is global`() {
		val morph = composedMorph(
			"Fade",
			PmxMorphType.MATERIAL,
			materialOffset(
				materialIndex = -1,
				operation = PmxMaterialMorphOperation.MULTIPLY,
				diffuse = PmxRgba(0.5f, 1f, 1f, 1f),
				textureTint = PmxRgba(1f, 1f, 1f, 1f),
			),
		)
		val set = MorphSet.from(1, listOf(material(), material(0.8f)), listOf(morph))
		val weights = MorphWeights(set).set(0, 0.5f)

		assertEquals(0.3f, weights.materialColor(0, textured = false).x, 0.0001f)
		assertEquals(0.6f, weights.materialColor(1, textured = false).x, 0.0001f)
	}

	@Test
	fun `rejects cyclic morph dependencies`() {
		val first = composedMorph("First", PmxMorphType.GROUP, PmxGroupMorphOffset(1, 1f))
		val second = composedMorph("Second", PmxMorphType.GROUP, PmxGroupMorphOffset(0, 1f))

		assertFailsWith<IllegalArgumentException> { MorphSet.from(1, listOf(first, second)) }
	}

	@Test
	fun `animator uses the longer morph timeline`() {
		val skeleton = Skeleton.from(emptyList())
		val set = morphSet(morph("Smile", offsets = arrayOf(PmxVertexMorphOffset(0, PmxVector3(1f, 0f, 0f)))))
		val boneClip = AnimationClip.bind(motion(), skeleton)
		val morphClip = MorphClip.bind(
			motion(
				VmdMorphKeyframe("Smile", 0, 0f),
				VmdMorphKeyframe("Smile", 60, 1f),
			),
			set,
		)
		val weights = MorphWeights(set)
		val animator = Animator(boneClip, Pose(skeleton), morphClip, weights).apply {
			looping = false
			play()
		}

		animator.update(0.5f)
		assertEquals(0.25f, weights[0])
		assertEquals(2f, animator.durationSeconds)
		animator.update(2f)
		assertEquals(2f, animator.timeSeconds)
		assertEquals(1f, weights[0])
		assertFalse(animator.isPlaying)
	}

	private fun morphSet(vararg morphs: PmxMorph): MorphSet = MorphSet.from(1, morphs.toList())

	private fun morph(
		name: String,
		englishName: String = "",
		vararg offsets: PmxVertexMorphOffset,
	): PmxMorph = PmxMorph(
		name = name,
		englishName = englishName,
		panel = PmxMorphPanel.OTHER,
		type = PmxMorphType.VERTEX,
		offsets = offsets.toList(),
	)

	private fun composedMorph(
		name: String,
		type: PmxMorphType,
		vararg offsets: PmxMorphOffset,
	): PmxMorph = PmxMorph(name, "", PmxMorphPanel.OTHER, type, offsets.toList())

	private fun material(red: Float = 0.4f): PmxMaterial = PmxMaterial(
		name = "Body",
		englishName = "Body",
		diffuse = PmxRgba(red, 0.5f, 0.6f, 1f),
		specular = PmxRgb(0f, 0f, 0f),
		specularStrength = 0f,
		ambient = PmxRgb(0f, 0f, 0f),
		drawFlags = PmxDrawFlags(0),
		edgeColor = PmxRgba(0f, 0f, 0f, 0f),
		edgeScale = 0f,
		textureIndex = 0,
		sphereTextureIndex = -1,
		sphereMode = PmxSphereMode.DISABLED,
		toonTexture = PmxToonTexture.Shared(0),
		metadata = "",
		firstIndex = 0,
		indexCount = 0,
	)

	private fun rootBone(): PmxBone = PmxBone(
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

	private fun materialOffset(
		materialIndex: Int = 0,
		operation: PmxMaterialMorphOperation = PmxMaterialMorphOperation.ADDITIVE,
		diffuse: PmxRgba = PmxRgba(0.2f, 0f, 0f, -0.2f),
		textureTint: PmxRgba = PmxRgba(0f, 0.2f, 0f, 0f),
	): PmxMaterialMorphOffset = PmxMaterialMorphOffset(
		materialIndex = materialIndex,
		operation = operation,
		diffuse = diffuse,
		specular = PmxRgb(0f, 0f, 0f),
		specularStrength = 0f,
		ambient = PmxRgb(0f, 0f, 0f),
		edgeColor = PmxRgba(0f, 0f, 0f, 0f),
		edgeScale = 0f,
		textureTint = textureTint,
		sphereTint = PmxRgba(0f, 0f, 0f, 0f),
		toonTint = PmxRgba(0f, 0f, 0f, 0f),
	)

	private fun motion(vararg morphs: VmdMorphKeyframe): VmdMotion = VmdMotion(
		modelName = "Test",
		boneKeyframes = emptyList(),
		morphKeyframes = morphs.toList(),
		cameraKeyframes = emptyList(),
		lightKeyframes = emptyList(),
		shadowKeyframes = emptyList(),
		ikKeyframes = emptyList(),
	)
}

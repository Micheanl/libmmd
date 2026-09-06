package com.micheanl.libmmd.format.pmx

import kotlin.math.abs

object PmxDecoder {
	private val signature = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), ' '.code.toByte())

	fun readDescriptor(bytes: ByteArray): PmxModelDescriptor = readDescriptor(PmxBinaryInput(bytes))

	fun readGeometry(
		bytes: ByteArray,
		limits: PmxDecodeLimits = PmxDecodeLimits(),
	): PmxGeometryAsset = readGeometry(PmxBinaryInput(bytes), limits)

	fun readMaterials(
		bytes: ByteArray,
		limits: PmxDecodeLimits = PmxDecodeLimits(),
	): PmxMaterialAsset = readMaterials(PmxBinaryInput(bytes), limits)

	fun readBones(
		bytes: ByteArray,
		limits: PmxDecodeLimits = PmxDecodeLimits(),
	): PmxBoneAsset = readBones(PmxBinaryInput(bytes), limits)

	fun readMorphs(
		bytes: ByteArray,
		limits: PmxDecodeLimits = PmxDecodeLimits(),
	): PmxMorphAsset = readMorphs(PmxBinaryInput(bytes), limits)

	fun readDisplayFrames(
		bytes: ByteArray,
		limits: PmxDecodeLimits = PmxDecodeLimits(),
	): PmxDisplayFrameAsset = readDisplayFrames(PmxBinaryInput(bytes), limits)

	fun readRigidBodies(
		bytes: ByteArray,
		limits: PmxDecodeLimits = PmxDecodeLimits(),
	): PmxRigidBodyAsset = readRigidBodies(PmxBinaryInput(bytes), limits)

	fun readJoints(
		bytes: ByteArray,
		limits: PmxDecodeLimits = PmxDecodeLimits(),
	): PmxJointAsset = readJoints(PmxBinaryInput(bytes), limits)

	fun readSoftBodies(
		bytes: ByteArray,
		limits: PmxDecodeLimits = PmxDecodeLimits(),
	): PmxSoftBodyAsset = readSoftBodies(PmxBinaryInput(bytes), limits)

	fun readModel(
		bytes: ByteArray,
		limits: PmxDecodeLimits = PmxDecodeLimits(),
	): PmxModel {
		val input = PmxBinaryInput(bytes)
		val asset = readSoftBodies(input, limits)
		input.requireEnd()
		return PmxModel(
			descriptor = asset.descriptor,
			geometry = asset.geometry,
			texturePaths = asset.texturePaths,
			materials = asset.materials,
			bones = asset.bones,
			morphs = asset.morphs,
			displayFrames = asset.displayFrames,
			rigidBodies = asset.rigidBodies,
			joints = asset.joints,
			softBodies = asset.softBodies,
		)
	}

	private fun readSoftBodies(input: PmxBinaryInput, limits: PmxDecodeLimits): PmxSoftBodyAsset {
		val jointAsset = readJoints(input, limits)
		var remainingAnchors = limits.maxSoftBodyAnchors
		var remainingPins = limits.maxSoftBodyPins
		val softBodies = if (jointAsset.descriptor.format.version == PmxVersion.V2_0) {
			emptyList()
		} else {
			val softBodyCount = readCount(input, "soft body", limits.maxSoftBodies)
			List(softBodyCount) {
				readSoftBody(
					input = input,
					format = jointAsset.descriptor.format,
					materialCount = jointAsset.materials.size,
					rigidBodyCount = jointAsset.rigidBodies.size,
					vertexCount = jointAsset.geometry.vertexCount,
					maxAnchors = remainingAnchors,
					maxPins = remainingPins,
				).also {
					remainingAnchors -= it.anchors.size
					remainingPins -= it.pinnedVertexIndices.size
				}
			}
		}

		return PmxSoftBodyAsset(
			descriptor = jointAsset.descriptor,
			geometry = jointAsset.geometry,
			texturePaths = jointAsset.texturePaths,
			materials = jointAsset.materials,
			bones = jointAsset.bones,
			morphs = jointAsset.morphs,
			displayFrames = jointAsset.displayFrames,
			rigidBodies = jointAsset.rigidBodies,
			joints = jointAsset.joints,
			softBodies = softBodies,
		)
	}

	private fun readJoints(input: PmxBinaryInput, limits: PmxDecodeLimits): PmxJointAsset {
		val rigidBodyAsset = readRigidBodies(input, limits)
		val format = rigidBodyAsset.descriptor.format
		val jointCount = readCount(input, "joint", limits.maxJoints)
		val joints = List(jointCount) {
			readJoint(input, format, rigidBodyAsset.rigidBodies.size)
		}

		return PmxJointAsset(
			descriptor = rigidBodyAsset.descriptor,
			geometry = rigidBodyAsset.geometry,
			texturePaths = rigidBodyAsset.texturePaths,
			materials = rigidBodyAsset.materials,
			bones = rigidBodyAsset.bones,
			morphs = rigidBodyAsset.morphs,
			displayFrames = rigidBodyAsset.displayFrames,
			rigidBodies = rigidBodyAsset.rigidBodies,
			joints = joints,
		)
	}

	private fun readSoftBody(
		input: PmxBinaryInput,
		format: PmxFormat,
		materialCount: Int,
		rigidBodyCount: Int,
		vertexCount: Int,
		maxAnchors: Int,
		maxPins: Int,
	): PmxSoftBody {
		val name = input.readText(format.textEncoding)
		val englishName = input.readText(format.textEncoding)
		val shapeOffset = input.position
		val shapeValue = input.readUnsignedByte()
		val shape = PmxSoftBodyShape.entries.firstOrNull { it.encodedValue == shapeValue }
			?: throw PmxFormatException("Unsupported soft body shape $shapeValue", shapeOffset)
		val materialIndex = readMaterialIndex(input, format.indices.material, materialCount, "soft body material")
		val collisionGroup = input.readUnsignedByte()
		val collisionExclusionMask = input.readUnsignedInt16()
		val flags = PmxSoftBodyFlags(input.readUnsignedByte())
		val bLinkDistance = readNonNegativeInt32(input, "soft body B-link distance")
		val clusterCount = readNonNegativeInt32(input, "soft body cluster count")
		val totalMass = input.readFloat32()
		val collisionMargin = input.readFloat32()
		val aerodynamicsOffset = input.position
		val aerodynamicsValue = input.readInt32()
		val aerodynamics = PmxSoftBodyAerodynamics.entries.firstOrNull { it.encodedValue == aerodynamicsValue }
			?: throw PmxFormatException("Unsupported soft body aerodynamics $aerodynamicsValue", aerodynamicsOffset)
		val config = readSoftBodyConfig(input)
		val cluster = readSoftBodyCluster(input)
		val solverIterations = PmxSoftBodySolverIterations(
			velocity = readNonNegativeInt32(input, "soft body velocity iterations"),
			position = readNonNegativeInt32(input, "soft body position iterations"),
			drift = readNonNegativeInt32(input, "soft body drift iterations"),
			cluster = readNonNegativeInt32(input, "soft body cluster iterations"),
		)
		val materialCoefficients = PmxSoftBodyMaterialCoefficients(
			linearStiffness = input.readFloat32(),
			angularStiffness = input.readFloat32(),
			volumeStiffness = input.readFloat32(),
		)
		val anchorCount = readCount(input, "soft body anchor", maxAnchors)
		val anchors = List(anchorCount) {
			val rigidBodyIndex = readRigidBodyIndex(
				input,
				format.indices.rigidBody,
				rigidBodyCount,
				"soft body anchor rigid body",
			)
			val vertexIndex = readCheckedVertexIndex(
				input,
				format.indices.vertex,
				vertexCount,
				"soft body anchor vertex",
			)
			val nearModeOffset = input.position
			val isNearMode = when (val nearMode = input.readUnsignedByte()) {
				0 -> false
				1 -> true
				else -> throw PmxFormatException("Unsupported soft body near-mode flag $nearMode", nearModeOffset)
			}
			PmxSoftBodyAnchor(rigidBodyIndex, vertexIndex, isNearMode)
		}
		val pinCount = readCount(input, "soft body pin", maxPins)
		val pinnedVertexIndices = IntArray(pinCount) {
			readCheckedVertexIndex(input, format.indices.vertex, vertexCount, "soft body pin vertex")
		}
		return PmxSoftBody(
			name = name,
			englishName = englishName,
			shape = shape,
			materialIndex = materialIndex,
			collisionGroup = collisionGroup,
			collisionExclusionMask = collisionExclusionMask,
			flags = flags,
			bLinkDistance = bLinkDistance,
			clusterCount = clusterCount,
			totalMass = totalMass,
			collisionMargin = collisionMargin,
			aerodynamics = aerodynamics,
			config = config,
			cluster = cluster,
			solverIterations = solverIterations,
			materialCoefficients = materialCoefficients,
			anchors = anchors,
			pinnedVertexIndices = pinnedVertexIndices,
		)
	}

	private fun readSoftBodyConfig(input: PmxBinaryInput): PmxSoftBodyConfig = PmxSoftBodyConfig(
		velocityCorrectionFactor = input.readFloat32(),
		dampingCoefficient = input.readFloat32(),
		dragCoefficient = input.readFloat32(),
		liftCoefficient = input.readFloat32(),
		pressureCoefficient = input.readFloat32(),
		volumeConservationCoefficient = input.readFloat32(),
		dynamicFrictionCoefficient = input.readFloat32(),
		poseMatchingCoefficient = input.readFloat32(),
		rigidContactHardness = input.readFloat32(),
		kineticContactHardness = input.readFloat32(),
		softContactHardness = input.readFloat32(),
		anchorHardness = input.readFloat32(),
	)

	private fun readSoftBodyCluster(input: PmxBinaryInput): PmxSoftBodyCluster = PmxSoftBodyCluster(
		softRigidHardness = input.readFloat32(),
		softKineticHardness = input.readFloat32(),
		softSoftHardness = input.readFloat32(),
		softRigidImpulseSplit = input.readFloat32(),
		softKineticImpulseSplit = input.readFloat32(),
		softSoftImpulseSplit = input.readFloat32(),
	)

	private fun readMaterialIndex(
		input: PmxBinaryInput,
		width: PmxIndexWidth,
		materialCount: Int,
		label: String,
	): Int {
		val indexOffset = input.position
		val index = readSignedIndex(input, width)
		if (index !in -1 until materialCount) {
			throw PmxFormatException("$label index $index is outside -1 until $materialCount", indexOffset)
		}
		return index
	}

	private fun readNonNegativeInt32(input: PmxBinaryInput, label: String): Int {
		val valueOffset = input.position
		val value = input.readInt32()
		if (value < 0) {
			throw PmxFormatException("$label $value must be non-negative", valueOffset)
		}
		return value
	}

	private fun readRigidBodies(input: PmxBinaryInput, limits: PmxDecodeLimits): PmxRigidBodyAsset {
		val displayFrameAsset = readDisplayFrames(input, limits)
		val format = displayFrameAsset.descriptor.format
		val rigidBodyCount = readCount(input, "rigid body", limits.maxRigidBodies)
		val rigidBodies = List(rigidBodyCount) {
			readRigidBody(input, format, displayFrameAsset.bones.size)
		}
		validateImpulseMorphIndices(input, displayFrameAsset.morphs, rigidBodyCount)

		return PmxRigidBodyAsset(
			descriptor = displayFrameAsset.descriptor,
			geometry = displayFrameAsset.geometry,
			texturePaths = displayFrameAsset.texturePaths,
			materials = displayFrameAsset.materials,
			bones = displayFrameAsset.bones,
			morphs = displayFrameAsset.morphs,
			displayFrames = displayFrameAsset.displayFrames,
			rigidBodies = rigidBodies,
		)
	}

	private fun readJoint(input: PmxBinaryInput, format: PmxFormat, rigidBodyCount: Int): PmxJoint {
		val name = input.readText(format.textEncoding)
		val englishName = input.readText(format.textEncoding)
		val typeOffset = input.position
		val typeValue = input.readUnsignedByte()
		val type = PmxJointType.entries.firstOrNull { it.encodedValue == typeValue }
			?: throw PmxFormatException("Unsupported joint type $typeValue", typeOffset)
		if (format.version == PmxVersion.V2_0 && type != PmxJointType.SPRING_SIX_DOF) {
			throw PmxFormatException("${type.name} joint requires PMX 2.1", typeOffset)
		}
		val firstRigidBodyIndex = readRigidBodyIndex(
			input,
			format.indices.rigidBody,
			rigidBodyCount,
			"first joint rigid body",
		)
		val secondRigidBodyIndex = readRigidBodyIndex(
			input,
			format.indices.rigidBody,
			rigidBodyCount,
			"second joint rigid body",
		)
		return PmxJoint(
			name = name,
			englishName = englishName,
			type = type,
			firstRigidBodyIndex = firstRigidBodyIndex,
			secondRigidBodyIndex = secondRigidBodyIndex,
			position = readVector3(input),
			rotation = readVector3(input),
			translationLimits = PmxVector3Range(
				minimum = readVector3(input),
				maximum = readVector3(input),
			),
			rotationLimits = PmxVector3Range(
				minimum = readVector3(input),
				maximum = readVector3(input),
			),
			translationSpring = readVector3(input),
			rotationSpring = readVector3(input),
		)
	}

	private fun readRigidBodyIndex(
		input: PmxBinaryInput,
		width: PmxIndexWidth,
		rigidBodyCount: Int,
		label: String,
	): Int {
		val indexOffset = input.position
		val index = readSignedIndex(input, width)
		if (index !in -1 until rigidBodyCount) {
			throw PmxFormatException("$label index $index is outside -1 until $rigidBodyCount", indexOffset)
		}
		return index
	}

	private fun readDisplayFrames(input: PmxBinaryInput, limits: PmxDecodeLimits): PmxDisplayFrameAsset {
		val morphAsset = readMorphs(input, limits)
		val displayFrameCount = readCount(input, "display frame", limits.maxDisplayFrames)
		var remainingElements = limits.maxDisplayFrameElements
		val displayFrames = List(displayFrameCount) {
			readDisplayFrame(
				input = input,
				format = morphAsset.descriptor.format,
				boneCount = morphAsset.bones.size,
				morphCount = morphAsset.morphs.size,
				maxElements = remainingElements,
			).also { remainingElements -= it.elements.size }
		}

		return PmxDisplayFrameAsset(
			descriptor = morphAsset.descriptor,
			geometry = morphAsset.geometry,
			texturePaths = morphAsset.texturePaths,
			materials = morphAsset.materials,
			bones = morphAsset.bones,
			morphs = morphAsset.morphs,
			displayFrames = displayFrames,
		)
	}

	private fun readMorphs(input: PmxBinaryInput, limits: PmxDecodeLimits): PmxMorphAsset {
		val boneAsset = readBones(input, limits)
		val format = boneAsset.descriptor.format
		val morphCount = readCount(input, "morph", limits.maxMorphs)
		var remainingOffsets = limits.maxMorphOffsets
		val morphs = List(morphCount) {
			readMorph(
				input = input,
				format = format,
				vertexCount = boneAsset.geometry.vertexCount,
				materialCount = boneAsset.materials.size,
				boneCount = boneAsset.bones.size,
				morphCount = morphCount,
				maxOffsets = remainingOffsets,
			).also { remainingOffsets -= it.offsets.size }
		}

		return PmxMorphAsset(
			descriptor = boneAsset.descriptor,
			geometry = boneAsset.geometry,
			texturePaths = boneAsset.texturePaths,
			materials = boneAsset.materials,
			bones = boneAsset.bones,
			morphs = morphs,
		)
	}

	private fun readDisplayFrame(
		input: PmxBinaryInput,
		format: PmxFormat,
		boneCount: Int,
		morphCount: Int,
		maxElements: Int,
	): PmxDisplayFrame {
		val name = input.readText(format.textEncoding)
		val englishName = input.readText(format.textEncoding)
		val specialOffset = input.position
		val isSpecial = when (val specialValue = input.readUnsignedByte()) {
			0 -> false
			1 -> true
			else -> throw PmxFormatException("Unsupported display frame special flag $specialValue", specialOffset)
		}
		val elementCount = readCount(input, "display frame element", maxElements)
		val elements = List(elementCount) {
			val typeOffset = input.position
			when (val type = input.readUnsignedByte()) {
				0 -> PmxBoneDisplayElement(
					readBoneIndex(input, format.indices.bone, boneCount, false, "display frame bone"),
				)

				1 -> PmxMorphDisplayElement(readMorphIndex(input, format.indices.morph, morphCount))
				else -> throw PmxFormatException("Unsupported display element type $type", typeOffset)
			}
		}
		return PmxDisplayFrame(name, englishName, isSpecial, elements)
	}

	private fun readRigidBody(input: PmxBinaryInput, format: PmxFormat, boneCount: Int): PmxRigidBody {
		val name = input.readText(format.textEncoding)
		val englishName = input.readText(format.textEncoding)
		val boneIndex = readBoneIndex(input, format.indices.bone, boneCount, true, "rigid body bone")
		val collisionGroup = input.readUnsignedByte()
		val collisionExclusionMask = input.readUnsignedInt16()
		val shapeOffset = input.position
		val shapeValue = input.readUnsignedByte()
		val shape = PmxRigidBodyShape.entries.firstOrNull { it.encodedValue == shapeValue }
			?: throw PmxFormatException("Unsupported rigid body shape $shapeValue", shapeOffset)
		val size = readVector3(input)
		val position = readVector3(input)
		val rotation = readVector3(input)
		val mass = input.readFloat32()
		val linearDamping = input.readFloat32()
		val angularDamping = input.readFloat32()
		val restitution = input.readFloat32()
		val friction = input.readFloat32()
		val modeOffset = input.position
		val modeValue = input.readUnsignedByte()
		val mode = PmxRigidBodyMode.entries.firstOrNull { it.encodedValue == modeValue }
			?: throw PmxFormatException("Unsupported rigid body mode $modeValue", modeOffset)
		return PmxRigidBody(
			name = name,
			englishName = englishName,
			boneIndex = boneIndex,
			collisionGroup = collisionGroup,
			collisionExclusionMask = collisionExclusionMask,
			shape = shape,
			size = size,
			position = position,
			rotation = rotation,
			mass = mass,
			linearDamping = linearDamping,
			angularDamping = angularDamping,
			restitution = restitution,
			friction = friction,
			mode = mode,
		)
	}

	private fun validateImpulseMorphIndices(input: PmxBinaryInput, morphs: List<PmxMorph>, rigidBodyCount: Int) {
		morphs.forEachIndexed { morphIndex, morph ->
			morph.offsets.forEachIndexed { offsetIndex, offset ->
				if (offset is PmxImpulseMorphOffset && offset.rigidBodyIndex >= rigidBodyCount) {
					input.fail(
						"Morph $morphIndex offset $offsetIndex references rigid body ${offset.rigidBodyIndex} " +
							"outside 0 until $rigidBodyCount",
					)
				}
			}
		}
	}

	private fun readBones(input: PmxBinaryInput, limits: PmxDecodeLimits): PmxBoneAsset {
		val materialAsset = readMaterials(input, limits)
		val format = materialAsset.descriptor.format
		val boneCount = readCount(input, "bone", limits.maxBones)
		validateSkinningBoneIndices(input, materialAsset.geometry.skinning, boneCount)
		val bones = List(boneCount) {
			readBone(input, format, boneCount, limits.maxIkIterationsPerBone, limits.maxIkLinksPerBone)
		}

		return PmxBoneAsset(
			descriptor = materialAsset.descriptor,
			geometry = materialAsset.geometry,
			texturePaths = materialAsset.texturePaths,
			materials = materialAsset.materials,
			bones = bones,
		)
	}

	private fun readMorph(
		input: PmxBinaryInput,
		format: PmxFormat,
		vertexCount: Int,
		materialCount: Int,
		boneCount: Int,
		morphCount: Int,
		maxOffsets: Int,
	): PmxMorph {
		val name = input.readText(format.textEncoding)
		val englishName = input.readText(format.textEncoding)
		val panelOffset = input.position
		val panelValue = input.readUnsignedByte()
		val panel = PmxMorphPanel.entries.firstOrNull { it.encodedValue == panelValue }
			?: throw PmxFormatException("Unsupported morph panel $panelValue", panelOffset)
		val typeOffset = input.position
		val typeValue = input.readUnsignedByte()
		val type = PmxMorphType.entries.firstOrNull { it.encodedValue == typeValue }
			?: throw PmxFormatException("Unsupported morph type $typeValue", typeOffset)
		if (
			format.version == PmxVersion.V2_0 &&
			(type == PmxMorphType.FLIP || type == PmxMorphType.IMPULSE)
		) {
			throw PmxFormatException("${type.name} morph requires PMX 2.1", typeOffset)
		}
		val uvChannel = type.uvChannel
		if (uvChannel != null && uvChannel > format.additionalUvChannels) {
			throw PmxFormatException(
				"Morph UV channel $uvChannel exceeds model additional UV count ${format.additionalUvChannels}",
				typeOffset,
			)
		}
		val offsetCount = readCount(input, "morph offset", maxOffsets)
		val offsets = List(offsetCount) {
			readMorphOffset(input, format, type, vertexCount, materialCount, boneCount, morphCount)
		}
		return PmxMorph(name, englishName, panel, type, offsets)
	}

	private fun readMorphOffset(
		input: PmxBinaryInput,
		format: PmxFormat,
		type: PmxMorphType,
		vertexCount: Int,
		materialCount: Int,
		boneCount: Int,
		morphCount: Int,
	): PmxMorphOffset = when (type) {
		PmxMorphType.GROUP -> PmxGroupMorphOffset(
			morphIndex = readMorphIndex(input, format.indices.morph, morphCount),
			influence = input.readFloat32(),
		)

		PmxMorphType.VERTEX -> PmxVertexMorphOffset(
			vertexIndex = readCheckedVertexIndex(input, format.indices.vertex, vertexCount, "vertex morph"),
			translation = readVector3(input),
		)

		PmxMorphType.BONE -> PmxBoneMorphOffset(
			boneIndex = readBoneIndex(input, format.indices.bone, boneCount, false, "bone morph"),
			translation = readVector3(input),
			rotation = readVector4(input),
		)

		PmxMorphType.UV,
		PmxMorphType.ADDITIONAL_UV_1,
		PmxMorphType.ADDITIONAL_UV_2,
		PmxMorphType.ADDITIONAL_UV_3,
		PmxMorphType.ADDITIONAL_UV_4,
		-> PmxUvMorphOffset(
			vertexIndex = readCheckedVertexIndex(input, format.indices.vertex, vertexCount, "UV morph"),
			uvChannel = requireNotNull(type.uvChannel),
			displacement = readVector4(input),
		)

		PmxMorphType.MATERIAL -> readMaterialMorphOffset(input, format.indices.material, materialCount)
		PmxMorphType.FLIP -> PmxFlipMorphOffset(
			morphIndex = readMorphIndex(input, format.indices.morph, morphCount),
			influence = input.readFloat32(),
		)

		PmxMorphType.IMPULSE -> readImpulseMorphOffset(input, format.indices.rigidBody)
	}

	private fun readMaterialMorphOffset(
		input: PmxBinaryInput,
		materialIndexWidth: PmxIndexWidth,
		materialCount: Int,
	): PmxMaterialMorphOffset {
		val materialIndexOffset = input.position
		val materialIndex = readSignedIndex(input, materialIndexWidth)
		if (materialIndex !in -1 until materialCount) {
			throw PmxFormatException(
				"Material morph index $materialIndex is outside -1 until $materialCount",
				materialIndexOffset,
			)
		}
		val operationOffset = input.position
		val operationValue = input.readUnsignedByte()
		val operation = PmxMaterialMorphOperation.entries.firstOrNull { it.encodedValue == operationValue }
			?: throw PmxFormatException("Unsupported material morph operation $operationValue", operationOffset)
		return PmxMaterialMorphOffset(
			materialIndex = materialIndex,
			operation = operation,
			diffuse = readRgba(input),
			specular = readRgb(input),
			specularStrength = input.readFloat32(),
			ambient = readRgb(input),
			edgeColor = readRgba(input),
			edgeScale = input.readFloat32(),
			textureTint = readRgba(input),
			sphereTint = readRgba(input),
			toonTint = readRgba(input),
		)
	}

	private fun readImpulseMorphOffset(
		input: PmxBinaryInput,
		rigidBodyIndexWidth: PmxIndexWidth,
	): PmxImpulseMorphOffset {
		val indexOffset = input.position
		val rigidBodyIndex = readSignedIndex(input, rigidBodyIndexWidth)
		if (rigidBodyIndex < 0) {
			throw PmxFormatException("Impulse rigid body index $rigidBodyIndex must be non-negative", indexOffset)
		}
		val localOffset = input.position
		val isLocal = when (val localValue = input.readUnsignedByte()) {
			0 -> false
			1 -> true
			else -> throw PmxFormatException("Unsupported impulse local flag $localValue", localOffset)
		}
		return PmxImpulseMorphOffset(
			rigidBodyIndex = rigidBodyIndex,
			isLocal = isLocal,
			velocity = readVector3(input),
			torque = readVector3(input),
		)
	}

	private fun readMorphIndex(input: PmxBinaryInput, width: PmxIndexWidth, morphCount: Int): Int {
		val indexOffset = input.position
		val index = readSignedIndex(input, width)
		if (index !in 0 until morphCount) {
			throw PmxFormatException("Morph index $index is outside 0 until $morphCount", indexOffset)
		}
		return index
	}

	private fun readCheckedVertexIndex(
		input: PmxBinaryInput,
		width: PmxIndexWidth,
		vertexCount: Int,
		label: String,
	): Int {
		val indexOffset = input.position
		val index = readVertexIndex(input, width)
		if (index !in 0 until vertexCount) {
			throw PmxFormatException("$label index $index is outside 0 until $vertexCount", indexOffset)
		}
		return index
	}

	private fun readVector4(input: PmxBinaryInput): PmxVector4 = PmxVector4(
		x = input.readFloat32(),
		y = input.readFloat32(),
		z = input.readFloat32(),
		w = input.readFloat32(),
	)

	private fun readMaterials(input: PmxBinaryInput, limits: PmxDecodeLimits): PmxMaterialAsset {
		val geometryAsset = readGeometry(input, limits)
		val encoding = geometryAsset.descriptor.format.textEncoding
		val textureCount = readCount(input, "texture", limits.maxTextures)
		val texturePaths = List(textureCount) { input.readText(encoding) }
		val materialCount = readCount(input, "material", limits.maxMaterials)
		var firstIndex = 0
		val materials = ArrayList<PmxMaterial>(materialCount)
		repeat(materialCount) {
			val material = readMaterial(
				input = input,
				format = geometryAsset.descriptor.format,
				textureCount = textureCount,
				firstIndex = firstIndex,
			)
			val nextIndex = firstIndex.toLong() + material.indexCount
			if (nextIndex > geometryAsset.geometry.triangleIndices.size) {
				input.fail("Material index ranges exceed geometry index count ${geometryAsset.geometry.triangleIndices.size}")
			}
			materials += material
			firstIndex = nextIndex.toInt()
		}
		if (firstIndex != geometryAsset.geometry.triangleIndices.size) {
			input.fail(
				"Material index ranges cover $firstIndex indices but geometry contains " +
					"${geometryAsset.geometry.triangleIndices.size}",
			)
		}

		return PmxMaterialAsset(
			descriptor = geometryAsset.descriptor,
			geometry = geometryAsset.geometry,
			texturePaths = texturePaths,
			materials = materials,
		)
	}

	private fun readBone(
		input: PmxBinaryInput,
		format: PmxFormat,
		boneCount: Int,
		maxIkIterationsPerBone: Int,
		maxIkLinksPerBone: Int,
	): PmxBone {
		val encoding = format.textEncoding
		val name = input.readText(encoding)
		val englishName = input.readText(encoding)
		val position = readVector3(input)
		val parentBoneIndex = readBoneIndex(input, format.indices.bone, boneCount, true, "parent bone")
		val deformLayer = input.readInt32()
		val flags = PmxBoneFlags(input.readUnsignedInt16())
		val tail = if (flags.hasIndexedTail) {
			PmxBoneTail.LinkedBone(readBoneIndex(input, format.indices.bone, boneCount, true, "tail bone"))
		} else {
			PmxBoneTail.Offset(readVector3(input))
		}
		val inheritance = if (flags.inheritsRotation || flags.inheritsTranslation) {
			PmxBoneInheritance(
				sourceBoneIndex = readBoneIndex(
					input,
					format.indices.bone,
					boneCount,
					true,
					"inheritance source bone",
				),
				influence = input.readFloat32(),
			)
		} else {
			null
		}
		val fixedAxis = if (flags.hasFixedAxis) readVector3(input) else null
		val localAxes = if (flags.hasLocalAxes) {
			PmxLocalAxes(
				xAxis = readVector3(input),
				zAxis = readVector3(input),
			)
		} else {
			null
		}
		val externalParentKey = if (flags.hasExternalParent) input.readInt32() else null
		val inverseKinematics = if (flags.usesInverseKinematics) {
			readInverseKinematics(
				input,
				format.indices.bone,
				boneCount,
				maxIkIterationsPerBone,
				maxIkLinksPerBone,
			)
		} else {
			null
		}

		return PmxBone(
			name = name,
			englishName = englishName,
			position = position,
			parentBoneIndex = parentBoneIndex,
			deformLayer = deformLayer,
			flags = flags,
			tail = tail,
			inheritance = inheritance,
			fixedAxis = fixedAxis,
			localAxes = localAxes,
			externalParentKey = externalParentKey,
			inverseKinematics = inverseKinematics,
		)
	}

	private fun readInverseKinematics(
		input: PmxBinaryInput,
		boneIndexWidth: PmxIndexWidth,
		boneCount: Int,
		maxIkIterationsPerBone: Int,
		maxIkLinksPerBone: Int,
	): PmxInverseKinematics {
		val targetBoneIndex = readBoneIndex(input, boneIndexWidth, boneCount, false, "IK target bone")
		val iterationCountOffset = input.position
		val iterationCount = input.readInt32()
		if (iterationCount !in 0..maxIkIterationsPerBone) {
			throw PmxFormatException(
				"IK iteration count $iterationCount is outside 0..$maxIkIterationsPerBone",
				iterationCountOffset,
			)
		}
		val angleLimit = input.readFloat32()
		val linkCount = readCount(input, "IK link", minOf(boneCount, maxIkLinksPerBone))
		val links = List(linkCount) {
			val boneIndex = readBoneIndex(input, boneIndexWidth, boneCount, false, "IK link bone")
			val limitsOffset = input.position
			val angleLimits = when (val hasLimits = input.readUnsignedByte()) {
				0 -> null
				1 -> PmxIkAngleLimits(
					minimum = readVector3(input),
					maximum = readVector3(input),
				)

				else -> throw PmxFormatException("Unsupported IK angle-limit flag $hasLimits", limitsOffset)
			}
			PmxIkLink(boneIndex, angleLimits)
		}
		return PmxInverseKinematics(targetBoneIndex, iterationCount, angleLimit, links)
	}

	private fun validateSkinningBoneIndices(input: PmxBinaryInput, skinning: PmxSkinning, boneCount: Int) {
		skinning.boneIndices.forEachIndexed { influenceIndex, boneIndex ->
			if (boneIndex < -1 || boneIndex >= boneCount) {
				input.fail("Skinning influence $influenceIndex references bone $boneIndex outside -1 until $boneCount")
			}
		}
	}

	private fun readBoneIndex(
		input: PmxBinaryInput,
		width: PmxIndexWidth,
		boneCount: Int,
		allowNil: Boolean,
		label: String,
	): Int {
		val indexOffset = input.position
		val index = readSignedIndex(input, width)
		val minimum = if (allowNil) -1 else 0
		if (index !in minimum until boneCount) {
			throw PmxFormatException("$label index $index is outside $minimum until $boneCount", indexOffset)
		}
		return index
	}

	private fun readVector3(input: PmxBinaryInput): PmxVector3 = PmxVector3(
		x = input.readFloat32(),
		y = input.readFloat32(),
		z = input.readFloat32(),
	)

	private fun readGeometry(input: PmxBinaryInput, limits: PmxDecodeLimits): PmxGeometryAsset {
		val descriptor = readDescriptor(input)
		val vertexCount = readCount(input, "vertex", limits.maxVertices)
		val positions = FloatArray(checkedArraySize(input, vertexCount, 3, "position"))
		val normals = FloatArray(checkedArraySize(input, vertexCount, 3, "normal"))
		val textureCoordinates = FloatArray(checkedArraySize(input, vertexCount, 2, "texture coordinate"))
		val additionalUvComponents = descriptor.format.additionalUvChannels * 4
		val additionalTextureCoordinates =
			FloatArray(checkedArraySize(input, vertexCount, additionalUvComponents, "additional texture coordinate"))
		val modes = ByteArray(vertexCount)
		val influenceCount = checkedArraySize(input, vertexCount, PmxSkinning.MAX_INFLUENCES, "bone influence")
		val boneIndices = IntArray(influenceCount) { -1 }
		val boneWeights = FloatArray(influenceCount)
		var sdefParameters: FloatArray? = null
		val edgeScales = FloatArray(vertexCount)

		repeat(vertexCount) { vertexIndex ->
			readFloatComponents(input, positions, vertexIndex * 3, 3)
			readFloatComponents(input, normals, vertexIndex * 3, 3)
			readFloatComponents(input, textureCoordinates, vertexIndex * 2, 2)
			readFloatComponents(
				input,
				additionalTextureCoordinates,
				vertexIndex * descriptor.format.additionalUvChannels * 4,
				descriptor.format.additionalUvChannels * 4,
			)

			val modeOffset = input.position
			val mode = decodeSkinningMode(input.readUnsignedByte(), descriptor.format.version, modeOffset)
			modes[vertexIndex] = mode.ordinal.toByte()
			readSkinning(
				input = input,
				mode = mode,
				boneIndexWidth = descriptor.format.indices.bone,
				vertexIndex = vertexIndex,
				boneIndices = boneIndices,
				boneWeights = boneWeights,
				ensureSdefParameters = {
					sdefParameters ?: FloatArray(
						checkedArraySize(input, vertexCount, PmxSkinning.SDEF_COMPONENTS, "SDEF parameter"),
					).also { sdefParameters = it }
				},
			)
			edgeScales[vertexIndex] = input.readFloat32()
		}

		val triangleIndexCount = readCount(input, "triangle index", limits.maxTriangleIndices)
		if (triangleIndexCount % 3 != 0) {
			input.fail("Triangle index count $triangleIndexCount is not divisible by 3")
		}
		val triangleIndices = IntArray(triangleIndexCount) { index ->
			val vertexIndex = readVertexIndex(input, descriptor.format.indices.vertex)
			if (vertexIndex !in 0 until vertexCount) {
				input.fail("Triangle index $index references vertex $vertexIndex outside 0 until $vertexCount")
			}
			vertexIndex
		}

		return PmxGeometryAsset(
			descriptor = descriptor,
			geometry = PmxGeometry(
				positions = positions,
				normals = normals,
				textureCoordinates = textureCoordinates,
				additionalTextureCoordinates = additionalTextureCoordinates,
				additionalUvChannels = descriptor.format.additionalUvChannels,
				skinning = PmxSkinning(modes, boneIndices, boneWeights, sdefParameters),
				edgeScales = edgeScales,
				triangleIndices = triangleIndices,
			),
		)
	}

	private fun readMaterial(
		input: PmxBinaryInput,
		format: PmxFormat,
		textureCount: Int,
		firstIndex: Int,
	): PmxMaterial {
		val encoding = format.textEncoding
		val name = input.readText(encoding)
		val englishName = input.readText(encoding)
		val diffuse = readRgba(input)
		val specular = readRgb(input)
		val specularStrength = input.readFloat32()
		val ambient = readRgb(input)
		val drawFlagsOffset = input.position
		val drawFlags = PmxDrawFlags(input.readUnsignedByte())
		if (format.version == PmxVersion.V2_0 && drawFlags.bits and 0xE0 != 0) {
			throw PmxFormatException("Vertex color, point, and line flags require PMX 2.1", drawFlagsOffset)
		}
		val edgeColor = readRgba(input)
		val edgeScale = input.readFloat32()
		val textureIndex = readTextureIndex(input, format.indices.texture, textureCount, "texture")
		val sphereTextureIndex = readTextureIndex(input, format.indices.texture, textureCount, "sphere texture")
		val sphereModeOffset = input.position
		val sphereMode = decodeSphereMode(input.readUnsignedByte(), sphereModeOffset)
		if (sphereMode == PmxSphereMode.ADDITIONAL_UV && format.additionalUvChannels == 0) {
			throw PmxFormatException("Additional UV sphere mode requires an additional UV channel", sphereModeOffset)
		}
		val toonReferenceOffset = input.position
		val toonTexture = when (val reference = input.readUnsignedByte()) {
			0 -> PmxToonTexture.Custom(
				readTextureIndex(input, format.indices.texture, textureCount, "toon texture"),
			)

			1 -> {
				val slotOffset = input.position
				val slot = input.readUnsignedByte()
				if (slot !in 0..9) {
					throw PmxFormatException("Shared toon slot $slot is outside 0..9", slotOffset)
				}
				PmxToonTexture.Shared(slot)
			}

			else -> throw PmxFormatException("Unsupported toon reference $reference", toonReferenceOffset)
		}
		val metadata = input.readText(encoding)
		val indexCountOffset = input.position
		val indexCount = input.readInt32()
		if (indexCount < 0 || indexCount % 3 != 0) {
			throw PmxFormatException("Material index count $indexCount must be a non-negative multiple of 3", indexCountOffset)
		}

		return PmxMaterial(
			name = name,
			englishName = englishName,
			diffuse = diffuse,
			specular = specular,
			specularStrength = specularStrength,
			ambient = ambient,
			drawFlags = drawFlags,
			edgeColor = edgeColor,
			edgeScale = edgeScale,
			textureIndex = textureIndex,
			sphereTextureIndex = sphereTextureIndex,
			sphereMode = sphereMode,
			toonTexture = toonTexture,
			metadata = metadata,
			firstIndex = firstIndex,
			indexCount = indexCount,
		)
	}

	private fun readRgb(input: PmxBinaryInput): PmxRgb = PmxRgb(
		red = input.readFloat32(),
		green = input.readFloat32(),
		blue = input.readFloat32(),
	)

	private fun readRgba(input: PmxBinaryInput): PmxRgba = PmxRgba(
		red = input.readFloat32(),
		green = input.readFloat32(),
		blue = input.readFloat32(),
		alpha = input.readFloat32(),
	)

	private fun readTextureIndex(
		input: PmxBinaryInput,
		width: PmxIndexWidth,
		textureCount: Int,
		label: String,
	): Int {
		val indexOffset = input.position
		val index = readSignedIndex(input, width)
		if (index < -1 || index >= textureCount) {
			throw PmxFormatException("$label index $index is outside -1 until $textureCount", indexOffset)
		}
		return index
	}

	private fun decodeSphereMode(value: Int, byteOffset: Int): PmxSphereMode =
		PmxSphereMode.entries.firstOrNull { it.encodedValue == value }
			?: throw PmxFormatException("Unsupported sphere mode $value", byteOffset)

	private fun readDescriptor(input: PmxBinaryInput): PmxModelDescriptor {
		val actualSignature = input.readBytes(signature.size)
		if (!actualSignature.contentEquals(signature)) {
			throw PmxFormatException("Invalid PMX signature", 0)
		}

		val version = decodeVersion(input.readFloat32(), input)
		val globalsOffset = input.position + Byte.SIZE_BYTES
		val globals = input.readBytes(input.readUnsignedByte())
		if (globals.size < 8) {
			throw PmxFormatException("PMX globals must contain at least 8 bytes", globalsOffset)
		}

		val encoding = decodeEncoding(globals[0].toInt() and 0xFF, globalsOffset)
		val additionalUvChannels = globals[1].toInt() and 0xFF
		if (additionalUvChannels !in 0..4) {
			throw PmxFormatException("Additional UV channel count must be between 0 and 4", globalsOffset + 1)
		}

		val format = PmxFormat(
			version = version,
			textEncoding = encoding,
			additionalUvChannels = additionalUvChannels,
			indices = PmxIndexLayout(
				vertex = decodeIndexWidth(globals[2], "vertex", globalsOffset + 2),
				texture = decodeIndexWidth(globals[3], "texture", globalsOffset + 3),
				material = decodeIndexWidth(globals[4], "material", globalsOffset + 4),
				bone = decodeIndexWidth(globals[5], "bone", globalsOffset + 5),
				morph = decodeIndexWidth(globals[6], "morph", globalsOffset + 6),
				rigidBody = decodeIndexWidth(globals[7], "rigid body", globalsOffset + 7),
			),
		)

		return PmxModelDescriptor(
			format = format,
			metadata = PmxModelMetadata(
				name = input.readText(encoding),
				englishName = input.readText(encoding),
				description = input.readText(encoding),
				englishDescription = input.readText(encoding),
			),
		)
	}

	private fun readSkinning(
		input: PmxBinaryInput,
		mode: PmxSkinningMode,
		boneIndexWidth: PmxIndexWidth,
		vertexIndex: Int,
		boneIndices: IntArray,
		boneWeights: FloatArray,
		ensureSdefParameters: () -> FloatArray,
	) {
		val influenceOffset = vertexIndex * PmxSkinning.MAX_INFLUENCES
		when (mode) {
			PmxSkinningMode.BDEF1 -> {
				boneIndices[influenceOffset] = readSignedIndex(input, boneIndexWidth)
				boneWeights[influenceOffset] = 1.0f
			}

			PmxSkinningMode.BDEF2 -> readTwoBoneSkinning(
				input,
				boneIndexWidth,
				influenceOffset,
				boneIndices,
				boneWeights,
			)

			PmxSkinningMode.BDEF4,
			PmxSkinningMode.QDEF,
			-> {
				repeat(PmxSkinning.MAX_INFLUENCES) { influence ->
					boneIndices[influenceOffset + influence] = readSignedIndex(input, boneIndexWidth)
				}
				repeat(PmxSkinning.MAX_INFLUENCES) { influence ->
					boneWeights[influenceOffset + influence] = input.readFloat32()
				}
			}

			PmxSkinningMode.SDEF -> {
				readTwoBoneSkinning(input, boneIndexWidth, influenceOffset, boneIndices, boneWeights)
				readFloatComponents(
					input,
					ensureSdefParameters(),
					vertexIndex * PmxSkinning.SDEF_COMPONENTS,
					PmxSkinning.SDEF_COMPONENTS,
				)
			}
		}
	}

	private fun readTwoBoneSkinning(
		input: PmxBinaryInput,
		boneIndexWidth: PmxIndexWidth,
		influenceOffset: Int,
		boneIndices: IntArray,
		boneWeights: FloatArray,
	) {
		boneIndices[influenceOffset] = readSignedIndex(input, boneIndexWidth)
		boneIndices[influenceOffset + 1] = readSignedIndex(input, boneIndexWidth)
		val firstWeight = input.readFloat32()
		boneWeights[influenceOffset] = firstWeight
		boneWeights[influenceOffset + 1] = 1.0f - firstWeight
	}

	private fun readFloatComponents(input: PmxBinaryInput, target: FloatArray, offset: Int, count: Int) {
		repeat(count) { target[offset + it] = input.readFloat32() }
	}

	private fun readCount(input: PmxBinaryInput, label: String, maximum: Int): Int {
		val countOffset = input.position
		val count = input.readInt32()
		if (count !in 0..maximum) {
			throw PmxFormatException("$label count $count is outside 0..$maximum", countOffset)
		}
		return count
	}

	private fun checkedArraySize(input: PmxBinaryInput, count: Int, components: Int, label: String): Int {
		val size = count.toLong() * components
		if (size > Int.MAX_VALUE) {
			input.fail("$label array size $size exceeds JVM limits")
		}
		return size.toInt()
	}

	private fun readSignedIndex(input: PmxBinaryInput, width: PmxIndexWidth): Int = when (width) {
		PmxIndexWidth.ONE -> input.readInt8()
		PmxIndexWidth.TWO -> input.readInt16()
		PmxIndexWidth.FOUR -> input.readInt32()
	}

	private fun readVertexIndex(input: PmxBinaryInput, width: PmxIndexWidth): Int = when (width) {
		PmxIndexWidth.ONE -> input.readUnsignedByte()
		PmxIndexWidth.TWO -> input.readUnsignedInt16()
		PmxIndexWidth.FOUR -> input.readInt32()
	}

	private fun decodeSkinningMode(value: Int, version: PmxVersion, byteOffset: Int): PmxSkinningMode {
		val mode = PmxSkinningMode.entries.firstOrNull { it.encodedValue == value }
			?: throw PmxFormatException("Unsupported PMX skinning mode $value", byteOffset)
		if (mode == PmxSkinningMode.QDEF && version != PmxVersion.V2_1) {
			throw PmxFormatException("QDEF requires PMX 2.1", byteOffset)
		}
		return mode
	}

	private fun decodeVersion(value: Float, input: PmxBinaryInput): PmxVersion =
		PmxVersion.entries.firstOrNull { abs(it.encodedValue - value) < 0.0001f }
			?: input.fail("Unsupported PMX version $value")

	private fun decodeEncoding(value: Int, byteOffset: Int): PmxTextEncoding = when (value) {
		0 -> PmxTextEncoding.UTF_16_LE
		1 -> PmxTextEncoding.UTF_8
		else -> throw PmxFormatException("Unsupported PMX text encoding $value", byteOffset)
	}

	private fun decodeIndexWidth(value: Byte, field: String, byteOffset: Int): PmxIndexWidth =
		PmxIndexWidth.entries.firstOrNull { it.byteCount == value.toInt() }
			?: throw PmxFormatException("Unsupported $field index width ${value.toInt() and 0xFF}", byteOffset)
}

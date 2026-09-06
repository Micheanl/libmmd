	package com.micheanl.libmmd.format.pmx

enum class PmxSoftBodyShape(val encodedValue: Int) {
	TRIANGLE_MESH(0),
	ROPE(1),
}

enum class PmxSoftBodyAerodynamics(val encodedValue: Int) {
	VERTEX_POINT(0),
	VERTEX_TWO_SIDED(1),
	VERTEX_ONE_SIDED(2),
	FACE_TWO_SIDED(3),
	FACE_ONE_SIDED(4),
}

@JvmInline
value class PmxSoftBodyFlags(val bits: Int) {
	val usesBLink: Boolean get() = contains(B_LINK)
	val createsClusters: Boolean get() = contains(CLUSTER_CREATION)
	val allowsLinkCrossing: Boolean get() = contains(LINK_CROSSING)

	private fun contains(mask: Int): Boolean = bits and mask != 0

	private companion object {
		const val B_LINK = 1 shl 0
		const val CLUSTER_CREATION = 1 shl 1
		const val LINK_CROSSING = 1 shl 2
	}
}

data class PmxSoftBodyConfig(
	val velocityCorrectionFactor: Float,
	val dampingCoefficient: Float,
	val dragCoefficient: Float,
	val liftCoefficient: Float,
	val pressureCoefficient: Float,
	val volumeConservationCoefficient: Float,
	val dynamicFrictionCoefficient: Float,
	val poseMatchingCoefficient: Float,
	val rigidContactHardness: Float,
	val kineticContactHardness: Float,
	val softContactHardness: Float,
	val anchorHardness: Float,
)

data class PmxSoftBodyCluster(
	val softRigidHardness: Float,
	val softKineticHardness: Float,
	val softSoftHardness: Float,
	val softRigidImpulseSplit: Float,
	val softKineticImpulseSplit: Float,
	val softSoftImpulseSplit: Float,
)

data class PmxSoftBodySolverIterations(
	val velocity: Int,
	val position: Int,
	val drift: Int,
	val cluster: Int,
)

data class PmxSoftBodyMaterialCoefficients(
	val linearStiffness: Float,
	val angularStiffness: Float,
	val volumeStiffness: Float,
)

data class PmxSoftBodyAnchor(
	val rigidBodyIndex: Int,
	val vertexIndex: Int,
	val isNearMode: Boolean,
)

data class PmxSoftBody(
	val name: String,
	val englishName: String,
	val shape: PmxSoftBodyShape,
	val materialIndex: Int,
	val collisionGroup: Int,
	val collisionExclusionMask: Int,
	val flags: PmxSoftBodyFlags,
	val bLinkDistance: Int,
	val clusterCount: Int,
	val totalMass: Float,
	val collisionMargin: Float,
	val aerodynamics: PmxSoftBodyAerodynamics,
	val config: PmxSoftBodyConfig,
	val cluster: PmxSoftBodyCluster,
	val solverIterations: PmxSoftBodySolverIterations,
	val materialCoefficients: PmxSoftBodyMaterialCoefficients,
	val anchors: List<PmxSoftBodyAnchor>,
	val pinnedVertexIndices: IntArray,
)

data class PmxSoftBodyAsset(
	val descriptor: PmxModelDescriptor,
	val geometry: PmxGeometry,
	val texturePaths: List<String>,
	val materials: List<PmxMaterial>,
	val bones: List<PmxBone>,
	val morphs: List<PmxMorph>,
	val displayFrames: List<PmxDisplayFrame>,
	val rigidBodies: List<PmxRigidBody>,
	val joints: List<PmxJoint>,
	val softBodies: List<PmxSoftBody>,
)

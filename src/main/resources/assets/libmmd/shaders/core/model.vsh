#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in vec4 Normal;
layout(location = 4) in ivec4 BoneIndices;
layout(location = 5) in vec4 BoneWeights;

uniform samplerBuffer BoneTransforms;
uniform samplerBuffer MorphOffsets;
uniform samplerBuffer SoftBodyOffsets;

layout(location = 0) out vec2 texCoord;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec3 vertexNormal;

void main() {
	int morphOffsetIndex = gl_VertexIndex * 2;
	vec4 primaryMorphOffset = texelFetch(MorphOffsets, morphOffsetIndex);
	vec4 secondaryMorphOffset = texelFetch(MorphOffsets, morphOffsetIndex + 1);
	vec3 morphedPosition = Position + primaryMorphOffset.xyz;
	mat4 skinTransform =
		mat4(
			texelFetch(BoneTransforms, BoneIndices.x * 4),
			texelFetch(BoneTransforms, BoneIndices.x * 4 + 1),
			texelFetch(BoneTransforms, BoneIndices.x * 4 + 2),
			texelFetch(BoneTransforms, BoneIndices.x * 4 + 3)
		) * BoneWeights.x +
		mat4(
			texelFetch(BoneTransforms, BoneIndices.y * 4),
			texelFetch(BoneTransforms, BoneIndices.y * 4 + 1),
			texelFetch(BoneTransforms, BoneIndices.y * 4 + 2),
			texelFetch(BoneTransforms, BoneIndices.y * 4 + 3)
		) * BoneWeights.y +
		mat4(
			texelFetch(BoneTransforms, BoneIndices.z * 4),
			texelFetch(BoneTransforms, BoneIndices.z * 4 + 1),
			texelFetch(BoneTransforms, BoneIndices.z * 4 + 2),
			texelFetch(BoneTransforms, BoneIndices.z * 4 + 3)
		) * BoneWeights.z +
		mat4(
			texelFetch(BoneTransforms, BoneIndices.w * 4),
			texelFetch(BoneTransforms, BoneIndices.w * 4 + 1),
			texelFetch(BoneTransforms, BoneIndices.w * 4 + 2),
			texelFetch(BoneTransforms, BoneIndices.w * 4 + 3)
		) * BoneWeights.w;
	vec4 skinnedPosition = skinTransform * vec4(morphedPosition, 1.0);
	skinnedPosition.xyz += texelFetch(SoftBodyOffsets, gl_VertexIndex).xyz;
	gl_Position = ProjMat * ModelViewMat * skinnedPosition;
	texCoord = UV0 + vec2(primaryMorphOffset.w, secondaryMorphOffset.x);
	vertexColor = Color;
	vec3 skinnedNormal = normalize(mat3(skinTransform) * Normal.xyz);
	vertexNormal = normalize(transpose(inverse(mat3(ModelViewMat))) * skinnedNormal);
}

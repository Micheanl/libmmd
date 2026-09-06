#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec3 vertexNormal;

layout(location = 0) out vec4 fragColor;

vec3 shadeToon(vec3 color, vec3 normal) {
	vec3 direction = normalize(normal);
	float halfLambert = dot(direction, normalize(vec3(0.3, 0.8, 0.5))) * 0.5 + 0.5;
	float light = mix(0.52, 0.78, smoothstep(0.42, 0.48, halfLambert));
	light = mix(light, 1.0, smoothstep(0.7, 0.76, halfLambert));
	vec3 shaded = color * light * mix(vec3(0.76, 0.82, 1.0), vec3(1.0), light);
	float facing = abs(direction.z);
	float silhouette = smoothstep(0.035, 0.14, facing);
	float rim = pow(1.0 - facing, 3.0) * 0.1 * silhouette;
	return min(mix(vec3(0.025, 0.03, 0.045), shaded, silhouette) + color * rim, vec3(1.0));
}

void main() {
	vec4 baseColor = texture(Sampler0, texCoord) * vertexColor * ColorModulator;
	if (baseColor.a <= 0.001) {
		discard;
	}
	fragColor = vec4(shadeToon(baseColor.rgb, vertexNormal), baseColor.a);
}

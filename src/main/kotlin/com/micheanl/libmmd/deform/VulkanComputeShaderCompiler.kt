package com.micheanl.libmmd.deform

import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import java.nio.ByteBuffer

object VulkanComputeShaderCompiler {
	fun compile(resource: String): ByteBuffer {
		val source = checkNotNull(javaClass.getResourceAsStream("/assets/libmmd/shaders/compute/$resource")) {
			"Missing compute shader $resource"
		}.bufferedReader().use { it.readText() }
		val compiler = Shaderc.shaderc_compiler_initialize()
		val options = Shaderc.shaderc_compile_options_initialize()
		check(compiler != 0L && options != 0L) { "Unable to initialize Shaderc" }
		try {
			Shaderc.shaderc_compile_options_add_macro_definition(options, "LIBMMD_VULKAN", "1")
			Shaderc.shaderc_compile_options_set_target_env(
				options,
				Shaderc.shaderc_target_env_vulkan,
				Shaderc.shaderc_env_version_vulkan_1_3,
			)
			val result = Shaderc.shaderc_compile_into_spv(
				compiler,
				source,
				Shaderc.shaderc_compute_shader,
				resource,
				"main",
				options,
			)
			check(result != 0L) { "$resource compilation failed" }
			try {
				check(Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success) {
					"$resource compilation failed: ${Shaderc.shaderc_result_get_error_message(result)}"
				}
				val sourceBytes = checkNotNull(Shaderc.shaderc_result_get_bytes(result))
				return MemoryUtil.memAlloc(sourceBytes.remaining()).put(sourceBytes).flip()
			} finally {
				Shaderc.shaderc_result_release(result)
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options)
			Shaderc.shaderc_compiler_release(compiler)
		}
	}
}

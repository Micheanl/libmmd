package com.micheanl.libmmd.deform

import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.system.MemoryUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ComputeShaderTest {
	@Test
	fun `all xpbd shaders compile to Vulkan spir-v`() {
		SHADERS.forEach { shader -> MemoryUtil.memFree(VulkanComputeShaderCompiler.compile(shader)) }
	}

	@Test
	fun `all xpbd shaders compile for OpenGL`() {
		compile(Shaderc.shaderc_target_env_opengl, Shaderc.shaderc_env_version_opengl_4_5, false)
	}

	private fun compile(target: Int, version: Int, vulkan: Boolean) {
		val compiler = Shaderc.shaderc_compiler_initialize()
		val options = Shaderc.shaderc_compile_options_initialize()
		check(compiler != 0L && options != 0L)
		try {
			if (vulkan) Shaderc.shaderc_compile_options_add_macro_definition(options, "LIBMMD_VULKAN", "1")
			if (!vulkan) Shaderc.shaderc_compile_options_set_auto_map_locations(options, true)
			Shaderc.shaderc_compile_options_set_target_env(options, target, version)
			SHADERS.forEach { shader ->
				val source = assertNotNull(javaClass.getResource("/assets/libmmd/shaders/compute/$shader")).readText()
				val result = Shaderc.shaderc_compile_into_spv(
					compiler,
					source,
					Shaderc.shaderc_compute_shader,
					shader,
					"main",
					options,
				)
				check(result != 0L)
				try {
					assertEquals(
						Shaderc.shaderc_compilation_status_success,
						Shaderc.shaderc_result_get_compilation_status(result),
						Shaderc.shaderc_result_get_error_message(result),
					)
					checkNotNull(Shaderc.shaderc_result_get_bytes(result))
				} finally {
					Shaderc.shaderc_result_release(result)
				}
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options)
			Shaderc.shaderc_compiler_release(compiler)
		}
	}

	private companion object {
		val SHADERS = listOf(
			"xpbd_integrate.comp",
			"xpbd_constraint.comp",
			"xpbd_volume.comp",
			"xpbd_capsule.comp",
			"xpbd_hash.comp",
			"xpbd_self_collision.comp",
			"xpbd_apply_collision.comp",
			"xpbd_output.comp",
		)
	}
}

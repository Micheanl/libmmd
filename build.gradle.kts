plugins {
	id("net.fabricmc.fabric-loom")
	`maven-publish`
}

val minecraftVersion = providers.gradleProperty("minecraft_version")
val loaderVersion = providers.gradleProperty("loader_version")
val fabricApiVersion = providers.gradleProperty("fabric_api_version")
val modVersion = version.toString()
val nativePlatform = when {
	System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
	System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
	else -> "linux"
}
val nativeArchitecture = when (System.getProperty("os.arch").lowercase()) {
	"amd64", "x86_64" -> "x86_64"
	"aarch64", "arm64" -> "aarch64"
	else -> System.getProperty("os.arch").lowercase()
}
val nativeLibraryName = System.mapLibraryName("mmd")
val nativeLibrary = layout.projectDirectory.file("bazel-bin/native/libmmd/$nativeLibraryName")
val generatedNativeResources = layout.buildDirectory.dir("generated/nativeResources")
val nativeBundle = providers.gradleProperty("libmmd.native.bundle")
    .map { layout.projectDirectory.dir(it) }
val rebuildNative = providers.gradleProperty("libmmd.native.rebuild")
	.map(String::toBoolean)
	.orElse(false)

val buildNative = tasks.register<Exec>("buildNative") {
	inputs.files(fileTree("native"), fileTree("third_party"), "MODULE.bazel", "BUILD.bazel", ".bazelrc", ".bazelversion")
	inputs.property("rebuild", rebuildNative)
	outputs.file(nativeLibrary)
	commandLine("bazel", "build", "//:libmmd", "--config=release")
	onlyIf {
		rebuildNative.get() || !nativeLibrary.asFile.isFile
	}
}

val stageNative = tasks.register<Sync>("stageNative") {
	into(generatedNativeResources)
	doFirst {
		delete(generatedNativeResources)
	}
	if (nativeBundle.isPresent) {
		from(nativeBundle)
		doFirst {
			require(nativeBundle.get().file("native").asFile.isDirectory) {
				"libmmd.native.bundle must contain a native directory"
			}
		}
	} else {
		dependsOn(buildNative)
		from(nativeLibrary) {
			into("native/$nativePlatform-$nativeArchitecture")
		}
	}
}

sourceSets.main {
	resources.srcDir(generatedNativeResources)
}

dependencies {
	minecraft(minecraftVersion.map { "com.mojang:minecraft:$it" })
	implementation(loaderVersion.map { "net.fabricmc:fabric-loader:$it" })

	implementation(fabricApiVersion.map { "net.fabricmc.fabric-api:fabric-api:$it" })
	testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

tasks.test {
	useJUnitPlatform()
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.processResources {
	dependsOn(stageNative)
	inputs.property("version", modVersion)

	filesMatching("fabric.mod.json") {
		expand("version" to modVersion)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

java {
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

tasks.named("sourcesJar") {
	dependsOn(stageNative)
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}
}

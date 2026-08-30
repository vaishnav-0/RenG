import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.3.21"
    id("com.android.kotlin.multiplatform.library") version "9.3.1"
    // ADR 0042. The harness reads a JSON corpus into RenG's own public types; `-core` arrives
    // transitively from RenG's `api` dependency, and only the format is added here.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
}

val declaredLibraryVersion = providers
    .fileContents(layout.projectDirectory.file("../gradle.properties"))
    .asText
    .map { text ->
        text.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("VERSION_NAME=") }
            ?.substringAfter('=')
            ?.trim()
            .orEmpty()
    }

val rengVersion: String = providers.gradleProperty("rengVersion")
    .orElse(declaredLibraryVersion)
    .orNull
    ?.takeIf(String::isNotEmpty)
    ?: error(
        "Cannot determine the RenG version to consume: pass " +
            "-PrengVersion=<version> or declare VERSION_NAME in the parent gradle.properties.",
    )

kotlin {
    android {
        namespace = "com.rohittp.reng.smoke"
        compileSdk = 37
        minSdk = 30
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    iosArm64()
    iosSimulatorArm64()
    // The visual harness is a macOS-only executable living beside the six-target resolution proof,
    // never inside it. The target factory keeps its parenthesised call form because
    // `tools/check_repository_policy.py` counts exactly that shape; `binaries` and `executable` are
    // not target factories and add no target.
    macosArm64().binaries {
        executable("harness") {
            entryPoint = "com.rohittp.reng.smoke.harness.main"
        }
    }
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("com.rohittp.reng:kmp:$rengVersion")
        }
        macosArm64Main.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
    }
}

/**
 * Runs the visual harness over one config and one plan file.
 *
 * **No style url is passed and none is checked in.** A config names a style by *id*; the base url
 * lives in the untracked `local.properties` as `reng.harness.styleBaseUrl` (or in the
 * `RENG_HARNESS_STYLE_BASE_URL` environment variable), and a run composes `<base>/<id>`. The api key
 * therefore never appears in a corpus file, a command line, or a commit.
 *
 *   ./gradlew -p consumer-smoke runHarness -Pconfig=corpus/configs/mercator.json \
 *                                          -Pplans=corpus/plans/storyboard.json
 *
 * `-Pemit=<path>` writes the built-in storyboard out as a plan file instead of rendering, which is
 * how `corpus/plans/storyboard.json` was authored.
 */
val runHarness by tasks.registering(Exec::class) {
    group = "verification"
    description = "Renders a plan file with RenG and writes PPM files for ffmpeg."
    val link = tasks.named("linkHarnessDebugExecutableMacosArm64")
    dependsOn(link)
    val binary = layout.buildDirectory.file("bin/macosArm64/harnessDebugExecutable/harness.kexe")
    val frames = layout.buildDirectory.dir("harness-frames")
    val configPath = providers.gradleProperty("config")
        .orElse("corpus/configs/style-59.json")
    val plansPath = providers.gradleProperty("plans")
        .orElse("corpus/plans/storyboard.json")
    val outputPath = providers.gradleProperty("out")
    val emitPath = providers.gradleProperty("emit")
    val verbose = providers.gradleProperty("verbose").isPresent
    // The repository root's `local.properties`, not the consumer project's: an `Exec` task runs in
    // its own project directory, and the settings file every other tool here reads is one level up.
    val localProperties = layout.projectDirectory.file("../local.properties").asFile.absolutePath
    doFirst {
        frames.get().asFile.mkdirs()
        outputPath.orNull?.let { File(it).mkdirs() }
    }
    commandLine(
        buildList {
            add(binary.get().asFile.absolutePath)
            val emit = emitPath.orNull
            if (emit != null) {
                add("--emit-plans")
                add(emit)
            } else {
                add("--config")
                add(configPath.get())
                add("--plans")
                add(plansPath.get())
                add("--out")
                add(outputPath.getOrElse(frames.get().asFile.absolutePath))
                add("--local-properties")
                add(localProperties)
            }
            if (verbose) add("--verbose")
        },
    )
}

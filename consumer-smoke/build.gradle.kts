import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.3.21"
    id("com.android.kotlin.multiplatform.library") version "9.3.1"
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
    }
}

/**
 * Runs the visual harness against a real map style.
 *
 * The style url carries the owner's api key, so it is never checked in: pass it as
 * `-PstyleUrl=<url>` or in the `RENG_HARNESS_STYLE_URL` environment variable. A model is optional and
 * equally uncheckable — `-PmodelUrl=<url>` or `RENG_HARNESS_MODEL_URL`; without one the storyboard
 * renders the basemap, stickers and geometry it always did, and no model.
 */
val runHarness by tasks.registering(Exec::class) {
    group = "verification"
    description = "Renders a frame sequence with RenG and writes PPM files for ffmpeg."
    val link = tasks.named("linkHarnessDebugExecutableMacosArm64")
    dependsOn(link)
    val binary = layout.buildDirectory.file("bin/macosArm64/harnessDebugExecutable/harness.kexe")
    val frames = layout.buildDirectory.dir("harness-frames")
    val styleUrl = providers.gradleProperty("styleUrl")
        .orElse(providers.environmentVariable("RENG_HARNESS_STYLE_URL"))
    // The Cycle F-2 model, optional and never checked in for the same reason the style is not: a GLB
    // url points at somebody's server. Absent, the storyboard simply carries no model.
    val modelUrl = providers.gradleProperty("modelUrl")
        .orElse(providers.environmentVariable("RENG_HARNESS_MODEL_URL"))
    val frameCount = providers.gradleProperty("frameCount")
    // `-PnoBasemap` renders the same camera path with `drawBasemap = false` on every frame, so the
    // stickers and the geometry can be watched even when the ground itself will not draw.
    val groundless = providers.gradleProperty("noBasemap").isPresent
    // `-PnoLabels` renders the same camera path with `drawLabels = false` on every frame. Subtracting
    // that run from an ordinary one is how a frame's label ink gets counted instead of eyeballed.
    val labelless = providers.gradleProperty("noLabels").isPresent
    val verbose = providers.gradleProperty("verbose").isPresent
    doFirst {
        frames.get().asFile.mkdirs()
    }
    commandLine(
        buildList {
            add(binary.get().asFile.absolutePath)
            add("--style")
            add(styleUrl.getOrElse(""))
            add("--out")
            add(frames.get().asFile.absolutePath)
            modelUrl.orNull?.takeIf(String::isNotBlank)?.let { add("--model"); add(it) }
            frameCount.orNull?.let { add("--frames"); add(it) }
            if (groundless) add("--no-basemap")
            if (labelless) add("--no-labels")
            if (verbose) add("--verbose")
        },
    )
}

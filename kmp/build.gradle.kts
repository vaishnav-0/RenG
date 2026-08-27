import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()

    // Required, not redundant. The explicit `dependsOn(commonTest)` edge on `androidDeviceTest`
    // below makes the Kotlin Gradle Plugin stop applying the default hierarchy template to this
    // project -- as a *warning*. The consequence is not a warning: `iosMain`, `nativeMain`,
    // `linuxMain` and `macosMain` stop being wired to `commonMain`, and every Kotlin/Native
    // compilation then fails with "Expected ... has no actual declaration in module <commonMain>
    // for Native". The whole Android half of the build stays green while that is true, so deleting
    // this line breaks four targets and shows nothing on the platform it was deleted for.
    applyDefaultHierarchyTemplate()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
        klib {
            keepUnsupportedTargets = false
        }
    }

    android {
        namespace = "com.rohittp.reng"
        compileSdk = 37
        minSdk = 30
        withHostTest {}
        // ADR 0032. Registers `compileAndroidDeviceTest`, `packageAndroidDeviceTest` and
        // `connectedAndroidDeviceTest`; the runner class named here lives in `androidx.test:runner`,
        // which is why that coordinate is declared below. Instrumented tests are the only way RenG
        // executes `AndroidGlBinding` against a real driver.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.rentile.kmp)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // `androidDeviceTest` is not reached by the default hierarchy template, so without this
        // edge it sees neither `commonTest`'s suites nor `commonMain`'s internals. See the comment
        // on `applyDefaultHierarchyTemplate()` above for what taking the edge costs.
        val androidDeviceTest by getting {
            dependsOn(commonTest.get())
        }
        // The edge carries source, not dependencies: an `androidDeviceTest` with no block of its
        // own does not even resolve `kotlin.test`. ADR 0032 admits exactly these three.
        androidDeviceTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    pom {
        name.set("RenG KMP")
        description.set(
            "Kotlin Multiplatform 3D renderer built on Rentile basemap tiles.",
        )
        inceptionYear.set("2026")
        url.set("https://rohittp.com/reng/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("rohittp0")
                name.set("Rohit T P")
                email.set("tprohit9@gmail.com")
                organization.set("rohittp.com")
                organizationUrl.set("https://rohittp.com")
                url.set("https://rohittp.com")
            }
        }
        scm {
            url.set("https://github.com/rohittp0/RenG")
            connection.set("scm:git:git://github.com/rohittp0/RenG.git")
            developerConnection.set("scm:git:ssh://git@github.com/rohittp0/RenG.git")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "LocalTest"
            url = uri(rootProject.layout.buildDirectory.dir("local-maven"))
        }
    }
}

val targetR2PublicationTasks = listOf(
    "publishAndroidPublicationToR2Repository",
    "publishIosArm64PublicationToR2Repository",
    "publishIosSimulatorArm64PublicationToR2Repository",
    "publishMacosArm64PublicationToR2Repository",
    "publishLinuxX64PublicationToR2Repository",
    "publishLinuxArm64PublicationToR2Repository",
)

tasks.withType<PublishToMavenRepository>()
    .matching { it.name == "publishKotlinMultiplatformPublicationToR2Repository" }
    .configureEach {
        dependsOn(targetR2PublicationTasks)
    }

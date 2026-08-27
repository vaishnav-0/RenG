from __future__ import annotations

import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
import struct
import subprocess
from tempfile import TemporaryDirectory

from unittest.mock import patch

from tools.check_repository_policy import (
    _EXPECTED_PRODUCTION_BUILD_FINGERPRINTS,
    _build_configuration_fingerprint,
    check_dependencies,
    check_repository,
    main,
)

TARGETS = """
android { compileSdk = 37; minSdk = 30 }
iosArm64()
iosSimulatorArm64()
macosArm64()
linuxX64()
linuxArm64()
"""

ROOT_PLUGIN_BLOCK = """plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.maven.publish) apply false
}
"""

KMP_PLUGIN_BLOCK = """plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
}
"""

SETTINGS_PLUGIN_BLOCK = """plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
"""

PLUGIN_MANAGEMENT_BLOCK = r"""pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
"""

DEPENDENCY_RESOLUTION_BLOCK = """dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "Rentile"
                    url = uri("https://maven.rohittp.com")
                }
            }
            filter {
                includeGroup("com.rohittp.rentile")
            }
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev")
            content {
                includeGroup("org.jetbrains.skiko")
            }
        }
    }
}
"""

ROOT_R2_SUPPORT = """val r2Endpoint = providers.environmentVariable("R2_ENDPOINT")
val r2Bucket = providers.environmentVariable("R2_BUCKET")

r2Endpoint.orNull?.let {
    System.setProperty("org.gradle.s3.endpoint", it)
}
"""

ROOT_PUBLISHING_REPOSITORY_BLOCK = """subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "R2"
                    url = uri("s3://${r2Bucket.orNull ?: "r2-publishing-not-configured"}")
                    credentials(AwsCredentials::class) {
                        accessKey = providers.environmentVariable("R2_ACCESS_KEY_ID").orNull
                        secretKey = providers.environmentVariable("R2_SECRET_ACCESS_KEY").orNull
                    }
                }
            }
        }
    }

    tasks.withType(PublishToMavenRepository::class.java)
        .matching { it.name.endsWith("ToR2Repository") }
        .configureEach {
            notCompatibleWithConfigurationCache(
                "Remote Maven publishing is not configuration-cache compatible.",
            )
        }
}
"""

KMP_PUBLISHING_REPOSITORY_BLOCK = """publishing {
    repositories {
        maven {
            name = "LocalTest"
            url = uri(rootProject.layout.buildDirectory.dir("local-maven"))
        }
    }
}
"""

ROOT_BUILD = (
    """import org.gradle.api.credentials.AwsCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

"""
    + ROOT_PLUGIN_BLOCK
    + "\n"
    + ROOT_R2_SUPPORT
    + """
allprojects {
    group = "com.rohittp.reng"
    version = providers.gradleProperty("VERSION_NAME").get()
}

"""
    + ROOT_PUBLISHING_REPOSITORY_BLOCK
)

SETTINGS_BUILD = (
    PLUGIN_MANAGEMENT_BLOCK
    + SETTINGS_PLUGIN_BLOCK
    + DEPENDENCY_RESOLUTION_BLOCK
    + """
rootProject.name = "RenG"
include(":kmp")
"""
)

KMP_BUILD = """import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()

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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
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
"""

VERSION_CATALOG = """[versions]
agp = "9.3.1"
kotlin = "2.3.21"
mavenPublish = "0.36.0"
rentile = "0.5.0"

[libraries]
rentile-kmp = { module = "com.rohittp.rentile:kmp", version.ref = "rentile" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
maven-publish = { id = "com.vanniktech.maven.publish", version.ref = "mavenPublish" }
"""

# ADR 0019's coroutines dependency (Cycle C task 1): the same fixtures as KMP_BUILD and
# VERSION_CATALOG above, with exactly the two permitted coroutines coordinates the policy
# checker's _PERMITTED_NEW_DEPENDENCIES / _PERMITTED_NEW_TEST_DEPENDENCIES admit. Built by
# amending the base fixtures rather than a standalone file, so every other check that
# `check_dependencies` runs on `kmp/build.gradle.kts` (plugin block, publishing block, and
# the whole-file content fingerprint) still recognizes the rest of the file.
_KMP_BUILD_WITH_COROUTINES = KMP_BUILD.replace(
    "        commonMain.dependencies {\n"
    "            implementation(libs.rentile.kmp)\n"
    "        }\n",
    "        commonMain.dependencies {\n"
    "            implementation(libs.rentile.kmp)\n"
    "            implementation(libs.kotlinx.coroutines.core)\n"
    "        }\n",
).replace(
    "        commonTest.dependencies {\n"
    "            implementation(kotlin(\"test\"))\n"
    "        }\n",
    "        commonTest.dependencies {\n"
    "            implementation(kotlin(\"test\"))\n"
    "            implementation(libs.kotlinx.coroutines.test)\n"
    "        }\n",
)

# Matches the real gradle/libs.versions.toml's alphabetical convention exactly (kotlin, then
# kotlinxCoroutines, then mavenPublish; kotlinx-coroutines-* before rentile-kmp), because this
# fixture and the real file must tokenize identically for the shared build-fingerprint pin.
_VERSION_CATALOG_WITH_COROUTINES = VERSION_CATALOG.replace(
    'kotlin = "2.3.21"\n',
    'kotlin = "2.3.21"\n'
    'kotlinxCoroutines = "1.11.0"\n',
).replace(
    'rentile-kmp = { module = "com.rohittp.rentile:kmp", version.ref = "rentile" }\n',
    'kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core",'
    ' version.ref = "kotlinxCoroutines" }\n'
    'kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test",'
    ' version.ref = "kotlinxCoroutines" }\n'
    'rentile-kmp = { module = "com.rohittp.rentile:kmp", version.ref = "rentile" }\n',
)

# ADR 0032's `androidDeviceTest` scope (Cycle H task 4): the with-coroutines fixtures above plus
# the device-test source set and the one coordinate it adds. Built by amending those rather than
# standing alone, for the same reason they amend the base fixtures -- every other check
# `check_dependencies` runs on `kmp/build.gradle.kts` must still recognize the rest of the file,
# and the token stream has to match the real file exactly for the shared build-fingerprint pin.
#
# `applyDefaultHierarchyTemplate()` is here because it is in the real file, where it is load-bearing:
# the explicit `dependsOn(commonTest)` edge below makes the Kotlin Gradle Plugin stop applying the
# default hierarchy template, which un-wires every Kotlin/Native source set while leaving Android
# green. The checker cannot see that, but the fingerprint pins it in place.
_KMP_BUILD_WITH_DEVICE_TEST = _KMP_BUILD_WITH_COROUTINES.replace(
    "    explicitApi()\n",
    "    explicitApi()\n"
    "\n"
    "    applyDefaultHierarchyTemplate()\n",
).replace(
    "        withHostTest {}\n",
    "        withHostTest {}\n"
    "        withDeviceTest {\n"
    "            instrumentationRunner = \"androidx.test.runner.AndroidJUnitRunner\"\n"
    "        }\n",
).replace(
    "        commonTest.dependencies {\n"
    "            implementation(kotlin(\"test\"))\n"
    "            implementation(libs.kotlinx.coroutines.test)\n"
    "        }\n",
    "        commonTest.dependencies {\n"
    "            implementation(kotlin(\"test\"))\n"
    "            implementation(libs.kotlinx.coroutines.test)\n"
    "        }\n"
    "        val androidDeviceTest by getting {\n"
    "            dependsOn(commonTest.get())\n"
    "        }\n"
    "        androidDeviceTest.dependencies {\n"
    "            implementation(kotlin(\"test\"))\n"
    "            implementation(libs.androidx.test.runner)\n"
    "            implementation(libs.kotlinx.coroutines.test)\n"
    "        }\n",
)

# Alphabetical in both tables, matching the real catalog, because this fixture and the real file
# must tokenize identically for the shared build-fingerprint pin.
_VERSION_CATALOG_WITH_DEVICE_TEST = _VERSION_CATALOG_WITH_COROUTINES.replace(
    'agp = "9.3.1"\n',
    'agp = "9.3.1"\n'
    'androidxTestRunner = "1.7.0"\n',
).replace(
    'kotlinx-coroutines-core = ',
    'androidx-test-runner = { module = "androidx.test:runner",'
    ' version.ref = "androidxTestRunner" }\n'
    'kotlinx-coroutines-core = ',
)

PUBLIC_SMOKE_STEP = """      - name: Resolve six targets from the public repository without credentials
        run: >-
          ./gradlew --gradle-user-home "$PUBLIC_HOME" --refresh-dependencies
          compileAndroidMain compileKotlinIosArm64 compileKotlinIosSimulatorArm64
          compileKotlinMacosArm64 compileKotlinLinuxX64 compileKotlinLinuxArm64
"""
COMPLETION_CREATE_STEP = """      - name: Create immutable release completion record
        env:
          SOURCE_COMMIT: ${{ github.sha }}
        run: >-
          python3 tools/verify_publication.py completion-create
          --source-commit "$SOURCE_COMMIT"
"""
COMPLETION_WRITE_STEP = """      - name: Create release completion record in R2
        env:
          R2_ENDPOINT: ${{ vars.R2_ENDPOINT }}
          R2_BUCKET: ${{ vars.R2_BUCKET }}
          RECORD_KEY: ${{ steps.completion.outputs.record_key }}
          AWS_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.R2_SECRET_ACCESS_KEY }}
        run: >-
          aws --endpoint-url "$R2_ENDPOINT" s3api put-object
          --bucket "$R2_BUCKET" --key "$RECORD_KEY"
          --body completion.json --content-type application/json --if-none-match '*'
"""
COMPLETION_PUBLIC_STEP = """      - name: Verify public release completion record without credentials
        env:
          R2_PUBLIC_URL: ${{ vars.R2_PUBLIC_URL }}
          SOURCE_COMMIT: ${{ github.sha }}
        run: >-
          python3 tools/verify_publication.py completion-public
          --source-commit "$SOURCE_COMMIT" --attempts 12 --retry-delay 5
"""
PUBLISH_WORKFLOW = (
    "steps:\n"
    "      - name: Verify exact public artifacts and aggregate metadata\n"
    "        run: python3 tools/verify_publication.py public\n"
    + PUBLIC_SMOKE_STEP
    + COMPLETION_CREATE_STEP
    + COMPLETION_WRITE_STEP
    + COMPLETION_PUBLIC_STEP
)

LIBRARY_CREATED_DATA_CLASSES = (
    ("Diagnostic", "kmp/src/commonMain/kotlin/com/rohittp/reng/Diagnostics.kt"),
    ("RawResourceKey", "kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceAdapters.kt"),
    ("ResourceKey", "kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt"),
    ("ResourceUsage", "kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt"),
    ("ResourceReportEntry", "kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt"),
    ("ResourceFreeResult", "kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt"),
)


def write(root: Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def write_bytes(root: Path, relative: str, contents: bytes) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(contents)


def _neutralized_fingerprint_overrides(root: Path) -> dict[str, frozenset[str]]:
    """Compute the *actual* whole-file content fingerprint for each pinned production file
    currently on disk at `root`, keyed exactly as `_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS` is,
    so a test can `patch.dict` that mapping to always accept whatever is there. This isolates
    one check_dependencies mechanism from the unrelated whole-file fingerprint pin, which would
    otherwise also fire on any fixture mutation and mask the mechanism actually under test."""
    return {
        relative: frozenset({
            _build_configuration_fingerprint(
                root / relative, (root / relative).read_text(encoding="utf-8"),
            ),
        })
        for relative in ("kmp/build.gradle.kts", "gradle/libs.versions.toml")
        if (root / relative).is_file()
    }


def force_track_fixture(root: Path) -> None:
    subprocess.run(("git", "init", "--quiet", str(root)), check=True)
    subprocess.run(
        ("git", "-C", str(root), "add", "--force", "."),
        check=True,
    )


def gnu_thin_archive_fixture() -> bytes:
    # Exact valid layout emitted by `llvm-ar crsT thin-archive member.txt`.
    return (
        b"!<thin>\n"
        b"//                                              12        `\n"
        b"member.txt/\n"
        b"/0              0           0     0     644     14        `\n"
    )


def pe32_plus_fixture_with_late_header(pe_offset: int = 0x1200) -> bytes:
    file_alignment = 0x200
    section_alignment = 0x1000
    optional_header_size = 0xF0
    section_header_size = 40
    headers_end = pe_offset + 4 + 20 + optional_header_size + section_header_size
    size_of_headers = (
        (headers_end + file_alignment - 1) // file_alignment * file_alignment
    )

    image = bytearray(size_of_headers + file_alignment)
    image[:2] = b"MZ"
    struct.pack_into("<H", image, 0x02, 0x0090)
    struct.pack_into("<H", image, 0x04, 0x0003)
    struct.pack_into("<H", image, 0x08, 0x0004)
    struct.pack_into("<H", image, 0x18, 0x0040)
    struct.pack_into("<I", image, 0x3C, pe_offset)

    image[pe_offset:pe_offset + 4] = b"PE\0\0"
    coff_offset = pe_offset + 4
    struct.pack_into(
        "<HHIIIHH",
        image,
        coff_offset,
        0x8664,
        1,
        0,
        0,
        0,
        optional_header_size,
        0x0022,
    )

    optional_offset = coff_offset + 20
    struct.pack_into("<H", image, optional_offset, 0x020B)
    image[optional_offset + 2] = 14
    struct.pack_into("<I", image, optional_offset + 4, file_alignment)
    struct.pack_into("<I", image, optional_offset + 16, section_alignment)
    struct.pack_into("<I", image, optional_offset + 20, section_alignment)
    struct.pack_into("<Q", image, optional_offset + 24, 0x140000000)
    struct.pack_into("<I", image, optional_offset + 32, section_alignment)
    struct.pack_into("<I", image, optional_offset + 36, file_alignment)
    struct.pack_into("<H", image, optional_offset + 40, 6)
    struct.pack_into("<H", image, optional_offset + 48, 6)
    struct.pack_into("<I", image, optional_offset + 56, 0x2000)
    struct.pack_into("<I", image, optional_offset + 60, size_of_headers)
    struct.pack_into("<H", image, optional_offset + 68, 3)
    struct.pack_into("<H", image, optional_offset + 70, 0x8160)
    struct.pack_into("<Q", image, optional_offset + 72, 0x100000)
    struct.pack_into("<Q", image, optional_offset + 80, 0x1000)
    struct.pack_into("<Q", image, optional_offset + 88, 0x100000)
    struct.pack_into("<Q", image, optional_offset + 96, 0x1000)
    struct.pack_into("<I", image, optional_offset + 108, 16)

    section_offset = optional_offset + optional_header_size
    image[section_offset:section_offset + 8] = b".text\0\0\0"
    struct.pack_into("<I", image, section_offset + 8, 1)
    struct.pack_into("<I", image, section_offset + 12, section_alignment)
    struct.pack_into("<I", image, section_offset + 16, file_alignment)
    struct.pack_into("<I", image, section_offset + 20, size_of_headers)
    struct.pack_into("<I", image, section_offset + 36, 0x60000020)
    image[size_of_headers] = 0xC3
    return bytes(image)


def create_clean_fixture(root: Path) -> None:
    write(root, "gradle.properties", "VERSION_NAME=0.1.0\n")
    write(root, "settings.gradle.kts", SETTINGS_BUILD)
    write(root, "build.gradle.kts", ROOT_BUILD)
    write(root, "gradle/libs.versions.toml", VERSION_CATALOG)
    write(root, "kmp/build.gradle.kts", KMP_BUILD)
    write(root, "consumer-smoke/build.gradle.kts", TARGETS + "\n" +
          "implementation(\"com.rohittp.reng:kmp:$rengVersion\")\n")
    write(root, "consumer-smoke/settings.gradle.kts", "rootProject.name = \"consumer-smoke\"\n")
    abi_classes = "\n".join(
        f"final class com.rohittp.reng/{class_name} {{\n}}\n"
        for class_name, _ in LIBRARY_CREATED_DATA_CLASSES
    )
    write(
        root,
        "kmp/api/kmp.klib.api",
        "// Targets: [iosArm64, iosSimulatorArm64, linuxArm64, linuxX64, macosArm64]\n"
        "final class com.rohittp.reng/Public\n\n"
        + abi_classes,
    )
    declarations_by_path: dict[str, list[str]] = {}
    for class_name, relative in LIBRARY_CREATED_DATA_CLASSES:
        declarations_by_path.setdefault(relative, []).append(
            "@ConsistentCopyVisibility\n"
            f"public data class {class_name} internal constructor(public val value: String)\n"
        )
    for relative, declarations in declarations_by_path.items():
        write(
            root,
            relative,
            "package com.rohittp.reng\n\n" + "\n".join(declarations),
        )
    write(root, "README.md", "RenG Apache-2.0 com.rohittp.reng:kmp:<version>\n")
    write(root, "LICENSE", "Apache License\nVersion 2.0, January 2004\n")
    for name in (".nojekyll", "robots.txt", "sitemap.xml", "llms.txt"):
        write(root, f"docs/{name}", "https://rohittp.com/reng/ Apache-2.0\n")
    write(root, "docs/index.html", """
<link rel="canonical" href="https://rohittp.com/reng/">
<link rel="stylesheet" href="style.css">
<script defer src="versions.js"></script>
<span data-maven-version="kmp">latest</span>
<footer>Apache-2.0</footer>
""")
    write(root, "docs/kmp.html", """
<link rel="canonical" href="https://rohittp.com/reng/kmp.html">
<link rel="stylesheet" href="style.css">
<script defer src="versions.js"></script>
<span data-maven-version="kmp">latest</span>
<footer>Apache-2.0</footer>
""")
    write(root, "docs/style.css", ":focus-visible { outline: 2px solid currentColor; }\n")
    write(root, "docs/versions.js", "https://maven.rohittp.com/com/rohittp/reng\n")
    write(root, ".github/workflows/publish.yml", PUBLISH_WORKFLOW)
    write(root, "docs/adr/9999-history.md", "Historical com.rohittp.reng:kmp:9.9.9\n")


class RepositoryPolicyTests(unittest.TestCase):
    def test_clean_fixture_passes(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            self.assertEqual([], check_repository(root))

    def test_clean_fixture_cli_reports_cycle_b_success_exactly(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            output = StringIO()
            with redirect_stdout(output):
                status = main(["--root", str(root)])
            self.assertEqual(0, status)
            self.assertEqual("Cycle B repository policy passed\n", output.getvalue())

    def test_cycle_b_abi_mutations_fail_closed(self) -> None:
        replacement_cases = (
            ("", "CYCLE_B_PUBLIC_ABI"),
            ("// comments only\n", "CYCLE_B_PUBLIC_ABI"),
        )
        for contents, expected in replacement_cases:
            with self.subTest(contents=contents):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    write(root, "kmp/api/kmp.klib.api", contents)
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn(expected, codes)

        append_cases = (
            ("com.rohittp.rentile/RenderOptions\n", "ABI_RENTILE_LEAK"),
            ("final class platform.posix/FILE\n", "ABI_PLATFORM_LEAK"),
        )
        for mutation, expected in append_cases:
            with self.subTest(expected=expected, mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    abi = root / "kmp/api/kmp.klib.api"
                    abi.write_text(abi.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn(expected, codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "kmp/api/jvm.api", "final class com.rohittp.reng/JvmOnly\n")
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("JVM_ABI", codes)

    def test_library_created_data_classes_require_source_copy_visibility_and_closed_abi(self) -> None:
        for class_name, relative in LIBRARY_CREATED_DATA_CLASSES:
            declaration = (
                "@ConsistentCopyVisibility\n"
                f"public data class {class_name} internal constructor(public val value: String)"
            )
            without_annotation = (
                f"public data class {class_name} internal constructor(public val value: String)"
            )
            exposed_annotation = (
                "@ExposedCopyVisibility\n"
                f"public data class {class_name} internal constructor(public val value: String)"
            )
            for replacement, expected in (
                (without_annotation, "CONSISTENT_COPY_VISIBILITY"),
                (exposed_annotation, "EXPOSED_COPY_VISIBILITY"),
            ):
                with self.subTest(class_name=class_name, source_expected=expected):
                    with TemporaryDirectory() as directory:
                        root = Path(directory)
                        create_clean_fixture(root)
                        source = root / relative
                        source.write_text(
                            source.read_text(encoding="utf-8").replace(declaration, replacement),
                            encoding="utf-8",
                        )
                        codes = {violation.code for violation in check_repository(root)}
                        self.assertIn(expected, codes)

            generated_leaks = (
                (
                    f"    constructor <init>(kotlin/String) // "
                    f"com.rohittp.reng/{class_name}.<init>|<init>(kotlin.String){{}}[0]\n",
                    "constructor",
                ),
                (
                    f"    final fun copy(kotlin/String = ...): com.rohittp.reng/{class_name} // "
                    f"com.rohittp.reng/{class_name}.copy|copy(kotlin.String){{}}[0]\n",
                    "copy",
                ),
            )
            for leak, shape in generated_leaks:
                with self.subTest(class_name=class_name, abi_leak=shape):
                    with TemporaryDirectory() as directory:
                        root = Path(directory)
                        create_clean_fixture(root)
                        abi = root / "kmp/api/kmp.klib.api"
                        header = f"final class com.rohittp.reng/{class_name} {{\n"
                        abi.write_text(
                            abi.read_text(encoding="utf-8").replace(header, header + leak),
                            encoding="utf-8",
                        )
                        codes = {violation.code for violation in check_repository(root)}
                        self.assertIn("LIBRARY_CREATED_DATA_CLASS_ABI", codes)

    def test_copy_visibility_parser_accepts_qualified_and_intervening_annotations(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            source = root / "kmp/src/commonMain/kotlin/com/rohittp/reng/Diagnostics.kt"
            source.write_text(
                source.read_text(encoding="utf-8").replace(
                    "@ConsistentCopyVisibility\npublic data class Diagnostic",
                    "@kotlin.ConsistentCopyVisibility\n"
                    "@Suppress(\"fixture\")\n"
                    "public data class Diagnostic",
                ),
                encoding="utf-8",
            )
            self.assertEqual([], check_repository(root))

    def test_copy_visibility_parser_accepts_annotation_between_public_and_data_class(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            source = root / "kmp/src/commonMain/kotlin/com/rohittp/reng/Diagnostics.kt"
            source.write_text(
                source.read_text(encoding="utf-8").replace(
                    "@ConsistentCopyVisibility\npublic data class Diagnostic",
                    "@ConsistentCopyVisibility\n"
                    "public\n"
                    "@kotlin.Suppress(\"fixture\")\n"
                    "data class Diagnostic",
                ),
                encoding="utf-8",
            )
            self.assertEqual([], check_repository(root))

    def test_exposed_copy_visibility_ignores_comments_and_strings_but_rejects_qualified_use(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            source = root / "kmp/src/commonMain/kotlin/com/rohittp/reng/Diagnostics.kt"
            source.write_text(
                source.read_text(encoding="utf-8")
                + "\n// @ExposedCopyVisibility\n"
                + 'private val exposedMarker = "@ExposedCopyVisibility"\n',
                encoding="utf-8",
            )
            self.assertEqual([], check_repository(root))

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            source = root / "kmp/src/commonMain/kotlin/com/rohittp/reng/Diagnostics.kt"
            source.write_text(
                source.read_text(encoding="utf-8").replace(
                    "@ConsistentCopyVisibility",
                    "@kotlin.ExposedCopyVisibility",
                    1,
                ),
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("EXPOSED_COPY_VISIBILITY", codes)

    def test_internal_package_public_abi_fails_closed_for_dot_and_slash_dumps(self) -> None:
        mutations = (
            "final class com.rohittp.reng.internal/Leaked\n",
            "final class com/rohittp/reng/internal/Leaked\n",
            "final class com.rohittp.reng.internal.Leaked\n",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    abi = root / "kmp/api/kmp.klib.api"
                    abi.write_text(abi.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("ABI_INTERNAL_LEAK", codes)

    def test_cycle_b_dependency_allowlist_rejects_every_addition(self) -> None:
        mutations = (
            ("commonMain", 'implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1")'),
            ("commonMain", 'implementation("org.kotlincrypto.hash:sha2:1")'),
            ("commonMain", 'implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1")'),
            ("commonMain", 'implementation("io.ktor:ktor-client-core:1")'),
            ("commonMain", 'implementation("org.jetbrains.skiko:skiko:1")'),
            ("commonMain", 'implementation("com.squareup.wire:wire-runtime:1")'),
            ("commonMain", 'implementation("example:corpus:1")'),
            ("commonMain", 'runtimeOnly("com.example:unknown-runtime:1")'),
            ("commonTest", 'implementation("com.example:test-helper:1")'),
        )
        for source_set, mutation in mutations:
            with self.subTest(source_set=source_set, mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    build = root / "kmp/build.gradle.kts"
                    marker = f"        {source_set}.dependencies {{\n"
                    build.write_text(
                        build.read_text(encoding="utf-8").replace(
                            marker,
                            marker + "            " + mutation + "\n",
                        ),
                        encoding="utf-8",
                    )
                    violations = check_repository(root)
                    matching = [
                        violation for violation in violations
                        if violation.code == "FORBIDDEN_CYCLE_B_DEPENDENCY"
                    ]
                    self.assertTrue(matching)
                    self.assertTrue(all("Cycle B" in violation.message for violation in matching))

    def test_cycle_b_dependency_allowlist_requires_both_exact_entries(self) -> None:
        removals = (
            "            implementation(libs.rentile.kmp)\n",
            '            implementation(kotlin("test"))\n',
        )
        for removal in removals:
            with self.subTest(removal=removal):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    build = root / "kmp/build.gradle.kts"
                    build.write_text(
                        build.read_text(encoding="utf-8").replace(removal, ""),
                        encoding="utf-8",
                    )
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_allows_only_the_named_coroutines_coordinates(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "kmp/build.gradle.kts", _KMP_BUILD_WITH_COROUTINES)
            self.assertEqual([], check_dependencies(root))

    def test_rejects_a_second_new_main_dependency(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(
                root,
                "kmp/build.gradle.kts",
                _KMP_BUILD_WITH_COROUTINES.replace(
                    "implementation(libs.kotlinx.coroutines.core)",
                    "implementation(libs.kotlinx.coroutines.core)\n"
                    "            implementation(libs.kotlinx.serialization.json)",
                ),
            )
            # A second, unlisted addition is rejected by more than one independent check at
            # once (the exact dependency shape and the whole-file content fingerprint both
            # fire), so the codes are compared as a set rather than an exact violation count.
            codes = {violation.code for violation in check_dependencies(root)}
            self.assertEqual({"FORBIDDEN_CYCLE_B_DEPENDENCY"}, codes)

    def test_rejects_a_second_new_test_dependency(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(
                root,
                "kmp/build.gradle.kts",
                _KMP_BUILD_WITH_COROUTINES.replace(
                    "implementation(libs.kotlinx.coroutines.test)",
                    "implementation(libs.kotlinx.coroutines.test)\n"
                    "            implementation(libs.kotlinx.serialization.json)",
                ),
            )
            codes = {violation.code for violation in check_dependencies(root)}
            self.assertEqual({"FORBIDDEN_CYCLE_B_DEPENDENCY"}, codes)

    def test_still_rejects_a_bare_coroutines_coordinate(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(
                root,
                "kmp/build.gradle.kts",
                _KMP_BUILD_WITH_COROUTINES.replace(
                    "implementation(libs.kotlinx.coroutines.core)",
                    'implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")',
                ),
            )
            self.assertNotEqual([], check_dependencies(root))

    def test_still_rejects_a_bare_coroutines_test_coordinate(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(
                root,
                "kmp/build.gradle.kts",
                _KMP_BUILD_WITH_COROUTINES.replace(
                    "implementation(libs.kotlinx.coroutines.test)",
                    'implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")',
                ),
            )
            self.assertNotEqual([], check_dependencies(root))

    def test_clean_fixture_with_coroutines_dependency_and_catalog_passes(self) -> None:
        # Exercises the exact combination Cycle C task 1 lands in the real repository: both
        # the build script and the version catalog carry the coroutines coordinates together,
        # so the catalog's exact library/version snapshot and the whole-file fingerprint for
        # both files must accept this shape end to end via the full check_repository pipeline.
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "kmp/build.gradle.kts", _KMP_BUILD_WITH_COROUTINES)
            write(root, "gradle/libs.versions.toml", _VERSION_CATALOG_WITH_COROUTINES)
            self.assertEqual([], check_repository(root))

    def test_catalog_forbidden_scan_still_covers_text_outside_the_exact_tables(self) -> None:
        # Stripping the two permitted coroutines coordinates from the forbidden-word scan must
        # not become a blanket exemption: a smuggled table the versions/libraries/plugins exact
        # comparisons never look at (here, [bundles]) still has to be caught even while the
        # catalog's known tables remain byte-for-byte the permitted with-coroutines shape. The
        # unrelated whole-file fingerprint pin (_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS) would
        # also fire on this exact fixture and mask a broken scan, so it is patched here to
        # always accept whatever is actually on disk — isolating the forbidden-scan mechanism
        # itself as the only thing this test can observe.
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "kmp/build.gradle.kts", _KMP_BUILD_WITH_COROUTINES)
            write(
                root,
                "gradle/libs.versions.toml",
                _VERSION_CATALOG_WITH_COROUTINES + "\n[bundles]\n"
                'sneaky = ["org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.0"]\n',
            )
            with patch.dict(
                _EXPECTED_PRODUCTION_BUILD_FINGERPRINTS,
                _neutralized_fingerprint_overrides(root),
            ):
                codes = {violation.code for violation in check_dependencies(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_catalog_rejects_a_third_library_entry_beyond_the_two_permitted(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "kmp/build.gradle.kts", _KMP_BUILD_WITH_COROUTINES)
            write(
                root,
                "gradle/libs.versions.toml",
                _VERSION_CATALOG_WITH_COROUTINES.replace(
                    "[plugins]",
                    'kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android",'
                    ' version.ref = "kotlinxCoroutines" }\n\n'
                    "[plugins]",
                ),
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    # ADR 0032's third dependency scope. A widening that admits anything is not a widening, so
    # every test below the first one is a mutation: each takes the accepted shape and changes one
    # thing, and each must still be refused.

    def test_device_test_scope_with_its_catalog_entry_passes(self) -> None:
        # The exact combination Cycle H task 4 lands in the real repository, through the full
        # check_repository pipeline: the build script's androidDeviceTest block and the catalog's
        # androidx-test-runner entry together, plus both files' build fingerprints.
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "kmp/build.gradle.kts", _KMP_BUILD_WITH_DEVICE_TEST)
            write(root, "gradle/libs.versions.toml", _VERSION_CATALOG_WITH_DEVICE_TEST)
            self.assertEqual([], check_repository(root))

    def _refuses_device_test_build(self, build: str) -> None:
        """Write `build` as the only mutation and require `check_dependencies` to refuse it.

        The build-fingerprint pin fires on any token change to `kmp/build.gradle.kts` at all, so it
        masks every mechanism underneath it: measured here, with `device_test_allowed` forced to
        `True`, all of these mutations were still "refused" and the whole suite still passed.
        Neutralizing the pin, exactly as the catalog forbidden-scan test does, leaves the dependency
        rules as the only thing that can produce a violation.
        """
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "kmp/build.gradle.kts", build)
            write(root, "gradle/libs.versions.toml", _VERSION_CATALOG_WITH_DEVICE_TEST)
            with patch.dict(
                _EXPECTED_PRODUCTION_BUILD_FINGERPRINTS,
                _neutralized_fingerprint_overrides(root),
            ):
                violations = check_dependencies(root)
            self.assertEqual(
                {"FORBIDDEN_CYCLE_B_DEPENDENCY"},
                {violation.code for violation in violations},
            )

    def test_rejects_a_fourth_device_test_dependency(self) -> None:
        # The coordinate here shares no hyphen-bounded word with _FORBIDDEN_DEPENDENCY, so this
        # measures the scope's own allowlist rather than the forbidden-word scan behind it.
        self._refuses_device_test_build(
            _KMP_BUILD_WITH_DEVICE_TEST.replace(
                "            implementation(libs.androidx.test.runner)\n",
                "            implementation(libs.androidx.test.runner)\n"
                "            implementation(libs.androidx.test.core)\n",
            ),
        )

    def test_rejects_a_repeated_device_test_dependency(self) -> None:
        # A fourth call naming a coordinate the scope *does* admit. Every other mechanism accepts
        # it — the coordinate is catalogued, permitted, and inside the device range — so the
        # scope's own shape rule is the only thing left that can refuse it. That is what makes this
        # the case that dies when that rule is weakened, and the reason it exists alongside the one
        # above rather than instead of it.
        self._refuses_device_test_build(
            _KMP_BUILD_WITH_DEVICE_TEST.replace(
                "            implementation(libs.androidx.test.runner)\n",
                "            implementation(libs.androidx.test.runner)\n"
                "            implementation(libs.androidx.test.runner)\n",
            ),
        )

    def test_rejects_a_dependency_block_in_a_fourth_source_set(self) -> None:
        # The scope is androidDeviceTest specifically, not "test source sets". androidHostTest is
        # the nearest neighbour -- also a test source set, also on the JVM -- and it is refused.
        self._refuses_device_test_build(
            _KMP_BUILD_WITH_DEVICE_TEST.replace(
                "        val androidDeviceTest by getting {\n",
                "        androidHostTest.dependencies {\n"
                "            implementation(kotlin(\"test\"))\n"
                "        }\n"
                "        val androidDeviceTest by getting {\n",
            ),
        )

    def test_still_rejects_a_bare_runner_coordinate(self) -> None:
        self._refuses_device_test_build(
            _KMP_BUILD_WITH_DEVICE_TEST.replace(
                "implementation(libs.androidx.test.runner)",
                'implementation("androidx.test:runner:1.7.0")',
            ),
        )

    def test_the_runner_is_not_admitted_into_the_production_scope(self) -> None:
        # The allowlist is per-scope. A coordinate ADR 0032 admits for instrumented tests must not
        # become admissible in commonMain, where it would reach every consumer.
        self._refuses_device_test_build(
            _KMP_BUILD_WITH_DEVICE_TEST.replace(
                "            implementation(libs.kotlinx.coroutines.core)\n",
                "            implementation(libs.kotlinx.coroutines.core)\n"
                "            implementation(libs.androidx.test.runner)\n",
            ),
        )

    def test_rejects_a_device_test_block_missing_its_required_kotlin_test_call(self) -> None:
        self._refuses_device_test_build(
            _KMP_BUILD_WITH_DEVICE_TEST.replace(
                "        androidDeviceTest.dependencies {\n"
                "            implementation(kotlin(\"test\"))\n",
                "        androidDeviceTest.dependencies {\n",
            ),
        )

    def test_catalog_rejects_a_fourth_library_entry_beyond_the_three_permitted(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "kmp/build.gradle.kts", _KMP_BUILD_WITH_DEVICE_TEST)
            write(
                root,
                "gradle/libs.versions.toml",
                _VERSION_CATALOG_WITH_DEVICE_TEST.replace(
                    "[plugins]",
                    'androidx-test-core = { module = "androidx.test:core",'
                    ' version.ref = "androidxTestRunner" }\n\n'
                    "[plugins]",
                ),
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_catalog_forbidden_scan_still_covers_the_device_test_shape(self) -> None:
        # The androidx-test-runner entry is deliberately not masked out of the raw forbidden-word
        # scan, unlike the two coroutines entries. This proves the scan still reaches text in a
        # catalog that carries it -- the fingerprint pin is neutralized so that only the scan can
        # be what fires.
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "kmp/build.gradle.kts", _KMP_BUILD_WITH_DEVICE_TEST)
            write(
                root,
                "gradle/libs.versions.toml",
                _VERSION_CATALOG_WITH_DEVICE_TEST + "\n[bundles]\n"
                'sneaky = ["com.squareup.wire:wire-runtime:1.0"]\n',
            )
            with patch.dict(
                _EXPECTED_PRODUCTION_BUILD_FINGERPRINTS,
                _neutralized_fingerprint_overrides(root),
            ):
                codes = {violation.code for violation in check_dependencies(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_cycle_b_dependency_allowlist_rejects_gradle_indirection(self) -> None:
        script_mutations = (
            (
                "kmp/build.gradle.kts",
                "\ndependencies.addProvider(\"commonMainImplementation\", "
                "providers.provider { \"com.example:runtime:1\" })\n",
            ),
            (
                "kmp/build.gradle.kts",
                "\nconfigurations.named(\"commonMainImplementation\") { "
                "dependencies += project.dependencies.create(\"com.example:runtime:1\") }\n",
            ),
            (
                "kmp/build.gradle.kts",
                "\ncommonMainImplementation(\"com.example:runtime:1\")\n",
            ),
            (
                "build.gradle.kts",
                "\nsubprojects { dependencies { "
                "add(\"commonMainImplementation\", \"com.example:runtime:1\") } }\n",
            ),
            (
                "build.gradle.kts",
                "\nallprojects { configurations.configureEach { withDependencies { "
                "add(project.dependencies.create(\"com.example:runtime:1\")) } } }\n",
            ),
            (
                "build.gradle.kts",
                "\nconfigurations.configureEach { resolutionStrategy.dependencySubstitution { "
                "substitute(module(\"com.rohittp.rentile:kmp\"))"
                ".using(module(\"com.example:replacement:1\")) } }\n",
            ),
        )
        for relative, mutation in script_mutations:
            with self.subTest(relative=relative, mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    path.write_text(path.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(
                root,
                "gradle/injected.gradle.kts",
                "dependencies { add(\"commonMainImplementation\", \"com.example:runtime:1\") }\n",
            )
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8")
                + "\napply(from = rootProject.file(\"gradle/injected.gradle.kts\"))\n",
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_cycle_b_dependency_allowlist_rejects_lexical_shadowing(self) -> None:
        mutations = (
            """
val libs = object {
    val rentile = object { val kmp = "com.example:unknown-runtime:1" }
}
""",
            """
object libs {
    object rentile { const val kmp: String = "com.example:unknown-runtime:1" }
}
""",
            """
fun kotlin(name: String): String = "com.example:unknown-runtime:1"
""",
            """
val kotlin: (String) -> String = { "com.example:unknown-runtime:1" }
""",
            """
fun shadow(libs: Any): Any = libs
""",
            """
fun Any.kotlin(): String = "com.example:unknown-runtime:1"
""",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    build = root / "kmp/build.gradle.kts"
                    build.write_text(build.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_cycle_b_dependency_allowlist_rejects_executable_string_templates(self) -> None:
        mutations = (
            (
                "kmp/build.gradle.kts",
                '\nval hidden = "${implementation("com.example:unknown-runtime:1")}"\n',
            ),
            (
                "kmp/build.gradle.kts",
                '\nval hidden = """${jvm()}"""\n',
            ),
            (
                "kmp/build.gradle.kts",
                '\nval hidden = """${pluginManager.apply("com.example.injected")}"""\n',
            ),
            (
                "settings.gradle.kts",
                '\nval hidden = "${includeBuild("build-logic")}"\n',
            ),
            (
                "build.gradle.kts",
                '\nval hidden = "$r2Bucket"\n',
            ),
        )
        for relative, mutation in mutations:
            with self.subTest(relative=relative, mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    path.write_text(path.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_build_token_freeze_ignores_comments_whitespace_and_standalone_consumer(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            comment_mutations = {
                "build.gradle.kts": (
                    '\n// val hidden = "${System.setProperty("org.gradle.s3.endpoint", "bad")}"\n'
                    "/* nested /* System.setProperty(\"x\", \"y\") */ if (false) { } */\n"
                ),
                "settings.gradle.kts": "\n// fun uri(value: Any) = value\n",
                "kmp/build.gradle.kts": "\n/* cinterops.create(\"injected\") */\n",
                "gradle/libs.versions.toml": "\n# injected = { module = \"bad:bad\" }\n",
            }
            for relative, mutation in comment_mutations.items():
                path = root / relative
                path.write_text(
                    path.read_text(encoding="utf-8") + mutation,
                    encoding="utf-8",
                )
            consumer = root / "consumer-smoke/build.gradle.kts"
            consumer.write_text(
                consumer.read_text(encoding="utf-8")
                + '\nval consumerOnly = "implementation(unknown)"\n',
                encoding="utf-8",
            )
            self.assertEqual([], check_repository(root))

    def test_build_token_freeze_normalizes_approved_template_expression_trivia(self) -> None:
        original = '"s3://${r2Bucket.orNull ?: "r2-publishing-not-configured"}"'
        equivalent_templates = (
            '"s3://${  r2Bucket . orNull  ?:  "r2-publishing-not-configured"  }"',
            '"s3://${/* outer /* nested */ comment */r2Bucket.orNull'
            ' ?: /* fallback */"r2-publishing-not-configured"}"',
        )
        for replacement in equivalent_templates:
            with self.subTest(replacement=replacement):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    build = root / "build.gradle.kts"
                    baseline = build.read_text(encoding="utf-8")
                    mutated = baseline.replace(original, replacement, 1)
                    self.assertEqual(
                        _build_configuration_fingerprint(build, baseline),
                        _build_configuration_fingerprint(build, mutated),
                    )
                    build.write_text(mutated, encoding="utf-8")
                    self.assertEqual([], check_repository(root))

        changed_templates = (
            '"s3://${r2Endpoint.orNull ?: "r2-publishing-not-configured"}"',
            '"s3://${r2Bucket.orNull ?: "changed-fallback"}"',
        )
        for replacement in changed_templates:
            with self.subTest(replacement=replacement):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    build = root / "build.gradle.kts"
                    baseline = build.read_text(encoding="utf-8")
                    mutated = baseline.replace(original, replacement, 1)
                    self.assertNotEqual(
                        _build_configuration_fingerprint(build, baseline),
                        _build_configuration_fingerprint(build, mutated),
                    )
                    build.write_text(mutated, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_cycle_b_dependency_allowlist_rejects_build_logic_and_plugin_injection(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "buildSrc/build.gradle.kts", "plugins { `kotlin-dsl` }\n")
            write(
                root,
                "buildSrc/src/main/kotlin/InjectedPlugin.kt",
                """import org.gradle.api.Plugin
import org.gradle.api.Project
class InjectedPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.dependencies.add("commonMainImplementation", "com.example:unknown-runtime:1")
    }
}
""",
            )
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8") + "\napply<InjectedPlugin>()\n",
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        def add_unknown_id(root: Path) -> None:
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8").replace(
                    "plugins {\n",
                    'plugins {\n    id("com.example.injected")\n',
                    1,
                ),
                encoding="utf-8",
            )

        def add_unknown_alias(root: Path) -> None:
            catalog = root / "gradle/libs.versions.toml"
            catalog.write_text(
                catalog.read_text(encoding="utf-8")
                + '\ninjected = { id = "com.example.injected", version = "1" }\n',
                encoding="utf-8",
            )
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8").replace(
                    "plugins {\n",
                    "plugins {\n    alias(libs.plugins.injected)\n",
                    1,
                ),
                encoding="utf-8",
            )

        def replace_settings_plugin(root: Path) -> None:
            settings = root / "settings.gradle.kts"
            settings.write_text(
                settings.read_text(encoding="utf-8").replace(
                    "org.gradle.toolchains.foojay-resolver-convention",
                    "com.example.injected",
                ),
                encoding="utf-8",
            )

        def add_groovy_apply(root: Path) -> None:
            build = root / "build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8")
                + '\napply plugin: "com.example.injected"\n',
                encoding="utf-8",
            )

        def add_groovy_applied_script(root: Path) -> None:
            build = root / "build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8")
                + '\napply from: "gradle/injected.gradle"\n',
                encoding="utf-8",
            )
            write(
                root,
                "gradle/injected.gradle",
                'dependencies.add("commonMainImplementation", "com.example:unknown-runtime:1")\n',
            )

        plugin_mutations = (
            add_unknown_id,
            add_unknown_alias,
            replace_settings_plugin,
            add_groovy_apply,
            add_groovy_applied_script,
        )
        for mutate in plugin_mutations:
            with self.subTest(mutate=mutate):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    mutate(root)
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            settings = root / "settings.gradle.kts"
            settings.write_text(
                settings.read_text(encoding="utf-8") + '\nincludeBuild("build-logic")\n',
                encoding="utf-8",
            )
            write(root, "build-logic/settings.gradle.kts", 'rootProject.name = "build-logic"\n')
            write(root, "build-logic/build.gradle.kts", "plugins { `kotlin-dsl` }\n")
            write(
                root,
                "build-logic/src/main/kotlin/InjectedPlugin.kt",
                """import org.gradle.api.Plugin
import org.gradle.api.Project
class InjectedPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.dependencies.add("commonMainImplementation", "com.example:unknown-runtime:1")
    }
}
""",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_cycle_b_dependency_catalog_requires_exact_plugin_aliases_and_versions(self) -> None:
        mutations = (
            lambda text: text.replace(
                'id = "com.vanniktech.maven.publish"',
                'id = "com.example.injected"',
            ),
            lambda text: text.replace('mavenPublish = "0.36.0"', 'mavenPublish = "9.9.9"'),
            lambda text: text + '\ninjected = { id = "com.example.injected", version = "1" }\n',
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    catalog = root / "gradle/libs.versions.toml"
                    catalog.write_text(mutate(catalog.read_text(encoding="utf-8")), encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_cycle_b_dependency_catalog_is_exact_and_custom_catalogs_are_rejected(self) -> None:
        catalog_mutations = (
            lambda text: text.replace("com.rohittp.rentile:kmp", "com.example:replacement"),
            lambda text: text.replace('rentile = "0.5.0"', 'rentile = "9.9.9"'),
            lambda text: text.replace(
                "[plugins]",
                'extra = { module = "com.example:runtime", version = "1" }\n[plugins]',
            ),
        )
        for mutate in catalog_mutations:
            with self.subTest(mutate=mutate):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    catalog = root / "gradle/libs.versions.toml"
                    catalog.write_text(mutate(catalog.read_text(encoding="utf-8")), encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(
                root,
                "gradle/injected.versions.toml",
                '[versions]\nextra = "1"\n[libraries]\n'
                'extra = { module = "com.example:runtime", version.ref = "extra" }\n',
            )
            settings = root / "settings.gradle.kts"
            settings.write_text(
                settings.read_text(encoding="utf-8")
                + "\ndependencyResolutionManagement { versionCatalogs { "
                "create(\"injected\") { from(files(\"gradle/injected.versions.toml\")) } } }\n",
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_build_token_freeze_rejects_reviewer_bypasses(self) -> None:
        mutations = (
            (
                "build.gradle.kts",
                lambda text: text
                + '\nSystem.setProperty("org.gradle.s3.endpoint", "replacement.invalid")\n',
            ),
            (
                "settings.gradle.kts",
                lambda text: (
                    "val uri: (Any) -> java.net.URI = {\n"
                    '    java.io.File("replacement-repo").toURI()\n'
                    "}\n"
                    + text
                ),
            ),
            (
                "build.gradle.kts",
                lambda text: text.replace(
                    "subprojects {\n",
                    "if (false) {\nsubprojects {\n",
                    1,
                )
                + "}\n",
            ),
        )
        for relative, mutate in mutations:
            with self.subTest(relative=relative, mutate=mutate):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    path.write_text(
                        mutate(path.read_text(encoding="utf-8")),
                        encoding="utf-8",
                    )
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "gradle/injected.gradle.kts", 'println("injected")\n')
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_build_token_freeze_rejects_cinterop_and_native_link_configuration(self) -> None:
        native_mutations = (
            """
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
        .configureEach {
            compilations.getByName("main").cinterops.create("injected")
        }
""",
            """
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
        .configureEach {
            binaries.all { linkerOpts(file("libInjected.a").absolutePath) }
        }
""",
        )
        for mutation in native_mutations:
            with self.subTest(mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    build = root / "kmp/build.gradle.kts"
                    build.write_text(
                        build.read_text(encoding="utf-8").replace(
                            "\n}\n\nmavenPublishing {",
                            mutation + "\n}\n\nmavenPublishing {",
                            1,
                        ),
                        encoding="utf-8",
                    )
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_repository_grammar_rejects_repointing_additions_and_filter_changes(self) -> None:
        mutations = (
            (
                "settings.gradle.kts",
                lambda text: text.replace(
                    'url = uri("https://maven.rohittp.com")',
                    'url = uri(rootDir.resolve("replacement-repo"))',
                ),
            ),
            (
                "settings.gradle.kts",
                lambda text: text.replace(
                    "        mavenCentral()",
                    '        maven { url = file("plugin-repo") }',
                    1,
                ),
            ),
            (
                "settings.gradle.kts",
                lambda text: text.replace(
                    "    repositories {",
                    '    repositories {\n        flatDir { dirs("replacement-repo") }',
                    1,
                ),
            ),
            (
                "settings.gradle.kts",
                lambda text: text.replace(
                    'includeGroup("com.rohittp.rentile")',
                    'includeGroup("com.example.replacement")',
                ),
            ),
            (
                "settings.gradle.kts",
                lambda text: text.replace(
                    'includeGroupByRegex("com\\\\.android.*")',
                    'includeGroupByRegex("com\\\\.example.*")',
                ),
            ),
            (
                "build.gradle.kts",
                lambda text: text.replace(
                    'providers.environmentVariable("R2_BUCKET")',
                    'providers.environmentVariable("R2_REPLACEMENT_BUCKET")',
                ),
            ),
            (
                "build.gradle.kts",
                lambda text: text.replace(
                    'System.setProperty("org.gradle.s3.endpoint", it)',
                    'System.setProperty("org.gradle.s3.endpoint", "replacement.invalid")',
                ),
            ),
            (
                "build.gradle.kts",
                lambda text: text.replace(
                    'url = uri("s3://${r2Bucket.orNull ?: "r2-publishing-not-configured"}")',
                    'url = file("replacement-repo")',
                ),
            ),
            (
                "kmp/build.gradle.kts",
                lambda text: text.replace(
                    'url = uri(rootProject.layout.buildDirectory.dir("local-maven"))',
                    'url = uri("file:///tmp/replacement-repo")',
                ),
            ),
            (
                "build.gradle.kts",
                lambda text: text + '\nrepositories { maven { url = file("replacement-repo") } }\n',
            ),
        )
        for relative, mutate in mutations:
            with self.subTest(relative=relative, mutate=mutate):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    path.write_text(mutate(path.read_text(encoding="utf-8")), encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_repository_policy_rejects_checked_in_maven_and_build_payloads(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "gradle/wrapper/gradle-wrapper.jar", "approved wrapper payload")
            self.assertEqual([], check_repository(root))

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(
                root,
                "replacement-repo/com/rohittp/rentile/kmp/0.1.5/kmp-0.1.5.pom",
                "<project/>",
            )
            write(
                root,
                "replacement-repo/com/rohittp/rentile/kmp/0.1.5/kmp-0.1.5.jar",
                "replacement payload",
            )
            settings = root / "settings.gradle.kts"
            settings.write_text(
                settings.read_text(encoding="utf-8").replace(
                    'url = uri("https://maven.rohittp.com")',
                    'url = uri(rootDir.resolve("replacement-repo"))',
                ),
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            marker = (
                "plugin-repo/org/jetbrains/kotlin/multiplatform/"
                "org.jetbrains.kotlin.multiplatform.gradle.plugin/2.3.21/"
            )
            write(root, marker + "org.jetbrains.kotlin.multiplatform.gradle.plugin-2.3.21.pom", "<project/>")
            write(root, marker + "org.jetbrains.kotlin.multiplatform.gradle.plugin-2.3.21.jar", "plugin payload")
            settings = root / "settings.gradle.kts"
            settings.write_text(
                settings.read_text(encoding="utf-8").replace(
                    "    repositories {",
                    '    repositories {\n        maven { url = uri(rootDir.resolve("plugin-repo")) }',
                    1,
                ),
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        forbidden_payloads = (
            "payload/module.module",
            "payload/library.aar",
            "payload/libInjected.a",
            "payload/library.so",
            "payload/library.dylib",
            "payload/object.o",
            "payload/object.obj",
            "payload/bitcode.bc",
            "payload/native.klib",
            "payload/module.wasm",
            "payload/archive.zip",
            "payload/maven-metadata.xml",
            "build/replacement/forced-library.jar",
        )
        for relative in forbidden_payloads:
            with self.subTest(relative=relative):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    write(root, relative, "replacement payload")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with self.subTest(relative="build/native/libInjected.a (force-added)"):
            with TemporaryDirectory() as directory:
                root = Path(directory)
                create_clean_fixture(root)
                write(root, ".gitignore", "build/\n")
                write_bytes(root, "build/native/libInjected.a", b"injected static library")
                force_track_fixture(root)
                codes = {violation.code for violation in check_repository(root)}
                self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        pe_payload = bytearray(128)
        pe_payload[:2] = b"MZ"
        pe_payload[0x3C:0x40] = (0x40).to_bytes(4, "little")
        pe_payload[0x40:0x44] = b"PE\0\0"
        magic_payloads = (
            ("payload/elf", b"\x7fELF\x02\x01\x01\0"),
            ("payload/mach-o", b"\xcf\xfa\xed\xfe\x0c\0\0\x01"),
            ("payload/static-archive", b"!<arch>\n"),
            ("payload/windows", bytes(pe_payload)),
            ("payload/webassembly", b"\0asm\x01\0\0\0"),
            ("payload/archive", b"PK\x03\x04injected"),
            ("payload/Layout.framework/Layout", b"framework executable"),
            (
                "payload/Injected.framework/Injected",
                b"\xcf\xfa\xed\xfe\x0c\0\0\x01",
            ),
        )
        for relative, contents in magic_payloads:
            with self.subTest(relative=relative):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    write_bytes(root, relative, contents)
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_repository_policy_rejects_force_tracked_extensionless_payloads(self) -> None:
        thin_archive = gnu_thin_archive_fixture()
        self.assertTrue(thin_archive.startswith(b"!<thin>\n"))

        late_pe = pe32_plus_fixture_with_late_header()
        pe_offset = int.from_bytes(late_pe[0x3C:0x40], "little")
        self.assertGreater(pe_offset, 4096)
        self.assertEqual(b"PE\0\0", late_pe[pe_offset:pe_offset + 4])

        fixtures = (
            (
                "build/native/thin-archive",
                thin_archive,
                ("build/native/member.txt", b"member payload"),
            ),
            (
                "build/native/windows-image",
                late_pe,
                None,
            ),
        )
        for relative, contents, companion in fixtures:
            with self.subTest(relative=relative):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    write(root, ".gitignore", "build/\n")
                    write_bytes(root, relative, contents)
                    if companion is not None:
                        write_bytes(root, companion[0], companion[1])
                    force_track_fixture(root)
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_repository_policy_handles_malformed_pe_offsets_without_crashing(self) -> None:
        invalid_images = []

        truncated = bytearray(0x20)
        truncated[:2] = b"MZ"
        invalid_images.append(bytes(truncated))

        before_dos_header_end = bytearray(128)
        before_dos_header_end[:2] = b"MZ"
        before_dos_header_end[0x3C:0x40] = (0x20).to_bytes(4, "little")
        before_dos_header_end[0x20:0x24] = b"PE\0\0"
        invalid_images.append(bytes(before_dos_header_end))

        beyond_end = bytearray(128)
        beyond_end[:2] = b"MZ"
        beyond_end[0x3C:0x40] = (0xFFFFFFFF).to_bytes(4, "little")
        invalid_images.append(bytes(beyond_end))

        truncated_signature = bytearray(128)
        truncated_signature[:2] = b"MZ"
        truncated_signature[0x3C:0x40] = (126).to_bytes(4, "little")
        truncated_signature[126:128] = b"PE"
        invalid_images.append(bytes(truncated_signature))

        for index, contents in enumerate(invalid_images):
            with self.subTest(index=index):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    write_bytes(root, f"payload/malformed-pe-{index}", contents)
                    self.assertEqual([], check_repository(root))

    def test_each_mutation_reports_expected_policy(self) -> None:
        mutations = (
            ("settings.gradle.kts", "\nmavenLocal()\n", "MAVEN_LOCAL"),
            ("gradle.properties", "VERSION_NAME=0.1.0-SNAPSHOT\n", "RENG_SNAPSHOT"),
            ("other.properties", "VERSION_NAME=0.2.0\n", "DUPLICATE_VERSION_INPUT"),
            ("kmp/build.gradle.kts", "\njvm()\n", "TARGET_SET"),
            ("kmp/api/kmp.klib.api", "com.rohittp.rentile/RenderOptions\n", "ABI_RENTILE_LEAK"),
            ("kmp/build.gradle.kts", "\nimplementation(\"org.jetbrains.skiko:skiko:1\")\n", "FORBIDDEN_CYCLE_B_DEPENDENCY"),
            ("kmp/build.gradle.kts", "\napi(libs.rentile.kmp)\n", "FORBIDDEN_CYCLE_B_DEPENDENCY"),
            ("kmp/build.gradle.kts", "\nsignAllPublications()\n", "ARTIFACT_SIGNING"),
            (".github/workflows/publish.yml", "\nSIGNING_KEY: configured\n", "ARTIFACT_SIGNING"),
            ("tools/verify_publication.py", "--require-signed-poms\n", "ARTIFACT_SIGNING"),
            ("README.md", "\ncom.rohittp.reng:kmp:0.1.0\n", "HARDCODED_RENG_VERSION"),
            ("docs/index.html", "\n<script src=\"https://cdn.example/app.js\"></script>\n", "DOCS_EXTERNAL_DEPENDENCY"),
            ("LICENSE", "MIT\n", "LICENSE_MISMATCH"),
        )
        for relative, mutation, expected in mutations:
            with self.subTest(expected=expected):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    if path.exists():
                        path.write_text(path.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    else:
                        write(root, relative, mutation)
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn(expected, codes)

    def test_missing_docs_and_wrong_canonical_are_reported(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            (root / "docs/kmp.html").unlink()
            index = root / "docs/index.html"
            index.write_text(
                index.read_text(encoding="utf-8").replace(
                    "https://rohittp.com/reng/", "https://wrong.example/"
                ),
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("DOCS_STRUCTURE", codes)
            self.assertIn("DOCS_CANONICAL", codes)

    def test_completion_record_workflow_requires_atomic_order_and_credential_scope(self) -> None:
        mutations = (
            lambda text: text.replace(" --if-none-match '*'", ""),
            lambda text: text.replace('--endpoint-url "$R2_ENDPOINT" ', ""),
            lambda text: text.replace(" compileKotlinLinuxArm64", ""),
            lambda text: text.replace(
                "          SOURCE_COMMIT: ${{ github.sha }}\n",
                "          SOURCE_COMMIT: ${{ github.sha }}\n"
                "          AWS_SECRET_ACCESS_KEY: ${{ secrets.R2_SECRET_ACCESS_KEY }}\n",
                1,
            ),
            lambda text: text.replace(
                PUBLIC_SMOKE_STEP,
                PUBLIC_SMOKE_STEP.replace(
                    "        run:",
                    "        env:\n"
                    "          AWS_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}\n"
                    "        run:",
                ),
            ),
            lambda text: text.replace(
                "          R2_PUBLIC_URL: ${{ vars.R2_PUBLIC_URL }}\n",
                "          AWS_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}\n",
            ),
            lambda text: text.replace(
                PUBLIC_SMOKE_STEP + COMPLETION_CREATE_STEP,
                COMPLETION_CREATE_STEP + PUBLIC_SMOKE_STEP,
            ),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    workflow = root / ".github/workflows/publish.yml"
                    workflow.write_text(
                        mutate(workflow.read_text(encoding="utf-8")),
                        encoding="utf-8",
                    )
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("COMPLETION_RECORD_WORKFLOW", codes)

    def test_target_checks_reject_real_extra_factory_and_ignore_non_code(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8") + "\nandroidNativeArm64()\n",
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("TARGET_SET", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8") +
                '\n// androidNativeArm64() and val example = "jvm()"\n',
                encoding="utf-8",
            )
            self.assertEqual([], check_repository(root))

    def test_maven_local_in_applied_production_script_is_reported(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "gradle/repositories.gradle.kts", "repositories { mavenLocal() }\n")
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("MAVEN_LOCAL", codes)

    def test_version_inputs_and_literals_cover_all_production_surfaces(self) -> None:
        cases = (
            (".github/workflows/ci.yml", "VERSION_NAME=0.2.0\n", "DUPLICATE_VERSION_INPUT"),
            (
                "consumer-smoke/src/commonMain/kotlin/Version.kt",
                "val coordinate = \"com.rohittp.reng:kmp:0.2.0\"\n",
                "HARDCODED_RENG_VERSION",
            ),
            (
                "gradle/libs.versions.toml",
                "\nreng-kmp = { module = \"com.rohittp.reng:kmp\", version = \"0.2.0\" }\n",
                "HARDCODED_RENG_VERSION",
            ),
            (
                "gradle/libs.versions.toml",
                "\nreng-kmp = { group = \"com.rohittp.reng\", name = \"kmp\", version = \"0.2.0\" }\n",
                "HARDCODED_RENG_VERSION",
            ),
            (
                "gradle/libs.versions.toml",
                "\nreng-kmp = { group = \"com.rohittp.reng\", name = \"kmp\", version.ref = \"reng\" }\n",
                "FORBIDDEN_CYCLE_B_DEPENDENCY",
            ),
            (
                "gradle/libs.versions.toml",
                "\nother-kmp = { group = \"example.org\", name = \"kmp\", version = \"0.2.0\" }\n",
                "FORBIDDEN_CYCLE_B_DEPENDENCY",
            ),
            (
                "consumer-smoke/src/commonMain/kotlin/Comment.kt",
                "// com.rohittp.reng:kmp:0.2.0\n",
                None,
            ),
        )
        for relative, mutation, expected in cases:
            with self.subTest(expected=expected):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    existing = path.read_text(encoding="utf-8") if path.exists() else ""
                    if relative == "gradle/libs.versions.toml":
                        contents = existing.replace("[plugins]", mutation + "\n[plugins]")
                    else:
                        contents = existing + mutation
                    path.write_text(contents, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    if expected is None:
                        self.assertEqual(set(), codes)
                    else:
                        self.assertIn(expected, codes)

    def test_dependency_checks_ignore_literals_and_toml_comments(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8").replace(
                    "implementation(libs.rentile.kmp)",
                    "val example = \"implementation(libs.rentile.kmp)\"",
                ),
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8") +
                "\nval dependency = libs.rentile.kmp\napi(dependency)\n",
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        forbidden_shapes = (
            "\nval serializationPlugin = kotlin(\"plugin.serialization\")\n",
            "\nadd(\"commonMainImplementation\", \"io.ktor:ktor-client-core:1\")\n",
        )
        for mutation in forbidden_shapes:
            with self.subTest(mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    build = root / "kmp/build.gradle.kts"
                    build.write_text(build.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            catalog = root / "gradle/libs.versions.toml"
            catalog.write_text(
                catalog.read_text(encoding="utf-8") +
                "\n# implementation(\"org.jetbrains.skiko:skiko:1\")\n",
                encoding="utf-8",
            )
            self.assertEqual([], check_repository(root))

    def test_docs_parser_enforces_active_resources_and_ignores_comments(self) -> None:
        cases = (
            ("docs/index.html", "\n<script src=https://cdn.example/app.js></script>\n", "DOCS_EXTERNAL_DEPENDENCY"),
            ("docs/index.html", "\n<link rel=\"preload stylesheet\" href=\"https://cdn.example/app.css\">\n", "DOCS_EXTERNAL_DEPENDENCY"),
            ("docs/index.html", "\n<!-- <script src=https://cdn.example/app.js></script> -->\n", None),
            ("docs/style.css", "\n/* @import url(https://cdn.example/app.css); */\n", None),
        )
        for relative, mutation, expected in cases:
            with self.subTest(expected=expected):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    path.write_text(path.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    if expected is None:
                        self.assertEqual(set(), codes)
                    else:
                        self.assertIn(expected, codes)

    def test_license_check_rejects_conflicts_on_each_public_surface(self) -> None:
        cases = (
            ("LICENSE", "\nmit\n"),
            ("README.md", "\nMIT\n"),
            ("docs/kmp.html", "\nMIT\n"),
            (
                "build.gradle.kts",
                '\nval injectedLicense = "MIT"\n',
            ),
        )
        for relative, mutation in cases:
            with self.subTest(relative=relative):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    path.write_text(
                        path.read_text(encoding="utf-8") + mutation,
                        encoding="utf-8",
                    )
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("LICENSE_MISMATCH", codes)


if __name__ == "__main__":
    unittest.main()

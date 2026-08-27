# Third-party notices

RenG depends on the following libraries. Their licenses remain with their respective copyright holders.

The versions below are baselines read from `gradle/libs.versions.toml`, which is the authority — a version
bump must update this table in the same change. This table went stale once already, recording Rentile at
`0.1.5` long after the catalog had moved on, and omitting `kotlinx-coroutines` entirely; nothing gates it,
so the only thing that keeps it honest is reading the catalog rather than this file.

| Component | Catalog key | Version baseline | License | Project |
|---|---|---:|---|---|
| Kotlin | `kotlin` | 2.3.21 | Apache-2.0 | https://kotlinlang.org/ |
| kotlinx.coroutines | `kotlinxCoroutines` | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Rentile | `rentile` | 0.5.0 | Apache-2.0 | https://rohittp.com/rentile/ |

Those three are what RenG *distributes against*: `commonMain` declares `com.rohittp.rentile:kmp` and
`org.jetbrains.kotlinx:kotlinx-coroutines-core`, so both appear as runtime dependencies in every published
POM and reach every consumer. `kotlinx.coroutines` is a first-party dependency by ADR 0019.

## Test-only dependencies are deliberately not listed

`androidx.test:runner` (Apache-2.0, taken by ADR 0032 for the `androidDeviceTest` source set) and
`org.jetbrains.kotlinx:kotlinx-coroutines-test` are declared for test source sets only and are **not** in
this table. This is a decision, recorded here so it is not re-litigated:

- A notices file discharges attribution obligations to the people who receive RenG's artifacts. Nobody
  receives these. Measured on the merged Cycle H tree, after `:kmp:bundleAndroidMainAar` and
  `:kmp:publishAllPublicationsToLocalTestRepository`: `kmp.aar` holds four entries — `R.txt`,
  `AndroidManifest.xml`, `classes.jar`, `META-INF/.../aar-metadata.properties` — with zero matches for
  `androidx`; `grep -rl androidx` over the 230 files of all seven local publications exits 1 with no hits;
  and `kmp-android`'s POM lists exactly `kotlin-stdlib`, `com.rohittp.rentile:kmp-android` and
  `kotlinx-coroutines-core-jvm`, with no `androidx` entry in any scope.
- Listing them would state a dependency relationship no consumer has, which is worse than silence.

**What would change the answer:** a test-only coordinate appearing in any published artifact or in any
scope of any published POM. If that ever happens it belongs in the table above, and the AAR and POM checks
in the previous paragraph are how to find out.

Rentile's own [third-party notices](https://github.com/rohittp0/rentile/blob/main/THIRD_PARTY_NOTICES.md)
cover its transitive runtime graph. Future copied or adapted upstream code must add its copyright and
license notice here in the same change.

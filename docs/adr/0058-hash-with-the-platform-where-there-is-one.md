# Hash with the platform where there is one

RenG computes SHA-256 in pure Kotlin, everywhere, for everything: the content-addressed identity of
every resource (ADR 0007), the firewall's integrity check on every store hit, and the digest latched
for every transport response. `PureKotlinSha256` is a single object threaded through eight call sites
behind a one-method `fun interface`, and no decision anywhere requires it to be the implementation.

Measured in this tree, digesting a 64 KiB tile:

| | per tile | throughput | 93 tiles | 167 tiles |
|---|---|---|---|---|
| Kotlin/Native, macOS arm64, **debug** | 3176 µs | 20.6 MB/s | 295 ms | 530 ms |
| JVM, warmed | 289 µs | 227 MB/s | 27 ms | 48 ms |

93 and 167 are `CLAUDE.md`'s own recorded canonical-tile counts for one frame at 3840x2160 — with no
LOD history and with one frame of it. A release Kotlin/Native binary will beat that debug figure, but
not by the order of magnitude that separates a hand-written block loop from an instruction the CPU
has had since 2013.

`MessageDigest.getInstance("SHA-256")` on a modern Android device reaches the ARMv8 SHA-2 extensions
and runs at gigabytes a second. CommonCrypto's `CC_SHA256` does the same on every Apple target RenG
ships. The seam to use them already exists; only the implementations are missing.

## The fallback is the point, not an afterthought

`platformSha256` answers `null` where there is no platform digest to reach, and the pure-Kotlin
implementation answers instead. That is not a degraded mode — it is what every release before this one
did on every target — and it is what keeps this change from needing a new dependency on Linux, where
reaching a hardware digest means linking OpenSSL and RenG links nothing.

So Linux keeps the block loop, Android and the four Apple targets do not, and the code that decides
is one `?:`.

## Why this is safe to do to a security check, and what makes it provable

The firewall hashes because the consumer's `Store` is untrusted: a record whose bytes do not match
its digest must not reach the engine. Replacing the hash with the platform's makes that check *more*
trustworthy, not less — an audited, hardware-backed implementation against a hand-written one — and
the property being checked is unchanged.

The real hazard is elsewhere and is worth naming: **a digest is an identity here, not only a check.**
`ResourceKey.stableId` is a SHA-256, content-addressed locators are SHA-256, and the firewall compares
a transport digest with a store digest. An implementation that disagreed with another by one byte
would not fail loudly; it would split a cache in half, silently, across platforms — and a consumer
whose store was written by an Android build and read by an iOS build would see every record refused
as corrupt.

That is why the equivalence test is the substance of this ADR rather than a formality. It runs on
**every** target, digests the same vectors through `PureKotlinSha256` and through whatever the
platform supplies, and asserts byte equality — empty input, one byte, exactly one block, one block
plus one, the two-block length-field boundary at 55/56/64 bytes, and a megabyte. A platform that
disagrees anywhere fails the build on the target it disagrees on.

## The copy that would have eaten the win

`CanonicalBytes.bytes` returns `snapshot.copyOf()` on every access, which is right for a type whose
whole job is to be immutable. At 20 MB/s that copy is noise. At a gigabyte a second it is most of the
work, and a change that made hashing fifty times faster while copying the input twice would have
measured as barely faster at all.

So `CanonicalBytes` gains `byteSnapshot`, the same internal no-copy accessor `StoredRawResource`
already documents and for the same reason: it is read by RenG-internal code only, never handed to a
caller, never mutated, and never retained past the reading call. `bytes` remains the only externally
visible read and still copies.

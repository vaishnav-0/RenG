# A gate that passed these bytes need not run again

`RenGClassGateRunner` answers "is this a valid PNG", "does this GLB parse", "are its features inside
ADR 0021's subset" by re-deriving each answer from the whole resource. Its own KDoc says it has no
memory: *"this interface answers one gate at a time with no result cache between calls."*

The driver asks on every preparation. A resident, fresh sticker still walks `residentObserved` ->
`selectContent` -> `PendingClassGates` each frame, so a PNG that has not changed since the last frame
is copied out of the store and decoded again purely to re-answer a question whose inputs are
identical.

The fork this is ported from measured it on a OnePlus 12R against a real 25-second project: a frame
carrying a 2048x1365 area fill went from **334 ms to 119 ms** when passes were remembered.

## Why a digest hit is sound and not merely likely

Validity here is a pure function of the bytes: `decodePng`, `parseGlbDocument` and the feature scan
read nothing else. `StoredRawResource.contentDigest` is a SHA-256 of exactly those bytes. Identical
bytes therefore cannot decode differently, and a digest match is a proof rather than a heuristic.

This is the argument the resident texture cache already makes for keying uploads by content rather
than by resource key, and ADR 0007's content-addressed identity rests on the same property.

The key is `gate:digest` and not the digest alone, because the three gates ask different questions of
the same bytes: a GLB that parses may still use a feature outside the subset, and `PARSE_GLB` passing
must not answer for `VALIDATE_GLB_FEATURES`.

## Only a pass is remembered

A failure is not cached, and that asymmetry is the interesting half.

A pass says the bytes are good, which is a fact about the bytes. A failure can say something about
this *run* — `decodePng` refuses an image over `maximumDecodedImageBytes`, a limit the consumer
configures and may raise between frames, and a decode can fail for want of memory. Remembering a
failure would turn a transient, configuration-dependent refusal into a permanent verdict on a
resource that is perfectly valid, and the consumer would have no way to clear it.

Failures are also cheap by comparison: a gate that rejects usually rejects early, where a pass is the
path that walks the whole buffer.

## Bounded, and what forgetting costs

A long editing session picks many images and nothing here would otherwise forget one, so the memory is
capped at 512 entries and drops the oldest first. Each entry is a gate name and a hex digest — about
seventy bytes — so the bound is generous rather than tight.

Forgetting costs exactly one re-validation, which is the behaviour every release before this one had
for every resource on every frame. That is what makes the eviction policy uninteresting: the worst
case of the cache is the entire behaviour of its absence.

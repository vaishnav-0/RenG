# Compare rendered frames exactly, never with a tolerance

The branch-comparison harness compares two renders **byte for byte**. There is no tolerance, no
per-channel epsilon, no percentage-of-pixels budget and no perceptual metric. One differing byte is
a difference, and a difference is reported for a human to look at rather than scored against a
threshold. This is the opposite of what the withdrawn Cycle J designed, and the reversal is not a
change of taste — it follows from a change in what is being compared.

**Exactness is attainable here because the comparison is between two commits on one machine.** J
compared a render against a stored baseline, which means comparing across machines, and its own §3.2
lists seven divergence sources of which four can only be answered with "absorb it with a tolerance".
Every one of those sources is a difference between *environments*: driver, rasteriser, `libm`,
shader compiler. K holds all of them fixed. The same physical machine renders both sides, minutes
apart, through the same GL driver and the same C library, so **the only variable left is RenG's own
source** — which is the variable the comparison exists to measure. Exactness stops being an
unreachable ideal and becomes the natural setting; a tolerance would be inventing slack that nothing
in the setup needs.

**A tolerance is not merely unnecessary, it is unusable, and both halves of that are measured.**
Cycle G's precision probe ran on `Apple Software Renderer` — the driver every hosted macOS runner
gets — and found it evaluating transcendentals at **atan 1,687 ULP, sin 170,439 ULP and cos 117,441
ULP**, which put a naive latitude formulation **7,118 m** away from where a `Double` reference says
it belongs, against the half-angle form's 0.680 m. A globe frame's geometry passes through exactly
`sin`, `cos`, `atan` and `exp`. So a cross-renderer tolerance is not a question of the last few
bits: two renderers can disagree by kilometres of ground, and the tolerance that survives that is a
tolerance that admits anything.

Now set that against the defects this project has actually found. The cycle's design records them as
**1 pixel of a 16,384-pixel frame** — a depth inversion, in the 128-pixel readback frame the basemap
suite draws into — and **14 pixels of 589,824**, a tile seam in the 768-pixel terrain frame. The
same driver's *own* rasterisation defect, measured by the project's probe on the same 128-pixel
frame, costs **3,005 of 15,876 interior pixels**. That is the comparison that settles it: the noise
a cross-machine tolerance must absorb is three thousand times the signal it is meant to detect, in
the same units, in the same frame. **Any threshold wide enough to survive `Apple Software Renderer`
hides both defects completely.** A tolerance in this instrument would not be a compromise between
sensitivity and noise; it would be a guarantee of insensitivity, dressed as rigour.

**The residual risk is cross-build determinism, and it is stated here rather than hidden because
this repository has never tested it.** What is gated today is byte-identity *within* one build:
thirteen analytical readback suites assert relationships over pixels a single process just drew. Two
commits are two builds. Kotlin/Native compiles through LLVM, and a change to a Kotlin source file
can shift inlining, instruction selection or floating-point contraction in a translation unit nobody
edited — so in principle a commit that touches a resource-cache key could move a pixel in the ground
shader's arithmetic. If that happens, the comparison reports it.

**That report is the right failure, not a false positive.** Its content is "this commit moved
pixels, come and look", and a developer looking at a moved pixel they did not expect to move is
precisely the outcome this instrument is for. The wrong response is to widen the comparison until
the report goes away, because that is how a gate becomes decoration. The right response, if it turns
out to be common rather than theoretical, is a **spike that measures it** — render the same corpus
from two builds of the *same* commit and count what differs — and then a decision made on that
measurement. Until such a spike exists, the frequency of this failure is unknown, and it is recorded
here as unknown.

**What a green comparison means is narrow, and overclaiming it would be worse than not running it.**
It means **nothing changed**. It does not mean anything is correct. A renderer that has been drawing
the ground upside down since `0.3.0` compares green against itself forever. Correctness comes from
two other places and only from them: the **human baseline review**, in which a person looks at every
frame in the corpus and declares it right, and the **thirteen analytical readback suites**, which
assert geometric relationships derived from the fixture rather than from a stored picture. The
comparison inherits its authority from the review; it manufactures none of its own. A baseline
nobody looked at proves only that two commits agree with each other.

**This is sharper still in CI, where the renderer is one the project's own probe distrusts.** A
hosted macOS runner has no GPU and gives `Apple Software Renderer`, whose rasteriser drops large
quads reaching far outside the viewport — measured at 2,112 disagreeing pixels against a 512-pixel
budget on the CGL path and 3,040 on the simulator, which is why two readback cases stand down out
loud there. **An A/B comparison is unharmed by that**, because both sides are rendered by the same
defective rasteriser and the defect cancels. What it means is that **CI's images are a statement
about change and never about correctness**, and the suite's own output has to say so, because an
image artifact attached to a CI run reads as evidence of a picture being right unless it explicitly
denies it.

**Nothing here reopens the cross-machine question.** Baselines stored in the repository, tolerances
derived per `(rendererString, dialect)`, and any comparison between images produced on different
hardware are all out of scope and stay out. If they ever come back, they come back as a new decision
with new measurements — not by relaxing this one a notch at a time.

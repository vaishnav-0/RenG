# Report engine label exclusions as one aggregate diagnostic

Until this cycle RenG has read exactly nothing the engine said about itself. `classifyEngineFailure`
(`internal/firewall/EngineFailureClassification.kt`) is the one seam where a Rentile failure becomes a RenG
failure, and its whole discipline is subtraction: it switches on `RentileErrorCode`, carries across three
values — the code, an enum `resourceClass`, and a `sanitizedResourceId` that is already a SHA-256 of a
credential-redacted URL — and drops everything else. The engine's `message` is dropped whole, so are its
`affectedTiles`, and its `diagnostics` are **never read at all**, on the recorded ground that Rentile's
`RenderDiagnostic` carries free-form `details`. That has been the right posture for failures, because a
failure is a thing RenG can describe in its own vocabulary. Labels are the first case where the engine knows
something RenG cannot find out any other way and the frame did not fail.

**RenG emits exactly one aggregate diagnostic, `DiagnosticCode.LABEL_CONTENT_EXCLUDED`, once per `prepare`
in which the engine reported any label exclusion at all. Only the code and a severity cross the boundary.**
It says *some label content is missing from this frame*. It says nothing about which script, which layer,
which font stack or which feature, and it carries nothing from the engine's `details`, which are free-form
and can carry a signed URL exactly as an injected adapter's message can — the rule ADR 0016 already sets and
`EngineFailureClassification` already cites. Severity is the one field the engine moves: the aggregate
carries the most severe of the exclusions reported on that `prepare`, because an aggregate reporting `INFO`
over an engine `ERROR` under-reports, and one reporting `ERROR` over an `INFO` cries wolf. An exclusion is
never a failure — the frame prepared and it drew — so severity says how much was lost, not whether anything
broke, and `Diagnostic`'s `init` is where that is enforced rather than left to the emitting call site.

**The rejected alternative is mirroring Rentile's own label-exclusion codes into RenG's `DiagnosticCode`,
and it is worth the space because it is the more useful design and it is still wrong.** A consumer told
`COMPLEX_SCRIPT_LABEL_EXCLUDED` rather than "something was excluded" knows immediately that the problem is
their language coverage and not their style; that is real information and this ADR gives it up on purpose.
The cost of keeping it is that RenG's public API would then track Rentile release by release. Every Rentile
version that adds an exclusion code, renames one, splits one in two or retires one becomes a RenG ABI event
— a public enum entry, an ABI dump diff, a `when` branch, and a version number RenG has to move for a reason
that has nothing to do with RenG. A consumer switching on RenG's code would be switching on Rentile's
vocabulary at one remove, so RenG's closed vocabulary would stop being closed and stop being RenG's. That
is precisely the coupling ADR 0016 built the firewall to prevent and ADR 0017 kept out of the lifecycle, and
it does not become acceptable because the payload is informational rather than a failure. The general rule
this fixes: **engine data may cross this boundary; engine vocabulary never does.** A code is vocabulary. A
severity is data, because RenG's three severities are RenG's own and the mapping onto them is RenG's.

**The motivating fact is worth stating plainly, because it is the reason to spend a public enum entry on
this at all.** `internal/glyph/ScriptSupport.kt` is **byte-identical** between Rentile `0.5.0` and `0.6.0` —
`git diff --stat` on that path across the range is empty — so the version bump that unlocked five expression
operators and hundreds of previously-excluded text layers moved nothing at all for Hebrew, Arabic, or the
Brahmic and South-East Asian abugidas. Twenty-three script ranges still produce no glyph quads; the engine
reports the exclusion and, today, RenG discards it. RenG cannot fix that downstream — shaping and glyph
metrics are the engine's half of the seam and reimplementing them is not a renderer's job — so the only
thing available is to stop being silent. Without this diagnostic a consumer pointing RenG at an
Arabic-labelled style sees a map with roads, water and no text whatsoever, and nothing anywhere tells them
the renderer knew.

**Two narrower rulings follow, and both exist to stop this decision growing.** The diagnostic is emitted
once per `prepare`, not once per excluded label or once per layer: a per-label count is engine internals
leaking out at volume, and the grouping itself is engine vocabulary, since it reflects how the engine chose
to batch its own exclusions. And RenG does not *detect* exclusions on its own by comparing input text to
output quads — that would mean carrying a copy of Rentile's script-support table inside RenG, which is a
tighter coupling to the engine's behaviour than reading a code is, and one with no version number on it.

This ADR opens no general licence to forward engine diagnostics. The owner's decision list recommended
deferring that question once the version pin moved, and it stays deferred: what ships is one aggregate code
covering label exclusion and nothing else. A second engine-derived diagnostic, for any other class of engine
observation, is a new decision made under this ADR's rule — engine data yes, engine vocabulary no, `details`
never — rather than an application of a precedent already set.

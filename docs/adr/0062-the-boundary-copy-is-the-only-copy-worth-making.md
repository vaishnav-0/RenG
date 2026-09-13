# The boundary copy is the only copy worth making

`selectContent` wraps the resource it was handed in `copyStored(stored)` before putting it in a
`ResolvedResourceContent`. That is a full array copy of every resource, on every route, on every
frame — and it copies something nothing outside this machine can reach.

Every caller already passes a private copy. Checked, not assumed, at all four call sites:

- the two resident paths pass `resident`, which is `event.resource?.let(::copyStored)` — copied at the
  moment the observation crosses into this machine;
- the two store paths pass `validated`, which is `copyValidStoredResource(...)`'s result — a resource
  that function constructs itself.

So the copy inside `selectContent` duplicates an object whose only other reference is the local that
was just handed to it.

## What the copy was defending is defended anyway

The habit this comes from is sound and stays: a value that crosses into RenG from a consumer's
`Store` or `Transport` is copied at that boundary, because the consumer keeps a reference and may
mutate the array afterwards. **That copy is untouched** — line 385's `copyStored` on the observation,
and `copyValidStoredResource`'s own construction, both remain.

What is removed is the *second* copy, taken from a value that never left. And `StoredRawResource`
defends itself independently of either: it keeps `storedBytes` private and `bytes` hands out
`freshCopy()`, so a caller reading the content this selects cannot reach the array behind it however
many times it was or was not copied on the way in.

## Why this is a decision and not a tidy-up

Removing a defensive copy is exactly the kind of change that is right until someone adds a fifth
caller that passes a borrowed array, and then it is a silent aliasing bug rather than a loud one.

The rule this records is therefore about the *boundary*, not about this function: **a resource is
copied where it enters RenG, and nowhere after.** A future caller of `selectContent` is bound by the
same rule its four existing callers already follow, and a caller that cannot honour it should copy at
its own boundary rather than ask this function to copy for everyone.

# A value compares its digest before its bytes

`StoredRawResource.equals` walks `storedBytes.contentEquals(other.storedBytes)` before it looks at
anything cheaper, and `hashCode` calls `contentHashCode()` on the whole array. For a resource of any
size that is a lot of work to reach answers something already present summarises.

`contentDigest` is a SHA-256 of exactly `storedBytes`. Two resources whose bytes differ at all differ
in their digest, so comparing the digest first lets every **unequal** comparison return without
walking megabytes — and unequal is the common case, because the reason anything compares two stored
resources is to find out whether one replaced the other.

## The byte walk is kept, and that is the decision

The tempting version drops `contentEquals` entirely: the digest is a SHA-256, so equal digests mean
equal bytes, and the walk is redundant.

It is not redundant, because of **where the digest comes from**. `StoredRawResource` is a public type
a consumer constructs, and its `contentDigest` is whatever that consumer passed. The firewall checks
it against the bytes on the paths that matter (`copyValidStoredResource`), but `equals` is not one of
those paths and must not assume it ran. Trusting the digest alone would make equality exactly as sound
as its least careful supplier, and a consumer whose store returns a stale digest beside fresh bytes
would find two different resources comparing equal.

So the order is: digest, then metadata, then bytes. Same answers as before, in the order that usually
avoids reaching the last one. The walk runs only when everything cheap has already matched, which is
the case where it is genuinely deciding something.

## `hashCode` may trust what `equals` proves

`hashCode` **does** hash the digest alone, and that is not the same trust the paragraph above refuses.

The contract `hashCode` owes is that equal objects hash equally. `equals` requires digests to match,
so two equal resources always have equal digests and therefore equal hashes. A lying digest can make
two *unequal* resources collide, which costs a bucket comparison that then calls `equals` and gets
the right answer — a hash collision is a performance event, never a correctness one.

That asymmetry is the whole reason `equals` keeps its byte walk while `hashCode` drops it.

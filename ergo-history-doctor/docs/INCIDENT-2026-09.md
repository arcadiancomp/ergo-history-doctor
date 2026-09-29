# Case study: September 2026 Ergo 6.0.6 recovery

This project grew out of a real recovery on a Windows Ergo 6.0.6 full node. The purpose of recording the incident is to preserve an empirical test case, not to claim a proven upstream root cause.

## Initial symptom

The node had suffered a crash and could not recover cleanly with the normal synchronization path. Headers could advance far beyond the full-block/state chain, but full-block application remained stuck.

The first concrete failure appeared while applying block 1,789,532 and involved the UTXO AVL state. We recovered state independently from a validated UTXO snapshot at height 1,775,615 and replayed canonical blocks to the last known-good boundary. That established that the canonical blockchain data itself could be applied to a clean state.

After state recovery, a second problem became visible in history.

## Persisted history state

The best-header chain was healthy and far ahead of the full-block chain. At the stalled frontier and across a long later range, block-section records had combinations such as:

```text
canonical header: present
TX raw object:    present in some heights
TX logical lookup: absent
TX semantic validity: Invalid

AD raw object:    present in some heights
AD logical lookup: absent
AD semantic validity: Invalid

EXT:              often healthy and retained
```

The important lesson was that **physical storage presence was not the same thing as native logical presence**.

Instead of editing LevelDB directly, we inspected the Ergo 6.0.6 JAR and used native classes/methods including:

- `ErgoHistory`
- `HistoryStorage`
- `HistoryModifierSerializer`
- `bestHeaderAtHeight()` / canonical header indexes
- `modifierById()`
- `isSemanticallyValid()`
- `validityKey()`
- `HistoryStorage.remove()`

## Range analysis

A read-only scanner found the poisoned range was exactly:

```text
1,789,534 .. 1,867,275
```

That is **77,742 canonical heights**.

Across the range:

```text
TX Invalid validity rows: 77,742
AD Invalid validity rows: 77,742
Total Invalid rows:       155,484

TX stale raw objects:      51,558
AD stale raw objects:      50,954
Total stale raw objects:  102,512

EXT present:               51,558 heights
EXT clean-missing:         26,184 heights
```

Canonical headers were preserved. Existing healthy extension sections were preserved.

## Repair

The repair helper was deliberately fail-closed. Before writing, it verified the exact canonical range and the expected poisoned state. It then processed bounded height batches and removed only:

- TX/AD validity keys proven to encode `Invalid`; and
- the corresponding stale raw TX/AD object where one existed.

It did **not**:

- delete the history database;
- delete canonical headers;
- rebuild state again;
- manually mark any modifier `Valid`;
- touch history beyond the proven range.

The repair was resumable. Each target was accepted only when it was still in the exact original poisoned state or was already in the exact clean state produced by a prior batch.

Final offline verification reported all 77,742 target heights clean and zero remaining poisoned rows in the target range.

## Normal node recovery after cleanup

After restarting Ergo normally, the node immediately downloaded the body for height 1,789,534, assembled the full block, applied it to UTXO state and continued sequentially through later heights.

The node ultimately returned to equal header/full heights. A later observed status was:

```text
08:31:14 H=1882977 F=1882977 TX=Y EXT=Y FULL=Y State=130b3a08
08:31:17 H=1882977 F=1882977 TX=Y EXT=Y FULL=Y State=130b3a08
08:31:20 H=1882977 F=1882977 TX=Y EXT=Y FULL=Y State=130b3a08
08:31:23 H=1882978 F=1882978 TX=Y EXT=Y FULL=Y State=dc7525e2
```

No genesis resync was required.

## Upstream connection

After the recovery was described publicly, Ergo core developer Alex Chepurnoy pointed us to PR #2548, "Improved repair after DB corruption":

https://github.com/ergoplatform/ergo/pull/2548

The PR addresses the same broad symptom family: full-block synchronization can remain stuck behind headers when persisted block sections/indexes are damaged or invalid and therefore do not naturally re-enter the download/application path.

That makes it plausible that the incident exercised a failure class the upstream work is intended to address. It does **not** establish that the initiating cause or every damaged record in this case was identical to the PR's test scenarios.

## Why preserve an external tool?

Even if upstream automatic recovery handles this class perfectly in future releases, an offline doctor remains useful for:

- answering *why* a node is stuck before deleting data;
- producing a machine-readable corruption map;
- examining very large header/full gaps;
- checking older node versions;
- creating an auditable repair plan;
- giving maintainers reproducible evidence rather than only a symptom report.

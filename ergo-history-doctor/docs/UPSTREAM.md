# Relevant upstream Ergo work

This document records upstream issues and pull requests related to the failure modes Ergo History Doctor is intended to diagnose. It is intentionally descriptive: similarity of symptoms does not prove an identical root cause.

## PR #2548 — Improved repair after DB corruption

https://github.com/ergoplatform/ergo/pull/2548

Status checked 2026-09-28: **open / under review**.

The current patch expands `ErgoHistory.repairIfNeeded` to repair a full-block chain stuck behind the header chain. The PR documentation explicitly discusses damaged/invalid block sections, corrupted or missing headers, lost height indexes, and the problem that damaged modifiers may not be re-downloaded when history still considers them stored.

It also adds `HistoryStorage.modifierByIdFromDb`, which deliberately bypasses in-memory caches and asks the native `HistoryModifierSerializer` to parse persisted bytes directly.

The PR's current implementation has `MaxHeadersToRepair = 1000`. That is appropriate as a bounded automatic startup repair, but it is a different operational role from an external forensic scanner that can deliberately inspect a very large gap while the node is stopped.

Review discussion has also raised useful safety questions around failure propagation from `HistoryStorage.remove`, actor-thread work, cached delivery state, sync-mode coverage and complete-but-unapplied blocks. Those concerns influenced this tool's conservative design: explicit offline planning, no automatic complete-but-unapplied repair, bounded/resumable batches, and persisted postverification.

## Issue #1443 — Non-atomic update of HistoryStorage

https://github.com/ergoplatform/ergo/issues/1443

This older open issue records the important symptom that history can physically contain a modifier ID while logical modifier lookup returns `None`:

```text
modifierById(id) // None
contains(id)     // true
```

That physical/logical distinction is central to this tool's scanner.

## Issue #1261 — Synchronisation stops when no disk space left on device

https://github.com/ergoplatform/ergo/issues/1261

This issue documents a node becoming stuck after LevelDB/state writes failed because the device ran out of space, and remaining stuck after space was freed and the node restarted.

## Issue #1827 — Repair corrupted UtxoState & History states after OOM error

https://github.com/ergoplatform/ergo/issues/1827

This open issue discusses repairing UTXO state and history inconsistencies after an OOM-related failure.

## PR #2523 — Propagate history repair failures before publishing recovered state

https://github.com/ergoplatform/ergo/pull/2523

Status checked 2026-09-28: open.

This work focuses on correctly propagating failures from history repair/storage operations instead of reporting recovery as successful when a lower-level operation failed.

That concern is especially relevant to Ergo 6.0.6: its `HistoryStorage.remove` implementation can discard the result of the nested index-store removal because of value-discarding in the outer `map`. Ergo History Doctor therefore does not trust `Success` alone; it reads every affected record back and then closes/reopens the DB for final verification.

## PR #2541 — Recover paired history insertions before exposing stored objects

https://github.com/ergoplatform/ergo/pull/2541

Status checked 2026-09-28: open.

This work introduces recovery/journaling around paired history object/index writes, addressing the broader class of partial/non-atomic persistence failures.

## Ergo 6.0.6 release

https://github.com/ergoplatform/ergo/releases/tag/v6.0.6

The 6.0.6 release included several recovery/synchronization fixes, including preserving prepared UTXO snapshot state across restart, a Digest recovery checkpoint-root fix, retrying cached block sections after remote-header processing, and NiPoPoW/UTXO snapshot bootstrap fixes.

Ergo History Doctor is not a claim that those fixes are defective. It is a forensic/recovery tool for histories that are already in an inconsistent persisted state, including older or unusual failure states that normal startup does not unwind.

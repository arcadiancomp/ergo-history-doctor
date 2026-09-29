# Design notes

## Why an external offline tool?

The Ergo node should ultimately repair recoverable database inconsistencies itself. Upstream PR #2548 is moving in that direction.

An external tool has a different risk envelope. It can require the node to be stopped, spend minutes scanning tens of thousands of heights, emit detailed reports, insist on a human-readable repair plan, and refuse any changed precondition. Those are useful properties for forensic recovery but undesirable for normal node startup.

## Native interpretation, not schema reimplementation

The doctor links against the target Ergo JAR and uses native APIs for:

- configuration and network settings;
- `HistoryStorage` database access;
- canonical best-header indexes;
- `HistoryModifierSerializer` parsing;
- semantic validity;
- native validity-key derivation;
- native history record removal.

Raw bytes and hashes are recorded to bind a repair plan to a specific pre-state, not to reinterpret Ergo serialization independently.

## Why not call `ErgoHistory.readOrGenerate()`?

Ergo 6.0.6 calls `repairIfNeeded()` from `ErgoHistory.readOrGenerate()`. That means simply opening history through the normal startup helper may perform a repair.

The doctor instead constructs `HistoryStorage` and the appropriate `ErgoHistory` processor directly, mirroring the node's history implementation while deliberately skipping startup repair and skipping state/wallet/mempool initialization.

## Why postverify `HistoryStorage.remove()`?

In Ergo 6.0.6, the storage removal routine removes object records and then invokes index removal inside the outer `Try.map`. The nested result can be value-discarded, so an outer success is not sufficient evidence that every requested index key was actually removed.

The doctor therefore treats native `remove()` as the write primitive but not as the proof of success. It immediately checks every target and then closes/reopens history for a final persisted verification.

## Why no automatic header repair in v0.1?

Header removal/truncation changes canonical synchronization structure and has wider consequences than converting a body section from an inconsistent state to a cleanly missing state. Upstream PR #2548 contains logic for this, but an independent external implementation should not duplicate it casually.

`walk-gap` is intended to diagnose header/index corruption. A future version may add a separately reviewed header repair mode with much stronger chain-boundary proofs.

## Why no automatic complete-but-unapplied repair?

PR #2548 includes logic to remove apparently complete block sections when a block is present but was never applied, so redownload can retrigger processing. Review discussion correctly notes that this condition deserves care because it can be a false positive in some histories or sync modes.

For v0.1, the doctor reports those states but only auto-plans repairs for direct persisted contradictions: Invalid metadata, unparsable raw objects, or Valid metadata with a missing object.

## Repair-plan invariants

A plan binds to:

- tool version;
- network;
- normalized node directory;
- Ergo JAR/code-source SHA-256 when available;
- header height;
- full-block height;
- best-header ID;
- best-full-block ID;
- selected height range and section types.

Each action binds to:

- canonical height and header ID;
- exact section ID from that header;
- semantic validity before repair;
- whether raw bytes existed;
- whether native parsing succeeded;
- SHA-256 of exact raw bytes, if any;
- exact validity-row bytes, if any.

The actual plan file is then SHA-256 hashed and that hash is required to enter repair mode.

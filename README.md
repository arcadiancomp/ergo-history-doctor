# Ergo History Doctor

**Offline, fail-closed diagnostics and narrowly scoped history repair for Ergo full nodes.**

Ergo History Doctor was created after recovering a real Ergo 6.0.6 node whose header chain was healthy and far ahead of its full-block/state chain, while persistent history metadata prevented the node from simply downloading the missing block bodies again.

The central design rule is simple:

> **Let the matching Ergo node code interpret its own database.**

The tool compiles against and runs with the same Ergo node JAR as the node being inspected. It uses Ergo's native `ErgoHistory`, `HistoryStorage`, `HistoryModifierSerializer`, canonical-header indexes, semantic-validity model, `validityKey()` and `HistoryStorage.remove()` rather than independently reverse-engineering LevelDB keys or serialized objects.

## Status

`v0.1.0` is intentionally conservative. It is an external/offline companion to the node, not a replacement for upstream recovery work.

As of **2026-09-28**, Ergo PR [#2548 — Improved repair after DB corruption](https://github.com/ergoplatform/ergo/pull/2548) is still under review. Its current code targets the same broad failure family: a full-block chain stuck behind the header chain because block sections or indexes are corrupted/invalid and therefore are not naturally re-downloaded. It is not proven that our incident had exactly the same root cause. See [docs/UPSTREAM.md](docs/UPSTREAM.md).

## Safety model

**Stop the Ergo node before running this tool.** History LevelDB must not be open by the node and the doctor at the same time.

The v0.1 repair path deliberately has strict boundaries:

- The **state DB is never opened**.
- Headers are **never automatically deleted, truncated, marked Valid, or rewritten**.
- No modifier is ever manually marked `Valid`.
- Healthy-looking **complete-but-unapplied** blocks are diagnose-only.
- Automatic repair is limited to non-header block sections (`TX`, `AD`, `EXT`) whose persisted state is mechanically inconsistent:
  - semantic validity is `Invalid`; or
  - a raw object exists but Ergo's native serializer cannot parse it as the expected section type; or
  - semantic validity is `Valid` but the raw object is missing.
- Repair requires a plan created against the stopped DB, then the **SHA-256 of that exact plan** must be supplied back to the tool.
- The plan records node heights, tip IDs, node directory and the SHA-256 of the actual Ergo code/JAR used by the tool. If those change, repair refuses to run.
- Repairs run in bounded height batches and are resumable. A target may only be in its **exact original state**, a **deletion-only partial state**, or the exact **clean state**.
- Each batch is read back after `HistoryStorage.remove()`.
- At the end, the history DB is closed and reopened and every target is verified again from persisted storage.

These constraints are deliberate. A diagnostic tool should prefer refusing an ambiguous repair over inventing one.

## Build

Ergo 6.0.6 uses Scala 2.12. This project builds a small thin JAR and expects the matching Ergo node JAR on both the compile and runtime classpaths.

### Windows / PowerShell

```powershell
cd C:\path\to\ergo-history-doctor

.\scripts\build.ps1 -ErgoJar C:\ERGO\ergo-6.0.6.jar
```

The build helper uses an installed `sbt` if available; otherwise it downloads the pinned sbt launcher. It does **not** copy or commit the Ergo JAR into this repository.

### Linux / macOS

```bash
./scripts/build.sh /path/to/ergo-6.0.6.jar
```

## First diagnostic pass

With the Ergo node stopped:

```powershell
$jar = 'C:\ERGO\ergo-6.0.6.jar'
$conf = 'C:\ERGO\ergo.conf'

.\scripts\doctor.ps1 -ErgoJar $jar summary --config $conf
.\scripts\doctor.ps1 -ErgoJar $jar walk-gap --config $conf
.\scripts\doctor.ps1 -ErgoJar $jar scan --config $conf --out C:\ERGO\history-scan.tsv
```

`summary` reports the header/full-block gap and the exact Ergo code source loaded by the tool.

`walk-gap` follows raw parent links from the best header down toward the best full block. It does not rely on every height index being present, so it can detect a missing/corrupt header or a height-index mismatch.

`scan` walks canonical heights and classifies TX/AD/EXT sections using both their **physical storage state** and Ergo's **logical/semantic view**.

Useful classifications include:

| Classification | Meaning |
| --- | --- |
| `PRESENT_VALID` | raw object parses, logical lookup resolves, semantic validity is Valid |
| `PRESENT_UNKNOWN` | raw object parses/resolves and no semantic verdict is stored |
| `INVALID_RAW_PARSEABLE` | raw section exists and parses, but validity is Invalid |
| `INVALID_RAW_CORRUPT` | raw section exists, does not parse, and validity is Invalid |
| `INVALID_NO_RAW` | no raw section remains, but an Invalid validity row remains |
| `CORRUPT_RAW_UNKNOWN` | raw object exists but native parsing fails; no semantic verdict |
| `VALID_BUT_MISSING_RAW` | validity says Valid but the raw object is absent |
| `CLEAN_MISSING` | raw object and validity row are absent; semantic state is Absent |
| `MALFORMED_VALIDITY_ROW` | a validity row exists but does not decode as Valid/Invalid |

## Inspect one height

```powershell
.\scripts\doctor.ps1 -ErgoJar $jar inspect --config $conf --height 1789534
```

This is useful before doing any broad scan or repair.

## Create a repair plan

The default range is `fullBlockHeight + 1 .. headersHeight` and the default section set is `TX,AD,EXT`.

```powershell
.\scripts\doctor.ps1 -ErgoJar $jar plan `
    --config $conf `
    --out C:\ERGO\repair-plan.tsv
```

You can restrict the range or types:

```powershell
.\scripts\doctor.ps1 -ErgoJar $jar plan `
    --config $conf `
    --from 1789534 `
    --to 1867275 `
    --sections TX,AD `
    --out C:\ERGO\repair-plan.tsv
```

Planning is read-only. It validates canonical continuity across the selected range and **refuses** if a canonical header is missing, corrupt, height-mismatched, parent-mismatched, or semantically Invalid.

The final output includes a SHA-256 such as:

```text
SHA-256 = 0123456789abcdef...
```

Review the plan file before doing anything else.

## Verify a plan without writing

```powershell
.\scripts\doctor.ps1 -ErgoJar $jar verify `
    --config $conf `
    --plan C:\ERGO\repair-plan.tsv
```

Rows are reported as:

- `original` — exactly as recorded in the plan;
- `partial` — only planned deletions have already happened;
- `clean` — raw object and validity row are absent and Ergo reports `Absent`;
- `unexpected` — something changed/reappeared; repair will refuse.

## Apply a plan

**The node must still be stopped.** Supply the exact SHA-256 printed when the plan was created:

```powershell
.\scripts\doctor.ps1 -ErgoJar $jar repair `
    --config $conf `
    --plan C:\ERGO\repair-plan.tsv `
    --confirm 0123456789abcdef...
```

Default batch size is 500 heights. To choose another bounded size:

```powershell
.\scripts\doctor.ps1 -ErgoJar $jar repair `
    --config $conf `
    --plan C:\ERGO\repair-plan.tsv `
    --confirm 0123456789abcdef... `
    --batch-heights 250
```

A crash/interruption is recoverable with the **same plan** as long as no unplanned changes occurred. The next run accepts exact original rows, deletion-only partial rows, and already-clean rows.

## Why use the node JAR itself?

A particularly important Ergo history state is possible when physical and logical presence disagree. In Ergo 6.0.6, `HistoryStorage.contains(id)` can be true because bytes exist, while `modifierById(id)` can still return `None` because semantic validity is `Invalid` or because `HistoryModifierSerializer` cannot parse the persisted bytes.

That distinction matters. A raw LevelDB dump can tell you bytes exist. It cannot, by itself, tell you what the **matching Ergo release** considers those bytes to mean.

Ergo History Doctor therefore uses the node implementation as the authority and keeps raw storage inspection secondary to native interpretation.

## The incident that motivated this project

In September 2026 we recovered an Ergo 6.0.6 node that had:

- healthy/canonical headers far ahead of the full-block chain;
- a repaired UTXO state through height 1,789,533;
- a poisoned body-history interval from **1,789,534 through 1,867,275** (77,742 heights);
- **155,484** persisted Invalid TX/AD validity records;
- **102,512** stale TX/AD raw objects to remove;
- canonical headers and useful extension sections that were preserved.

After the narrowly targeted cleanup, the ordinary Ergo synchronizer immediately resumed downloading, validating and applying full blocks. The node eventually returned to equal header/full heights without deleting its history or resyncing from genesis.

See [docs/INCIDENT-2026-09.md](docs/INCIDENT-2026-09.md) for the case study.

## Relationship to upstream Ergo repair work

This project should get *less* necessary as the node itself becomes better at self-repair. That is a success condition, not a problem.

The external tool still has useful roles:

- read-only forensic analysis and reports;
- checking a history DB before deciding whether to wipe/resync;
- whole-gap inspection when the header/full gap is very large;
- explicit repair planning and audit trails;
- conservative recovery against older node releases;
- helping produce reproducible bug reports for upstream maintainers.

The current head of PR #2548 caps its corruption walk at 1,000 headers above the best full block. Our incident had a gap of roughly 93,000 headers and a 77,742-height poisoned region, so an external full-range scanner remains useful even if that upstream repair lands unchanged.

## Version compatibility

`v0.1.0` was designed against **Ergo 6.0.6** APIs. Build and run the doctor with the exact node JAR you intend to inspect.

Do not assume a binary built against one Ergo release is safe with another. Rebuild against the target JAR, run read-only commands first, and treat compile/runtime incompatibility as a feature: it is safer to fail than silently guess a changed storage API.

## License

MIT. See [LICENSE](LICENSE).

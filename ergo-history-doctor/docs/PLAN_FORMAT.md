# Repair plan format

Repair plans are deliberately plain UTF-8 TSV so an operator can inspect them without special tooling.

The first line identifies the format:

```text
# ergo-history-doctor-plan-v1
```

Metadata lines bind the plan to the exact stopped-node view used to create it:

```text
# toolVersion=0.1.0
# generatedAt=...
# network=mainnet
# nodeDirectory=...
# ergoCodeSource=...
# ergoCodeSha256=...
# ergoVersionHint=...
# headersHeight=...
# fullBlockHeight=...
# bestHeaderId=...
# bestFullBlockId=...
# from=...
# to=...
# sections=AD,EXT,TX
```

The action table contains:

```text
height
section
headerId
sectionId
preValidity
preRaw
preRawParse
preRawSha256
preValidityRowHex
action
```

The plan file itself is SHA-256 hashed after creation. `repair` requires that hash through `--confirm`.

## Resume states

For each plan row the repair command accepts only:

- **ORIGINAL** — raw object and validity row exactly match the recorded pre-state;
- **PARTIAL** — one or both planned components have already been deleted, while every surviving component is byte-for-byte identical to the original;
- **CLEAN** — raw object absent, validity row absent, semantic validity `Absent`.

Any new/replaced raw bytes, changed validity bytes, changed canonical header linkage, changed node tip/full height, changed node directory, or changed Ergo JAR hash is **UNEXPECTED** and causes a refusal.

This is what makes a batch repair safely resumable without treating arbitrary intermediate database states as acceptable.

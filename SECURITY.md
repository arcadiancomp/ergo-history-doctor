# Safety and security

Ergo History Doctor can delete selected records from an Ergo history database. Treat `repair` as a privileged recovery operation.

## Before using repair

1. Stop the Ergo node completely.
2. Keep a filesystem-level backup appropriate to your storage constraints and risk tolerance when possible.
3. Run `summary`, `walk-gap`, `scan`, and `plan` first.
4. Read the generated plan.
5. Run `verify` before `repair`.
6. Use the exact matching Ergo node JAR.

## Fail-closed policy

Please report any case where the tool writes despite an ambiguous or changed precondition. That is a higher-priority defect than refusing a repair it might have been able to perform.

## What v0.1 will not repair automatically

- state/AVL databases;
- headers or canonical-chain selection;
- lost/corrupt height indexes;
- complete-but-unapplied blocks whose individual sections look healthy;
- arbitrary LevelDB corruption;
- a database being modified concurrently by a running Ergo node.

Those states can be diagnosed, but require a separate reviewed recovery procedure or upstream tooling.

#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
source = root / "src/main/scala/org/ergoplatform/nodeView/history/ErgoHistoryDoctor.scala"
text = source.read_text(encoding="utf-8")

required = [
    "HistoryStorage",
    "HistoryModifierSerializer",
    "validityKey",
    "ModifierSemanticValidity.Absent",
    "PERSISTED POSTFLIGHT PASSED",
    "automatic header repair is unsupported",
]

prohibited_calls = [
    "reportModifierIsValid(",
    "reportModifierIsInvalid(",
    "forgetHeader(",
    "updateBestFullBlock(",
    "UtxoState(",
    "ErgoState.readOrGenerate(",
]

failed = False
for token in required:
    if token not in text:
        print(f"ERROR: required safety marker missing: {token}")
        failed = True

for token in prohibited_calls:
    if token in text:
        print(f"ERROR: prohibited write primitive appears in doctor source: {token}")
        failed = True

if failed:
    sys.exit(1)

print("Safety guard source check passed.")

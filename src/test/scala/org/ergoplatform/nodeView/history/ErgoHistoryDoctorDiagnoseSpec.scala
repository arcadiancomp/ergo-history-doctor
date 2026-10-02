package org.ergoplatform.nodeView.history

import org.scalatest.funsuite.AnyFunSuite

class ErgoHistoryDoctorDiagnoseSpec extends AnyFunSuite {

  private def facts(
      gap: Int = 10,
      headerProblems: Long = 0,
      repairCandidates: Long = 0,
      unsupportedAnomalies: Long = 0,
      completeUnappliedHeights: Long = 0
  ): ErgoHistoryDoctor.DiagnoseFacts =
    ErgoHistoryDoctor.DiagnoseFacts(
      gap,
      headerProblems,
      repairCandidates,
      unsupportedAnomalies,
      completeUnappliedHeights
    )

  private def section(
      classification: String,
      validity: String,
      rawPresent: Boolean,
      rawParseable: Boolean
  ): ErgoHistoryDoctor.DiagnoseSectionFacts =
    ErgoHistoryDoctor.DiagnoseSectionFacts(
      classification,
      validity,
      rawPresent,
      rawParseable
    )

  test("no active gap is reported explicitly") {
    assert(
      ErgoHistoryDoctor.diagnosisCode(facts(gap = 0)) ==
        "NO_ACTIVE_GAP"
    )
  }

  test("header problems take precedence over section findings") {
    assert(
      ErgoHistoryDoctor.diagnosisCode(
        facts(
          headerProblems = 1,
          repairCandidates = 3,
          unsupportedAnomalies = 2
        )
      ) == "ACTIVE_GAP_WITH_HEADER_ANOMALIES"
    )
  }

  test("mixed repairable and unsupported section anomalies are distinguished") {
    assert(
      ErgoHistoryDoctor.diagnosisCode(
        facts(
          repairCandidates = 3,
          unsupportedAnomalies = 2
        )
      ) == "ACTIVE_GAP_WITH_MIXED_ANOMALIES"
    )
  }

  test("unsupported anomalies without repair candidates are diagnose-only") {
    assert(
      ErgoHistoryDoctor.diagnosisCode(
        facts(unsupportedAnomalies = 2)
      ) == "ACTIVE_GAP_WITH_UNSUPPORTED_ANOMALIES"
    )
  }

  test("repair candidates are reported when no higher-priority anomaly exists") {
    assert(
      ErgoHistoryDoctor.diagnosisCode(
        facts(repairCandidates = 3)
      ) == "ACTIVE_GAP_WITH_REPAIR_CANDIDATES"
    )
  }

  test("healthy complete unapplied blocks are diagnose-only") {
    assert(
      ErgoHistoryDoctor.diagnosisCode(
        facts(completeUnappliedHeights = 4)
      ) == "ACTIVE_GAP_WITH_COMPLETE_UNAPPLIED_BLOCKS"
    )
  }

  test("ordinary active gap without anomalies is not labeled corrupt") {
    assert(
      ErgoHistoryDoctor.diagnosisCode(facts()) ==
        "ACTIVE_GAP_WITHOUT_REPAIR_CANDIDATES"
    )
  }

  test("ordinary missing bodies are not mislabeled as corruption") {
    val cleanMissing =
      section("CLEAN_MISSING", "Absent", rawPresent = false, rawParseable = false)

    val f = ErgoHistoryDoctor.diagnoseSectionFacts(
      cleanMissing,
      cleanMissing,
      cleanMissing
    )

    assert(f.repairCandidates == 0)
    assert(f.unsupportedAnomalies == 0)

    assert(
      ErgoHistoryDoctor.diagnosisCode(f) ==
        "ACTIVE_GAP_WITHOUT_REPAIR_CANDIDATES"
    )
  }

  test("invalid TX and AD with present extension match repair-candidate diagnosis") {
    val tx =
      section("INVALID_RAW_PARSEABLE", "Invalid", rawPresent = true, rawParseable = true)

    val ad =
      section("INVALID_NO_RAW", "Invalid", rawPresent = false, rawParseable = false)

    val ext =
      section("PRESENT_VALID", "Valid", rawPresent = true, rawParseable = true)

    val f = ErgoHistoryDoctor.diagnoseSectionFacts(tx, ad, ext)

    assert(f.repairCandidates == 2)
    assert(f.unsupportedAnomalies == 0)

    assert(
      ErgoHistoryDoctor.diagnosisCode(f) ==
        "ACTIVE_GAP_WITH_REPAIR_CANDIDATES"
    )
  }

  test("healthy complete unapplied block stays diagnose-only") {
    val valid =
      section("PRESENT_VALID", "Valid", rawPresent = true, rawParseable = true)

    val unknown =
      section("PRESENT_UNKNOWN", "Unknown", rawPresent = true, rawParseable = true)

    val f = ErgoHistoryDoctor.diagnoseSectionFacts(
      valid,
      valid,
      unknown
    )

    assert(f.repairCandidates == 0)
    assert(f.completeUnappliedHeights == 1)

    assert(
      ErgoHistoryDoctor.diagnosisCode(f) ==
        "ACTIVE_GAP_WITH_COMPLETE_UNAPPLIED_BLOCKS"
    )
  }

  test("unresolved parseable raw section is unsupported rather than auto-repairable") {
    val unresolved =
      section(
        "RAW_PARSEABLE_UNRESOLVED",
        "Unknown",
        rawPresent = true,
        rawParseable = true
      )

    val cleanMissing =
      section("CLEAN_MISSING", "Absent", rawPresent = false, rawParseable = false)

    val valid =
      section("PRESENT_VALID", "Valid", rawPresent = true, rawParseable = true)

    val f = ErgoHistoryDoctor.diagnoseSectionFacts(
      unresolved,
      cleanMissing,
      valid
    )

    assert(f.repairCandidates == 0)
    assert(f.unsupportedAnomalies == 1)

    assert(
      ErgoHistoryDoctor.diagnosisCode(f) ==
        "ACTIVE_GAP_WITH_UNSUPPORTED_ANOMALIES"
    )
  }

  test("repairable and unsupported section states produce mixed diagnosis") {
    val invalid =
      section("INVALID_RAW_PARSEABLE", "Invalid", rawPresent = true, rawParseable = true)

    val unresolved =
      section(
        "RAW_PARSEABLE_UNRESOLVED",
        "Unknown",
        rawPresent = true,
        rawParseable = true
      )

    val cleanMissing =
      section("CLEAN_MISSING", "Absent", rawPresent = false, rawParseable = false)

    val f = ErgoHistoryDoctor.diagnoseSectionFacts(
      invalid,
      unresolved,
      cleanMissing
    )

    assert(f.repairCandidates == 1)
    assert(f.unsupportedAnomalies == 1)

    assert(
      ErgoHistoryDoctor.diagnosisCode(f) ==
        "ACTIVE_GAP_WITH_MIXED_ANOMALIES"
    )
  }
}

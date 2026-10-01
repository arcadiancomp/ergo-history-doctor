package org.ergoplatform.nodeView.history

import org.scalatest.funsuite.AnyFunSuite

class ErgoHistoryDoctorSafetySpec extends AnyFunSuite {

  test("height at full block tip is historical report-only") {
    assert(
      ErgoHistoryDoctor.heightScope(100, 100) ==
        "HISTORICAL_APPLIED_REPORT_ONLY"
    )
  }

  test("height below full block tip is historical report-only") {
    assert(
      ErgoHistoryDoctor.heightScope(100, 99) ==
        "HISTORICAL_APPLIED_REPORT_ONLY"
    )
  }

  test("height above full block tip is active gap") {
    assert(
      ErgoHistoryDoctor.heightScope(100, 101) ==
        "ACTIVE_GAP"
    )
  }

  test("repair planning refuses a start exactly at full block tip") {
    val e = intercept[IllegalArgumentException] {
      ErgoHistoryDoctor.requireRepairStartAboveFullBlockHeight(100, 100)
    }

    assert(
      e.getMessage.contains(
        "Refusing to plan repairs at or below best full block height 100"
      )
    )
  }

  test("repair planning refuses a start below full block tip") {
    intercept[IllegalArgumentException] {
      ErgoHistoryDoctor.requireRepairStartAboveFullBlockHeight(100, 99)
    }
  }

  test("repair planning allows the first height above full block tip") {
    ErgoHistoryDoctor.requireRepairStartAboveFullBlockHeight(100, 101)
  }
}
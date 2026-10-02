package org.ergoplatform.nodeView.history

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

class ErgoHistoryDoctorHistoryLayoutSpec extends AnyFunSuite {

  private def withTempRoot(f: Path => Unit): Unit = {
    val root = Files.createTempDirectory("ehd-history-layout-")
    try {
      f(root)
    } finally {
      if (Files.exists(root)) {
        val walk = Files.walk(root)
        try {
          walk.iterator().asScala.toSeq
            .sortBy(_.getNameCount)
            .reverse
            .foreach(p => Files.deleteIfExists(p))
        } finally {
          walk.close()
        }
      }
    }
  }

  private def createStore(historyDir: Path, name: String): Path = {
    val store = Files.createDirectories(historyDir.resolve(name))
    Files.write(
      store.resolve("CURRENT"),
      "MANIFEST-000001\n".getBytes(StandardCharsets.US_ASCII)
    )
    store
  }

  test("missing history directory is rejected and is not created") {
    withTempRoot { root =>
      val history = root.resolve("history")

      assert(!Files.exists(history))

      intercept[IllegalArgumentException] {
        ErgoHistoryDoctor.requireExistingHistoryLayout(history)
      }

      assert(!Files.exists(history))
    }
  }

  test("missing required store is rejected and is not created") {
    withTempRoot { root =>
      val history = Files.createDirectories(root.resolve("history"))

      createStore(history, "index")
      createStore(history, "objects")

      val extra = history.resolve("extra")
      assert(!Files.exists(extra))

      intercept[IllegalArgumentException] {
        ErgoHistoryDoctor.requireExistingHistoryLayout(history)
      }

      assert(!Files.exists(extra))
    }
  }

  test("store without CURRENT is rejected and CURRENT is not created") {
    withTempRoot { root =>
      val history = Files.createDirectories(root.resolve("history"))

      createStore(history, "index")
      createStore(history, "objects")

      val extra = Files.createDirectories(history.resolve("extra"))
      val current = extra.resolve("CURRENT")

      assert(!Files.exists(current))

      intercept[IllegalArgumentException] {
        ErgoHistoryDoctor.requireExistingHistoryLayout(history)
      }

      assert(!Files.exists(current))
    }
  }

  test("CURRENT must be a regular file") {
    withTempRoot { root =>
      val history = Files.createDirectories(root.resolve("history"))

      createStore(history, "index")
      createStore(history, "objects")

      val extra = Files.createDirectories(history.resolve("extra"))
      Files.createDirectory(extra.resolve("CURRENT"))

      intercept[IllegalArgumentException] {
        ErgoHistoryDoctor.requireExistingHistoryLayout(history)
      }
    }
  }

  test("complete existing history marker layout is accepted") {
    withTempRoot { root =>
      val history = Files.createDirectories(root.resolve("history"))

      createStore(history, "index")
      createStore(history, "objects")
      createStore(history, "extra")

      ErgoHistoryDoctor.requireExistingHistoryLayout(history)

      assert(Files.isRegularFile(history.resolve("index").resolve("CURRENT")))
      assert(Files.isRegularFile(history.resolve("objects").resolve("CURRENT")))
      assert(Files.isRegularFile(history.resolve("extra").resolve("CURRENT")))
    }
  }
}

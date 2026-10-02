package org.ergoplatform.nodeView.history

import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

class ErgoHistoryDoctorLockSpec extends AnyFunSuite {

  private def withTempRoot(f: Path => Unit): Unit = {
    val root = Files.createTempDirectory("ehd-lock-")

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

  private def createLockFile(path: Path): Path = {
    Files.createDirectories(path.getParent)

    if (!Files.exists(path)) {
      Files.createFile(path)
    }

    path
  }

  private def createHistoryLocks(history: Path): Unit = {
    Seq("index", "objects", "extra").foreach { name =>
      createLockFile(history.resolve(name).resolve("LOCK"))
    }
  }

  test("missing LOCK is reported missing and is not created") {
    withTempRoot { root =>
      val lockFile = root.resolve("missing").resolve("LOCK")

      val probe = ErgoHistoryDoctor.probeLevelDbLock(lockFile)

      assert(probe.status == "MISSING")
      assert(!Files.exists(lockFile))
    }
  }

  test("unlocked LOCK file is reported free") {
    withTempRoot { root =>
      val lockFile = createLockFile(root.resolve("store").resolve("LOCK"))

      val probe = ErgoHistoryDoctor.probeLevelDbLock(lockFile)

      assert(probe.status == "FREE")
    }
  }

  test("already locked LOCK file is reported busy") {
    withTempRoot { root =>
      val lockFile = createLockFile(root.resolve("store").resolve("LOCK"))

      val channel = FileChannel.open(
        lockFile,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE
      )

      val held = channel.lock()

      try {
        val probe = ErgoHistoryDoctor.probeLevelDbLock(lockFile)

        assert(probe.status == "BUSY")
      } finally {
        held.release()
        channel.close()
      }
    }
  }

  test("all free history store locks are accepted") {
    withTempRoot { root =>
      val history = Files.createDirectories(root.resolve("history"))
      createHistoryLocks(history)

      ErgoHistoryDoctor.requireHistoryStoresUnlocked(history)
    }
  }

  test("one busy history store lock rejects offline opening") {
    withTempRoot { root =>
      val history = Files.createDirectories(root.resolve("history"))
      createHistoryLocks(history)

      val objectsLock = history.resolve("objects").resolve("LOCK")

      val channel = FileChannel.open(
        objectsLock,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE
      )

      val held = channel.lock()

      try {
        val e = intercept[IllegalArgumentException] {
          ErgoHistoryDoctor.requireHistoryStoresUnlocked(history)
        }

        assert(e.getMessage.contains("objects=BUSY"))
      } finally {
        held.release()
        channel.close()
      }
    }
  }
}

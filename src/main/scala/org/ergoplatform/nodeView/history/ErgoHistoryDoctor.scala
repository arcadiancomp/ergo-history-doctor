package org.ergoplatform.nodeView.history

import java.io.{BufferedInputStream, File, FileInputStream, PrintWriter}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.time.Instant
import java.util.jar.JarFile

import org.ergoplatform.consensus.ModifierSemanticValidity
import org.ergoplatform.modifiers.BlockSection
import org.ergoplatform.modifiers.history.{ADProofs, BlockTransactions, HistoryModifierSerializer}
import org.ergoplatform.modifiers.history.extension.Extension
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.nodeView.history.storage.HistoryStorage
import org.ergoplatform.nodeView.history.storage.modifierprocessors.{EmptyBlockSectionProcessor, FullBlockSectionProcessor}
import org.ergoplatform.settings.{Args, ErgoSettings, ErgoSettingsReader, NetworkType}
import scorex.db.ByteArrayWrapper
import scorex.util.ModifierId

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

/**
  * Ergo History Doctor
  *
  * Offline, fail-closed history inspection and narrowly scoped repair for Ergo nodes.
  *
  * The important design choice is that this tool uses the Ergo node's own history classes,
  * serializers, semantic-validity model, canonical-header indexes and storage removal routine.
  * It does not reimplement the LevelDB schema.
  *
  * Safety boundaries:
  *   - the Ergo node MUST be stopped;
  *   - the state DB is never opened;
  *   - headers are never automatically deleted or rewritten;
  *   - modifiers are never marked Valid;
  *   - ambiguous "complete but unapplied" blocks are diagnose-only in v0.1;
  *   - repair is driven by an immutable SHA-256-confirmed plan;
  *   - every batch is verified, and the database is reopened for a persisted postflight.
  *
  * This source intentionally lives in org.ergoplatform.nodeView.history so it can use
  * package-scoped native APIs such as validityKey and historyStorage.
  */
object ErgoHistoryDoctor {

  private val ToolVersion = "0.1.0"
  private val PlanMagic = "ergo-history-doctor-plan-v1"
  private val DefaultBatchHeights = 500
  private val ProgressEvery = 10000
  private val SupportedSections = Set("TX", "AD", "EXT")

  final case class Cli(
      command: String,
      config: Path,
      network: String,
      from: Option[Int],
      to: Option[Int],
      height: Option[Int],
      max: Option[Int],
      out: Option[Path],
      plan: Option[Path],
      confirm: Option[String],
      batchHeights: Int,
      sections: Set[String]
  )

  final case class RuntimeIdentity(
      toolVersion: String,
      ergoCodeSource: String,
      ergoCodeSha256: String,
      ergoVersionHint: String
  )

  final case class Opened(
      settings: ErgoSettings,
      storage: HistoryStorage,
      history: ErgoHistory,
      runtime: RuntimeIdentity
  ) extends AutoCloseable {
    @volatile private var closed = false

    override def close(): Unit = synchronized {
      if (!closed) {
        history.closeStorage()
        closed = true
      }
    }
  }

  final case class SectionState(
      section: String,
      id: ModifierId,
      rawPresent: Boolean,
      rawSha256: String,
      rawParseable: Boolean,
      parsedClass: String,
      logicalResolves: Boolean,
      logicalClass: String,
      validity: String,
      validityRowHex: String,
      classification: String
  )

  final case class HeightState(
      height: Int,
      headerId: ModifierId,
      headerValidity: String,
      headerRawPresent: Boolean,
      headerRawParseable: Boolean,
      parentMatchesCanonical: Boolean,
      tx: SectionState,
      ad: SectionState,
      ext: SectionState
  ) {
    def signature: String =
      s"HDR=$headerValidity/${bool(headerRawPresent)}/${bool(headerRawParseable)}/parent=${bool(parentMatchesCanonical)} " +
        s"TX=${tx.classification} AD=${ad.classification} EXT=${ext.classification}"
  }

  final case class PlanMeta(
      toolVersion: String,
      generatedAt: String,
      network: String,
      nodeDirectory: String,
      ergoCodeSource: String,
      ergoCodeSha256: String,
      ergoVersionHint: String,
      headersHeight: Int,
      fullBlockHeight: Int,
      bestHeaderId: String,
      bestFullBlockId: String,
      from: Int,
      to: Int,
      sections: String
  )

  final case class PlanRow(
      height: Int,
      section: String,
      headerId: String,
      sectionId: String,
      preValidity: String,
      preRaw: Boolean,
      preRawParse: Boolean,
      preRawSha256: String,
      preValidityRowHex: String,
      action: String
  )

  final case class Plan(meta: PlanMeta, rows: Vector[PlanRow])

  def main(args: Array[String]): Unit = {
    try {
      val cli = parseCli(args)
      cli.command match {
        case "help" => usage()
        case _ =>
          require(Files.isRegularFile(cli.config), s"Config file not found: ${cli.config}")
          withHistory(cli) { opened =>
            cli.command match {
              case "summary" => summary(opened)
              case "inspect" => inspect(opened, cli.height.getOrElse(fail("inspect requires --height")))
              case "scan" => scan(opened, cli)
              case "walk-gap" => walkGap(opened, cli.max.getOrElse(Int.MaxValue))
              case "plan" => createPlan(opened, cli)
              case "verify" => verify(opened, cli)
              case "repair" => repair(opened, cli)
              case other => fail(s"Unknown command: $other")
            }
          }
      }
    } catch {
      case e: IllegalArgumentException =>
        System.err.println(s"ERROR: ${e.getMessage}")
        System.exit(2)
      case e: Throwable =>
        System.err.println(s"FATAL: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
        e.printStackTrace(System.err)
        System.exit(1)
    }
  }

  private def withHistory[A](cli: Cli)(f: Opened => A): A = {
    val opened = open(cli.config, cli.network)
    try f(opened)
    finally opened.close()
  }

  /**
    * Open ONLY HistoryStorage and an ErgoHistory implementation.
    *
    * Deliberately do not call ErgoHistory.readOrGenerate(): in Ergo 6.0.6 that method invokes
    * repairIfNeeded() while opening history. A diagnostic command must not mutate merely by opening.
    *
    * No state, wallet or mempool database is opened here.
    */
  private def open(config: Path, network: String): Opened = {
    val nt = NetworkType.fromString(network).getOrElse(fail(s"Unsupported network: $network"))
    val ergoSettings = ErgoSettingsReader.read(Args(Some(config.toAbsolutePath.toString), Some(nt)))

    // HistoryStorage/ErgoHistory.historyDir will create a missing history directory.
    // A diagnostic tool must never silently create a new empty database because the
    // working directory or config path was wrong. Require an existing LevelDB first.
    val historyDir = Paths.get(ergoSettings.directory, "history").toAbsolutePath.normalize
    require(
      Files.isDirectory(historyDir),
      s"History directory does not exist: $historyDir. Refusing to create a new history database; check the node directory/config."
    )
    require(
      Files.isRegularFile(historyDir.resolve("CURRENT")),
      s"Existing Ergo LevelDB was not found at $historyDir (missing CURRENT). Refusing to create/open a new history database."
    )

    val storage = HistoryStorage(ergoSettings)

    val history: ErgoHistory =
      if (ergoSettings.nodeSettings.verifyTransactions) {
        new ErgoHistory with FullBlockSectionProcessor {
          override protected val settings: ErgoSettings = ergoSettings
          override protected[history] val historyStorage: HistoryStorage = storage
          override val powScheme = ergoSettings.chainSettings.powScheme
        }
      } else {
        new ErgoHistory with EmptyBlockSectionProcessor {
          override protected val settings: ErgoSettings = ergoSettings
          override protected[history] val historyStorage: HistoryStorage = storage
          override val powScheme = ergoSettings.chainSettings.powScheme
        }
      }

    Opened(ergoSettings, storage, history, detectRuntimeIdentity())
  }

  private def detectRuntimeIdentity(): RuntimeIdentity = {
    val sourcePath = Try {
      val cs = classOf[ErgoHistory].getProtectionDomain.getCodeSource
      if (cs == null || cs.getLocation == null) "unknown"
      else Paths.get(cs.getLocation.toURI).toAbsolutePath.normalize.toString
    }.getOrElse("unknown")

    val sourceSha = if (sourcePath != "unknown") {
      val p = Try(Paths.get(sourcePath)).toOption
      p.filter(path => Files.isRegularFile(path)).map(sha256File).getOrElse("")
    } else ""

    val versionHint = if (sourcePath != "unknown") {
      Try {
        val p = Paths.get(sourcePath)
        if (!Files.isRegularFile(p) || !sourcePath.toLowerCase.endsWith(".jar")) ""
        else {
          val jar = new JarFile(p.toFile)
          try {
            val attrs = Option(jar.getManifest).map(_.getMainAttributes)
            attrs.flatMap(a => Option(a.getValue("Implementation-Version")))
              .orElse(attrs.flatMap(a => Option(a.getValue("Specification-Version"))))
              .getOrElse("")
          } finally jar.close()
        }
      }.getOrElse("")
    } else ""

    RuntimeIdentity(ToolVersion, sourcePath, sourceSha, versionHint)
  }

  private def summary(o: Opened): Unit = {
    val h = o.history
    println("========================================")
    println(s"ERGO HISTORY DOCTOR $ToolVersion - SUMMARY")
    println("========================================")
    println(s"Node directory     = ${normalizedNodeDirectory(o.settings)}")
    println(s"Network            = ${o.settings.networkType.verboseName}")
    println(s"verifyTransactions = ${o.settings.nodeSettings.verifyTransactions}")
    println(s"stateType          = ${o.settings.nodeSettings.stateType}")
    println(s"blocksToKeep       = ${o.settings.nodeSettings.blocksToKeep}")
    println(s"headersHeight      = ${h.headersHeight}")
    println(s"fullBlockHeight    = ${h.fullBlockHeight}")
    println(s"gap                = ${math.max(0, h.headersHeight - h.fullBlockHeight)}")
    println(s"bestHeaderId       = ${h.bestHeaderIdOpt.getOrElse("-")}")
    println(s"bestFullBlockId    = ${h.bestFullBlockIdOpt.getOrElse("-")}")
    println(s"Ergo code source   = ${o.runtime.ergoCodeSource}")
    println(s"Ergo code SHA-256  = ${emptyDash(o.runtime.ergoCodeSha256)}")
    println(s"Ergo version hint  = ${emptyDash(o.runtime.ergoVersionHint)}")
    println()
    readOnlyFooter()
  }

  private def inspect(o: Opened, height: Int): Unit = {
    require(height >= 1 && height <= o.history.headersHeight,
      s"height=$height is outside 1..${o.history.headersHeight}")

    inspectHeight(o, height) match {
      case Some(state) => printHeightState(state)
      case None =>
        println(s"height=$height canonical header cannot be resolved from raw history")
        println(s"bestHeaderIdAtHeight=${o.history.bestHeaderIdAtHeight(height).getOrElse("-")}")
        o.history.bestHeaderIdAtHeight(height).foreach { id =>
          val raw = o.storage.get(id)
          println(s"rawPresent=${bool(raw.nonEmpty)} rawSha256=${raw.map(sha256).getOrElse("-")}")
          println(s"semanticValidity=${o.history.isSemanticallyValid(id)}")
        }
    }
    println()
    readOnlyFooter()
  }

  private def printHeightState(s: HeightState): Unit = {
    println(s"height=${s.height}")
    println(
      s"HEADER id=${s.headerId} validity=${s.headerValidity} raw=${bool(s.headerRawPresent)} " +
        s"rawParse=${bool(s.headerRawParseable)} parentCanonical=${bool(s.parentMatchesCanonical)}"
    )
    Seq(s.tx, s.ad, s.ext).foreach { x =>
      println(
        f"${x.section}%-3s id=${x.id} raw=${bool(x.rawPresent)} parse=${bool(x.rawParseable)} " +
          s"logical=${bool(x.logicalResolves)} validity=${x.validity} row=${emptyDash(x.validityRowHex)} class=${x.classification}"
      )
    }
  }

  private def scan(o: Opened, cli: Cli): Unit = {
    val h = o.history
    val from = cli.from.getOrElse(math.max(1, h.fullBlockHeight + 1))
    val to = cli.to.getOrElse(h.headersHeight)

    if (from > to && cli.from.isEmpty && cli.to.isEmpty) {
      println("========================================")
      println(s"ERGO HISTORY DOCTOR $ToolVersion - SCAN")
      println("========================================")
      println(s"headersHeight   = ${h.headersHeight}")
      println(s"fullBlockHeight = ${h.fullBlockHeight}")
      println("No header/full-block gap exists; default scan range is empty.")
      println()
      readOnlyFooter()
      return
    }

    requireRange(from, to, h.headersHeight)

    println("========================================")
    println(s"ERGO HISTORY DOCTOR $ToolVersion - SCAN")
    println("========================================")
    println(s"headersHeight   = ${h.headersHeight}")
    println(s"fullBlockHeight = ${h.fullBlockHeight}")
    println(s"scan range      = $from..$to")
    println()

    val counts = mutable.Map.empty[String, Long].withDefaultValue(0L)
    var runStart = from
    var previousSig: Option[String] = None
    var previousHeight = from - 1

    val tsv = cli.out.map { path =>
      ensureParentDirectory(path)
      new PrintWriter(path.toFile, StandardCharsets.UTF_8.name())
    }

    tsv.foreach(_.println(
      "height\theaderId\theaderValidity\theaderRaw\theaderParse\tparentCanonical\t" +
        "txClass\ttxValidity\ttxRaw\ttxParse\t" +
        "adClass\tadValidity\tadRaw\tadParse\t" +
        "extClass\textValidity\textRaw\textParse"
    ))

    def flushRun(end: Int): Unit = previousSig.foreach { sig =>
      if (runStart == end) println(s"$runStart  $sig")
      else println(s"$runStart..$end  $sig")
    }

    var height = from
    try {
      while (height <= to) {
        if ((height - from) > 0 && (height - from) % ProgressEvery == 0) {
          System.err.println(s"...scanned through height ${height - 1}")
        }

        inspectHeight(o, height) match {
          case None =>
            counts("HEADER_UNRESOLVED") += 1
            val sig = "HEADER_UNRESOLVED"
            if (previousSig.exists(_ != sig)) {
              flushRun(previousHeight)
              runStart = height
            }
            previousSig = Some(sig)
            previousHeight = height
            tsv.foreach(_.println(
              s"$height\t-\t-\t-\t-\t-\tHEADER_UNRESOLVED\t-\t-\t-\tHEADER_UNRESOLVED\t-\t-\t-\tHEADER_UNRESOLVED\t-\t-\t-"
            ))

          case Some(s) =>
            Seq(s.tx, s.ad, s.ext).foreach(x => counts(s"${x.section}:${x.classification}") += 1)
            counts(s"HEADER:${s.headerValidity}") += 1
            if (!s.parentMatchesCanonical) counts("HEADER:PARENT_MISMATCH") += 1

            val sig = s.signature
            if (previousSig.exists(_ != sig)) {
              flushRun(previousHeight)
              runStart = height
            }
            previousSig = Some(sig)
            previousHeight = height

            tsv.foreach { out =>
              out.println(Seq(
                s.height,
                s.headerId,
                s.headerValidity,
                bool(s.headerRawPresent),
                bool(s.headerRawParseable),
                bool(s.parentMatchesCanonical),
                s.tx.classification,
                s.tx.validity,
                bool(s.tx.rawPresent),
                bool(s.tx.rawParseable),
                s.ad.classification,
                s.ad.validity,
                bool(s.ad.rawPresent),
                bool(s.ad.rawParseable),
                s.ext.classification,
                s.ext.validity,
                bool(s.ext.rawPresent),
                bool(s.ext.rawParseable)
              ).mkString("\t"))
            }
        }
        height += 1
      }
      flushRun(previousHeight)
    } finally {
      tsv.foreach(_.close())
    }

    println()
    println("SUMMARY")
    counts.toSeq.sortBy(_._1).foreach { case (k, v) => println(f"$k%-36s $v%d") }
    cli.out.foreach(p => println(s"TSV report                           ${p.toAbsolutePath}"))
    println()
    readOnlyFooter()
  }

  /**
    * Walk the raw persisted best-header parent chain from tip toward best full block.
    * Unlike scan(), this does not require every height->header-id index to be intact.
    */
  private def walkGap(o: Opened, maxHeaders: Int): Unit = {
    require(maxHeaders > 0, s"--max must be > 0, got $maxHeaders")
    val h = o.history

    println("========================================")
    println(s"ERGO HISTORY DOCTOR $ToolVersion - WALK GAP")
    println("========================================")
    println(s"headersHeight   = ${h.headersHeight}")
    println(s"fullBlockHeight = ${h.fullBlockHeight}")
    println(s"max headers     = $maxHeaders")
    println()

    var currentId = h.bestHeaderIdOpt
    var walked = 0
    var problems = 0
    var done = false

    while (!done && currentId.nonEmpty && walked < maxHeaders) {
      val id = currentId.get
      readRawModifier(o.storage, id) match {
        case Some(header: Header) =>
          val canonicalAtHeight = h.bestHeaderIdAtHeight(header.height).contains(header.id)
          val validity = h.isSemanticallyValid(header.id).toString
          val parentCanonical =
            if (header.height <= 1) true
            else h.bestHeaderIdAtHeight(header.height - 1).contains(header.parentId)

          if (!canonicalAtHeight || !parentCanonical || validity == "Invalid") {
            problems += 1
            println(
              s"PROBLEM height=${header.height} id=${header.id} canonicalIndex=${bool(canonicalAtHeight)} " +
                s"parentCanonical=${bool(parentCanonical)} validity=$validity"
            )
          }

          walked += 1
          if (walked % ProgressEvery == 0) {
            System.err.println(s"...walked $walked headers; now at ${header.height}")
          }

          if (header.height <= h.fullBlockHeight) {
            if (header.height == h.fullBlockHeight && !h.bestFullBlockIdOpt.contains(header.id)) {
              problems += 1
              println(
                s"PROBLEM full-block boundary height=${header.height}: raw parent-chain header=${header.id} " +
                  s"bestFullBlockId=${h.bestFullBlockIdOpt.getOrElse("-")}"
              )
            }
            done = true
          } else {
            currentId = Some(header.parentId)
          }

        case Some(other) =>
          problems += 1
          println(s"BROKEN id=$id parsedAs=${other.getClass.getName}; expected Header")
          done = true

        case None =>
          problems += 1
          val raw = o.storage.get(id)
          println(
            s"BROKEN id=$id rawPresent=${bool(raw.nonEmpty)} rawSha256=${raw.map(sha256).getOrElse("-")} " +
              "raw parser could not produce a Header"
          )
          done = true
      }
    }

    println()
    println(s"walked   = $walked")
    println(s"problems = $problems")
    if (!done && walked >= maxHeaders) println("stopped because --max limit was reached")
    println()
    readOnlyFooter()
  }

  private def createPlan(o: Opened, cli: Cli): Unit = {
    val h = o.history
    val from = cli.from.getOrElse(math.max(1, h.fullBlockHeight + 1))
    val to = cli.to.getOrElse(h.headersHeight)
    val out = cli.out.getOrElse(fail("plan requires --out <file>"))

    requireRange(from, to, h.headersHeight)
    require(from > h.fullBlockHeight,
      s"Refusing to plan repairs at or below best full block height ${h.fullBlockHeight}")

    val allowedSections = cli.sections.map(_.toUpperCase)
    require(allowedSections.nonEmpty, "--sections must not be empty")
    require(allowedSections.subsetOf(SupportedSections),
      s"Unsupported sections: ${allowedSections.diff(SupportedSections).mkString(",")}")

    val rows = ArrayBuffer.empty[PlanRow]
    var previousCanonicalId: Option[ModifierId] =
      if (from > 1) h.bestHeaderIdAtHeight(from - 1) else None

    if (from == h.fullBlockHeight + 1 && h.fullBlockHeight > 0) {
      require(previousCanonicalId == h.bestFullBlockIdOpt,
        s"Refusing: best-header index at full-block boundary does not match bestFullBlockId at height ${h.fullBlockHeight}")
    }

    var height = from
    while (height <= to) {
      if ((height - from) > 0 && (height - from) % ProgressEvery == 0) {
        System.err.println(s"...planned through height ${height - 1}")
      }

      val bestId = h.bestHeaderIdAtHeight(height).getOrElse {
        fail(s"Refusing: no best-header index entry at height $height")
      }

      val header = readRawModifier(o.storage, bestId) match {
        case Some(x: Header) => x
        case Some(x) => fail(s"Refusing: best-header id $bestId at height $height parses as ${x.getClass.getName}")
        case None => fail(s"Refusing: canonical header $bestId at height $height is missing or corrupt")
      }

      require(header.id == bestId, s"Refusing: parsed header identity mismatch at height $height")
      require(header.height == height,
        s"Refusing: parsed header height mismatch: expected $height got ${header.height}")
      previousCanonicalId.foreach { previousId =>
        require(header.parentId == previousId,
          s"Refusing: canonical parent mismatch at height $height: parent=${header.parentId} expected=$previousId")
      }
      val headerValidity = h.isSemanticallyValid(header.id)
      require(headerValidity == ModifierSemanticValidity.Valid || headerValidity == ModifierSemanticValidity.Unknown,
        s"Refusing: canonical header ${header.id} at height $height has semantic validity $headerValidity; " +
          "automatic header repair is unsupported")

      sectionTuples(header)
        .filter { case (name, _) => allowedSections.contains(name) }
        .foreach { case (name, id) =>
          val s = inspectSection(o, name, id)
          repairCandidateAction(s).foreach { action =>
            rows += PlanRow(
              height = height,
              section = name,
              headerId = header.id,
              sectionId = id,
              preValidity = s.validity,
              preRaw = s.rawPresent,
              preRawParse = s.rawParseable,
              preRawSha256 = s.rawSha256,
              preValidityRowHex = s.validityRowHex,
              action = action
            )
          }
        }

      previousCanonicalId = Some(header.id)
      height += 1
    }

    val duplicateIds = rows.groupBy(_.sectionId).collect {
      case (id, rs) if rs.map(_.height).distinct.size > 1 => id -> rs.map(_.height).distinct.sorted
    }
    require(duplicateIds.isEmpty,
      "Refusing automatic plan: at least one repair-target section ID is referenced at multiple heights: " +
        duplicateIds.take(5).map { case (id, hs) => s"$id@${hs.mkString(",")}" }.mkString("; "))

    val meta = PlanMeta(
      toolVersion = ToolVersion,
      generatedAt = Instant.now().toString,
      network = o.settings.networkType.verboseName,
      nodeDirectory = normalizedNodeDirectory(o.settings),
      ergoCodeSource = o.runtime.ergoCodeSource,
      ergoCodeSha256 = o.runtime.ergoCodeSha256,
      ergoVersionHint = o.runtime.ergoVersionHint,
      headersHeight = h.headersHeight,
      fullBlockHeight = h.fullBlockHeight,
      bestHeaderId = h.bestHeaderIdOpt.getOrElse("-"),
      bestFullBlockId = h.bestFullBlockIdOpt.getOrElse("-"),
      from = from,
      to = to,
      sections = allowedSections.toSeq.sorted.mkString(",")
    )

    val plan = Plan(meta, rows.toVector.sortBy(r => (r.height, r.section)))
    writePlan(out, plan)
    val hash = sha256(Files.readAllBytes(out))

    println("========================================")
    println(s"ERGO HISTORY DOCTOR $ToolVersion - PLAN")
    println("========================================")
    println(s"range              = $from..$to")
    println(s"sections           = ${meta.sections}")
    println(s"actions            = ${plan.rows.size}")
    println(s"plan               = ${out.toAbsolutePath}")
    println(s"SHA-256            = $hash")
    println()
    println("NO HISTORY RECORDS WERE CHANGED.")
    println("Review the plan file before applying it.")
    println("To apply this exact plan, with the node still stopped, run repair with:")
    println(s"  --plan ${out.toAbsolutePath} --confirm $hash")
    println("STATE DATABASE WAS NOT OPENED.")
  }

  /**
    * Auto-repair only states that are mechanically safe to convert to clean Absent:
    *   1) section marked Invalid (with or without a raw object);
    *   2) raw section object exists but native Ergo serializer cannot parse it;
    *   3) validity says Valid but the referenced raw object does not exist.
    *
    * We intentionally do NOT auto-repair a healthy-looking complete-but-unapplied block.
    */
  private def repairCandidateAction(s: SectionState): Option[String] = {
    if (s.validity == "Invalid") Some("CLEAR_INVALID_AND_DROP_RAW")
    else if (s.rawPresent && !s.rawParseable) Some("DROP_CORRUPT_RAW_AND_CLEAR_VALIDITY")
    else if (s.validity == "Valid" && !s.rawPresent) Some("CLEAR_ORPHAN_VALIDITY")
    else None
  }

  private def repair(o: Opened, cli: Cli): Unit = {
    val planPath = cli.plan.getOrElse(fail("repair requires --plan <file>"))
    val confirm = cli.confirm.getOrElse(fail("repair requires --confirm <sha256>"))
    require(cli.batchHeights > 0, s"--batch-heights must be > 0, got ${cli.batchHeights}")

    val bytes = Files.readAllBytes(planPath)
    val actualHash = sha256(bytes)
    require(actualHash.equalsIgnoreCase(confirm),
      s"Plan hash mismatch. confirmation=$confirm actual=$actualHash")

    val plan = readPlan(planPath)
    verifyPlanIdentity(o, plan)

    println("========================================")
    println(s"ERGO HISTORY DOCTOR $ToolVersion - REPAIR")
    println("========================================")
    println(s"plan          = ${planPath.toAbsolutePath}")
    println(s"SHA-256       = $actualHash")
    println(s"actions       = ${plan.rows.size}")
    println(s"batch heights = ${cli.batchHeights}")
    println()

    if (plan.rows.isEmpty) {
      println("Plan contains no repair actions. Nothing to do.")
      println("STATE DATABASE WAS NOT OPENED.")
      return
    }

    plan.rows.foreach(row => verifyRowPrecondition(o, row))
    println("PRECHECK PASSED")

    val grouped = plan.rows.groupBy(_.height).toSeq.sortBy(_._1)
    grouped.grouped(cli.batchHeights).foreach { heightGroup =>
      val batchRows = heightGroup.flatMap(_._2).toVector
      val minH = heightGroup.head._1
      val maxH = heightGroup.last._1

      val validityKeys = ArrayBuffer.empty[ByteArrayWrapper]
      val objectIds = ArrayBuffer.empty[ModifierId]

      batchRows.foreach { row =>
        verifyRowContext(o, row)
        val id = modifierId(row.sectionId)
        if (o.storage.getIndex(o.history.validityKey(id)).nonEmpty) {
          validityKeys += o.history.validityKey(id)
        }
        if (o.storage.get(id).nonEmpty) {
          objectIds += id
        }
      }

      if (validityKeys.nonEmpty || objectIds.nonEmpty) {
        o.storage.remove(validityKeys.distinct.toArray, objectIds.distinct.toArray) match {
          case Failure(e) =>
            fail(s"HistoryStorage.remove failed for heights $minH..$maxH: ${Option(e.getMessage).getOrElse(e.toString)}")
          case Success(_) => ()
        }
      }

      // v6.0.6 can discard an inner index-store removal failure inside HistoryStorage.remove.
      // Never trust the returned Success alone: read all affected rows back immediately.
      batchRows.foreach(row => verifyRowClean(o, row))
      println(
        s"CLEANED $minH..$maxH actions=${batchRows.size} " +
          s"validityKeys=${validityKeys.distinct.size} rawObjects=${objectIds.distinct.size}"
      )
    }

    plan.rows.foreach(row => verifyRowClean(o, row))
    println()
    println("IN-PROCESS POSTFLIGHT PASSED")
    println("Closing and reopening history for persisted verification...")

    // Reopen LevelDB before declaring success. This verifies persisted state rather than only
    // the current process/cache view. Opened.close() is idempotent so outer cleanup remains safe.
    o.close()
    val reopened = open(cli.config, cli.network)
    try {
      verifyPlanIdentity(reopened, plan)
      plan.rows.foreach { row =>
        verifyRowContext(reopened, row)
        verifyRowClean(reopened, row)
      }
    } finally reopened.close()

    println("PERSISTED POSTFLIGHT PASSED")
    println("All planned section records are cleanly Absent after database reopen.")
    println("No headers were removed or marked Valid.")
    println("STATE DATABASE WAS NOT OPENED.")
  }

  private def verify(o: Opened, cli: Cli): Unit = {
    val planPath = cli.plan.getOrElse(fail("verify requires --plan <file>"))
    val plan = readPlan(planPath)
    verifyPlanIdentity(o, plan)

    var clean = 0
    var original = 0
    var partial = 0
    var bad = 0

    plan.rows.foreach { row =>
      verifyRowContext(o, row)
      rowState(o, row) match {
        case "CLEAN" => clean += 1
        case "ORIGINAL" => original += 1
        case "PARTIAL" => partial += 1
        case _ => bad += 1
      }
    }

    println("========================================")
    println(s"ERGO HISTORY DOCTOR $ToolVersion - VERIFY")
    println("========================================")
    println(s"actions       = ${plan.rows.size}")
    println(s"clean         = $clean")
    println(s"original      = $original")
    println(s"partial       = $partial")
    println(s"unexpected    = $bad")
    println()
    if (bad > 0) fail("Verification found unexpected states; no writes were performed")
    println("VERIFY PASSED")
    readOnlyFooter()
  }

  private def inspectHeight(o: Opened, height: Int): Option[HeightState] = {
    o.history.bestHeaderIdAtHeight(height).flatMap { id =>
      val raw = o.storage.get(id)
      raw.flatMap(bytes => parseRaw(bytes).toOption) match {
        case Some(header: Header) if header.height == height && header.id == id =>
          val parentMatches =
            if (height <= 1) true
            else o.history.bestHeaderIdAtHeight(height - 1).contains(header.parentId)

          val sections = sectionTuples(header)
            .map { case (name, sid) => name -> inspectSection(o, name, sid) }
            .toMap

          Some(HeightState(
            height = height,
            headerId = header.id,
            headerValidity = o.history.isSemanticallyValid(header.id).toString,
            headerRawPresent = raw.nonEmpty,
            headerRawParseable = true,
            parentMatchesCanonical = parentMatches,
            tx = sections("TX"),
            ad = sections("AD"),
            ext = sections("EXT")
          ))

        case _ => None
      }
    }
  }

  private def sectionTuples(h: Header): Vector[(String, ModifierId)] = Vector(
    "TX" -> h.transactionsId,
    "AD" -> h.ADProofsId,
    "EXT" -> h.extensionId
  )

  private def sectionIdFor(header: Header, section: String): ModifierId = section match {
    case "TX" => header.transactionsId
    case "AD" => header.ADProofsId
    case "EXT" => header.extensionId
    case other => fail(s"Unsupported section in plan: $other")
  }

  private def inspectSection(o: Opened, name: String, id: ModifierId): SectionState = {
    val raw = o.storage.get(id)
    val parsed = raw.flatMap(bytes => parseRaw(bytes).toOption)
    val rawParseable = parsed.exists(matchesExpected(name, _))
    val logical = o.history.modifierById(id)
    val validity = o.history.isSemanticallyValid(id).toString
    val validityRow = o.storage.getIndex(o.history.validityKey(id)).map(hex).getOrElse("")
    val classification = classify(validity, validityRow, raw.nonEmpty, rawParseable, logical.nonEmpty)

    SectionState(
      section = name,
      id = id,
      rawPresent = raw.nonEmpty,
      rawSha256 = raw.map(sha256).getOrElse(""),
      rawParseable = rawParseable,
      parsedClass = parsed.map(_.getClass.getSimpleName).getOrElse(""),
      logicalResolves = logical.nonEmpty,
      logicalClass = logical.map(_.getClass.getSimpleName).getOrElse(""),
      validity = validity,
      validityRowHex = validityRow,
      classification = classification
    )
  }

  private def matchesExpected(name: String, x: BlockSection): Boolean = name match {
    case "TX" => x.isInstanceOf[BlockTransactions]
    case "AD" => x.isInstanceOf[ADProofs]
    case "EXT" => x.isInstanceOf[Extension]
    case "HEADER" => x.isInstanceOf[Header]
    case _ => false
  }

  private def classify(
      validity: String,
      validityRowHex: String,
      raw: Boolean,
      parse: Boolean,
      logical: Boolean
  ): String = {
    validity match {
      case "Invalid" if raw && parse => "INVALID_RAW_PARSEABLE"
      case "Invalid" if raw && !parse => "INVALID_RAW_CORRUPT"
      case "Invalid" => "INVALID_NO_RAW"

      case "Valid" if raw && parse && logical => "PRESENT_VALID"
      case "Valid" if raw && !parse => "VALID_BUT_CORRUPT_RAW"
      case "Valid" if !raw => "VALID_BUT_MISSING_RAW"

      case "Unknown" if raw && parse && logical => "PRESENT_UNKNOWN"
      case "Unknown" if raw && !parse => "CORRUPT_RAW_UNKNOWN"
      case "Unknown" if raw && parse && !logical => "RAW_PARSEABLE_UNRESOLVED"

      case "Absent" if validityRowHex.nonEmpty => "MALFORMED_VALIDITY_ROW"
      case "Absent" if !raw && !logical => "CLEAN_MISSING"
      case "Absent" if raw && parse => "RAW_PRESENT_BUT_ABSENT"
      case "Absent" if raw && !parse => "CORRUPT_RAW_ABSENT"

      case other => s"INCONSISTENT_${other}_raw=${raw}_parse=${parse}_logical=${logical}"
    }
  }

  private def readRawModifier(storage: HistoryStorage, id: ModifierId): Option[BlockSection] =
    storage.get(id).flatMap(bytes => parseRaw(bytes).toOption)

  private def parseRaw(bytes: Array[Byte]): Try[BlockSection] =
    HistoryModifierSerializer.parseBytesTry(bytes)

  private def writePlan(path: Path, plan: Plan): Unit = {
    ensureParentDirectory(path)
    val out = new PrintWriter(path.toFile, StandardCharsets.UTF_8.name())
    try {
      out.println(s"# $PlanMagic")
      out.println(s"# toolVersion=${escapeMeta(plan.meta.toolVersion)}")
      out.println(s"# generatedAt=${escapeMeta(plan.meta.generatedAt)}")
      out.println(s"# network=${escapeMeta(plan.meta.network)}")
      out.println(s"# nodeDirectory=${escapeMeta(plan.meta.nodeDirectory)}")
      out.println(s"# ergoCodeSource=${escapeMeta(plan.meta.ergoCodeSource)}")
      out.println(s"# ergoCodeSha256=${escapeMeta(plan.meta.ergoCodeSha256)}")
      out.println(s"# ergoVersionHint=${escapeMeta(plan.meta.ergoVersionHint)}")
      out.println(s"# headersHeight=${plan.meta.headersHeight}")
      out.println(s"# fullBlockHeight=${plan.meta.fullBlockHeight}")
      out.println(s"# bestHeaderId=${plan.meta.bestHeaderId}")
      out.println(s"# bestFullBlockId=${plan.meta.bestFullBlockId}")
      out.println(s"# from=${plan.meta.from}")
      out.println(s"# to=${plan.meta.to}")
      out.println(s"# sections=${plan.meta.sections}")
      out.println(
        "height\tsection\theaderId\tsectionId\tpreValidity\tpreRaw\tpreRawParse\t" +
          "preRawSha256\tpreValidityRowHex\taction"
      )
      plan.rows.foreach { r =>
        out.println(Seq(
          r.height,
          r.section,
          r.headerId,
          r.sectionId,
          r.preValidity,
          r.preRaw,
          r.preRawParse,
          r.preRawSha256,
          r.preValidityRowHex,
          r.action
        ).mkString("\t"))
      }
    } finally out.close()
  }

  private def readPlan(path: Path): Plan = {
    import scala.collection.JavaConverters._

    val all = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector
    require(all.headOption.contains(s"# $PlanMagic"), s"Not an $PlanMagic file: $path")

    val metaMap = all.iterator
      .filter(_.startsWith("# "))
      .drop(1)
      .flatMap { line =>
        val x = line.drop(2)
        val i = x.indexOf('=')
        if (i > 0) Some(x.substring(0, i) -> unescapeMeta(x.substring(i + 1))) else None
      }
      .toMap

    def m(k: String): String = metaMap.getOrElse(k, fail(s"Plan missing metadata: $k"))

    val meta = PlanMeta(
      toolVersion = m("toolVersion"),
      generatedAt = m("generatedAt"),
      network = m("network"),
      nodeDirectory = m("nodeDirectory"),
      ergoCodeSource = m("ergoCodeSource"),
      ergoCodeSha256 = m("ergoCodeSha256"),
      ergoVersionHint = m("ergoVersionHint"),
      headersHeight = m("headersHeight").toInt,
      fullBlockHeight = m("fullBlockHeight").toInt,
      bestHeaderId = m("bestHeaderId"),
      bestFullBlockId = m("bestFullBlockId"),
      from = m("from").toInt,
      to = m("to").toInt,
      sections = m("sections")
    )

    val headerIndex = all.indexWhere(_.startsWith("height\tsection\theaderId"))
    require(headerIndex >= 0, "Plan is missing TSV header")

    val rows = all.drop(headerIndex + 1).filter(_.nonEmpty).map { line =>
      val f = line.split("\t", -1)
      require(f.length == 10, s"Malformed plan row: $line")
      PlanRow(
        height = f(0).toInt,
        section = f(1),
        headerId = f(2),
        sectionId = f(3),
        preValidity = f(4),
        preRaw = f(5).toBoolean,
        preRawParse = f(6).toBoolean,
        preRawSha256 = f(7),
        preValidityRowHex = f(8),
        action = f(9)
      )
    }

    Plan(meta, rows)
  }

  private def verifyPlanIdentity(o: Opened, plan: Plan): Unit = {
    val h = o.history
    require(plan.meta.toolVersion == ToolVersion,
      s"Plan was generated by tool ${plan.meta.toolVersion}; this binary is $ToolVersion")
    require(o.settings.networkType.verboseName == plan.meta.network,
      s"Plan network ${plan.meta.network} does not match node network ${o.settings.networkType.verboseName}")
    require(pathsEquivalent(normalizedNodeDirectory(o.settings), plan.meta.nodeDirectory),
      s"Plan node directory ${plan.meta.nodeDirectory} does not match ${normalizedNodeDirectory(o.settings)}")

    if (plan.meta.ergoCodeSha256.nonEmpty) {
      require(o.runtime.ergoCodeSha256.nonEmpty,
        "Plan records an Ergo JAR SHA-256 but the current Ergo code source could not be hashed")
      require(o.runtime.ergoCodeSha256.equalsIgnoreCase(plan.meta.ergoCodeSha256),
        s"Ergo code/JAR changed since planning: plan=${plan.meta.ergoCodeSha256} current=${o.runtime.ergoCodeSha256}")
    }

    require(h.headersHeight == plan.meta.headersHeight,
      s"headersHeight changed: plan=${plan.meta.headersHeight} current=${h.headersHeight}")
    require(h.fullBlockHeight == plan.meta.fullBlockHeight,
      s"fullBlockHeight changed: plan=${plan.meta.fullBlockHeight} current=${h.fullBlockHeight}")
    require(h.bestHeaderIdOpt.getOrElse("-") == plan.meta.bestHeaderId,
      "bestHeaderId changed since the plan was created")
    require(h.bestFullBlockIdOpt.getOrElse("-") == plan.meta.bestFullBlockId,
      "bestFullBlockId changed since the plan was created")
    require(plan.meta.from > h.fullBlockHeight,
      s"Refusing plan touching height ${plan.meta.from} at/below fullBlockHeight ${h.fullBlockHeight}")
    require(plan.meta.to <= h.headersHeight,
      s"Refusing plan whose to=${plan.meta.to} exceeds current headersHeight=${h.headersHeight}")
  }

  private def verifyRowContext(o: Opened, row: PlanRow): Unit = {
    require(SupportedSections.contains(row.section), s"Unsupported plan section ${row.section}")
    require(row.height > o.history.fullBlockHeight,
      s"Refusing ${row.height}/${row.section}: height is now at/below fullBlockHeight")

    val best = o.history.bestHeaderIdAtHeight(row.height)
      .getOrElse(fail(s"Height ${row.height} lost best-header index after plan creation"))
    require(best == row.headerId,
      s"Canonical header changed at height ${row.height}: plan=${row.headerId} current=$best")

    val header = readRawModifier(o.storage, best) match {
      case Some(h: Header) => h
      case Some(other) => fail(s"Canonical header ${row.headerId} now parses as ${other.getClass.getName}")
      case None => fail(s"Canonical header ${row.headerId} is now missing/corrupt")
    }
    require(header.height == row.height, s"Canonical header height changed for ${row.headerId}")
    val headerValidity = o.history.isSemanticallyValid(header.id)
    require(headerValidity == ModifierSemanticValidity.Valid || headerValidity == ModifierSemanticValidity.Unknown,
      s"Canonical header ${row.headerId} now has semantic validity $headerValidity")
    require(sectionIdFor(header, row.section) == row.sectionId,
      s"${row.section} id changed at height ${row.height}: plan=${row.sectionId} current=${sectionIdFor(header, row.section)}")
  }

  private def verifyRowPrecondition(o: Opened, row: PlanRow): Unit = {
    verifyRowContext(o, row)
    rowState(o, row) match {
      case "ORIGINAL" | "PARTIAL" | "CLEAN" => ()
      case other => fail(s"Refusing row ${row.height}/${row.section}/${row.sectionId}: unexpected state $other")
    }
  }

  /**
    * Resume model:
    *   ORIGINAL = exact bytes/validity row from the plan still exist;
    *   PARTIAL  = one of those original components has already been deleted, nothing changed/replaced;
    *   CLEAN    = raw object and validity row are absent and semantic validity is Absent;
    *   UNEXPECTED = anything was added or changed; repair must stop.
    */
  private def rowState(o: Opened, row: PlanRow): String = {
    val id = modifierId(row.sectionId)
    val raw = o.storage.get(id)
    val validityRow = o.storage.getIndex(o.history.validityKey(id))
    val rawHash = raw.map(sha256).getOrElse("")
    val validityHex = validityRow.map(hex).getOrElse("")

    val rawOriginalOrGone =
      if (row.preRaw) raw.isEmpty || rawHash == row.preRawSha256
      else raw.isEmpty

    val validityOriginalOrGone =
      if (row.preValidityRowHex.nonEmpty) validityRow.isEmpty || validityHex == row.preValidityRowHex
      else validityRow.isEmpty

    if (!rawOriginalOrGone || !validityOriginalOrGone) {
      "UNEXPECTED"
    } else if (raw.isEmpty && validityRow.isEmpty &&
      o.history.isSemanticallyValid(id) == ModifierSemanticValidity.Absent) {
      "CLEAN"
    } else {
      val rawExactlyOriginal =
        raw.nonEmpty == row.preRaw && (!row.preRaw || rawHash == row.preRawSha256)
      val validityExactlyOriginal = validityHex == row.preValidityRowHex
      val semanticExactlyOriginal = o.history.isSemanticallyValid(id).toString == row.preValidity

      if (rawExactlyOriginal && validityExactlyOriginal && semanticExactlyOriginal) "ORIGINAL"
      else "PARTIAL"
    }
  }

  private def verifyRowClean(o: Opened, row: PlanRow): Unit = {
    val id = modifierId(row.sectionId)
    val raw = o.storage.get(id)
    val validityRow = o.storage.getIndex(o.history.validityKey(id))
    val semantic = o.history.isSemanticallyValid(id)

    require(raw.isEmpty,
      s"Postflight failed: raw object still exists for ${row.height}/${row.section}/${row.sectionId}")
    require(validityRow.isEmpty,
      s"Postflight failed: validity row still exists for ${row.height}/${row.section}/${row.sectionId}")
    require(semantic == ModifierSemanticValidity.Absent,
      s"Postflight failed: semantic validity is $semantic for ${row.height}/${row.section}/${row.sectionId}")
  }

  private def parseCli(args: Array[String]): Cli = {
    if (args.isEmpty || args.head == "help" || args.head == "--help" || args.head == "-h") {
      return Cli("help", Paths.get("."), "mainnet", None, None, None, None, None, None, None,
        DefaultBatchHeights, SupportedSections)
    }

    val command = args.head.toLowerCase
    val opts = mutable.Map.empty[String, String]
    var i = 1

    while (i < args.length) {
      val a = args(i)
      require(a.startsWith("--"), s"Unexpected argument: $a")
      require(i + 1 < args.length, s"Missing value for $a")
      opts(a) = args(i + 1)
      i += 2
    }

    val known = Set(
      "--config", "--network", "--from", "--to", "--height", "--max", "--out",
      "--plan", "--confirm", "--batch-heights", "--sections"
    )
    val unknown = opts.keySet.diff(known)
    require(unknown.isEmpty, s"Unknown option(s): ${unknown.toSeq.sorted.mkString(", ")}")

    def intOpt(k: String): Option[Int] = opts.get(k).map { value =>
      Try(value.toInt).getOrElse(fail(s"$k must be an integer, got '$value'"))
    }

    val config = Paths.get(opts.getOrElse("--config", fail("--config <ergo.conf> is required")))
    val sections = opts.get("--sections")
      .map(_.split(",").map(_.trim.toUpperCase).filter(_.nonEmpty).toSet)
      .getOrElse(SupportedSections)

    Cli(
      command = command,
      config = config,
      network = opts.getOrElse("--network", "mainnet"),
      from = intOpt("--from"),
      to = intOpt("--to"),
      height = intOpt("--height"),
      max = intOpt("--max"),
      out = opts.get("--out").map(Paths.get(_)),
      plan = opts.get("--plan").map(Paths.get(_)),
      confirm = opts.get("--confirm"),
      batchHeights = intOpt("--batch-heights").getOrElse(DefaultBatchHeights),
      sections = sections
    )
  }

  private def usage(): Unit = {
    println(
      s"""Ergo History Doctor $ToolVersion
         |
         |The Ergo node MUST be stopped before using this tool.
         |
         |Commands:
         |  summary   --config ergo.conf [--network mainnet]
         |  inspect   --config ergo.conf --height N
         |  scan      --config ergo.conf [--from N --to N] [--out report.tsv]
         |  walk-gap  --config ergo.conf [--max N]
         |  plan      --config ergo.conf [--from N --to N] --out repair-plan.tsv [--sections TX,AD,EXT]
         |  verify    --config ergo.conf --plan repair-plan.tsv
         |  repair    --config ergo.conf --plan repair-plan.tsv --confirm SHA256 [--batch-heights 500]
         |
         |Defaults:
         |  scan/plan range: fullBlockHeight + 1 through headersHeight
         |  plan sections:   TX,AD,EXT
         |
         |Automatic repair scope in v0.1:
         |  - section is semantically Invalid, OR
         |  - raw section exists but Ergo's native serializer cannot parse it, OR
         |  - semantic validity is Valid but the raw section is missing.
         |
         |Safety boundaries:
         |  - state DB is never opened
         |  - headers are never automatically removed or rewritten
         |  - modifiers are never marked Valid
         |  - healthy-looking complete-but-unapplied blocks are diagnose-only
         |  - repair only removes selected block-section raw objects and/or validity keys
         |  - repair requires a SHA-256-confirmed plan and performs persisted postverification
         |""".stripMargin
    )
  }

  private def requireRange(from: Int, to: Int, headersHeight: Int): Unit = {
    require(from >= 1, s"from must be >= 1, got $from")
    require(to >= from, s"to must be >= from, got $from..$to")
    require(to <= headersHeight, s"to=$to exceeds headersHeight=$headersHeight")
  }

  private def normalizedNodeDirectory(settings: ErgoSettings): String =
    Try(Paths.get(settings.directory).toAbsolutePath.normalize.toString).getOrElse(settings.directory)

  private def pathsEquivalent(a: String, b: String): Boolean = {
    val na = Try(Paths.get(a).toAbsolutePath.normalize.toString).getOrElse(a)
    val nb = Try(Paths.get(b).toAbsolutePath.normalize.toString).getOrElse(b)
    if (File.separatorChar == '\\') na.equalsIgnoreCase(nb) else na == nb
  }

  private def modifierId(encoded: String): ModifierId = ModifierId @@ encoded

  private def ensureParentDirectory(path: Path): Unit =
    Option(path.toAbsolutePath.getParent).foreach(p => Files.createDirectories(p))

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private def sha256File(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = new BufferedInputStream(new FileInputStream(path.toFile))
    val buffer = new Array[Byte](1024 * 1024)
    try {
      var n = in.read(buffer)
      while (n >= 0) {
        if (n > 0) digest.update(buffer, 0, n)
        n = in.read(buffer)
      }
    } finally in.close()
    digest.digest().map(b => f"${b & 0xff}%02x").mkString
  }

  private def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  private def bool(v: Boolean): String = if (v) "Y" else "-"
  private def emptyDash(s: String): String = if (s.isEmpty) "-" else s

  private def escapeMeta(s: String): String =
    s.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")

  private def unescapeMeta(s: String): String =
    s.replace("%0D", "\r").replace("%0A", "\n").replace("%25", "%")

  private def readOnlyFooter(): Unit = {
    println("NO HISTORY RECORDS WERE CHANGED.")
    println("STATE DATABASE WAS NOT OPENED.")
  }

  private def fail(msg: String): Nothing = throw new IllegalArgumentException(msg)
}

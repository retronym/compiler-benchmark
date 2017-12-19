package scala.tools.nsc

import java.io.PrintWriter
import java.nio.file.{Files, Path, Paths}
import java.util.DoubleSummaryStatistics

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class PhaseId(name: String)

class RunData(val target: String) {
  val results = mutable.LinkedHashMap[PhaseId, mutable.LinkedHashMap[String, (DoubleSummaryStatistics, String)]]()
  def log(phaseId: PhaseId, name: String, value: Double, unit: String): Unit = {
    val map = results.getOrElseUpdate(phaseId, new mutable.LinkedHashMap())
    map.get(name) match {
      case Some((oldvalue, _)) => oldvalue.accept(value)
      case None =>
        val stats = new DoubleSummaryStatistics
        stats.accept(value)
        map.update(name, (stats, unit))

    }
  }
}

object ProfileParser {
  def main(args: Array[String]): Unit = {
    val input = Paths.get(args.apply(0))
    val parser = new ProfileParser(false)
    val outFile = input.resolveSibling(input.getFileName + ".csv")
    parser.processFiles(List(input), outFile)
    println("Wrote: " + outFile.toAbsolutePath.toString)
  }
}

class ProfileParser(collapse: Boolean) {
  private val runs = new mutable.LinkedHashMap[String, RunData]()
  private val DataPattern = """Profiler compile (\d+)\s+after phase\s+(\d)+:\s*(\w+)\s*(single|total)\s*--(.*)""".r
  private val StartPattern = """Profiler start \((\d+)\) (.*)""".r
  private val MeasurementPattern = """(.*?):?\s+([-\d\.]+)(ms|MB).*""".r
  private val YStatsBanner = """\*\*\* Cumulative statistics at phase (\S+)""".r
  private val YStatsSimpleValue = """#(.*?)\s+:\s+(\d+)""".r
  private val YStatsTimeValue = """(.*?)\s+:\s+\d+ spans, (\d+)ms""".r
  private val YStatsTimePartialValue = """(.*?)\s+:\s+\d+ spans, (\d+)ms \(([\d\.]+)%\)""".r

  private var lastRun: String = _
  private var inStats = false
  private var statsPhaseName: String = ""

  def processFiles(inputs: List[Path], outFile: Path): Unit = {
    for (input <- inputs) {
      val lines = Files.lines(input)
      println(input)
      try {
        lines.iterator().asScala.foreach(process)
      } finally {
        lines.close()
      }
    }

    val writer = new PrintWriter(Files.newBufferedWriter(outFile))

    try {
      writeCsv(writer)
    } finally {
      writer.close()
    }
  }

  private def runFor(runString: String): String = if (collapse) "<all>" else runString
  private def process(i: String) = {
    i match {
      case StartPattern(run, target) =>
        runs.put(runFor(run), new RunData(target))
        inStats = false
      case DataPattern(run, phaseIdString, phaseName, style, data) =>
        lastRun = runFor(run)
        runs.get(lastRun) match {
          case Some(runData) =>
            data.split(',').foreach {
              x =>
                val MeasurementPattern(name, value, unit) = x.trim
                if (style != "total") {
                  val phaseId = PhaseId(phaseName)
                  runData.log(phaseId, name, value.toDouble, unit)
                }
            }
          case _ =>
            println(i)
        }
        inStats = false
      case YStatsBanner(phaseName) =>
        inStats = true
        statsPhaseName = phaseName
      case YStatsSimpleValue(name, value) =>
        if (inStats) {
          runs.get(lastRun) match {
            case Some(runData) =>
              if (!collapse || !name.endsWith("%"))
                runData.log(PhaseId(statsPhaseName), name, value.toDouble, "")
            case None =>
          }
        }
      case YStatsTimePartialValue(name, value, pct) =>
        if (inStats) {
          runs.get(lastRun) match {
            case Some(runData) =>
              runData.log(PhaseId(statsPhaseName), name, value.toDouble, "")
              if (!collapse)
                runData.log(PhaseId(statsPhaseName), name + " %", pct.toDouble, "")
            case None =>
          }
        }
      case YStatsTimeValue(name, value) =>
        if (inStats) {
          runs.get(lastRun) match {
            case Some(runData) =>
              runData.log(PhaseId(statsPhaseName), name, value.toDouble, "")
            case None =>
          }
        }
      case _ =>
    }
  }

  def writeCsv(writer: PrintWriter) = {
    val headings = mutable.LinkedHashSet[String]()
    for (r <- runs; result <- r._2.results; key <- result._2.keys) headings += key
    writer.print("Run, Target, Phase, ")
    writer.println(headings.mkString("", ", ", ", "))
    for ((run, data) <- runs) {
      val runFields = List(run, data.target)
      for ((phase, phaseResults) <- data.results) {
        val phaseFields = List(phase.name)
        val dataFields = headings.toList.map(key => phaseResults.get(key).map(_._1.getAverage).getOrElse(0d))
        writer.println((runFields ::: phaseFields ::: dataFields).mkString(", "))
      }
    }
  }
}

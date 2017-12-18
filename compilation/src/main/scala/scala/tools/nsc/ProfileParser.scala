package scala.tools.nsc

import java.io.PrintWriter
import java.nio.file.{Files, Paths}

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.collection.mutable

class ProfileParser {
  //  Profiler compile 219 after phase  9:optimus_remove_reporter     single                   -- wallClockTime:       2.4109ms, idleTime:       0.0000ms, cpuTime       2.0810ms, userTime      10.0000ms, allocatedBytes       0.0430MB, retainedHeapBytes       0.0000MB, gcTime      0ms


}

case class PhaseId(id: Int, name: String)

class RunData(val target: String) {
  val results = mutable.LinkedHashMap[PhaseId, mutable.LinkedHashMap[String, (Double, String)]]()
  def log(phaseId: PhaseId, name: String, value: Double, unit: String): Unit = {
    results.getOrElseUpdate(phaseId, new mutable.LinkedHashMap[String, (Double, String)]()).update(name, (value, unit))
  }
}

object ProfileParser {
  private val runs = new mutable.LinkedHashMap[String, RunData]()
  private val DataPattern = """Profiler compile (\d+)\s+after phase\s+(\d)+:\s*(\w+)\s*(single|total)\s*--(.*)""".r
  private val StartPattern = """Profiler start \((\d+)\) (.*)""".r
  private val MeasurementPattern = """(.*?):?\s+([-\d\.]+)(ms|MB).*""".r

  def process(i: String) = {
    i match {
      case StartPattern(run, target) =>
        runs.put(run.toString, new RunData(target))
      case DataPattern(run, phaseId, phaseName, style, data) =>
        runs.get(run.toString) match {
          case Some(runData) =>
            data.split(',').map {
              x =>
                val MeasurementPattern(name, value, unit) = x.trim
                if (style == "total")
                  runData.log(PhaseId(-1, "total"), name, value.toDouble, unit)
                else
                  runData.log(PhaseId(phaseId.toInt, phaseName), name, value.toDouble, unit)
            }
          case _ =>
            println(i)
        }
      case _ =>
    }
  }

  def writeCsv(writer: PrintWriter) = {
    val headings = runs.head._2.results.head._2.keys.toList
    writer.print("Run, Target, Phase Id, Phase, ")
    writer.println(headings.mkString("", ", ", ", "))
    for ((run, data) <- runs) {
      val runFields = List(run, data.target)
      for ((phase, phaseResults) <- data.results) {
        val phaseFields = List(phase.id, phase.name)
        val dataFields = headings.map(key => phaseResults(key)._1)
        writer.println((runFields ::: phaseFields ::: dataFields).mkString(", "))
      }
    }
  }

  def main(args: Array[String]): Unit = {
    val input = Paths.get(args.apply(0))

    val lines = Files.lines(input)
    try {
      lines.iterator().asScala.foreach(process)
    } finally {
      lines.close()
    }

    val outFile = input.resolveSibling(input.getFileName + ".csv")
    val writer = new PrintWriter(Files.newBufferedWriter(outFile))

    try {
      writeCsv(writer)
    } finally {
      writer.close()
    }
    println("Wrote: " + outFile.toAbsolutePath.toString)
  }
}

package scala.tools.nsc

import java.io.{BufferedOutputStream, File, PrintStream}
import java.nio.file.{Files, Path, Paths}
import java.util.Collections

import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.profile.{ExternalProfiler, InternalProfiler}
import org.openjdk.jmh.results.{BenchmarkResult, IterationResult, Result}
import org.openjdk.jmh.runner.IterationType
import org.openjdk.jmh.util.FileUtils

object YProfiler {
  val EnableProfilerPropName = "scala.bench.yprofile"
}

class YProfiler extends InternalProfiler {
  private var measurementIterationCount = 0
  private var outputFile: Path = _
  private var outStream: PrintStream = _

  override def beforeIteration(benchmarkParams: BenchmarkParams, iterationParams: IterationParams): Unit = {
    if (outStream == null) {
      outputFile = FileUtils.tempFile("compilerout").toPath
      outStream = new PrintStream(new BufferedOutputStream(Files.newOutputStream(outputFile)))
      Console.setOut(outStream)
      Console.setErr(outStream)
    }

    if (iterationParams.getType == IterationType.MEASUREMENT) {
      measurementIterationCount += 1
      if (measurementIterationCount == 1) {
        System.setProperty(YProfiler.EnableProfilerPropName, "true")
      }
    }
  }
  override def afterIteration(benchmarkParams: BenchmarkParams, iterationParams: IterationParams, result: IterationResult): java.util.Collection[_ <: Result[_]] = {
    if (measurementIterationCount == iterationParams.getCount) {
      System.setProperty(YProfiler.EnableProfilerPropName, "false")
      outStream.close()
      val outFile = Paths.get("target/profile.csv")
      new ProfileParser(collapse = true).processFiles(List(outputFile), outFile)
      println("Wrote: " + outFile.toAbsolutePath.toString)
      // TODO expose results to JMH
      val constructor = Class.forName("pl.project13.scala.jmh.extras.profiler.NoResult").getDeclaredConstructor(classOf[String], classOf[String])
      constructor.setAccessible(true)
      val result = constructor.newInstance("-Yprofile", "Wrote: " + outFile.toAbsolutePath.toString).asInstanceOf[Result[_]]
      java.util.Arrays.asList(result)
    } else Collections.emptyList()
  }
  override def getDescription: String = "Enable -Yprofile and -Ystatistics during measurement phase and aggregate results"
}

package scala.tools.nsc

import java.io.{File, IOException}
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.stream.Collectors

import org.openjdk.jmh.annotations
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.annotations.Mode._
import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.profile.StackProfiler
import org.openjdk.jmh.runner.IterationType
import org.openjdk.jmh.runner.options.TimeValue

@State(Scope.Benchmark)
class ScalacBenchmark {
  @Param(value = Array[String]())
  var source: String = _
  @Param(value = Array(""))
  var extraArgs: String = _
  var driver: Driver = _

  def compileImpl(): Unit = {
    val compilerArgs =
      if (source.startsWith("@")) Array(source)
      else {
        import scala.collection.JavaConverters._
        val allFiles = Files.walk(findSourceDir).collect(Collectors.toList[Path]).asScala.toList
        allFiles.filter(_.getFileName.toString.endsWith(".scala")).map(_.toAbsolutePath.toString).toArray
      }

    // MainClass is copy-pasted from compiler for source compatibility with 2.10.x - 2.13.x
    class MainClass extends Driver with EvalLoop {
      def resident(compiler: Global): Unit = loop { line =>
        val command = new CompilerCommand(line split "\\s+" toList, new Settings(scalacError))
        compiler.reporter.reset()
        new compiler.Run() compile command.files
      }

      override def newCompiler(): Global = Global(settings, reporter)

      override protected def processSettingsHook(): Boolean = {
        settings.usejavacp.value = true
        settings.outdir.value = tempDir.getAbsolutePath
        settings.nowarn.value = true
        if (extraArgs != null && extraArgs != "")
          settings.processArgumentString(extraArgs)
        true
      }
    }
    val driver = new MainClass

    driver.process(compilerArgs)
    assert(!driver.reporter.hasErrors)
  }

  private var tempDir: File = null

  @Setup(Level.Trial) def initTemp(): Unit = {
    val tempFile = java.io.File.createTempFile("output", "")
    tempFile.delete()
    tempFile.mkdir()
    tempDir = tempFile
  }
  @TearDown(Level.Trial) def clearTemp(): Unit = {
    val directory = tempDir.toPath
    Files.walkFileTree(directory, new SimpleFileVisitor1[Path]() {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.delete(file)
        FileVisitResult.CONTINUE
      }
      override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
        Files.delete(dir)
        FileVisitResult.CONTINUE
      }
    })
  }

  private def findSourceDir: Path = {
    val path = Paths.get("../corpus/" + source)
    if (Files.exists(path)) path
    else Paths.get(source)
  }
}

// JMH-independent entry point to run the code in the benchmark, for debugging or
// using external profilers.
object ScalacBenchmarkStandalone {
  def main(args: Array[String]): Unit = {
    val bench = new ScalacBenchmark
    bench.source = args(0)
    val iterations = args(1).toInt
    bench.initTemp()
    var i = 0
    while (i < iterations) {
      bench.compileImpl()
      i += 1
    }
    bench.clearTemp()
  }
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
// TODO -Xbatch reduces fork-to-fork variance, but incurs 5s -> 30s slowdown
@Fork(value = 16, jvmArgs = Array("-XX:CICompilerCount=2", "-Xms2G", "-Xmx2G"))
class ColdScalacBenchmark extends ScalacBenchmark {
  @Benchmark
  def compile(): Unit = compileImpl()
}

@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1, time = 30, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class WarmScalacBenchmark extends ScalacBenchmark {
  @Benchmark
  def compile(): Unit = compileImpl()
}

@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class HotScalacBenchmark extends ScalacBenchmark {
  @Benchmark
  def compile(): Unit = compileImpl()
}


@State(Scope.Benchmark)
@BenchmarkMode(Array(SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 50)
@Measurement(iterations = 50)
@Fork(value = 3, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class PhasedScalacBenchmark {
  @Param(value = Array[String]())
  var source: String = _
  @Param(value = Array(""))
  var extraArgs: String = _
  var driver: Driver = _

  @Param(value = Array[String]())
  var profileDuring: String = _

  private var tempDir: File = null
  private val pool = java.util.concurrent.Executors.newFixedThreadPool(1)
  var latch1: CountDownLatch = _
  var latch2: CountDownLatch = _
  var latch3: CountDownLatch = _
  var latch4: CountDownLatch = _
  var profileStart: Phase = _
  var profileStop: Phase = _

  @Setup(Level.Invocation) def startCompilation(): Unit = {
    val compilerArgs =
      if (source.startsWith("@")) Array(source)
      else {
        import scala.collection.JavaConverters._
        val allFiles = Files.walk(findSourceDir).collect(Collectors.toList[Path]).asScala.toList
        allFiles.filter(_.getFileName.toString.endsWith(".scala")).map(_.toAbsolutePath.toString).toArray
      }

    // MainClass is copy-pasted from compiler for source compatibility with 2.10.x - 2.13.x
    class MainClass extends Driver with EvalLoop {
      override def newCompiler(): Global = new Global(settings, reporter)

      override protected def processSettingsHook(): Boolean = {
        settings.usejavacp.value = true
        settings.outdir.value = tempDir.getAbsolutePath
        settings.nowarn.value = true
        if (extraArgs != null && extraArgs != "")
          settings.processArgumentString(extraArgs)
        val compiler = newCompiler()
        val run = new compiler.Run() {
          override def advancePhase(): Unit = {

            if (compiler.globalPhase == profileStart) {
              startProfiling()
            }
            else if (compiler.globalPhase == profileStop.next) {
              stopProfiling()
            }
          }
        }
        val Range =  """(\w+)-(\w+)""".r
        profileDuring match {
          case Range(a, b) =>
            profileStart = run.phaseNamed(a)
            profileStop = run.phaseNamed(b)
          case a =>
            profileStart = run.phaseNamed(a)
            profileStop = run.phaseNamed(a)
        }
        run compile command.files
        reporter.printSummary()

        false
      }
    }
    val driver = new MainClass
    latch1 = new java.util.concurrent.CountDownLatch(1)
    latch2 = new java.util.concurrent.CountDownLatch(1)
    latch3 = new java.util.concurrent.CountDownLatch(1)
    latch4 = new java.util.concurrent.CountDownLatch(1)
    pool.submit(new Runnable { def run() {
      driver.process(compilerArgs)
      latch4.countDown()
    }})
    latch1.await()
  }

  private def startProfiling(): Unit = {
    latch1.countDown()
    latch2.await()
  }
  private def stopProfiling(): Unit = {
    latch3.countDown()

  }
  @Benchmark def compilePhase(): Unit = {
    latch2.countDown()
    latch3.await()
  }
  @TearDown(Level.Iteration) def awaitCompilationEnd(): Unit = {
    latch4.await()
  }

  @Setup(Level.Trial) def initTemp(): Unit = {
    val tempFile = java.io.File.createTempFile("output", "")
    tempFile.delete()
    tempFile.mkdir()
    tempDir = tempFile
  }
  @TearDown(Level.Trial) def clearTemp(): Unit = {
    val directory = tempDir.toPath
    Files.walkFileTree(directory, new SimpleFileVisitor1[Path]() {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.delete(file)
        FileVisitResult.CONTINUE
      }
      override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
        Files.delete(dir)
        FileVisitResult.CONTINUE
      }
    })

    pool.shutdown()
    pool.awaitTermination(1L, TimeUnit.SECONDS)
  }

  private def findSourceDir: Path = {
    val path = Paths.get("../corpus/" + source)
    val path2 = Paths.get("corpus/" + source)
    if (Files.exists(path)) path
    else if (Files.exists(path2)) path2
    else Paths.get(source)
  }

}
object PhasedScalacBenchmark {
  def main(args: Array[String]): Unit = {
    org.openjdk.jmh.Main.main(Array("PhasedScalacBenchmark", "-prof", "stack", "-f0", "-wi", "2", "-i", "2", "-psource=scalap", "-pprofileDuring=uncurry-erasure"));
    val profiler = new StackProfiler("")
    val dummyParams = new BenchmarkParams("bench", "", false, 0, Array(), java.util.Arrays.asList(), 0, 0, null, null, annotations.Mode.SingleShotTime, null, TimeUnit.DAYS, 0, "", null, null)
    val dummyIterationParams = new IterationParams(IterationType.MEASUREMENT, 0, TimeValue.NONE, 0)
    profiler.beforeIteration(dummyParams, dummyIterationParams)

  }
}
package scala.tools.nsc

import java.io.{BufferedInputStream, File}
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

import org.openjdk.jmh.annotations.Mode.SampleTime
import org.openjdk.jmh.annotations._

import scala.collection.JavaConverters.enumerationAsScalaIteratorConverter

@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 4)
@State(Scope.Thread)
class ZipFileBenchmark {

  @Benchmark
  def testSingleOpenShut(): Unit = {
    val zf = new ZipFile(new File(Predef.getClass.getProtectionDomain.getCodeSource.getLocation.toURI))
    for (e <- zf.entries().asScala) {
      val stream = new BufferedInputStream(zf.getInputStream(e))
      try {
        while(stream.read() != -1) {
        }
      } finally {
        stream.close()
      }
    }
    zf.close()
  }

  @Benchmark
  def testOpenShutEachEntry(): Unit = {
    def openZipFile = new ZipFile(new File(Predef.getClass.getProtectionDomain.getCodeSource.getLocation.toURI))
    val zf0 = openZipFile
    val entries = zf0.entries().asScala.map(_.getName).toArray
    zf0.close()
    for (e <- entries) {
      val zf = openZipFile
      try {
        val stream = new BufferedInputStream(zf.getInputStream(zf.getEntry(e)))
        try {
          while (stream.read() != -1) {
          }
        } finally {
          stream.close()
        }
      } finally {
        zf.close()
      }
    }
  }
}

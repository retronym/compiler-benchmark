package scala.collection

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.Mode._
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.collection.mutable.Builder

@BenchmarkMode(Array(AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2)
@State(Scope.Thread)
class GroupByBenchmark {
  @Param(value = Array[String]())
  var _scalaVersion: String = _

  var is: Traversable[AnyRef] = _
  var f: AnyRef => AnyRef = _

  @Setup def setup(): Unit = {
    is = (1 to 100).map(_.toString)
    f = _ => "const"
  }

  @Benchmark def inlined1: Any = {
    val m = mutable.Map.empty[AnyRef, Builder[AnyRef, Traversable[AnyRef]]]
    is.foreach { elem =>
      val key = f(elem)
      val bldr: mutable.Builder[AnyRef, Traversable[AnyRef]] = m.get(key) match {
        case Some(v) => v
        case None => val b = is.companion.newBuilder[AnyRef]; m(key) = b; b
      }
      bldr += elem
    }
    val b = immutable.Map.newBuilder[AnyRef, Traversable[AnyRef]]
    for ((k, v) <- m)
      b += ((k, v.result))

    b.result
  }

  @Benchmark def inlined2: Any = {
    val m = mutable.Map.empty[AnyRef, Builder[AnyRef, Traversable[AnyRef]]]
    is.foreach { elem =>
      val key = f(elem)
      val bldr = m.getOrElseUpdate(key, is.companion.newBuilder[AnyRef])
      bldr += elem
    }
    val b = immutable.Map.newBuilder[AnyRef, Traversable[AnyRef]]
    for ((k, v) <- m)
      b += ((k, v.result))

    b.result
  }

  @Benchmark def measure(): Any = {
    is.groupBy(f)
  }

  def groupBy1(t: Traversable[AnyRef], blackhole: Blackhole)(f: AnyRef => AnyRef): Any = {
    val m = mutable.HashMap.empty[AnyRef, Builder[AnyRef, Traversable[AnyRef]]]
    t.foreach { elem =>
      val key = f(elem)
      blackhole.consume(m.get(key))
      val bldr: mutable.Builder[AnyRef, Traversable[AnyRef]] = m.get(key) match {
        case Some(v) => v
        case None => t.companion.newBuilder
      }
      blackhole.consume(bldr)
      bldr += elem
    }
    m
  }
}

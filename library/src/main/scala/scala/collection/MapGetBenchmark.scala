package scala.collection

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.Mode._
import org.openjdk.jmh.annotations._

@BenchmarkMode(Array(AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@State(Scope.Thread)
class MapGetBenchmark {
  @Param(value = Array[String]())
  var _scalaVersion: String = _

  var map: scala.collection.mutable.HashMap[AnyRef, AnyRef] = _
  var map2: HashMap2[AnyRef, AnyRef] = _
  var map3: HashMap3[AnyRef, AnyRef] = _
  var mapAnyRef: mutable.AnyRefMap[AnyRef, AnyRef] = _
  var key = Int.box(8)

  @Setup def setup(): Unit = {
    map = mutable.HashMap()
    map2 = new HashMap2()
    map3 = new HashMap3()
    mapAnyRef = mutable.AnyRefMap()
    (0 to 256).foreach { i =>
      map(Int.box(i % 16)) = Int.box(i)
      map2(Int.box(i % 16)) = Int.box(i)
      map3(Int.box(i % 16)) = Int.box(i)
      mapAnyRef(Int.box(i % 16)) = Int.box(i)
    }
  }
  @Benchmark def mapGet: Any = {
    map.get(key)
  }

  @Benchmark def map2Get: Any = {
    map2.get2(key)
  }

  @Benchmark def map3Get: Any = {
    map3.get2(key)
  }

  @Benchmark def mapAnyRefGet: Any = {
    mapAnyRef.get(key)
  }
}


final class HashMap2[A, B] extends mutable.HashMap[A, B] {
  def get2(a: A): Any = {
    val e = findEntry0(a, index0(elemHashCode(a)))
    if (e eq null) None
    else Some(e.value)
  }

  // Note:
  // we take the most significant bits of the hashcode, not the lower ones
  // this is of crucial importance when populating the table in parallel
  protected final def index0(hcode: Int) = {
    val ones = table.length - 1
    val improved = improve0(hcode, seedvalue)
    val shifted = (improved >> (32 - java.lang.Integer.bitCount(ones))) & ones
    shifted
  }

  override protected def elemHashCode(key: A) = key.##

  def improve0(hcode: Int, seed: Int) = {
    val i= scala.util.hashing.byteswap32(hcode)
    val rotation = seed % 32
    val rotated = (i >>> rotation) | (i << (32 - rotation))
    rotated
  }


  private[this] def findEntry0(key: A, h: Int): Entry = {
    var e = table(h).asInstanceOf[Entry]
    while (e != null && !elemEquals(e.key, key)) e = e.next
    e
  }
}

class HashMap3[A, B] extends BaseHashMap3[A, B]

trait BaseHashMap3[A, B] extends mutable.HashMap[A, B] {
  def get2(a: A): Any = {
    val e = findEntry0(a, index0(elemHashCode(a)))
    if (e eq null) None
    else Some(e.value)
  }

  protected final def index0(hcode: Int) = {
    val ones = table.length - 1
    val improved = improve0(hcode, seedvalue)
    val shifted = (improved >> (32 - java.lang.Integer.bitCount(ones))) & ones
    shifted
  }

  override protected def elemHashCode(key: A) = key.##

  def improve0(hcode: Int, seed: Int) = {
    val i= scala.util.hashing.byteswap32(hcode)
    val rotation = seed % 32
    val rotated = (i >>> rotation) | (i << (32 - rotation))
    rotated
  }


  private[this] def findEntry0(key: A, h: Int): Entry = {
    var e = table(h).asInstanceOf[Entry]
    while (e != null && !elemEquals(e.key, key)) e = e.next
    e
  }
}

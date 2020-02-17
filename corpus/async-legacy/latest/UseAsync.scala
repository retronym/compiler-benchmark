import scala.concurrent._, duration.Duration, ExecutionContext.Implicits.global
import scala.async.Async.{async, await}

object Test {
  def test: Future[Int] = async {
    val x: Option[Either[Object, (String, String)]] = Some(Right(("a", "b")))
    x match {
      case Some(Left(_)) => 1
      case Some(Right(("a", "c"))) => 2
      case Some(Right(("a", "e"))) => 3
      case Some(Right(("a", x))) if "ab".isEmpty => 4
      case Some(Right(("a", "b"))) => await(f(5))
      case Some(Right((y, x))) if x == y => 6
      case Some(Right((_, _))) => await(f(7))
      case None => 8
    }
  }
  def f(x: Int): Future[Int] = Future.successful(x)
}

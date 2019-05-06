import java.util.concurrent.TimeUnit

import org.scalatest.{Matchers, WordSpecLike}
import scalaz.zio._
import scalaz.zio.duration.Duration
import scalaz.zio.random.Random

import scala.util.control.NonFatal


class SchedulingTest extends WordSpecLike with DefaultRuntime with Matchers {

  class StubService(val failUntil: Int) {
    var current = 0

    def request(): String = {
      current += 1
      if (current >= failUntil) {
        s"Succeed at $current attempt"
      } else {
        throw new Exception(s"failed at $current attempt")
      }
    }
  }

  "Schedule.never" should {
    "never execute the effect" in {
      unsafeRun(UIO(println("Hello")).repeat(Schedule.never))
    }
    "never try a failure attempt" in {
      val service = new StubService(2)
      unsafeRun(
        IO(service.request()).retry(Schedule.never) >>= (s => UIO(println(s)))
      )
    }
  }

  "Schedule.forever" should {
    "repeat the successful effect forever" in {
      unsafeRun(
        UIO(println("Hello")).repeat(Schedule.forever)
          .race(UIO("done").delay(Duration(1, TimeUnit.SECONDS)))
      )
    }
    "retry the effect until succeed" in {
      val service = new StubService(2)
      unsafeRun(
        IO(service.request()).retry(Schedule.forever) >>= (s => UIO(println(s)))
      )
    }
    "repeat does not repeat failed effect" in {
      val service = new StubService(2)
      assertThrows[FiberFailure] {
        unsafeRun(
          IO(service.request()).repeat(Schedule.forever) >>= (s => UIO(println(s)))
        )
      }
    }
  }

  "Schedule.identity" should {
    "repeat the successful effect forever and return each result" in {
      unsafeRun(
        UIO("Hello")
          .repeat(Schedule.identity <* Schedule.recurs(2)) >>= (s => UIO(println(s)))
      )
    }
  }


  "Schedule.once" should {
    "repeat the effect once" in {
      unsafeRun(
        UIO(println("Hello")).repeat(Schedule.once) >>= (s => UIO(println(s)))
      )
      // prints out "Hello" twice:
      // Hello
      // Hello
    }
    "retry the effect once" in {
      val service = new StubService(2)
      unsafeRun(
        IO(service.request()).retry(Schedule.once) >>= (s => UIO(println(s)))
        // prints out: Succeeded at 2 attempt
      )
    }
    "retry the effect once but failed with exception" in {
      val service = new StubService(3)
      assertThrows[FiberFailure] {
        unsafeRun(
          IO(service.request()).retry(Schedule.once) >>= (s => UIO(println(s)))
        )
      }
    }
  }

  "Schedule recurse" should {
    "repeat the effect for specified numbers" in {
      unsafeRun(
        UIO(println("Hello")).repeat(Schedule.recurs(2))
      )
      // prints out "Hello" three times:
      // Hello
      // Hello
      // Hello
    }
    "retry the effect for specified numbers" in {
      val service = new StubService(2)
      unsafeRun(
        IO(service.request()).retry(Schedule.recurs(2)) >>= (s => UIO(println(s)))
        // prints out: Succeeded at 2 attempt
      )
    }
  }

  "Schedule doWhile" should {
    "repeat an effect while a predicate is satisfied" in {
      var i = 0
      unsafeRun(
        UIO {
          i += 1; i
        }.repeat(Schedule.doWhile(_ < 3)) >>= (n => UIO(n))
      ) shouldBe 3
    }
    "retry an effect while a predicate is satisfied" in {
      val service = new StubService(2)
      unsafeRun(IO(service.request()).retry(Schedule.doWhile { (e: Throwable) =>
        e match {
          case NonFatal(_) => true
        }
      }).either) shouldBe a[Right[_, _]]
    }
  }

  "Schedule doUntil" should {
    // doUntil is equivalent to doWhile negated
    "repeat an effect until a predicate is satisfied" in {
      var i = 0
      unsafeRun(
        UIO {
          i += 1; i
        }.repeat(Schedule.doUntil(_ >= 3)) >>= (n => UIO(n))
      ) shouldBe 3
    }
    "retry an effect while a predicate is satisfied" in {
      val service = new StubService(2)
      unsafeRun(IO(service.request()).retry(Schedule.doUntil { (e: Throwable) =>
        e match {
          case NonFatal(_) => false
        }
      }).either) shouldBe a[Right[_, _]]
    }
  }

  "Schedule spaced" should {
    "repeat an effect " in {
      unsafeRun(
        UIO(println("Hello"))
          .repeat(Schedule.spaced(Duration(100, TimeUnit.MILLISECONDS)))
          .race(UIO(()).delay(Duration(1, TimeUnit.SECONDS)))
      )
    }
  }

  "compare spaced/linear/fibonacci/exponential" in {
    val duration = Duration(100, TimeUnit.MILLISECONDS)
    unsafeRun(
      UIO(())
        .repeat(
          (Schedule.spaced(duration)
            || Schedule.linear(duration)
            || Schedule.fibonacci(duration)
            || Schedule.exponential(duration)).logOutput(p => UIO(
            println(s"${p._1._1._1}, ${p._1._1._2.toMillis},  ${p._1._2.toMillis}, ${p._2.toMillis}")
          ))
        )
    )
  }

  "unfold" in {
    unsafeRun(
      UIO(()).repeat(Schedule.unfold(0)(_ + 1))
    )
  }

  "Intersection" should {
    "take the max of duration" in {
      unsafeRun(
        // prints "Hello" 4 times every 100 millisecond.
        UIO(println("Hello"))
          .repeat(Schedule.spaced(Duration(100, TimeUnit.MILLISECONDS)) && Schedule.recurs(3))
      )
    }
  }

  "Union" should {
    "execute computation when either one of schedules triggers" in {
      unsafeRun(
        // prints "Hello" every two days or every week
        UIO(println("Hello"))
          .repeat(Schedule.spaced(Duration(2, TimeUnit.DAYS)) || Schedule.spaced(Duration(7, TimeUnit.DAYS)))
      )
    }
  }

  "andThen" should {
    "complete the first schedule and then execute the second" in {
      unsafeRun(
        UIO(println("Hello"))
          .repeat(
            (Schedule.exponential(Duration(100, TimeUnit.MILLISECONDS)) &&
              Schedule.recurs(4)) andThen
              (Schedule.spaced(Duration(2, TimeUnit.SECONDS)) &&
                Schedule.recurs(2))
          )
      )
    }
  }

  "Ethernet protocol" should {
    "avoid collision by exponential backoff" in {
      val base = Duration(51200, TimeUnit.NANOSECONDS)
      val backOffPolicy = Schedule.exponential(base).jittered

      unsafeRun(
        UIO(println("Hello"))
          .repeat(backOffPolicy)
      )
    }
  }

  "logInput" should {
    "log every input" in {
      val service = new StubService(100)

      unsafeRun(
        IO(service.request())
          .retry(Schedule.recurs(4)
            .logInput((e: Throwable) => UIO(println(e))))
      )
    }
  }
}

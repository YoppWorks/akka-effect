package aio.runtime

import aio._
import org.specs2.mutable.Specification

object SyncRuntimeSpec extends Specification {

  "AIO (SyncRuntime)" should {

    "have stack safety (without finalizers)" in {
      val zero = AIO.pure(0).map(identity)
      val sum = (1 to 5000).foldLeft(zero) { case (acc, _) =>
        acc.map(_ + 1)
      }

      sum.runSync must_=== Outcome.Success(5000)
    }

    "have stack safety (with finalizers)" in {
      val zero = AIO.pure(0).map(identity)
      val sum = (1 to 5000).foldLeft(zero) { case (acc, _) =>
        acc.map(_ + 1).finalize(_ => AIO.unit)
      }

      sum.runSync must_=== Outcome.Success(5000)
    }

    "handle simple exceptions within the runtime" in {
      val divByZero = AIO.pure(42).map(_ / 0)
      divByZero.runSync match {
        case Outcome.Failure(_: ArithmeticException) => ok
        case _ => ko
      }
    }

    // TODO: Add __many__ more test cases!
  }

}

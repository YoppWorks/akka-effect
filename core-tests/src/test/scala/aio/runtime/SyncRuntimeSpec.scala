package aio.runtime

import aio._
import aio.internal.Null
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

    "evaluate a complex AIO chain" in {
      val v1 = AIO.pure("hello")
      val v2 = v1.map(_ + " world")
      val v3 = v2.flatMap(str => AIO.eval(str.reverse))
      val v4 = v3.transform(_.length)(_ => 0)
      val v5 = v4.transformWith(num => AIO(num.toString))(AIO.error)

      val text = "hello world"
      v5.runSync must_=== Outcome.Success(text.length.toString)
    }

    "be able to recover from errors using error handlers" in {
      def recurse(v: Float, n: Int): AIO[Float, Effect.Sync] =
        AIO.suspend {
          if (n > 10) AIO.error(new StackOverflowError)
          else AIO.eval(v).flatMap(f => recurse(f / 2.0f, n + 1))
        }

      val recover = recurse(18.0f, 0).recover(_ => 0.0f)
      val recoverWith = recurse(36.0f, 0).recoverWith(_ => AIO.eval(0.0f))

      recover.runSync must_=== Outcome.Success(0.0f)
      recoverWith.runSync must_=== Outcome.Success(0.0f)

      val multiLayer =
        AIO(36.0 / 3.0).flatMap(_ => AIO.error(new ArithmeticException))
          .recoverWith(_ => AIO.eval(12.0 / 2))
          .map(d => d / 0)
          .recover(_ => 1.0)
          .flatMap(_ => throw new RuntimeException)
          .recover(_ => 1.0)

      multiLayer.runSync must_=== Outcome.Success(1.0)
      multiLayer.unsafeRunSync must_== 1.0
    }

    "always run finalizers (no-finalizer errors)" in {
      import java.util.concurrent.atomic.AtomicBoolean
      val toggle1 = new AtomicBoolean(false)
      val toggle2 = new AtomicBoolean(false)
      val error = new ArithmeticException()
      def badDivide(d: Double): Double = { val _temp = d; throw error }

      val expr = AIO(1.0).map(badDivide).finalize(_ => AIO(toggle1.set(true))).finalize(_ => AIO(toggle2.set(true)))
      expr.runSync must_=== Outcome.Failure(error)
      toggle1.get() must_== true
      toggle2.get() must_== true
    }

    "always run finalizers (finalizer errors)" in {
      import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
      val toggle1 = new AtomicBoolean(false)
      val toggle2 = new AtomicBoolean(false)
      val toggle3 = new AtomicBoolean(false)
      val captureError = new AtomicReference[Throwable](Null[Throwable])
      val error = new ArithmeticException()
      def badDivide(d: Double): Double = { val _temp = d; throw error }
      def badFinalizer[A](toggle: AtomicBoolean)(outcome: Outcome[A]): AIO[Unit, Effect.Sync] = {
        toggle.set(true)
        outcome match {
          case Outcome.Success(_) => AIO.unit
          case Outcome.Failure(e) => throw e
          case Outcome.Interrupted    => throw evaluationInterruptedError
        }
      }

      val expr =
        AIO.finallyBlock(
          AIO(1.0)
            .onError(e => AIO(captureError.set(e)))
            .map(_ * 1.0)
            .finalize(badFinalizer(toggle1))
        )
        .map(badDivide)
        .finalize(badFinalizer(toggle2))
        .recoverWith(e => AIO.error(e))
        .finalize(badFinalizer(toggle3))

      expr.attempt.unsafeRunSync must_=== Outcome.Failure(error)
      toggle1.get() must_== true
      toggle2.get() must_== true
      toggle3.get() must_== true
      captureError.get() must beNull
    }
  }

}

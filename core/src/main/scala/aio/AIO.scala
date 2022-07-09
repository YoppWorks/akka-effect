package aio

import aio.runtime.AkkaRuntime

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal
import java.util.concurrent.CompletableFuture

sealed abstract class AIO[+A, E <: Effect] private(private[aio] val id: Int) extends Serializable {
  import AIO._
  import Effect._

  @inline private final def evalGo[B](go: GreaterOf[_ <: Effect, _ <: Effect])(aio: AIO[B, _]): AIO[B, go.Result] =
    aio.asInstanceOf[AIO[B, go.Result]]

  final def ap[B, E2 <: Effect](af: AIO[A => B, E2])(implicit go: GreaterOf[E, E2]): AIO[B, go.Result] =
    Transform[A, B, go.Result](this,
      a => af.map(f => f(a))(go.asInstanceOf[GreaterOf[E2, Sync]]),
      identityError
    )

  final def as[B](b: => B)(implicit go: GreaterOf[E, Sync]): AIO[B, go.Result] = map(_ => b)(go)

  final def asUnit(implicit go: GreaterOf[E, Sync]): AIO[Unit, go.Result] = map(_ => ())(go)

  final def finalize[E2 <: Effect](fin: Outcome[A] => AIO[Unit, E2])(implicit go: GreaterOf[E, E2]): AIO[A, go.Result] =
    Finalize(this, fin)

  final def flatMap[B, E2 <: Effect](f: A => AIO[B, E2])(implicit go: GreaterOf[E, E2]): AIO[B, go.Result] =
    Transform[A, B, go.Result](this, f, identityError)

  final def map[B](f: A => B)(implicit go: GreaterOf[E, Sync]): AIO[B, go.Result] =
    Transform[A, B, go.Result](this, a => Value(f(a)), identityError)

  final def onAborted[E2 <: Effect](onAbort: AIO[Unit, E2])(implicit go: GreaterOf[E, E2]): AIO[A, go.Result] =
    Finalize[A, go.Result](this,
      {
        case Outcome.Failure(_)   => AIO.unit
        case Outcome.Success(_)   => AIO.unit
        case Outcome.Interrupted  => evalGo(go)(onAbort)
      }
    )

  final def onError[E2 <: Effect](onErrK: Throwable => AIO[Unit, E2])(implicit go: GreaterOf[E, E2]): AIO[A, go.Result] =
    Finalize[A, go.Result](this,
      {
        case Outcome.Failure(err) => try evalGo(go)(onErrK(err)) catch { case NonFatal(err2) => Error(err2) }
        case Outcome.Success(_)   => AIO.unit
        case Outcome.Interrupted  => AIO.unit
      }
    )

  final def recover[B >: A](f: Throwable => B)(implicit go: GreaterOf[E, Sync]): AIO[B, go.Result] =
    Transform[A, B, go.Result](this, identityValue, err => Value(f(err)), hasErrHandler = true)

  final def recoverWith[B >: A, E2 <: Effect](f: Throwable => AIO[B, E2])(implicit go: GreaterOf[E, E2]): AIO[B, go.Result] =
    Transform[A, B, go.Result](this, identityValue, err => f(err), hasErrHandler = true)

  final def transform[B](onSucc: A => B)(onErr: Throwable => B)(implicit go: GreaterOf[E, Sync]): AIO[B, go.Result] =
    Transform[A, B, go.Result](this,
      a => Value(onSucc(a)),
      err => Value(onErr(err)),
      hasErrHandler = true
    )

  final def transformWith[B, E2 <: Effect, E3 <: Effect](
    onSuccK: A => AIO[B, E2])(
    onErrK: Throwable => AIO[B, E3])(
    implicit go: GreatestOf[E, E2, E3]
  ): AIO[B, go.Result] =
    Transform[A, B, go.Result](this,
      a => onSuccK(a).asInstanceOf[AIO[B, go.Result]],
      err => onErrK(err).asInstanceOf[AIO[B, go.Result]],
      hasErrHandler = true
    )

}

object AIO {
  import Effect._
  import runtime.Errors

  private final val _never = async[Nothing, Async]((_, _) => ())
  private final val _unit: AIO[Unit, Pure] = Value(())

  final val unit: AIO[Unit, Pure] = _unit
  final val cancel: AIO[Nothing, Async] = CancelEval

  final def never: AIO[Nothing, Async] = _never
  final def yieldEval: AIO[Unit, Async] = YieldEval

  final def apply[A](expr: => A): AIO[A, Sync] = Eval(() => expr)

  final def asyncCancellation[A, E <: Effect](
    cont: (Outcome[A] => Unit, Cancellable) => AIO[Unit, E])(implicit
    go: GreaterOf[E, Async]
  ): AIO[A, go.Result] = AsyncCont[A, go.Result](cont)

  final def async[A, E <: Effect](
    cont: (Outcome[A] => Unit, Cancellable) => Unit)(implicit
    go: GreaterOf[E, Async]
  ): AIO[A, go.Result] = AsyncCont[A, go.Result](
    (cb, cancellable) => {
      cont(cb, cancellable)
      AIO.unit
    }
  )

  final def finallyBlock[A, E <: Effect](aio: AIO[A, E]): AIO[A, E] = Block[A, E](aio)

  final def error(error: Throwable): AIO[Nothing, Pure] = Error(error)

  final def eval[A](expr: => A): AIO[A, Sync] = Eval(() => expr)

  final def executionContext: AIO[ExecutionContext, Async] = CurrentContext

  final def pure[A](value: A): AIO[A, Pure] = Value(value)

  final def suspend[A, E <: Effect](expr: => AIO[A, E]): AIO[A, E] = Suspend(() => expr)

  final def using[R, A, E1 <: Effect, E2 <: Effect, E3 <: Effect](
    acquire: AIO[R, E1])(
    use: R => AIO[A, E2])(
    release: (R, Outcome[A]) => AIO[Unit, E3])(implicit
    go: GreatestOf[E1, E2, E3],
    go2: GreaterOf[E2, E3]
  ): AIO[A, go.Result] =
    Block[A, go.Result] {
      acquire.flatMap(resource => use(resource).finalize(oc => release(resource, oc)))(GreaterOf.make)
    }

  final def fromEither[A](either: Either[Throwable, A]): AIO[A, Pure] =
    either match {
      case Right(value) => Value(value)
      case Left(error)  => Error(error)
    }

  final def fromFuture[A, E <: Effect](
    futureAio: AIO[Future[A], E])(implicit
    go: GreaterOf[E, Async]
  ): AIO[A, go.Result] = {
    futureAio.flatMap { future =>
      executionContext.flatMap { implicit ec =>
        AsyncCont[A, go.Result] { (cb, _) =>
          future.onComplete {
            case scala.util.Success(value) => cb(Outcome.Success(value))
            case scala.util.Failure(error) => cb(Outcome.Failure(error))
          }
          AIO.unit
        }
      }(GreaterOf.make).asInstanceOf[AIO[A, go.Result]]
    }(GreaterOf.make).asInstanceOf[AIO[A, go.Result]]
  }

  final def fromFutureCompletable[A, E <: Effect](
    futureAio: AIO[CompletableFuture[A], E])(implicit
    go: GreaterOf[E, Async]
  ): AIO[A, go.Result] = {
    import scala.jdk.FutureConverters._
    futureAio.flatMap { completableFuture =>
      val future = completableFuture.asScala
      executionContext.flatMap { implicit ec =>
        AsyncCont[A, go.Result] { (cb, _) =>
          future.onComplete {
            case scala.util.Success(value) => cb(Outcome.Success(value))
            case scala.util.Failure(error) => cb(Outcome.Failure(error))
          }
          AIO(completableFuture.cancel(true)).asUnit
        }
      }(GreaterOf.make).asInstanceOf[AIO[A, go.Result]]
    }(GreaterOf.make).asInstanceOf[AIO[A, go.Result]]
  }

  final def fromOutcome[A](outcome: Outcome[A]): AIO[A, outcome.Result] =
    outcome match {
      case Outcome.Success(value) => Value(value).asInstanceOf[AIO[A, outcome.Result]]
      case Outcome.Failure(error) => Error(error).asInstanceOf[AIO[A, outcome.Result]]
      case Outcome.Interrupted    => cancel.asInstanceOf[AIO[A, outcome.Result]]
    }

  final def fromTry[A](`try`: Try[A]): AIO[A, Pure] =
    `try` match {
      case scala.util.Success(value) => Value(value)
      case scala.util.Failure(error) => Error(error)
    }

  /****************/
  /* Implicit ops */
  /****************/
  import scala.language.implicitConversions

  implicit def upcastEffect[A, E <: Sync](aio: AIO[A, _ >: E]): AIO[A, E] = aio.asInstanceOf[AIO[A, E]]

  implicit class AIOPureOps[A](private val aio: AIO[A, Pure]) extends AnyVal {
    def extractValue: Either[Throwable, A] =
      aio match {
        case Value(value) => Right(value)
        case Error(error) => Left(error)
        case _            => throw Errors.runtimeError("Tried to extract a non-pure AIO.")
      }
  }

  implicit class AIOSyncOps[A](private val aio: AIO[A, Sync]) extends AnyVal {
    def runSync: Outcome[A] = runtime.SyncRuntime.runSync(aio)

    def attempt: AIO[Outcome[A], Sync] = Eval(() => runtime.SyncRuntime.runSync(aio))

    def unsafeRunSync: A = runSync match {
      case Outcome.Success(value) => value
      case Outcome.Failure(error) => throw error
      case Outcome.Interrupted    => throw evaluationInterruptedError
    }

    def unsafeRunBlocking(implicit as: akka.actor.ActorSystem): Outcome[A] =
      AkkaRuntime.runBlocking(aio)

    def unsafeRunAsync(callback: Outcome[A] => Unit)(implicit as: akka.actor.ActorSystem): Unit =
      AkkaRuntime.runAsync(aio, callback)
  }

  implicit class AIOAsyncOps[A](private val aio: AIO[A, _ <: Async]) extends AnyVal {
    def unsafeRunBlocking(implicit as: akka.actor.ActorSystem): Outcome[A] =
      AkkaRuntime.runBlocking(aio)

    def unsafeRunAsync(callback: Outcome[A] => Unit)(implicit as: akka.actor.ActorSystem): Unit =
      AkkaRuntime.runAsync(aio, callback)
  }

  /***************/
  /* Algebra Ids */
  /***************/
  private[aio] object Identifiers {
    final val Value = 0
    final val Error = 1

    final val Eval = 2
    final val Suspend = 3
    final val Block = 4
    final val Transform = 5
    final val Finalize = 6

    final val CurrentContext = 7
    final val AsyncCont = 8
    final val YieldEval = 9
    final val CancelEval = 10
  }

  /****************/
  /* Core Algebra */
  /****************/
  private[aio] final case class Value[+A](value: A) extends AIO[A, Pure](Identifiers.Value)

  private[aio] final case class Error(error: Throwable) extends AIO[Nothing, Pure](Identifiers.Error)

  private[aio] final case class Eval[+A](expr: () => A) extends AIO[A, Sync](Identifiers.Eval)

  private[aio] final case class Block[+A, E <: Effect](aio: AIO[A, _ <: Effect]) extends AIO[A, E](Identifiers.Block)

  private[aio] final case class Suspend[+A, E <: Effect](expr: () => AIO[A, E]) extends AIO[A, E](Identifiers.Suspend)

  private[aio] final case class Transform[A, +B, E <: Effect](
    aio: AIO[A, _ <: Effect],
    onSucc: A => AIO[B, _ <: Effect],
    onErr: Throwable => AIO[B, _ <: Effect],
    hasErrHandler: Boolean = false
  ) extends AIO[B, E](Identifiers.Transform)

  private[aio] final case class Finalize[A, E <: Effect](
    aio: AIO[A, _ <: Effect],
    finalizer: Outcome[A] => AIO[Unit, _ <: Effect]
  ) extends AIO[A, E](Identifiers.Finalize)

  private[aio] final case class AsyncCont[A, E <: Effect](
    cont: (Outcome[A] => Unit, Cancellable) => AIO[Unit, _ <: Effect]
  ) extends AIO[A, E](Identifiers.AsyncCont)

  private[aio] case object CurrentContext extends AIO[ExecutionContext, Async](Identifiers.CurrentContext)

  private[aio] case object YieldEval extends AIO[Unit, Async](Identifiers.YieldEval)

  private[aio] case object CancelEval extends AIO[Nothing, Async](Identifiers.CancelEval)

  /**********************/
  /* Internal Functions */
  /**********************/
  private final def identityError[A](error: Throwable): AIO[A, Pure] = Error(error)
  private final def identityValue[A](value: A): AIO[A, Pure] = Value(value)

}

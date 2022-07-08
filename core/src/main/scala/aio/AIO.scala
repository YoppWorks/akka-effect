package aio

import scala.util.Try
import scala.util.control.NonFatal

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

  private final def impossible: Nothing = sys.error("Impossible operation")

  final val unit: AIO[Unit, Pure] = Value(())

  final def apply[A](expr: => A): AIO[A, Sync] = Eval(() => expr)

  final def finallyBlock[A, E <: Effect](aio: AIO[A, E]): AIO[A, E] = Block[A, E](aio)

  final def error(error: Throwable): AIO[Nothing, Pure] = Error(error)

  final def eval[A](expr: => A): AIO[A, Sync] = Eval(() => expr)

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
      implicit val go3 = go2.asInstanceOf[GreaterOf[E1, go2.Result]]
      acquire.flatMap(resource => use(resource).finalize(oc => release(resource, oc)))
    }

  final def fromTry[A](`try`: Try[A]): AIO[A, Pure] =
    `try` match {
      case scala.util.Success(value) => Value(value)
      case scala.util.Failure(error) => Error(error)
    }

  final def fromOutcome[A](outcome: Outcome[A]): AIO[A, outcome.Result] =
    outcome match {
      case Outcome.Success(value) => Value(value).asInstanceOf[AIO[A, outcome.Result]]
      case Outcome.Failure(error) => Error(error).asInstanceOf[AIO[A, outcome.Result]]
      case Outcome.Interrupted    => Error(evaluationInterruptedError).asInstanceOf[AIO[A, outcome.Result]]
    }

  /****************/
  /* Implicit ops */
  /****************/
  import scala.language.implicitConversions

  implicit def upcastEffect[A, E <: Pure](aio: AIO[A, _ >: E]): AIO[A, E] = aio.asInstanceOf[AIO[A, E]]

  implicit class AIOPureOps[A](private val aio: AIO[A, Pure]) extends AnyVal {
    def extractValue: Either[Throwable, A] =
      aio match {
        case Value(value) => Right(value)
        case Error(error) => Left(error)
        case _            => impossible
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

  /**********************/
  /* Internal Functions */
  /**********************/
  private final def identityError[A](error: Throwable): AIO[A, Pure] = Error(error)
  private final def identityValue[A](value: A): AIO[A, Pure] = Value(value)

}

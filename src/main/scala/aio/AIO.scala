package aio

import scala.util.control.NonFatal

sealed abstract class AIO[+A, E <: Effect] private(private[aio] val id: Byte) {
  import AIO._
  import Effect._

  final def ap[B, E2 <: Effect](af: AIO[A => B, E2])(implicit go: GreaterOf[E, E2]): AIO[B, go.Result] =
    Transform[A, B, go.Result](this,
      a => af.map(f => f(a))(go.asInstanceOf[GreaterOf[E2, Sync]]),
      err => Error(err)
    )

  final def as[B](b: => B)(implicit go: GreaterOf[E, Sync]): AIO[B, go.Result] = map(_ => b)

  final def asUnit(implicit go: GreaterOf[E, Sync]): AIO[Unit, go.Result] = map(_ => ())

  final def finalize[E2 <: Effect](fin: Outcome[A] => AIO[Unit, E2])(implicit go: GreaterOf[E, E2]): AIO[A, go.Result] =
    Finalize(this, fin)

  final def flatMap[B, E2 <: Effect](f: A => AIO[B, E2])(implicit go: GreaterOf[E, E2]): AIO[B, go.Result] =
    Transform[A, B, go.Result](this,
      a => { try f(a) catch { case NonFatal(err) => Error(err) } },
      err => Error(err)
    )

  final def map[B](f: A => B)(implicit go: GreaterOf[E, Sync]): AIO[B, go.Result] =
    Transform[A, B, go.Result](this,
      a => { try Value(f(a)) catch { case NonFatal(err) => Error(err) } },
      err => Error(err)
    )

  final def onAborted[E2 <: Effect](onAbort: AIO[Unit, E2])(implicit go: GreaterOf[E, E2]): AIO[A, go.Result] =
    Finalize[A, go.Result](this,
      {
        case Outcome.Failure(err) => AIO.unit
        case Outcome.Success(_)   => AIO.unit
        case Outcome.Aborted      => onAbort
      }
    )

  final def onError[E2 <: Effect](onErrK: Throwable => AIO[Unit, E2])(implicit go: GreaterOf[E, E2]): AIO[A, go.Result] =
    Finalize[A, go.Result](this,
      {
        case Outcome.Failure(err) => try onErrK(err) catch { case NonFatal(err2) => Error(err2) }
        case Outcome.Success(_)   => AIO.unit
        case Outcome.Aborted      => AIO.unit
      }
    )

  final def recover[B >: A](f: Throwable => B)(implicit go: GreaterOf[E, Sync]): AIO[B, go.Result] =
    Transform[A, B, go.Result](this,
      a => Value(a),
      err => { try Value(f(err)) catch { case NonFatal(err2) => Error(err2) } }
    )

  final def recoverWith[B >: A, E2 <: Effect](f: Throwable => AIO[B, E2])(implicit go: GreaterOf[E, E2]): AIO[B, go.Result] =
    Transform[A, B, go.Result](this,
      a => Value(a),
      err => { try f(err) catch { case NonFatal(err2) => Error(err2) } }
    )

  final def transform[B](onSucc: A => B)(onErr: Throwable => B): AIO[B, E] =
    Transform[A, B, E](this,
      a => { try Value(onSucc(a)) catch { case NonFatal(err) => Error(err) } },
      err => { try Value(onErr(err)) catch { case NonFatal(err2) => Error(err2) } }
    )

  final def transformWith[B, E2 <: Effect, E3 <: Effect](
    onSuccK: A => AIO[B, E2])(
    onErrK: Throwable => AIO[B, E3])(
    implicit go: GreatestOf[E, E2, E3]
  ): AIO[B, go.Result] =
    Transform[A, B, go.Result](this,
      a => { try onSuccK(a) catch { case NonFatal(err) => Error(err) } },
      err => { try onErrK(err) catch { case NonFatal(err2) => Error(err2) } }
    )

}

object AIO {
  import Effect._

  private final def impossible: Nothing = sys.error("Impossible operation")

  final val unit: AIO[Unit, Pure] = Value(())

  final def apply[A](expr: => A): AIO[A, Sync] = Eval(() => expr)

  final def error(error: Throwable): AIO[Nothing, Pure] = Error(error)

  final def eval[A](expr: => A): AIO[A, Sync] = Eval(() => expr)

  final def pure[A](value: A): AIO[A, Pure] = Value(value)

  final def suspend[A, E <: Effect](expr: => AIO[A, E]): AIO[A, E] = Suspend(() => expr)

  /****************/
  /* Implicit ops */
  /****************/
  implicit class AIOPureOps[A](private val aio: AIO[A, Pure]) extends AnyVal {
    def extractValue: Either[Throwable, A] =
      aio match {
        case Value(value) => Right(value)
        case Error(error) => Left(error)
        case _            => impossible
      }
  }

  /***************/
  /* Algebra Ids */
  /***************/
  private[aio] object Identifiers {
    final val Value: Byte = 0
    final val Error: Byte = 1
    final val Sync: Byte = 2
    final val Suspend: Byte = 3
    final val Transform: Byte = 4
    final val Finalize: Byte = 5
  }

  /****************/
  /* Core Algebra */
  /****************/
  private[aio] final case class Value[+A](value: A) extends AIO[A, Pure](Identifiers.Value)

  private[aio] final case class Error(error: Throwable) extends AIO[Nothing, Pure](Identifiers.Error)

  private[aio] final case class Eval[+A](expr: () => A) extends AIO[A, Sync](Identifiers.Sync)

  private[aio] final case class Suspend[+A, E <: Effect](expr: () => AIO[A, E]) extends AIO[A, E](Identifiers.Suspend)

  private[aio] final case class Transform[A, +B, E <: Effect](
    aio: AIO[A, _ <: Effect],
    onSucc: A => AIO[B, _ <: Effect],
    onErr: Throwable => AIO[B, _ <: Effect]
  ) extends AIO[B, E](Identifiers.Transform)

  private[aio] final case class Finalize[A, E <: Effect](
    aio: AIO[A, _ <: Effect],
    finalizer: Outcome[A] => AIO[Unit, _ <: Effect]
  ) extends AIO[A, E](Identifiers.Finalize)

}

package aio

sealed trait Outcome[+A] extends Product with Serializable {
  import Outcome._

  type Result <: Effect

  private[aio] final def unsafeUnwrap: A =
    this match {
      case Success(value) => value
      case Failure(error) => throw error
      case Interrupted    => throw evaluationInterruptedError
    }

  final def isSuccess: Boolean =
    this match {
      case Success(_)  => true
      case Failure(_)  => false
      case Interrupted => false
    }

  final def isFailure: Boolean =
    this match {
      case Success(_)  => false
      case Failure(_)  => true
      case Interrupted => false
    }

  final def isInterrupted: Boolean =
    this match {
      case Success(_)  => false
      case Failure(_)  => false
      case Interrupted => true
    }

  final def raiseError(error: Throwable): Outcome[A] =
    this match {
      case Success(_)       => Outcome.Failure(error)
      case Interrupted      => Outcome.Failure(error)
      case Failure(err)
        if error != err     => err.addSuppressed(error); Outcome.Failure(err)
      case fl @ Failure(_)  => fl
    }
}

object Outcome {

  case class Success[A](value: A) extends Outcome[A] { final type Result = Effect.Pure }
  case class Failure[A](error: Throwable) extends Outcome[A] { final type Result = Effect.Pure }
  case object Interrupted extends Outcome[Nothing] { final type Result = Effect.Async }

  @inline private[aio] def resultOrError[A](result: Outcome[A], orError: Outcome[A]) =
    if (orError.isFailure || orError.isInterrupted) orError else result

  private[aio] final def combineLeft[A](lhs: Outcome[A], rhs: Outcome[A]): Outcome[A] = {
    import internal._

    if (IsNull(lhs)) rhs
    else if (IsNull(rhs)) lhs
    else if (lhs.isFailure && rhs.isFailure) {
      val rhsError = rhs.asInstanceOf[Outcome.Failure[A]].error
      lhs.raiseError(rhsError)
    }
    else if (rhs.isFailure) rhs
    else if (lhs.isInterrupted) lhs
    else if (rhs.isInterrupted) rhs
    else lhs
  }

}

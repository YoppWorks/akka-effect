package aio

sealed trait Outcome[+A] extends Product with Serializable {
  import Outcome._

  type Result <: Effect
  
  final def isSuccess: Boolean =
    this match {
      case Success(_) => true
      case Failure(_) => false
      case Aborted    => false
    }

  final def isFailure: Boolean =
    this match {
      case Success(_) => false
      case Failure(_) => true
      case Aborted    => false
    }

  final def isAborted: Boolean =
    this match {
      case Success(_) => false
      case Failure(_) => false
      case Aborted    => true
    }

  final def raiseError(error: Throwable): Outcome[A] =
    this match {
      case Success(_)       => Outcome.Failure(error)
      case Aborted          => Outcome.Failure(error)
      case Failure(err)
        if error != err     => err.addSuppressed(error); Outcome.Failure(err)
      case fl @ Failure(_)  => fl
    }
}

object Outcome {

  case class Success[A](value: A) extends Outcome[A] { final type Result = Effect.Pure }
  case class Failure[A](error: Throwable) extends Outcome[A] { final type Result = Effect.Pure }
  case object Aborted extends Outcome[Nothing] { final type Result = Effect.Async }

  @inline private[aio] def resultOrError[A](result: Outcome[A], orError: Outcome[A]) =
    if (orError.isFailure || orError.isAborted) orError else result

}

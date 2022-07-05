package aio

sealed trait Outcome[+A] extends Product with Serializable {
  import Outcome._
  
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
      case Success(_)   => Outcome.Failure(error)
      case Aborted      => Outcome.Failure(error)
      case Failure(err) => err.addSuppressed(error); Outcome.Failure(err)
    }
}

object Outcome {

  case class Success[A](value: A) extends Outcome[A]
  case class Failure[A](error: Throwable) extends Outcome[A]
  case object Aborted extends Outcome[Nothing]

}

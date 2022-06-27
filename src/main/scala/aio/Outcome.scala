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
}

object Outcome {

  case class Success[A](value: A) extends Outcome[A]
  case class Failure[A](error: Throwable) extends Outcome[A]
  case object Aborted extends Outcome[Nothing]

}

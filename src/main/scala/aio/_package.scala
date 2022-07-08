package object aio {

  sealed trait Effect
  case object Effect {

    sealed trait Pure extends Effect

    sealed trait Sync extends Pure

    sealed trait Async extends Sync

    sealed trait Concurrent extends Async

    sealed trait Total extends Concurrent
  }

  final class EvaluationAbortedError private[aio]() extends Error("Evaluation of AIO aborted.")
  private[aio] val evaluationAbortedError: Throwable = new EvaluationAbortedError

  // Type not-equals:
  // Taken from Shapeless, from here: https://github.com/milessabin/shapeless/blob/main/core/src/main/scala/shapeless/package.scala
  // All credit goes to Miles Saben and other authors
  private def unexpected : Nothing = sys.error("Unexpected invocation")
  sealed trait =:!=[A, B] extends Serializable
  object =:!= {
    implicit def neq[A, B] : A =:!= B = new =:!=[A, B] {}
    implicit def neqAmbig1[A] : A =:!= A = unexpected
    implicit def neqAmbig2[A] : A =:!= A = unexpected
  }

  sealed trait GreaterOf[E1 <: Effect, E2 <: Effect] extends Serializable {
    type Result <: Effect
  }
  case object GreaterOf {
    import Effect._

    type Aux[A <: Effect, B <: Effect, Res <: Effect] = GreaterOf[A, B] { type Result = Res }
    private final val instance: Aux[Nothing, Nothing, Nothing] =
      new GreaterOf[Nothing, Nothing] { type Result = Nothing }

    implicit def atLeastSync: Aux[Pure, Pure, Sync] = instance.asInstanceOf[Aux[Pure, Pure, Sync]]

    implicit def allSame[A <: Sync]: Aux[A, A, A] = instance.asInstanceOf[Aux[A, A, A]]
      
    implicit def firstGreater[A <: Sync, B <: Effect](implicit ev1: A <:< B, ev2: A =:!= B): Aux[A, B, A] =
      instance.asInstanceOf[Aux[A, B, A]]

    implicit def secondGreater[A <: Effect, B <: Sync](implicit ev1: B <:< A, ev2: A =:!= B): Aux[A, B, B] =
      instance.asInstanceOf[Aux[A, B, B]]
  }

  sealed trait GreatestOf[E1 <: Effect, E2 <: Effect, E3 <: Effect] extends Serializable {
    type Result <: Effect
  }
  object GreatestOf {
    import Effect._

    type Aux[A <: Effect, B <: Effect, C <: Effect, Res <: Effect] = GreatestOf[A, B, C] { type Result = Res }
    private val instance: Aux[Nothing, Nothing, Nothing, Nothing] =
      new GreatestOf[Nothing, Nothing, Nothing] { type Result = Nothing }

    implicit def atLeastSync: Aux[Pure, Pure, Pure, Sync] = instance.asInstanceOf[Aux[Pure, Pure, Pure, Sync]]

    implicit def allSame[A <: Sync]: Aux[A, A, A, A] = instance.asInstanceOf[Aux[A, A, A, A]]

    implicit def firstTwo[A <: Sync, B <: Effect](
      implicit ev1: A <:< B, ev2: A =:!= B
    ): Aux[A, A, B, A] = instance.asInstanceOf[Aux[A, A, B, A]]

    implicit def lastTwo[A <: Effect, B <: Sync](
      implicit ev1: B <:< A, ev2: A =:!= B
    ): Aux[A, B, B, B] = instance.asInstanceOf[Aux[A, B, B, B]]

    implicit def firstAndLast[A <: Sync, B <: Effect](
      implicit ev1: A <:< B, ev2: A =:!= B
    ): Aux[A, B, A, A] = instance.asInstanceOf[Aux[A, B, A, A]]

    implicit def firstGreatest[A <: Sync, B <: Effect, C <: Effect](
      implicit ev1: A <:< B, ev2: A <:< C, ev3: A =:!= B, ev4: A =:!= C
    ): Aux[A, B, C, A] = instance.asInstanceOf[Aux[A, B, C, A]]

    implicit def secondGreatest[A <: Effect, B <: Sync, C <: Effect](
      implicit ev1: B <:< A, ev2: B <:< C, ev3: B =:!= A, ev4: B =:!= C
    ): Aux[A, B, C, B] = instance.asInstanceOf[Aux[A, B, C, B]]

    implicit def thirdGreatest[A <: Effect, B <: Effect, C <: Sync](
      implicit ev1: C <:< A, ev2: C <:< B, ev3: C =:!= A, ev4: C =:!= B
    ): Aux[A, B, C, C] = instance.asInstanceOf[Aux[A, B, C, C]]
  }

}

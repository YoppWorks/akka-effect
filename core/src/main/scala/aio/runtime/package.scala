package aio

package object runtime {

  private[aio] type AIOAny    = AIO[Any, _ <: Effect]
  private[aio] type StackOp   = AIO.Transform[Any, Any, _ <: Effect]
  private[aio] type Finalizer = Outcome[Any] => AIO[Unit, _ <: Effect]

  private[aio] object Constants {
    final val askTimeoutDuration = scala.concurrent.duration.FiniteDuration(5, "seconds")
    final val autoYieldLimit = 512
    final val finalizerRecursionMaxDepth = 32
  }

}

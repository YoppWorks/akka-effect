package aio.internal

private[aio] final class Deferred[A <: AnyRef] private() {
  import Deferred.alreadyCompletedError

  @volatile private[this] var completedValue: A = _
  private[this] val context = new AnyRef

  def isComplete: Boolean = NotNull(completedValue)

  def value: A = completedValue match {
    case complete if NotNull(complete) => complete
    case _ => waitForValue()
  }

  def complete(a: A): Unit =
    if (NotNull(completedValue)) throw alreadyCompletedError
    else context synchronized {
      if (NotNull(completedValue)) throw alreadyCompletedError
      completedValue = a
      context.notifyAll()
    }

  private def waitForValue(): A =
    context synchronized {
      context.wait()
      completedValue
    }

}

private[aio] object Deferred {
  private def alreadyCompletedError: Throwable = new IllegalStateException("Deferred already completed.")
  def apply[A <: AnyRef]: Deferred[A] = new Deferred[A]()
}

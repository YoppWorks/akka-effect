package aio.runtime

sealed class AIORuntimeError private[aio](message: String) extends Throwable(message)

final class FinalizerRecursionDepthExceeded private[aio]
  extends AIORuntimeError("Recursion/nesting depth of finalizers exceeded.")

final class AIORuntimeFault private[aio](override val getCause: Throwable)
  extends AIORuntimeError("An internal AIO runtime error has occurred.")

private[aio] object Errors {

  def runtimeError(message: String): AIORuntimeError    = new AIORuntimeError(message)
  def finalizerRecursionDepthExceeded: AIORuntimeError  = new FinalizerRecursionDepthExceeded
  def runtimeFault(cause: Throwable): AIORuntimeError   = new AIORuntimeFault(cause)

}

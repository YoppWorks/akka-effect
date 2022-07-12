package aio.internal

import java.util.concurrent.atomic.AtomicReference

private[aio] final class OneShotLatch private() {
  private val token = new AnyRef
  private val ref = new AtomicReference[AnyRef](token)

  def isSet: Boolean = IsNull(ref.get())
  def reset(): Unit = ref.set(token)
  def set(): Boolean = ref.getAndSet(Null[AnyRef]) match {
    case n if IsNull(n) => true
    case _              => false
  }

}

private[aio] object OneShotLatch {
  def apply(): OneShotLatch = new OneShotLatch()
}

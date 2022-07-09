package aio

trait Cancellable {

  /**
   * Cancels this Cancellable and returns true if that was successful.
   * If this cancellable was (concurrently) cancelled already, then this method
   * will return false although isCancelled will return true.
   */
  def cancel(): AIO[Boolean, Effect.Async]

  /**
   * Returns true if and only if this [[Cancellable]] has been successfully cancelled.
   */
  def isCancelled: Boolean

}

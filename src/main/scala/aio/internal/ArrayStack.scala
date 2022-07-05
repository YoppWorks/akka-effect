package aio.internal

/**
 * A very fast, growable/shrinkable, mutable stack.
 * @note Taken from the ZIO project,
 *       link: [[https://github.com/zio/zio/blob/series/1.x/core/shared/src/main/scala/zio/internal/Stack.scala]]
 */
private[aio] final class ArrayStack[A <: AnyRef]() {
  private[this] var array   = new Array[AnyRef](13)
  private[this] var size    = 0
  private[this] var nesting = 0

  /**
   * Determines if the stack is empty.
   */
  def isEmpty: Boolean = size == 0

  /**
   * Pushes an item onto the stack.
   */
  def push(a: A): Unit =
    if (size == 13) {
      array = Array(array, a, null, null, null, null, null, null, null, null, null, null, null)
      size = 2
      nesting += 1
    } else {
      array(size) = a
      size += 1
    }

  /**
   * Pops an item off the stack, or returns `null` if the stack is empty.
   */
  def pop(): A =
    if (size <= 0) {
      null.asInstanceOf[A]
    } else {
      val idx = size - 1
      var a   = array(idx)
      if (idx == 0 && nesting > 0) {
        array = a.asInstanceOf[Array[AnyRef]]
        a = array(12)
        array(12) = null // GC
        size = 12
        nesting -= 1
      } else {
        array(idx) = null // GC
        size = idx
      }
      a.asInstanceOf[A]
    }

  /**
   * Peeks the item on the head of the stack, or returns `null` if empty.
   */
  def peek(): A =
    if (size <= 0) {
      null.asInstanceOf[A]
    } else {
      val idx = size - 1
      var a   = array(idx)
      if (idx == 0 && nesting > 0) a = (a.asInstanceOf[Array[AnyRef]])(12)
      a.asInstanceOf[A]
    }

  def peekOrElse(a: A): A = if (size <= 0) a else peek()
}

private[aio] object ArrayStack {
  def apply[A <: AnyRef]: ArrayStack[A] = new ArrayStack[A]
}

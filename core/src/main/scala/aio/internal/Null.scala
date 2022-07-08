package aio.internal

object Null {
  /** Creates an empty (`null`) reference of type [[A]] */
  @inline final def apply[A <: AnyRef]: A =
    null.asInstanceOf[A]
}

object IsNull {
  /** Checks if the [[A reference]] is null */
  @inline final def apply[A <: AnyRef](ref: A): Boolean =
    ref == null

}

object NotNull {
  /** Checks if the [[A reference]] is null */
  @inline final def apply[A <: AnyRef](ref: A): Boolean =
    ref != null

}

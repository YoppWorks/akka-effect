package aio

sealed trait Effect
case object Effect {

  sealed trait Pure extends Effect

  sealed trait Sync extends Pure

  sealed trait Async extends Sync

  sealed trait Concurrent extends Async

  sealed trait Total extends Concurrent
}

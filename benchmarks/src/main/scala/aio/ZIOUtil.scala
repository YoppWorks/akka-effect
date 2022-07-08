package aio

import cats._
import cats.effect.{Fiber => CFiber, IO => CIO}
import zio.BootstrapRuntime
import zio.internal._

import scala.concurrent.ExecutionContext

object ZIOUtil extends BootstrapRuntime {
  override val platform: Platform = Platform.benchmark
}

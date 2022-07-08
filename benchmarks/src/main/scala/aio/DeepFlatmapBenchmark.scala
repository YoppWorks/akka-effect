package aio

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class DeepFlatmapBenchmark {
  @Param(Array("20"))
  var depth: Int = _

  @Benchmark
  def aioDeepFlatMap(): BigInt = {
    import Effect.Sync

    def fib(n: Int): AIO[BigInt, Sync] =
      if (n <= 1) AIO(n)
      else
        fib(n - 1).flatMap(a => fib(n - 2).flatMap(b => AIO(a + b)))

    fib(depth).unsafeRunSync
  }

  @Benchmark
  def catsDeepFlatMap(): BigInt = {
    import cats.effect._
    implicit val catsRuntime = cats.effect.unsafe.IORuntime.global

    def fib(n: Int): IO[BigInt] =
      if (n <= 1) IO(n)
      else
        fib(n - 1).flatMap(a => fib(n - 2).flatMap(b => IO(a + b)))

    fib(depth).unsafeRunSync()
  }

  @Benchmark
  def zioDeepFlatMap(): BigInt = {
    import zio._

    def fib(n: Int): UIO[BigInt] =
      if (n <= 1) ZIO.effectTotal[BigInt](n)
      else
        fib(n - 1).flatMap(a => fib(n - 2).flatMap(b => ZIO.effectTotal(a + b)))

    ZIOUtil.unsafeRun(fib(depth))
  }
}

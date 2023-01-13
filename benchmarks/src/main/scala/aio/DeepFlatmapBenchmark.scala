package aio

import akka.actor.ActorSystem
import aio.internal._
import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@OutputTimeUnit(TimeUnit.SECONDS)
class DeepFlatmapBenchmark {
  @Param(Array("20"))
  var depth: Int = _

  implicit var actorSystem = Null[ActorSystem]

  @Setup(Level.Trial)
  def setup(): Unit = {
    actorSystem = ActorSystem("ActorBenchmark")
  }

  @TearDown(Level.Trial)
  def tearDown(): Unit = {
    import scala.concurrent.{Await, duration}
    val _ignored = Await.ready(actorSystem.terminate(), duration.Duration.Inf)
  }

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
  def aioDeepFlatMapAsync(): BigInt = {
    import Effect.Sync
    import scala.concurrent.{Await, Promise, duration}

    def fib(n: Int): AIO[BigInt, Sync] =
      if (n <= 1) AIO(n)
      else
        fib(n - 1).flatMap(a => fib(n - 2).flatMap(b => AIO(a + b)))

    val promise = Promise[Outcome[BigInt]]()
    fib(depth).unsafeRunAsync(promise.success)
    Await.result(promise.future, duration.Duration.Inf).unsafeUnwrap
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

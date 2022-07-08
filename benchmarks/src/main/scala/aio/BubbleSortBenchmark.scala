package aio

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class BubbleSortBenchmark {
  import BubbleSortBenchmark._

  @Param(Array("1000"))
  var size: Int = _

  def createTestArray: Array[Int] = Range.inclusive(1, size).toArray.reverse
  def assertSorted(array: Array[Int]): Unit =
    if (!array.sorted.sameElements(array)) {
      throw new Exception("Array not correctly sorted")
    }

  @Benchmark
  def aioBubbleSort(): Unit = {
    import AIOArray._
    (for {
      array <- AIO(createTestArray)
      _     <- bubbleSort[Int](_ <= _)(array)
      _     <- AIO(assertSorted(array))
    } yield ()).unsafeRunSync
  }

  @Benchmark
  def catsBubbleSort(): Unit = {
    import CatsIOArray._
    import cats.effect.IO
    implicit val catsRuntime = cats.effect.unsafe.IORuntime.global

    (for {
      array <- IO(createTestArray)
      _     <- bubbleSort[Int](_ <= _)(array)
      _     <- IO(assertSorted(array))
    } yield ()).unsafeRunSync()
  }

  @Benchmark
  def zioBubbleSort(): Unit = {
    import zio._
    import ZIOArray._

    val _ = ZIOUtil.unsafeRunSync(
      for {
        array <- IO.effectTotal[Array[Int]](createTestArray)
        _     <- bubbleSort[Int](_ <= _)(array)
        _     <- IO.effectTotal[Unit](assertSorted(array))
      } yield ()
    )
  }

}

object BubbleSortBenchmark {

  object AIOArray {
    import Effect._

    def bubbleSort[A](lessThanEqual0: (A, A) => Boolean)(array: Array[A]): AIO[Unit, Sync] = {
      def outerLoop(i: Int): AIO[Unit, Sync] =
        if (i >= array.length - 1) AIO.unit else innerLoop(i, i + 1).flatMap(_ => outerLoop(i + 1))

      def innerLoop(i: Int, j: Int): AIO[Unit, Sync] =
        if (j >= array.length) AIO.unit
        else
          AIO((array(i), array(j))).flatMap { case (ia, ja) =>
            val maybeSwap = if (lessThanEqual0(ia, ja)) AIO.unit else swapIJ(i, ia, j, ja)

            maybeSwap.flatMap(_ => innerLoop(i, j + 1))
          }

      def swapIJ(i: Int, ia: A, j: Int, ja: A): AIO[Unit, Sync] =
        AIO { array.update(i, ja); array.update(j, ia) }

      outerLoop(0)
    }
  }

  object CatsIOArray {
    import cats.effect.IO

    def bubbleSort[A](lessThanEqual0: (A, A) => Boolean)(array: Array[A]): IO[Unit] = {
      def outerLoop(i: Int): IO[Unit] =
        if (i >= array.length - 1) IO.unit else innerLoop(i, i + 1).flatMap(_ => outerLoop(i + 1))

      def innerLoop(i: Int, j: Int): IO[Unit] =
        if (j >= array.length) IO.unit
        else
          IO((array(i), array(j))).flatMap { case (ia, ja) =>
            val maybeSwap = if (lessThanEqual0(ia, ja)) IO.unit else swapIJ(i, ia, j, ja)

            maybeSwap.flatMap(_ => innerLoop(i, j + 1))
          }

      def swapIJ(i: Int, ia: A, j: Int, ja: A): IO[Unit] =
        IO { array.update(i, ja); array.update(j, ia) }

      outerLoop(0)
    }
  }

  object ZIOArray {
    import zio._

    def bubbleSort[A](lessThanEqual0: (A, A) => Boolean)(array: Array[A]): UIO[Unit] = {
      def outerLoop(i: Int): UIO[Unit] =
        if (i >= array.length - 1) UIO.unit else innerLoop(i, i + 1).flatMap(_ => outerLoop(i + 1))

      def innerLoop(i: Int, j: Int): UIO[Unit] =
        if (j >= array.length) UIO.unit
        else
          UIO((array(i), array(j))).flatMap { case (ia, ja) =>
            val maybeSwap = if (lessThanEqual0(ia, ja)) UIO.unit else swapIJ(i, ia, j, ja)

            maybeSwap.flatMap(_ => innerLoop(i, j + 1))
          }

      def swapIJ(i: Int, ia: A, j: Int, ja: A): UIO[Unit] =
        UIO { array.update(i, ja); array.update(j, ia) }

      outerLoop(0)
    }
  }

}

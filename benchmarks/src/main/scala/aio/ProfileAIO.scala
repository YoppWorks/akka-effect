package aio

object ProfileAIO {

  def main(args: Array[String]): Unit = {
    val benchmark = new DeepFlatmapBenchmark
    benchmark.depth = 30
    benchmark.setup()
    val _ = scala.io.StdIn.readLine("Press [Enter] to start...")
    for (i <- 1 to 5) {
      val start = System.currentTimeMillis()
      benchmark.aioDeepFlatMapAsync()
      val end = System.currentTimeMillis()
      println(f"Run $i took ${end - start}%,d milliseconds")
    }
    benchmark.tearDown()
  }

}

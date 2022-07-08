package aio

import org.specs2.mutable.Specification
import org.specs2.execute.Typecheck._


object AIOTypeCheckSpec extends Specification {
  import Effect._

  "AIO (Syntax Type Check)" should {

    "be able to extract values from pure AIO" in {
      val error = new RuntimeException()
      val pure1 = AIO.pure(10)
      val pure2: AIO[Int, Effect.Pure] = AIO.error(error)

      pure1.extractValue must beRight(10)
      pure2.extractValue must beLeft(error)
    }

    "not be able to extract values from non-pure AIO" in {
      val start = AIO.pure(10)
      val mult10 = start.map(_ * 10)
      val num10Async = AIO.fromOutcome(Outcome.Interrupted).as(10)

      typecheck {
        """
        start.extractValue
        """
      }.isSuccess must beTrue

      typecheck {
        """
        mult10.extractValue
        """
      }.isSuccess must beFalse

      typecheck {
        """
        mult10.runSync
        """
      }.isSuccess must beTrue

      typecheck {
        """
        num10Async.runSync
        """
      }.isSuccess must beFalse
    }

    "change the effect type when using operators" in {
      val start = AIO.pure("Hello")

      start.as(10) must beAnInstanceOf[AIO[Int, Sync]]
      start.asUnit must beAnInstanceOf[AIO[Unit, Sync]]

      start.finalize(_ => AIO.unit) must beAnInstanceOf[AIO[String, Sync]]
      val test1 = start.flatMap(_ => AIO.pure(10)) 
      test1 must beAnInstanceOf[AIO[Int, Sync]]
      start.map(_ + " World") must beAnInstanceOf[AIO[String, Sync]]
      start.onAborted(AIO.unit) must beAnInstanceOf[AIO[String, Sync]]
      start.onError(_ => AIO.unit) must beAnInstanceOf[AIO[String, Sync]]

      start.recover(_ => "Goodbye") must beAnInstanceOf[AIO[String, Sync]]
      start.recoverWith(_ => AIO.pure("Hello World")) must beAnInstanceOf[AIO[String, Sync]]

      start.transform(_ => 10)(_ => 10) must beAnInstanceOf[AIO[Int, Sync]]
      start.transformWith(_ => AIO.pure(10))(_ => AIO.pure(10)) must beAnInstanceOf[AIO[String, Sync]]

      val cont = start.map(_ + "World")
      val cont2 = cont.map(_ + ", Welcome!")
      val contUnit = cont.asUnit
      val five = AIO.pure(5)
      val ten = five.map(_ * 2)

      cont.finalize(_ => contUnit) must beAnInstanceOf[AIO[String, Sync]]
      cont.flatMap(_ => cont) must beAnInstanceOf[AIO[String, Sync]]
      cont.onError(_ => contUnit) must beAnInstanceOf[AIO[String, Sync]]
      cont.recoverWith(_ => cont2) must beAnInstanceOf[AIO[String, Sync]]
      cont.transformWith(_ => ten)(_ => ten) must beAnInstanceOf[AIO[String, Sync]]

      cont.flatMap(_ => AIO.pure("Test")) must beAnInstanceOf[AIO[String, Sync]]
      start.recoverWith(_ => cont2)

      cont.transformWith(_ => AIO.pure(15))(_ => AIO.pure(10)) must beAnInstanceOf[AIO[String, Sync]]
      cont.transformWith(_ => ten)(_ => AIO.pure(10)) must beAnInstanceOf[AIO[String, Sync]]
      cont.transformWith(_ => AIO.pure(10))(_ => ten) must beAnInstanceOf[AIO[String, Sync]]
      cont.transformWith(_ => ten)(_ => ten) must beAnInstanceOf[AIO[String, Sync]]
      cont.transformWith(_ => ten)(_ => AIO.fromOutcome(Outcome.Interrupted)) must beAnInstanceOf[AIO[String, Async]]
    }

  }

}
package aio.runtime

import akka.actor.{ActorSystem => ClassicActorSystem}
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.util.Timeout

import aio._
import aio.internal._

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.{switch, tailrec}
import scala.concurrent.{Await, Promise, blocking}
import scala.util.control.NonFatal

private sealed trait RuntimeCommand

private case class RunAIO[A, E <: Effect](aio: AIO[A, E], callback: Outcome[A] => Unit) extends RuntimeCommand
private case class RunBlock(
  aio: AIOAny,
  opStack: ArrayStack[StackOp],
  finalizers: ArrayStack[Finalizer]
) extends RuntimeCommand

private case class AsyncResult[A](outcome: Outcome[A])                extends RuntimeCommand
private case class CancellationRequested(replyTo: ActorRef[Boolean])  extends RuntimeCommand
private case class ContinueEvaluation(aio: AIOAny)                    extends RuntimeCommand
private case object ContinueFinalization                              extends RuntimeCommand

object AkkaRuntime {
  private type AIOCallback      = Outcome[Any] => Unit
  private type AIOCancellation  = AIO[Unit, _ <: Effect]
  private type AsyncCallback    = Outcome[Any] => RuntimeBehavior
  private type RuntimeBehavior  = Behavior[RuntimeCommand]
  private type RuntimeActorRef  = ActorRef[RuntimeCommand]

  final def runCancellable[A](
    aio: AIO[A, _ <: Effect.Async], callback: Outcome[A] => Unit)(implicit
    actorSystem: ClassicActorSystem
  ): Cancellable = {
    import scaladsl.adapter._

    val id = java.util.UUID.randomUUID()
    val cancellableDeferred = Deferred[Cancellable]()

    val runtimeBehavior = Behaviors.setup[RuntimeCommand] { context =>
      val cancellable = new AkkaCancellable(context.self)(context.system.scheduler, Constants.askTimeoutDuration)
      cancellableDeferred.set(cancellable)
      new AkkaRuntime(context, cancellable) 
    }

    val runtime = actorSystem.spawn(runtimeBehavior, s"AIOActor-${id.toString}")
    runtime ! RunAIO(aio, callback)

    cancellableDeferred.value
  }

  final def runAsync[A](
    aio: AIO[A, _ <: Effect.Async], callback: Outcome[A] => Unit)(implicit
    actorSystem: ClassicActorSystem
  ): Unit = {
    import scaladsl.adapter._

    val id = java.util.UUID.randomUUID()
    val runtimeBehavior = Behaviors.setup[RuntimeCommand] { context =>
      val cancellable = new AkkaCancellable(context.self)(context.system.scheduler, Constants.askTimeoutDuration)
      new AkkaRuntime(context, cancellable) 
    }

    val runtime = actorSystem.spawn(runtimeBehavior, s"AIOActor-${id.toString}")
    runtime ! RunAIO(aio, callback)
  }

  final def runBlocking[A](aio: AIO[A, _ <: Effect.Async])(implicit actorSystem: ClassicActorSystem): Outcome[A] = {
    val promise = Promise[Outcome[A]]()
    runAsync(aio, promise.success)
    blocking {
      Await.result(promise.future, scala.concurrent.duration.Duration.Inf)
    }
  }

  private def onCompleteCallback(cb: Outcome[Nothing] => Unit, shutdownBehavior: RuntimeBehavior): AsyncCallback =
    new AsyncCallback {
      val callback = cb.asInstanceOf[Outcome[Any] => Unit]
      final def apply(outcome: Outcome[Any]): RuntimeBehavior = {
        callback(outcome)
        shutdownBehavior
      }
    }

  @inline private final def asyncCallback(f: Outcome[Any] => RuntimeBehavior): AsyncCallback = f

  private sealed trait EvaluateFinalizerResult { def loopCount: Int }
  private object EvaluateFinalizerResult {
    final case class Sync(loopCount: Int)                                 extends EvaluateFinalizerResult
    final case class Async(loopCount: Int, onCan: AIOCancellation)        extends EvaluateFinalizerResult
    final case class Failure(loopCount: Int, error: Throwable)            extends EvaluateFinalizerResult
    final case class Cancelled(loopCount: Int)                            extends EvaluateFinalizerResult
    final case class Paused(loopCount: Int, opStack: ArrayStack[StackOp]) extends EvaluateFinalizerResult
  }

  private final class AkkaCancellable(actorRef: RuntimeActorRef)(implicit
    scheduler: Scheduler,
    timeout: Timeout
  ) extends Cancellable {
    import scaladsl.AskPattern._

    private val evaluationCancelled = new AtomicBoolean(false)
    private val cancellationRef = new AtomicReference[RuntimeActorRef](actorRef)

    def onActorStopped(): Unit = cancellationRef.set(Null[RuntimeActorRef])
    def markCancelled(): Boolean = !evaluationCancelled.getAndSet(true)

    def isCancelled: Boolean = evaluationCancelled.get()

    def cancel(): AIO[Boolean, Effect.Async] =
      if (evaluationCancelled.get()) AIO.pure(false)
      else {
        val actorRef = cancellationRef.get()
        if (NotNull(actorRef)) {
          val askAIO = AIO(actorRef.ask(ref => CancellationRequested(ref)))
          AIO.fromFuture(askAIO)
        } else AIO.pure(false)
      }
  }

  private final class AkkaCallback(actorRef: RuntimeActorRef) {
    private val receiverRef = new AtomicReference[RuntimeActorRef](actorRef)

    def onActorStopped(): Unit = receiverRef.set(Null[RuntimeActorRef])

    def callback: AIOCallback = { outcome =>
      val receiver = receiverRef.get()
      if (NotNull(receiver)) {
        receiver ! AsyncResult(outcome)
      }
    }
  }

}

private final class AkkaRuntime(context: ActorContext[RuntimeCommand], cancellable: AkkaCancellable) extends AbstractBehavior[RuntimeCommand](context) {
  import AkkaRuntime._

  /* Constants */
  private[this] val self = context.self
  private[this] val maxLoopCount = Constants.autoYieldLimit
  private[this] val executionContext = context.executionContext
  private[this] val akkaCallback = new AkkaCallback(self)
  private lazy val log = context.log

  /* Akka Behaviors */
  private val onStopBehavior: RuntimeBehavior = Behaviors.stopped {
    () => {
      akkaCallback.onActorStopped()
      cancellable.onActorStopped()
    }
  }

  override def onMessage(msg: RuntimeCommand): RuntimeBehavior =
    msg match {
      case CancellationRequested(replyTo) =>
        val didCancel = cancellable.markCancelled()
        replyTo ! didCancel
        onStopBehavior

      case RunAIO(aio, callback) =>
        val asyncCallback = onCompleteCallback(callback, onStopBehavior)
        evaluate(aio, ArrayStack[StackOp], ArrayStack[Finalizer], asyncCallback)

      case unexpected =>
        log.warn(s"Received unexpected message $unexpected while in `asyncBehavior`")
        Behaviors.same
    }

  private def asyncBehavior(
    cancellation: AIOCancellation,
    opStack: ArrayStack[StackOp],
    finalizers: ArrayStack[Finalizer],
    callback: AsyncCallback
  ): RuntimeBehavior =
    Behaviors.receiveMessage[RuntimeCommand] {
      case AsyncResult(outcome)           => evaluate(AIO.fromOutcome(outcome), opStack, finalizers, callback)
      case ContinueEvaluation(aio)        => evaluate(aio, opStack, finalizers, callback)
      case CancellationRequested(replyTo) =>
        val cancelledCallback = asyncCallback { outcome =>
          self ! ContinueFinalization
          finalizingBehavior(outcome, finalizers, callback, 0)
        }
        replyTo ! cancellable.markCancelled()
        evaluate(cancellation, ArrayStack[StackOp], ArrayStack[Finalizer], cancelledCallback)

      case unexpected =>
        log.warn(s"Received unexpected message $unexpected while in `asyncBehavior`")
        Behaviors.same
    } .receiveSignal {
      case (_, Terminated(_)) => finalizingBehavior(Outcome.Interrupted, finalizers, callback, 0)
    }

  private def runningBehavior(
    opStack: ArrayStack[StackOp],
    finalizers: ArrayStack[Finalizer],
    callback: AsyncCallback
  ): RuntimeBehavior =
    Behaviors.receiveMessage[RuntimeCommand] {
      case ContinueEvaluation(aio) => evaluate(aio, opStack, finalizers, callback)
      case CancellationRequested(replyTo) =>
        replyTo ! cancellable.markCancelled()
        self ! ContinueFinalization
        finalizingBehavior(Outcome.Interrupted, finalizers, callback, 0)

      case RunBlock(aio, oldStack, oldFinalizers) =>
        val blockCallback = asyncCallback { outcome =>
          self ! ContinueEvaluation(AIO.fromOutcome(outcome))
          runningBehavior(oldStack, oldFinalizers, callback)
        }
        evaluate(aio, ArrayStack[StackOp], ArrayStack[Finalizer], blockCallback)

      case unexpected =>
        log.warn(s"Received unexpected message $unexpected while in `runningBehavior`")
        Behaviors.same
    } .receiveSignal {
      case (_, Terminated(_)) => finalizingBehavior(Outcome.Interrupted, finalizers, callback, 0)
    }

  private def finalizingBehavior(
    result: Outcome[Any],
    finalizers: ArrayStack[Finalizer],
    callback: AsyncCallback,
    loopCount: Int
  ): RuntimeBehavior = Behaviors.receiveMessage {
    case ContinueFinalization => runFinalizers(result, finalizers, callback, loopCount)
    case CancellationRequested(replyTo) =>
      replyTo ! cancellable.markCancelled()
      Behaviors.same

    case AsyncResult(outcome) =>
      val asyncResult = Outcome.combineLeft(result, outcome)
      runFinalizers(asyncResult, finalizers, callback, loopCount)

    case RunBlock(aio, oldStack, oldFinalizers) =>
      val blockCallback = asyncCallback { outcome =>
        self ! ContinueEvaluation(AIO.fromOutcome(outcome))
        asyncFinalizingBehavior(result, Null[AIOCancellation], oldStack, oldFinalizers, callback, loopCount)
      }
      evaluate(aio, ArrayStack[StackOp], ArrayStack[Finalizer], blockCallback)

    case unexpected =>
      log.warn(s"Received unexpected message $unexpected while `finalizingBehavior`")
      Behaviors.same
  }

  private def asyncFinalizingBehavior(
    result: Outcome[Any],
    cancellation: AIOCancellation,
    opStack: ArrayStack[StackOp],
    finalizers: ArrayStack[Finalizer],
    callback: AsyncCallback,
    loopCount: Int
  ): RuntimeBehavior = Behaviors.receiveMessage {
    case ContinueFinalization => runFinalizers(result, finalizers, callback, loopCount)
    case CancellationRequested(replyTo) =>
      val didCancel = cancellable.markCancelled()
      replyTo ! didCancel
      if (didCancel) runCancellation(result, cancellation, finalizers, callback, loopCount)
      else           Behaviors.same

    case ContinueEvaluation(aio) =>
      import EvaluateFinalizerResult._
      evaluateFinalizers(aio, opStack, finalizers, loopCount) match {
        case Async(loopCount, onCan)    => asyncFinalizingBehavior(result, onCan, opStack, finalizers, callback, loopCount)
        case Paused(loopCount, opStack) => asyncFinalizingBehavior(result, cancellation, opStack, finalizers, callback, loopCount)
        case Sync(loopCount)            => runFinalizers(result, finalizers, callback, loopCount)
        case Failure(loopCount, error)  => runFinalizers(result.raiseError(error), finalizers, callback, loopCount)
        case Cancelled(loopCount)       => runCancellation(result, cancellation, finalizers, callback, loopCount)
      }

    case AsyncResult(outcome) =>
      val asyncResult = Outcome.combineLeft(result, outcome)
      runFinalizers(asyncResult, finalizers, callback, loopCount)

    case RunBlock(aio, oldStack, oldFinalizers) =>
      val blockCallback = asyncCallback { outcome =>
        self ! ContinueEvaluation(AIO.fromOutcome(outcome))
        asyncFinalizingBehavior(result, cancellation, oldStack, oldFinalizers, callback, loopCount)
      }
      evaluate(aio, ArrayStack[StackOp], ArrayStack[Finalizer], blockCallback)

    case unexpected =>
      log.warn(s"Received unexpected message $unexpected while in `asyncFinalizingBehavior`")
      Behaviors.same
  }

  @inline private def runCancellation(
    result: Outcome[Any],
    cancellation: AIOCancellation,
    finalizers: ArrayStack[Finalizer],
    callback: AsyncCallback,
    loopCount: Int
  ): RuntimeBehavior = {
    import EvaluateFinalizerResult._

    if (NotNull(cancellation)) {
      evaluateFinalizers(cancellation, ArrayStack[StackOp], finalizers, loopCount) match {
        case Async(loopCount, onCan)    => asyncFinalizingBehavior(result, onCan, ArrayStack[StackOp], finalizers, callback, loopCount)
        case Sync(loopCount)            => runFinalizers(result, finalizers, callback, loopCount)
        case Failure(loopCount, error)  => runFinalizers(result.raiseError(error), finalizers, callback, loopCount)
        case Paused(loopCount, opStack) => asyncFinalizingBehavior(result, Null[AIOCancellation], opStack, finalizers, callback, loopCount)
        case Cancelled(_)               => finalizingBehavior(result, finalizers, callback, loopCount)
      }
    }
    else
      finalizingBehavior(result, finalizers, callback, loopCount)
  }

  /* Evaluation */
  @inline private def nextErrorHandler(opStack: ArrayStack[StackOp]): StackOp = {
    var empty = opStack.isEmpty
    while(!empty) {
      val nextOp = opStack.pop()
      if (IsNull(nextOp)) empty = true
      else if (nextOp.hasErrHandler) return nextOp
    }
    Null[StackOp]
  }

  @tailrec private def runFinalizers(
    outcome: Outcome[Any],
    finalizers: ArrayStack[Finalizer],
    callback: AsyncCallback,
    loopCount: Int = 0
  ): RuntimeBehavior = {
    import EvaluateFinalizerResult._

    val nextFin = finalizers.pop()
    if (NotNull(nextFin)) {
      // Check loopCount
      if (loopCount > maxLoopCount) {
        finalizers.push(nextFin)
        self ! ContinueFinalization
        finalizingBehavior(outcome, finalizers, callback, loopCount)
      }
      else {
        var nextOutcome: Outcome[Any] = outcome
        val nextAio: AIOAny =
          try nextFin(nextOutcome)
          catch {
            case NonFatal(error) =>
              nextOutcome = nextOutcome.raiseError(error)
              AIO.Error(error)
          }
        val result = evaluateFinalizers(nextAio, ArrayStack[StackOp], ArrayStack[Finalizer], loopCount)
        result match {
          case Async(lc, onCan)    => asyncFinalizingBehavior(nextOutcome, onCan, ArrayStack[StackOp], finalizers, callback, lc)
          case Paused(lc, opStack) => asyncFinalizingBehavior(nextOutcome, Null[AIOCancellation], opStack, finalizers, callback, lc)
          case Failure(lc, error)  => runFinalizers(nextOutcome.raiseError(error), finalizers, callback, lc)
          case otherResult         => runFinalizers(nextOutcome, finalizers, callback, otherResult.loopCount)
        }
      }
    } else callback(outcome)
  }

  @inline private def handleTransform(aio: AIOAny, opStack: ArrayStack[StackOp]): AIOAny = {
    import AIO._

    val curAio = aio.asInstanceOf[Transform[Any, Any, _ <: Effect]]
    val nextAio = curAio.aio
    val bind = curAio.onSucc
    val onErr = curAio.onErr

    // Check next element first, to see if we can avoid the heap allocation
    (nextAio.id: @switch) match {
      case Identifiers.Value =>
        val aio2 = nextAio.asInstanceOf[Value[Any]]
        bind(aio2.value)

      case Identifiers.Eval =>
        val aio2 = nextAio.asInstanceOf[Eval[Any]]
        bind(aio2.expr())

      case Identifiers.Error =>
        val aio2 = nextAio.asInstanceOf[Error]
        onErr(aio2.error)

      case _ =>
        opStack.push(curAio)
        nextAio
    }
  }

  @inline private def handleFinalizer(aio: AIOAny, finalizers: ArrayStack[Finalizer]): AIOAny = {
    val curAio = aio.asInstanceOf[AIO.Finalize[Any, _ <: Effect]]
    finalizers.push(curAio.finalizer)
    curAio.aio
  }

  private def evaluate(
    toEval: AIOAny,
    opStack: ArrayStack[StackOp],
    finalizers: ArrayStack[Finalizer],
    callback: AsyncCallback
  ): RuntimeBehavior = {
    import AIO._

    var aio: AIOAny = toEval
    var done = false
    var loopCount = 0

    import AIO.{Identifiers => IDs}

    while(!done) {
      // Check cancellation
      if (cancellable.isCancelled) {
        return runFinalizers(Outcome.Interrupted, finalizers, callback, loopCount)
      }
      // Evaluate current `aio`
      try {
        val id = aio.id
        (id: @switch) match {
          case IDs.Transform =>
            aio = handleTransform(aio, opStack)

          case IDs.Value =>
            val curAio = aio.asInstanceOf[Value[Any]]
            if (!opStack.isEmpty) {
              val nextTransform = opStack.pop()
              aio = nextTransform.onSucc(curAio.value)
            }
            else
              return runFinalizers(Outcome.Success(curAio.value), finalizers, callback, loopCount)

          case IDs.Eval =>
            val curAio = aio.asInstanceOf[Eval[Any]]
            aio = AIO.Value(curAio.expr())

          case IDs.Error =>
            val curAio = aio.asInstanceOf[Error]
            if (!opStack.isEmpty) {
              val nextOp = nextErrorHandler(opStack)
              if (IsNull(nextOp))
                return runFinalizers(Outcome.Failure(curAio.error), finalizers, callback, loopCount)
              else
                aio = nextOp.onErr(curAio.error)
            } else {
              return runFinalizers(Outcome.Failure(curAio.error), finalizers, callback, loopCount)
            }

          case IDs.Suspend =>
            val curAio = aio.asInstanceOf[Suspend[Any, _ <: Effect]]
            aio = curAio.expr()

          case IDs.Block =>
            val curAio = aio.asInstanceOf[Block[Any, _ <: Effect]]
            self ! RunBlock(curAio.aio, opStack, finalizers)
            done = true

          case IDs.Finalize =>
            aio = handleFinalizer(aio, finalizers)

          case IDs.AsyncCont =>
            val curAio = aio.asInstanceOf[AsyncCont[Any, _ <: Effect]]
            val onCancelled = curAio.cont(akkaCallback.callback, cancellable)
            return asyncBehavior(onCancelled, opStack, finalizers, callback)

          case IDs.CurrentContext =>
            aio = AIO.pure(this.executionContext)

          case IDs.YieldEval =>
            self ! ContinueEvaluation(AIO.unit)
            done = true

          case IDs.CancelEval =>
            val _ignored = cancellable.markCancelled()
            runFinalizers(Outcome.Interrupted, finalizers, callback, loopCount)
        }
      } catch { case error if NonFatal(error) =>
        // Errors here are either a problem with the `SyncRuntime` or in user code
        aio = Error(error)
      }
      // Check auto-yield
      if (loopCount > maxLoopCount) {
        self ! ContinueEvaluation(aio)
        done = true
      }
      loopCount += 1
    }

    runningBehavior(opStack, finalizers, callback)
  }

  private def evaluateFinalizers(
    toEval: AIOAny,
    opStack: ArrayStack[StackOp],
    finalizers: ArrayStack[Finalizer],
    previousCount: Int
  ): EvaluateFinalizerResult = {
    import AIO._
    import EvaluateFinalizerResult._

    var loopCount = previousCount
    var aio: AIOAny = toEval
    var done = false

    import AIO.{Identifiers => IDs}

    while(true) {
      // Evaluate current `aio`
      try {
        val id = aio.id
        (id: @switch) match {
          case IDs.Transform =>
            aio = handleTransform(aio, opStack)

          case IDs.Value =>
            val curAio = aio.asInstanceOf[Value[Any]]
            if (!opStack.isEmpty) {
              val nextTransform = opStack.pop()
              aio = nextTransform.onSucc(curAio.value)
            } else return Sync(loopCount)

          case IDs.Eval =>
            val curAio = aio.asInstanceOf[Eval[Any]]
            aio = AIO.Value(curAio.expr())

          case IDs.Error =>
            val curAio = aio.asInstanceOf[Error]
            if (!opStack.isEmpty) {
              val nextOp = nextErrorHandler(opStack)
              if (IsNull(nextOp))
                return Failure(loopCount, curAio.error)
              else
                aio = nextOp.onErr(curAio.error)
            } else {
              return Failure(loopCount, curAio.error)
            }

          case IDs.Suspend =>
            val curAio = aio.asInstanceOf[Suspend[Any, _ <: Effect]]
            aio = curAio.expr()

          case IDs.Block =>
            val curAio = aio.asInstanceOf[Block[Any, _ <: Effect]]
            aio = curAio.aio

          case IDs.Finalize =>
            aio = handleFinalizer(aio, finalizers)

          case IDs.AsyncCont =>
            val curAio = aio.asInstanceOf[AsyncCont[Any, _ <: Effect]]
            val onCancelled = curAio.cont(akkaCallback.callback, cancellable)
            return Async(loopCount, onCancelled)

          case IDs.CurrentContext =>
            aio = AIO.pure(this.executionContext)

          case IDs.YieldEval =>
            self ! ContinueEvaluation(AIO.unit)
            done = true

          case IDs.CancelEval =>
            val _ignored = cancellable.markCancelled()
            return Cancelled(loopCount)
        }
      } catch { case error if NonFatal(error) =>
        // Errors here are either a problem with the `SyncRuntime` or in user code
        aio = Error(error)
      }
      // Check auto-yield
      if (loopCount > maxLoopCount) {
        self ! ContinueEvaluation(aio)
        return Paused(loopCount, opStack)
      }
      loopCount += 1
    }

    Sync(loopCount)
  }

}

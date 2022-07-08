package aio.runtime

import aio._
import aio.internal._

import scala.annotation.{switch, tailrec}
import scala.util.control.NonFatal

object SyncRuntime {
  import AIO.{Identifiers => IDs}
  import AIO.{
    Value,
    Error,
    Eval,
    Suspend,
    Block,
    Transform,
    Finalize
  }
  import Constants._

  private type StackOp   = Transform[Any, Any, _ <: Effect]
  private type Finalizer = Outcome[Any] => AIO[Unit, _ <: Effect]

  final def runSync[A](aio: AIO[A, Effect.Sync]): Outcome[A] = {
    val evalAio: AIO[Any, Effect.Sync] = aio.asInstanceOf[AIO[Any, Effect.Sync]]
    val result = evaluate(evalAio, ArrayStack[StackOp], ArrayStack[Finalizer], 0)
    result.asInstanceOf[Outcome[A]]
  }

  @tailrec private final def runFinalizers(
    finalizers: ArrayStack[Finalizer],
    result: Outcome[Any],
    finalizerDepth: Int
  ): Outcome[Any] = {
    val nextFin = finalizers.pop()
    if (NotNull(nextFin)) {
      // Check finalizer recursion depth
      if (finalizerDepth + 1 > finalizerRecursionMaxDepth)
        return Outcome.Failure(Errors.finalizerRecursionDepthExceeded)

      var nextResult: Outcome[Any] = result
      val nextAio: AIO[Any, _ <: Effect] =
        try nextFin(nextResult)
        catch {
          case NonFatal(error) =>
            nextResult = nextResult.raiseError(error)
            AIO.Error(error)
        }
      val finalizerResult = evaluate(nextAio, ArrayStack[StackOp], ArrayStack[Finalizer], finalizerDepth + 1)
      runFinalizers(finalizers, Outcome.resultOrError(nextResult, finalizerResult), finalizerDepth)
    } else result
  }

  @inline private final def runBlock(
    blockAio: AIO[Any, _ <: Effect],
    recursionDepth: Int
  ): AIO[Any, _ <: Effect] = {
    val result = evaluate(blockAio, ArrayStack[StackOp], ArrayStack[Finalizer], recursionDepth + 1)
    AIO.fromOutcome(result)
  }

  @inline private final def nextErrorHandler(opStack: ArrayStack[StackOp]): StackOp = {
    var empty = opStack.isEmpty
    while(!empty) {
      val nextOp = opStack.pop()
      if (IsNull(nextOp)) empty = true
      else if (nextOp.hasErrHandler) return nextOp
    }
    Null[StackOp]
  }

  private final def evaluate(
    toEval: AIO[Any, _ <: Effect],
    opStack: ArrayStack[StackOp],
    finalizers: ArrayStack[Finalizer],
    finalizerDepth: Int
  ): Outcome[Any] = {
    var aio: AIO[Any, _ <: Effect] = toEval
    var result: Outcome[Any] = Null[Outcome[Any]]

    // Check finalizer recursion depth
    if (finalizerDepth > finalizerRecursionMaxDepth)
      return Outcome.Failure(Errors.finalizerRecursionDepthExceeded)

    while(IsNull(result)) {
      try {
        val id = aio.id
        (id: @switch) match {
          case IDs.Transform =>
            val curAio = aio.asInstanceOf[Transform[Any, Any, _ <: Effect]]
            val nextAio = curAio.aio
            val bind = curAio.onSucc
            val onErr = curAio.onErr

            // Check next element first, to see if we can avoid the heap allocation
            (nextAio.id: @switch) match {
              case IDs.Value =>
                val aio2 = nextAio.asInstanceOf[Value[Any]]
                aio = bind(aio2.value)

              case IDs.Eval =>
                val aio2 = nextAio.asInstanceOf[Eval[Any]]
                aio = bind(aio2.expr())

              case IDs.Error =>
                val aio2 = nextAio.asInstanceOf[Error]
                aio = onErr(aio2.error)

              case _ =>
                opStack.push(curAio)
                aio = nextAio
            }

          case IDs.Value =>
            val curAio = aio.asInstanceOf[Value[Any]]
            if (!opStack.isEmpty) {
              val nextTransform = opStack.pop()
              aio = nextTransform.onSucc(curAio.value)
            } else {
              result = runFinalizers(finalizers, Outcome.Success(curAio.value), finalizerDepth)
            }

          case IDs.Eval =>
            val curAio = aio.asInstanceOf[Eval[Any]]
            aio = AIO.Value(curAio.expr())

          case IDs.Error =>
            val curAio = aio.asInstanceOf[Error]
            if (!opStack.isEmpty) {
              val nextOp = nextErrorHandler(opStack)
              if (IsNull(nextOp)) {
                result = runFinalizers(finalizers, Outcome.Failure(curAio.error), finalizerDepth)
              } else {
                aio = nextOp.onErr(curAio.error)
              }
            } else {
              result = runFinalizers(finalizers, Outcome.Failure(curAio.error), finalizerDepth)
            }

          case IDs.Suspend =>
            val curAio = aio.asInstanceOf[Suspend[Any, _ <: Effect]]
            aio = curAio.expr()

          case IDs.Block =>
            val curAio = aio.asInstanceOf[Block[Any, _ <: Effect]]
            aio = runBlock(curAio.aio, finalizerDepth)

          case IDs.Finalize =>
            val curAio = aio.asInstanceOf[Finalize[Any, _ <: Effect]]
            finalizers.push(curAio.finalizer)
            aio = curAio.aio
        }
      } catch { case error if NonFatal(error) =>
        // Errors here are either a problem with the `SyncRuntime` or in user code
        aio = Error(error)
      }
    }

    result
  }

}

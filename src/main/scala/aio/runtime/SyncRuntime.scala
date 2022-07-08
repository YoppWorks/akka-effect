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

  private type StackOp   = Transform[Any, Any, _ <: Effect]
  private type Finalizer = Outcome[Any] => AIO[Unit, _ <: Effect]

  final def runSync[A](aio: AIO[A, Effect.Sync]): Outcome[A] = {
    val evalAio: AIO[Any, Effect.Sync] = aio.asInstanceOf[AIO[Any, Effect.Sync]]
    val result = evaluate(evalAio, ArrayStack[StackOp], ArrayStack[Finalizer])
    result.asInstanceOf[Outcome[A]]
  }

  @inline private final def evalExpr[A, E <: Effect](f: () => AIO[A, E]): AIO[A, E] =
    try f() catch { case NonFatal(error) => AIO.Error(error).asInstanceOf[AIO[A, E]] }

  @tailrec private final def runFinalizers(finalizers: ArrayStack[Finalizer], result: Outcome[Any]): Outcome[Any] = {
    val nextFin = finalizers.pop()
    if (NotNull(nextFin)) {
      var nextResult: Outcome[Any] = result
      val nextAio: AIO[Any, _ <: Effect] =
        try nextFin(nextResult)
        catch { case NonFatal(error) =>
          nextResult = nextResult.raiseError(error)
          AIO.Error(error)
        }
      val finalizerResult = evaluate(nextAio, ArrayStack[StackOp], ArrayStack[Finalizer])
      runFinalizers(finalizers, Outcome.resultOrError(nextResult, finalizerResult))
    } else result
  }

  @inline private final def runBlock(
    aio: AIO[Any, _ <: Effect],
    finalizers: ArrayStack[Finalizer],
    opStack: ArrayStack[StackOp]
  ): Outcome[Any] = {
    val result = evaluate(aio, ArrayStack[StackOp], ArrayStack[Finalizer])
    evaluate(AIO.fromOutcome(result), opStack, finalizers)
  }

  @tailrec private final def evaluate(
    aio: AIO[Any, _ <: Effect],
    opStack: ArrayStack[StackOp],
    finalizers: ArrayStack[Finalizer],
  ): Outcome[Any] =
    (aio.id: @switch) match {
      case IDs.Value =>
        val curAio = aio.asInstanceOf[Value[Any]]
        val value = curAio.value
        val curResult = Outcome.Success(value)
        if (opStack.isEmpty) runFinalizers(finalizers, curResult)
        else evaluate(opStack.pop().onSucc(value), opStack, finalizers)

      case IDs.Error =>
        val curAio = aio.asInstanceOf[Error]
        val error = curAio.error
        val curResult = Outcome.Failure(error)
        if (opStack.isEmpty) runFinalizers(finalizers, curResult)
        else {
          val nextOp = nextErrorHandler(opStack)
          if (IsNull(nextOp)) runFinalizers(finalizers, curResult)
          else evaluate(nextOp.onErr(error), opStack, finalizers)
        }

      case IDs.Eval =>
        val curAio = aio.asInstanceOf[Eval[Any]]
        evaluate(evalExpr(() => AIO.Value(curAio.expr())), opStack, finalizers)

      case IDs.Suspend =>
        val curAio = aio.asInstanceOf[Suspend[Any, _ <: Effect]]
        evaluate(evalExpr(curAio.expr), opStack, finalizers)

      case IDs.Block =>
        val curAio = aio.asInstanceOf[Block[Any, _ <: Effect]]
        runBlock(curAio.aio, finalizers, opStack)

      case IDs.Finalize =>
        val curAio = aio.asInstanceOf[Finalize[Any, _ <: Effect]]
        finalizers.push(curAio.finalizer); evaluate(curAio.aio, opStack, finalizers)

      case IDs.Transform =>
        val curAio = aio.asInstanceOf[Transform[Any, Any, _ <: Effect]]
        opStack.push(curAio); evaluate(curAio.aio, opStack, finalizers)
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

}

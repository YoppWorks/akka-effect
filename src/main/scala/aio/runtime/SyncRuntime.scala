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
    Transform,
    Finalize
  }

  private type StackOp   = Transform[Any, Any, _ <: Effect]
  private type Finalizer = Outcome[Any] => AIO[Unit, _ <: Effect]

  final def runSync[A](aio: AIO[A, Effect.Sync]): Outcome[A] = {
    val evalAio: AIO[Any, Effect.Sync] = aio.asInstanceOf[AIO[Any, Effect.Sync]]
    val result = evaluate(evalAio, ArrayStack[StackOp], ArrayStack[Finalizer], Null[Outcome[Any]])
    result.asInstanceOf[Outcome[A]]
  }

  @inline private final def evalExpr[A, E <: Effect](f: () => AIO[A, E]): AIO[A, E] =
    try f() catch { case NonFatal(error) => AIO.Error(error).asInstanceOf[AIO[A, E]] }

  @tailrec private final def evaluate(
    aio: AIO[Any, _ <: Effect],
    opStack: ArrayStack[StackOp],
    finalizers: ArrayStack[Finalizer],
    result: Outcome[Any]
  ): Outcome[Any] =
    (aio.id: @switch) match {
      case IDs.Value =>
        val curAio = aio.asInstanceOf[Value[Any]]
        val value = curAio.value
        if (opStack.isEmpty) {
          val nextFin = finalizers.pop()
          val curResult = if (IsNull(result)) Outcome.Success(value) else result
          if (NotNull(nextFin)) {
            var res1: Outcome[Any] = curResult
            val nextAio =
              try nextFin(res1).asInstanceOf[AIO[Unit, Effect.Sync]]
              catch { case NonFatal(error) =>
                res1 = res1.raiseError(error)
                AIO.Error(error)
              }

            evaluate(nextAio, opStack, finalizers, res1)
          }
          else curResult
        }
        else evaluate(opStack.pop().onSucc(value), opStack, finalizers, result)

      case IDs.Error =>
        val curAio = aio.asInstanceOf[Error]
        val error = curAio.error
        if (opStack.isEmpty) {
          val nextFin = finalizers.pop()
          val curResult = if (IsNull(result)) Outcome.Failure(error) else result.raiseError(error)
          if (NotNull(nextFin)) {
            var res1: Outcome[Any] = curResult
            val nextAio =
              try nextFin(res1).asInstanceOf[AIO[Unit, Effect.Sync]]
              catch { case NonFatal(error) =>
                res1 = res1.raiseError(error)
                AIO.Error(error)
              }

            evaluate(nextAio, opStack, finalizers, res1)
          }
          else curResult
        }
        else {
          val nextOp = nextErrorHandler(opStack)
          if (IsNull(nextOp)) {
            val nextFin = finalizers.pop()
            val curResult = if (IsNull(result)) Outcome.Failure(error) else result.raiseError(error)
            if (NotNull(nextFin)) {
              var res1: Outcome[Any] = curResult
              val nextAio =
                try nextFin(res1).asInstanceOf[AIO[Unit, Effect.Sync]]
                catch { case NonFatal(error) =>
                  res1 = res1.raiseError(error)
                  AIO.Error(error)
                }

              evaluate(nextAio, opStack, finalizers, res1)
            }
            else curResult
          }
          else evaluate(nextOp.onErr(error), opStack, finalizers, result)
        }

      case IDs.Eval =>
        val curAio = aio.asInstanceOf[Eval[Any]]
        evaluate(evalExpr(() => AIO.Value(curAio.expr())), opStack, finalizers, result)

      case IDs.Suspend =>
        val curAio = aio.asInstanceOf[Suspend[Any, _ <: Effect]]
        evaluate(evalExpr(curAio.expr), opStack, finalizers, result)

      case IDs.Finalize =>
        val curAio = aio.asInstanceOf[Finalize[Any, _ <: Effect]]
        finalizers.push(curAio.finalizer); evaluate(curAio.aio, opStack, finalizers, result)

      case IDs.Transform =>
        val curAio = aio.asInstanceOf[Transform[Any, Any, _ <: Effect]]
        opStack.push(curAio); evaluate(curAio.aio, opStack, finalizers, result)
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

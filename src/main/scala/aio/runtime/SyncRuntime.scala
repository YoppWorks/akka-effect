package aio.runtime

import aio._
import aio.internal._

import scala.annotation.tailrec
import scala.util.control.NonFatal

object SyncRuntime {
  import Effect._
  import AIO.{
    Value,
    Error,
    Eval,
    Suspend,
    Transform,
    Finalize
  }

  private type StackOp   = Transform[Any, Any, _ <: Effect]
  private type Finalizer = Finalize[Any, _ <: Effect]

  @inline private final def evalExpr[A, E <: Effect](f: () => AIO[A, E]): AIO[A, E] =
    try f() catch { case NonFatal(error) => AIO.Error(error).asInstanceOf[AIO[A, E]] }

  @tailrec def evaluate(
    aio: AIO[Any, _ <: Effect],
    opStack: ArrayStack[StackOp],
    finalizers: ArrayStack[Finalizer]
  ): Outcome[Any] =
    aio match {
      case Value(value)   =>  if (opStack.isEmpty) finalization(Outcome.Success(value), finalizers)
                              else evaluate(opStack.pop().onSucc(value), opStack, finalizers)

      case Error(error)   =>  if (opStack.isEmpty) finalization(Outcome.Failure(error), finalizers)
                              else {
                                val nextOp = nextErrorHandler(opStack)
                                if (IsNull(nextOp)) finalization(Outcome.Failure(error), finalizers)
                                else evaluate(nextOp.onErr(error), opStack, finalizers)
                              }

      case Eval(expr)     =>  evaluate(evalExpr(() => AIO.Value(expr)), opStack, finalizers)

      case Suspend(expr)  =>  evaluate(evalExpr(expr), opStack, finalizers)

      case op @ Transform(aio, _, _, _) => opStack.push(op); evaluate(aio, opStack, finalizers)

      case fin @ Finalize(aio, _)       => finalizers.push(fin.asInstanceOf[Finalizer]); evaluate(aio, opStack, finalizers)
    }

  private def finalization[A](outcome: Outcome[Any], finalizers: ArrayStack[Finalizer]): Outcome[A] = ???

  private def nextErrorHandler(opStack: ArrayStack[StackOp]): StackOp = {
    var empty = opStack.isEmpty
    while(!empty) {
      val nextOp = opStack.pop()
      if (IsNull(nextOp)) empty = true
      else if (nextOp.hasErrHandler) return nextOp
    }
    Null[StackOp]
  }

}

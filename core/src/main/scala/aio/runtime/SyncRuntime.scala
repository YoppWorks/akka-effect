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
    // val result = evaluate2(evalAio)
    result.asInstanceOf[Outcome[A]]
  }

  @tailrec private final def runFinalizers(
    finalizers: ArrayStack[Finalizer],
    result: Outcome[Any],
    recursionDepth: Int
  ): Outcome[Any] = {
    val nextFin = finalizers.pop()
    if (nextFin != null) {
      if (recursionDepth > finalizerRecursionMaxDepth) Outcome.Failure(Errors.finalizerRecursionDepthExceeded)
      else {
        var nextResult: Outcome[Any] = result
        val nextAio: AIO[Any, _ <: Effect] =
          try nextFin(nextResult)
          catch {
            case NonFatal(error) =>
              nextResult = nextResult.raiseError(error)
              AIO.Error(error)
          }
        val finalizerResult = evaluate(nextAio, ArrayStack[StackOp], ArrayStack[Finalizer], recursionDepth + 1)
        runFinalizers(finalizers, Outcome.resultOrError(nextResult, finalizerResult), recursionDepth)
      }
    } else result
  }

  @inline private final def runBlock(
    aio: AIO[Any, _ <: Effect],
    finalizers: ArrayStack[Finalizer],
    opStack: ArrayStack[StackOp],
    recursionDepth: Int
  ): Outcome[Any] = {
    val result = evaluate(aio, ArrayStack[StackOp], ArrayStack[Finalizer], recursionDepth)
    evaluate(AIO.fromOutcome(result), opStack, finalizers, recursionDepth)
  }

  @tailrec private final def evaluate(
    aio: AIO[Any, _ <: Effect],
    opStack: ArrayStack[StackOp],
    finalizers: ArrayStack[Finalizer],
    finalizerRecursionDepth: Int
  ): Outcome[Any] = {
    (aio.id: @switch) match {
      case IDs.Transform =>
        val curAio = aio.asInstanceOf[Transform[Any, Any, _ <: Effect]]
        val nextAio = curAio.aio

        // Check next element first, to see if we can avoid the heap allocation
        (nextAio.id: @switch) match {
          case IDs.Value =>
            val aio2 = nextAio.asInstanceOf[Value[Any]]
            evaluate(curAio.onSucc(aio2.value), opStack, finalizers, finalizerRecursionDepth)

          case IDs.Eval =>
            val aio2 = nextAio.asInstanceOf[Eval[Any]]
            val result = try curAio.onSucc(aio2.expr()) catch { case NonFatal(error) => Error(error) }
            evaluate(result, opStack, finalizers, finalizerRecursionDepth)

          case IDs.Error =>
            val aio2 = nextAio.asInstanceOf[Error]
            evaluate(curAio.onErr(aio2.error), opStack, finalizers, finalizerRecursionDepth)

          case _ =>
            opStack.push(curAio)
            evaluate(nextAio, opStack, finalizers, finalizerRecursionDepth)
        }

      case IDs.Value =>
        val curAio = aio.asInstanceOf[Value[Any]]
        if (opStack.isEmpty) runFinalizers(finalizers, Outcome.Success(curAio.value), finalizerRecursionDepth)
        else {
          val nextOp = opStack.pop()
          evaluate(nextOp.onSucc(curAio.value), opStack, finalizers, finalizerRecursionDepth)
        }

      case IDs.Eval =>
        val curAio = aio.asInstanceOf[Eval[Any]]
        val nextAio = try AIO.Value(curAio.expr()) catch { case NonFatal(error) => Error(error) }
        evaluate(nextAio, opStack, finalizers, finalizerRecursionDepth)

      case IDs.Error =>
        val curAio = aio.asInstanceOf[Error]
        if (opStack.isEmpty) runFinalizers(finalizers, Outcome.Failure(curAio.error), finalizerRecursionDepth)
        else {
          val nextOp = nextErrorHandler(opStack)
          if (IsNull(nextOp)) runFinalizers(finalizers, Outcome.Failure(curAio.error), finalizerRecursionDepth)
          else evaluate(nextOp.onErr(curAio.error), opStack, finalizers, finalizerRecursionDepth)
        }

      case IDs.Suspend =>
        val curAio = aio.asInstanceOf[Suspend[Any, _ <: Effect]]
        val nextAio = try curAio.expr() catch { case NonFatal(error) => Error(error) }
        evaluate(nextAio, opStack, finalizers, finalizerRecursionDepth)

      case IDs.Block =>
        val curAio = aio.asInstanceOf[Block[Any, _ <: Effect]]
        val nextAio = curAio.aio
        runBlock(nextAio, finalizers, opStack, finalizerRecursionDepth)

      case IDs.Finalize =>
        val curAio = aio.asInstanceOf[Finalize[Any, _ <: Effect]]
        finalizers.push(curAio.finalizer)
        evaluate(curAio.aio, opStack, finalizers, finalizerRecursionDepth)
    }
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

  private final def evaluate2(toEval: AIO[Any, _ <: Effect]): Outcome[Any] = {
    val opStack = ArrayStack[StackOp]
    val finalizers = ArrayStack[Finalizer]

    var aio: AIO[Any, _ <: Effect] = toEval
    var result: Outcome[Any] = Null[Outcome[Any]]

    while(result == null) {
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
              result = runFinalizers(finalizers, Outcome.Success(curAio.value), 0)
            }

          case IDs.Eval =>
            val curAio = aio.asInstanceOf[Eval[Any]]
            aio = AIO.Value(curAio.expr())

          case IDs.Error =>
            val curAio = aio.asInstanceOf[Error]
            if (!opStack.isEmpty) {
              val nextOp = nextErrorHandler(opStack)
              if (IsNull(nextOp)) {
                result = runFinalizers(finalizers, Outcome.Failure(curAio.error), 0)
              } else {
                aio = nextOp.onErr(curAio.error)
              }
            } else {
              result = runFinalizers(finalizers, Outcome.Failure(curAio.error), 0)
            }

          case IDs.Suspend =>
            val curAio = aio.asInstanceOf[Suspend[Any, _ <: Effect]]
            aio = curAio.expr()

          case IDs.Block =>
            val curAio = aio.asInstanceOf[Block[Any, _ <: Effect]]
            runBlock(curAio.aio, finalizers, opStack, 0)

          case IDs.Finalize =>
            val curAio = aio.asInstanceOf[Finalize[Any, _ <: Effect]]
            finalizers.push(curAio.finalizer)
            aio = curAio.aio
        }
      } catch { case error if NonFatal(error) =>
        aio = Error(error)
      }
    }

    result
  }

}

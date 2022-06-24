# Akka-Effect

**Akka-Effect (AIO)** is a modern, fiber based, effect system built on the Akka platform, implemented using Akka actors. **AIO** also supports deep integration with Akka itself and the Akka ecosystem, as well as (optional) integrations with [Cats Effect](https://typelevel.org/cats-effect) and the [Typelevel](https://typelevel.org/) ecosystem.

# Effect Systems

_TODO_ - Write a brief summary of effect systems.

Basically: `def main(args): AIO[Unit, Total]`, and the application/microservice runs in the `AIO` monad.
See _Haskell_'s `IO` monad, or `IOApp` from Cats Effect.

## Features

_TODO_ - Add descriptions to each feature.

### Effect Tracking
### Finalizers
### Forking/Racing
### Interruption/Uninterruptible Regions
### Asynchronous / CPS
### Safe Resource Acquisition and Release
### Yield/Cede
### Fine-grained Concurrency
### Performant!

# Core Algebra

### `Effect`

The following a closed-algebra (ADT) of the various effect capabilities tracked for each `AIO`:

> [Pure] --> [Sync] --> [Async] --> [Concurrent] --> [Total]

#### Pure

`Pure` represents literal _outcomes_ (like a value or an error). Pure effects could be safely pattern matched and do not
require any sort of evaluation / stack.

#### Sync extends Pure

`Sync` represents an effect chain that _can_ be synchronously evaluated via a stack to produce an outcome.
Synchronous effects also introduce the notion of _finalization_, being an effect which is **always** evaluated
after the evalution of the effect chain (regardless of the outcome). _Note:_ `Sync` _can_ also be evaluated 
concurrently (e.g. via `fork`) as well as interrupted (e.g. via adding `AIO.cancel` into the chain or using `unsafeRunCancelable()`), but doing so makes them _asynchronous_.

#### Async extends Sync, Pure

`Async` extends `Sync` by adding the notion of asynchronicity and interruptiblity. Asynchronous effects
can be _yielded_ (via `AIO.yieldEval`), cancelled (via `AIO.cancel`), returning an `Outcome.cancelled` value to a callback, etc. They also might not follow a simple linear execution flow (similar to CPS or `async/await` type patterns), and although
interruption _might_ start concurrently to evaluation, it will always finish _after_ evaluation is interrupted.
`Async` effects can be forked (via `fork()`) as well, but forking makes the effect chain _concurrent_.

#### Concurrent extends Concurrent, Async, Sync, Pure

`Concurrent` extends `Async` to include the notion that effects might be evaluated concurrently (or in parallel).
Concurrency also means that interruption (via `AIO.cancel` or `Fiber.join`) might occur (_and complete_) concurrently
with evaluation of the effect chain, which includes running finalizers.

#### Total extends Concurrent, Async, Sync, Pure
_Note:_ An `AIO` with the **Total** effect type is basically telling you it can do whatever it wants, _including_ ignoring
interruption! It is an indiction that the result/outcome of the evaluation of the effect chain might need to be handled
outside the context of the `AIO` machinery (i.e. at the JVM/language level).

### `AIO[+A, -F]`

The following is an outline of the core algebra of the **Akka-Effect** free monad:

#### Value[*A*, *Pure*]
`Value` contains a pure value (also known as `point`) or the result of a successful operation in the algebra. A value needs no further evaluation.

#### Error[*Nothing*, *Pure*]
`Failure` contains a `Throwable` type error/exception, representing the functional equivalent of `throw`.
Failures can be handled by further effects, handled by user code, or (_unsafely!_) thrown into the JVM runtime.

#### Transform[*A*, *B*, *Sync*]
`Transform` is a functional operation which transforms one `AIO[A, _ <: Sync]` into another `AIO[B, _ <: Sync]` with two functions: the first of which is a _Kleisli_ arrow from `A => AIO[B, _ <: Sync]`, and the second of which allows for error recovery via `Throwable => AIO[B, _ <: Sync]`.

This transformation facilitates the implementation of several of the monadic operations available for `AIO`, including: `ap`, `map`, `flatMap`, `recover` and `recoverWith`.

#### FinalizeCase[*A*, *Sync*]
`FinalizeCase` allows for a _finalizer_ to be set in the execution stack of the `AIO` interpreter, which will upon completion of evaluation of the predecessor operations, *always* be executed. The finalizer function signature of `Outcome[A] => AIO[Unit, Async]` allows for the finalizer to be aware of the outcome of the evaluation of the previous operations, being either: `A` (success), `Throwable` (error), or `Interrupted` (interruption or cancellation).

This is the functional equivalent of `finally { finalizer() }` in a 
>  `try { ... } finally { finalizer() }`
>
expression, and provides the same semantic guarantees.

**TODO** - fill in the rest, several more to add...

# Operations

**TODO** - Fill in the rest and add descriptions

### Constructors and Instances (`AIO` companion)
 - `unit: AIO[Unit, Pure]` - The no-op (_unit_) value, lifted into an `AIO` (constant value)
 - `never: AIO[Nothing, Async]` - An `AIO` effect which never completes (but may be interrupted). Useful for services and
    racing tasks.
 - `cancel: AIO[Nothing, Async]` - An `AIO` effect which causes interruption/cancellation of the evaluation of an effect
    chain. _Note:_ **finalizers** are still executed as part of interruption/cancellation!

 - `value[A](a: A): AIO[A, Pure]` - Lifts a pure value (also known as `point`) into the `AIO` context.
 - `error(error: Throwable): AIO[Nothing, Pure]` - Lifts an error (`Throwable`) into the `AIO` context. Semantically
    equivalent to throwing an exception (i.e. `throw new Exception(...)`)

 - `eval[A](expr: => A): AIO[A, Sync]` - Lazyily evaluates an _expression_ (during `AIO` evaluation), catching any
    thrown errors/exceptions, or producting a `value` of `A`.
 - `apply[A](expr => A): AIO[A, Sync]` - An alias for `eval`.
 - `suspend[A, E <: Effect](expr => AIO[A, E]): AIO[A, E]`
 - `fromTry[A]/fromEither[Throwable, A]/fromOutcome[A]: AIO[A, Sync]`

 - `fromCompletableFuture[A](cfExpr: => CompletableFuture[A]): AIO[A, Async]`
 - `fromFuture[A](futureExpr: => Future[A]): AIO[A, Concurrent]`
 - `yieldEval: AIO[Unit, Async]`
 - `using[R, A, Eff](acquire: AIO[R, E1])(use: R => AIO[A, E2])(release: (R, ErrorCase) => AIO[Unit, Async]): AIO[A, Eff]`

 - `asyncInterruptible[A](cont: (Cancellation, Outcome[A] => Unit) => AIO[Unit, Async]): AIO[A, Async]`
 - `concurrent[A](cont: (Cancellation, Outcome[A] => Unit) => AIO[Unit, Concurrent]): AIO[A, Concurrent]`
 - `uninterruptible[A](cont: (Cancellation, Outcome[A] => Unit) => Unit): AIO[A, Total]` - **Here be dragons!**

 - `sleep(duration: FiniteDuration): AIO[Unit, Concurrent]`
 - `race[A, B](lhs: AIO[A, _ >: Concurrent], rhs: AIO[B, _ >: Concurrent]): AIO[Either[A, B], Concurrent]`
 - `runBoth[A, B](lhs: AIO[A, _ >: Concurrent], rhs: AIO[B, _ >: Concurrent]): AIO[(A, B), Concurrent]`
 - `runParallel[A](count: Int)(seq: Seq[AIO[A, _ >: Concurrent]]): AIO[Seq[A], Concurrent]`
 - `runParallelAll(seq: Seq[AIO[A, _ >: Concurrent]]): AIO[Seq[A], Concurrent]`

 - **...**

### For `AIO[A, Effect]`
 - `map[B](f: A => B): AIO[B, Effect]`
 - `as[B](b: => B): AIO[B, Effect]` - An alias for `map[B](_ => b)`, which ignores/discards the current `a` value 
    (if one has been successfully created).
 - `asUnit: AIO[Unit, Effect]` - An alias for `map(_ => ())` (or `as[Unit]( () )`)

 - `ap[B, Eff2](f: AIO[A => B, Eff2]): AIO[B, Effect | Eff2]`
 - `flatMap[B, Eff2](f: A => AIO[B, Eff2]): AIO[B, Effect | Eff2]`
 - `sideEffect[Eff2 >: Effect](f: A => AIO[Unit, Eff2]): AIO[A, Effect]` - Like `flatMap`, evaluates a _bind_ function to
    evaluate its effects, but ignores any (successful) results of the intermediate `AIO`. _Note:_ errors/exceptions **and**
    interruption/cancellation are still propagated from the side-effectful `AIO` into the final outcome.

 - `recover[B](f: Throwable => B): AIO[B, Effect]`
 - `recoverWith[B, Eff2 <: Total](f: Throwable => AIO[B, Eff2]): AIO[B, Effect | Eff2]`
 - `transform[B](ok: A => B)(nok: Throwable => B): AIO[B, Effect]`
 - `transformWith[B, Eff2 <: Total](ok: A => AIO[B, Eff2])(nok: Throwable => AIO[B, Eff2]): AIO[B, Effect | Eff2]`

 - `finalize(f: Outcome[A] => AIO[Unit, Async]): AIO[B, Effect | Async]`
 - `onInterrupted[E2 >: Async](fin: AIO[Unit, E2]): AIO[A, (Effect | E2) >: Async]`
 - `onError[E2 >: Async](f: Throwable => AIO[Unit, E2]): AIO[A, (Effect | E2) >: Async]`

 - `fork(): AIO[Fiber[A, Effect], Concurrent]` - Forks execution of this `AIO` into an new light-weight `fiber`. This fiber
    can then be joined (via `join`) to await the `outcome` of the fiber, or it can interrupted (via `interrupt`), which will
    signal termination/cancellation of the fiber (simliar to thread interruption). _Note:_ fibers can be evaluating
    `uninterruptible` regions of an `AIO` effect chain, such that they cannot be interrupted until that non-interruptible
    region is exited. As such, **it is possible to create non-interruptible and non-terminating fibers**, so caution must be
    taken with non-interruptible regions.
 - `forkOn(dispatcher: Dispatcher): AIO[Fiber[A, Effect], Concurrent]` - Identical to `fork`, only allows selecting which
    [Akka Dispatcher](https://doc.akka.io/docs/akka/current/typed/dispatchers.html) the `fiber` will execute on.
 - `uninterruptible: AIO[A, Async]` - Marks the entire prior `AIO` effect chain as _uninterruptible_, meaning it will not be
    eligible for interruption/cancellation. Subsequent effects after this chain, i.e. in the next `flatMap` bind, **will**
    still be eligible for interruption like normal. _Note:_ the effect type of this operator is _Async_ because interruption/
    cancellation is an inheritantly asynchronous operation.
 - `interruptible: AIO[A, Async]` - Marks the entire prior `AIO` effect chain as _interruptible_, meaning it **will** be
    eligible for interruption/cancellation. _Note:_ (almost) all `AIO` operations are interruptible by default, so this
    operator is _only_ needed for nesting interruptible regions within a larger _uninterruptible_ region.
 - **...**

### For evaluation of `AIO[A, E]`

 - `toOutcome: AIO[Outcome[A], E]`
 - `unsafeRunSync(): A` - **here be dragons!**
 - `unsafeRunAsync(cb: Outcome[A] => Unit): Unit`
 - `unsafeRunAndForget(): Unit`
 - `unsafeToFuture(): Future[A]`
 - `unsafeRunCancelable(): Cancellable`


### For `Fiber[A, Effect]`
 - `join: AIO[A, Effect]`
 - `joinOutcome: AIO[Outcome[A], Effect]`
 - `interrupt: AIO[Unit, Effect]`

# Implementation

**TODO**

Effect tracking allows for:
 - Any effects which are < `Async` can be evaluated using a speedy, hand-optimized synchronous interpreter.
 - Any effects which are >= `Async` require spinning up an Actor to run the full interpreter/evaluation loop.

Notes:
 - Implement a _Runtime_, simliar to what ZIO, Cats Effect, and Monix Task have (only using Actors/ActorSystem).
 - Safe and clear effect tracking via types (in `AIO[+A, +F <: Effect]` type signature)
 - Synchronous evaluation loop
 - (Typed) actor based fiber implementation
 - Actor for state synchronization
 - Fork = spawn new actor
 - ActorSystem _is_ the fiber supervisor
 - How to handle `forkOn(ec: ExecutionContext)`? Possibly a new (custom) Actor Dispatcher?

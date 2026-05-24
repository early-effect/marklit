# ZIO Service Pattern Example

A worked recipe: using ZIO's service pattern (ZLayer) inside marklit code
blocks via the `marklit:zio-app` modifier and named scopes
(`id=`/`extends=`). For the scope mechanics themselves, see
[scopes-and-versions.md](scopes-and-versions.md).

## Basic ZIO Effect

With the `zio-app` modifier, your code becomes the body of `ZIOAppDefault.run` - no boilerplate needed:

```scala marklit:zio-app
//> using dep dev.zio::zio:2.1.25

for
  _ <- Console.printLine("Hello from ZIO!")
  _ <- Console.printLine("ZIO effects just work!")
yield ()
```

## Service Pattern with Layers

ZIO's service pattern lets you define services as traits and provide implementations via layers.

### Define a Service

```scala marklit:zio-app,id=zio-services
//> using dep dev.zio::zio:2.1.25

// Define the service interface
trait UserRepository:
  def findUser(id: Int): Task[Option[String]]
  def saveUser(id: Int, name: String): Task[Unit]

// Companion object with accessor methods
object UserRepository:
  def findUser(id: Int): ZIO[UserRepository, Throwable, Option[String]] =
    ZIO.serviceWithZIO[UserRepository](_.findUser(id))
  
  def saveUser(id: Int, name: String): ZIO[UserRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[UserRepository](_.saveUser(id, name))

ZIO.unit // this block just defines the service
```

### Implement the Service

```scala marklit:zio-app,extends=zio-services,id=zio-impl
// In-memory implementation
case class InMemoryUserRepository(ref: Ref[Map[Int, String]]) extends UserRepository:
  def findUser(id: Int): Task[Option[String]] =
    ref.get.map(_.get(id))
  
  def saveUser(id: Int, name: String): Task[Unit] =
    ref.update(_ + (id -> name))

object InMemoryUserRepository:
  val layer: ZLayer[Any, Nothing, UserRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[Int, String]).map(InMemoryUserRepository(_))
    )

ZIO.unit // this block just defines the implementation
```

### Use the Service

```scala marklit:zio-app,extends=zio-impl
// Program that uses the service - just provide the layer
val userProgram = for
  _     <- UserRepository.saveUser(1, "Alice")
  _     <- UserRepository.saveUser(2, "Bob")
  user1 <- UserRepository.findUser(1)
  user2 <- UserRepository.findUser(2)
  user3 <- UserRepository.findUser(3)
  _     <- Console.printLine(s"User 1: $user1")
  _     <- Console.printLine(s"User 2: $user2")
  _     <- Console.printLine(s"User 3: $user3")
yield ()

userProgram.provide(InMemoryUserRepository.layer)
```

## Multiple Services

You can compose multiple services together:

```scala marklit:zio-app
//> using dep dev.zio::zio:2.1.25

// Logger service
trait Logger:
  def log(msg: String): UIO[Unit]

object Logger:
  def log(msg: String): URIO[Logger, Unit] =
    ZIO.serviceWithZIO[Logger](_.log(msg))
  
  val console: ZLayer[Any, Nothing, Logger] =
    ZLayer.succeed(new Logger:
      def log(msg: String): UIO[Unit] = 
        Console.printLine(s"[LOG] $msg").orDie
    )

// Counter service
trait Counter:
  def increment: UIO[Int]
  def get: UIO[Int]

object Counter:
  def increment: URIO[Counter, Int] =
    ZIO.serviceWithZIO[Counter](_.increment)
  
  def get: URIO[Counter, Int] =
    ZIO.serviceWithZIO[Counter](_.get)
  
  val live: ZLayer[Any, Nothing, Counter] =
    ZLayer.fromZIO(
      Ref.make(0).map(ref => new Counter:
        def increment: UIO[Int] = ref.updateAndGet(_ + 1)
        def get: UIO[Int] = ref.get
      )
    )

// Compose services - clean ZIO code, no boilerplate
val program = for
  _ <- Logger.log("Starting...")
  _ <- Counter.increment
  _ <- Counter.increment
  _ <- Counter.increment
  n <- Counter.get
  _ <- Logger.log(s"Counter is now: $n")
  _ <- Console.printLine(s"Final result: $n")
yield ()

program.provide(Logger.console ++ Counter.live)
```

## Ref for State

ZIO's `Ref` provides safe concurrent state:

```scala marklit:zio-app
//> using dep dev.zio::zio:2.1.25

for
  counter <- Ref.make(0)
  _       <- ZIO.foreachDiscard(1 to 5) { i =>
               counter.update(_ + i) *> 
               counter.get.flatMap(n => Console.printLine(s"After adding $i: $n"))
             }
  result  <- counter.get
  _       <- Console.printLine(s"Final value: $result")
yield ()
```

## See also

- [tutorial.md](tutorial.md) — modifier basics (`silent`, `invisible`,
  `compile-only`, `fail`, `warn`, `crash`, `passthrough`).
- [scopes-and-versions.md](scopes-and-versions.md) — how `id=` and
  `extends=` work; multi-version compilation.
- [using-deps.md](using-deps.md) — declaring dependencies via
  `//> using dep`.

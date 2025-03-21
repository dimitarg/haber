package haber.log

import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Sync}
import io.odin.*
import io.odin.slf4j.OdinLoggerServiceProvider

// this provides Odin as an slf4j backend to http4s and other library code
class HaberLogger extends OdinLoggerServiceProvider[IO]:

  implicit val F: Sync[IO] = IO.asyncForIO
  implicit val dispatcher: Dispatcher[IO] = Dispatcher.sequential[IO].allocated.unsafeRunSync()._1

  val loggers: PartialFunction[String, Logger[IO]] =
    case "some.external.package.SpecificClass" =>
      consoleLogger[IO](minLevel = Level.Warn)
    case _ =>
      consoleLogger[IO](minLevel = Level.Info)

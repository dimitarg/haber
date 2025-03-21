package haber.rest

import cats.effect.{Async, Resource}
import cats.implicits.*
import com.comcast.ip4s.*
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder

final case class Server[F[_]: Async: Network](routes: Routes[F]):
  def make: Resource[F, Unit] =
    EmberServerBuilder
      .default[F]
      .withPort(port"8080")
      .withHttpApp(routes.all.orNotFound)
      .build
      .void

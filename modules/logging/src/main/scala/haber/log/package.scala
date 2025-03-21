package haber

import cats.effect.*
import io.odin.*

package object log:
  def defaultLogger[F[_]: Sync]: Logger[F] = consoleLogger[F](minLevel = Level.Info)

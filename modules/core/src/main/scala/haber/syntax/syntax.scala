package haber.syntax

import cats.MonadThrow
import cats.implicits.*

extension [A](x: Option[A])
  def orNotFound[F[_]: MonadThrow](error: String): F[A] =
    x.fold {
      MonadThrow[F].raiseError(new RuntimeException(error))
    } {
      _.pure[F]
    }

extension [F[_]: MonadThrow, A](fa: F[Option[A]])
  def orNotFound(error: String): F[A] =
    fa.flatMap(_.orNotFound(error))

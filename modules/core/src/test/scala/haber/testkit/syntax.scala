package haber.testkit

import cats.effect.IO
import cats.implicits.*
import cats.{MonadThrow, Parallel}
import haber.domain.*
import haber.service.EmailStore
import haber.syntax.*
import org.scalacheck.Gen

object syntax:
  extension [A](gen: Gen[A]) def sampleIO: IO[A] = gen.sample.orNotFound("could not sample")

  extension [F[_]: MonadThrow: Parallel](store: EmailStore[F])
    def parCreateMany(xs: List[EmailMessage.Create]): F[List[EmailMessage]] =
      xs.parTraverse(store.createMessage(_).orNotFound("unexpected"))

    def createMany(xs: List[EmailMessage.Create]): F[List[EmailMessage]] =
      xs.traverse(store.createMessage(_).orNotFound("unexpected"))

    def parGetMany(mailbox: Email, messages: List[EmailMessage]): F[List[EmailMessage]] =
      messages.parTraverse(m => store.getMessage(mailbox, m.id)).map(_.flatten)

    def getMany(mailbox: Email, messages: List[EmailMessage]): F[List[EmailMessage]] =
      messages.traverse(m => store.getMessage(mailbox, m.id)).map(_.flatten)

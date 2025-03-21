package haber.domain

import cats.Show
import cats.derived.*
import cats.kernel.{Eq, Order}

opaque type Email = String

extension (email: Email) inline def value: String = email

object Email:
  inline def apply(x: String): Email = x
  given (using underlying: Eq[String]): Eq[Email] = underlying
  given (using underlying: Show[String]): Show[Email] = underlying

final case class EmailMessage(
  id: EmailMessage.Id,
  from: Email,
  to: Email,
  subject: String,
  body: String
) derives Eq,
    Show

object EmailMessage:

  opaque type Id = Long
  object Id:
    inline def apply(x: Long): Id = x
    given (using underlying: Order[Long]): Order[Id] = underlying
    given (using underlying: Show[Long]): Show[Id] = underlying

    extension (id: Id) inline def value: Long = id

  final case class Create(
    from: Email,
    to: Email,
    subject: String,
    body: String
  ) derives Eq,
      Show:
    def toMessage(id: EmailMessage.Id) = EmailMessage(
      id = id,
      from = from,
      to = to,
      subject = subject,
      body = body
    )

final case class Page[Id, A](
  items: List[A],
  nextCursor: Option[Id]
) derives Eq,
    Show

object Page:
  def empty[Id, A]: Page[Id, A] = Page(List.empty, None)

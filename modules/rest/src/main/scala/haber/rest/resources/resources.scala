package haber.rest.resources

import haber.domain.*
import haber.rest.resources.DomainCodecs.given
import io.circe.Codec

final case class ErrorResponse(
  message: String
) derives Codec

final case class PagedResponse[Id, A](
  items: Seq[A],
  next: Option[Id]
) derives Codec

final case class HealthResponse(
  message: String
) derives Codec

final case class CreateMailboxResponse(
  email: Email
) derives Codec

final case class CreateEmailMessageResource(
  sender: Email,
  subject: String,
  body: String
) derives Codec:
  def toDomain(receiver: Email): EmailMessage.Create = EmailMessage.Create(
    from = sender,
    to = receiver,
    subject = subject,
    body = body
  )

final case class CreateEmailMessageResponse(
  id: EmailMessage.Id
) derives Codec

final case class EmailMessageResource(
  sender: Email,
  subject: String,
  body: String
) derives Codec

object EmailMessageResource:
  def fromDomain(x: EmailMessage): EmailMessageResource = EmailMessageResource(
    sender = x.from,
    subject = x.subject,
    body = x.body
  )

final case class EmailMessageSummaryResource(
  id: EmailMessage.Id,
  sender: Email,
  subject: String
) derives Codec

object EmailMessageSummaryResource:
  def fromDomain(x: EmailMessage): EmailMessageSummaryResource = EmailMessageSummaryResource(
    id = x.id,
    sender = x.from,
    subject = x.subject
  )

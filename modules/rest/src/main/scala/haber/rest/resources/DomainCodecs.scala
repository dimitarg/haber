package haber.rest.resources

import cats.implicits.*
import haber.domain.*
import io.circe.Codec

object DomainCodecs:
  given Codec[Email] = Codec.implied[String].iemap(Email.apply(_).asRight)(_.value)
  given Codec[EmailMessage.Id] = Codec.implied[Long].iemap(EmailMessage.Id.apply(_).asRight)(_.value)

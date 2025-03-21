package haber.rest.resources

import cats.implicits.*
import haber.domain.*
import org.http4s.QueryParamDecoder

object EmailVar:
  def unapply(str: String): Option[Email] =
    // this would have validation in real life
    Email(str).some

object IdVar:
  def unapply(x: String): Option[EmailMessage.Id] =
    x.toLongOption.map(EmailMessage.Id.apply)

given QueryParamDecoder[EmailMessage.Id] =
  QueryParamDecoder[Long].map(EmailMessage.Id.apply)

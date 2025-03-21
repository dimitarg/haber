package haber.rest

import cats.effect.Concurrent
import cats.implicits.*
import haber.domain.*
import haber.rest.resources.*
import haber.rest.resources.DomainCodecs.given
import haber.rest.resources.given
import haber.service.EmailStore
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

final case class Routes[F[_]: Concurrent](store: EmailStore[F]) extends Http4sDsl[F]:

  object FromMatcher extends OptionalQueryParamDecoderMatcher[EmailMessage.Id]("from")
  object CountMatcher extends QueryParamDecoderMatcher[Int]("count")

  def inboxNotFound(email: Email) = ErrorResponse(show"Inbox $email not found")

  def withValidation(validation: Either[String, Unit])(success: F[Response[F]]): F[Response[F]] =
    validation.fold(e => BadRequest(ErrorResponse(e)), _ => success)

  val all: HttpRoutes[F] = HttpRoutes.of {

    case GET -> Root / "health" =>
      Ok(HealthResponse("Service is healthy."))

    case POST -> Root / "mailboxes" =>
      store.createEmail
        .map(CreateMailboxResponse(_))
        .flatMap(Created(_))
    case req @ POST -> Root / "mailboxes" / EmailVar(email) / "messages" =>
      for
        createMessageReq <- req.as[CreateEmailMessageResource]
        message <- store.createMessage(createMessageReq.toDomain(receiver = email))
        result <- message.fold {
          NotFound(inboxNotFound(email))
        } { message =>
          Created(CreateEmailMessageResponse(message.id))
        }
      yield result
    case GET -> Root / "mailboxes" / EmailVar(email) / "messages" / IdVar(id) =>
      for
        msg <- store.getMessage(email, id)
        result <- msg.fold {
          NotFound(ErrorResponse(show"Email message with id $id not found in inbox $email"))
        } { msg =>
          Ok(EmailMessageResource.fromDomain(msg))
        }
      yield result
    case GET -> Root / "mailboxes" / EmailVar(email) / "messages" :? FromMatcher(from) +& CountMatcher(count) =>
      withValidation {
        if count < 0 then "count cannot be negative".asLeft else ().asRight
      } {
        for
          page <- store.getMessages(email, pageSize = count, cursor = from)
          result <- page.fold {
            NotFound(ErrorResponse("Not found"))
          } { page =>
            val items = page.items.map(EmailMessageSummaryResource.fromDomain)
            Ok(PagedResponse(items = items, next = page.nextCursor))
          }
        yield result
      }
    case DELETE -> Root / "mailboxes" / EmailVar(email) =>
      store.deleteMessages(email).flatMap {
        case true  => NoContent()
        case false => NotFound(inboxNotFound(email))
      }
    case DELETE -> Root / "mailboxes" / EmailVar(email) / "messages" / IdVar(id) =>
      store.deleteMessage(email, id).flatMap {
        case true  => NoContent()
        case false => NotFound(ErrorResponse(show"Inbox $email not found, or it has no message with id $id"))
      }

  }

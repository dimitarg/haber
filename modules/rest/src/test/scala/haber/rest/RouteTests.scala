package haber.rest

import cats.effect.IO
import cats.implicits.*
import fs2.Stream
import haber.domain.*
import haber.rest.resources.*
import haber.rest.resources.DomainCodecs.given
import haber.service.EmailStore
import org.http4s.Method.*
import org.http4s.Uri.Path.SegmentEncoder
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.implicits.*
import org.http4s.{QueryParamEncoder, Response, Status, Uri}
import weaver.pure.*
import weaver.{Expectations, SourceLocation}

object RouteTests extends Suite with Http4sClientDsl[IO]:

  val routesClient: IO[Client[IO]] = for
    store <- EmailStore.make[IO]
    routes = Routes(store)
    client = Client.fromHttpApp(routes.all.orNotFound)
  yield client

  def withClient[A](f: Client[IO] => IO[A]): IO[A] = routesClient.flatMap(f)

  def routeTest(name: String)(f: Client[IO] => IO[Expectations])(implicit loc: SourceLocation): IO[Test] =
    test(s"Routes - $name")(withClient(f))

  override def suitesStream: Stream[IO, Test] = parSuite(
    List(
      routeTest("Health check") { client =>
        client
          .expect[HealthResponse](GET(uri"health"))
          .as(success)
      },
      routeTest("POST /mailboxes") { client =>
        client.createMailbox
          .as(success)
      },
      routeTest("POST /mailboxes/{email}/messages to an existing mailbox") { client =>
        for
          mailbox <- client.createMailbox
          _ <- client.createMessage(mailbox.email, newMessage)
        yield success
      },
      routeTest("POST /mailboxes/{email}/messages to a non-existent mailbox") { client =>
        client.run(POST(Uris.messages(nonExistentMailbox)).withEntity(newMessage)).use {
          expectNotFound
        }
      },
      routeTest("GET /mailboxes/{email}/messages/{id} can retrieve an existing message") { client =>
        for
          mailbox <- client.createMailbox
          id <- client.createMessage(mailbox.email, newMessage).map(_.id)
          message <- client.getMessage(mailbox.email, id)
        yield expectSame(newMessage, message)
      },
      routeTest("GET /mailboxes/{email}/messages/{id} with non-existent message id") { client =>
        for
          mailbox <- client.createMailbox
          result <- client.run(GET(Uris.message(mailbox.email, nonExistentId))).use {
            expectNotFound
          }
        yield result
      },
      routeTest("GET /mailboxes/{email}/messages/{id} with non-existent mailbox") { client =>
        client.run(GET(Uris.message(nonExistentMailbox, nonExistentId))).use {
          expectNotFound
        }
      },
      routeTest("GET /mailboxes/{email}/messages?count={count}") { client =>
        for
          mailbox <- client.createMailbox
          createdIds <- client.parCreateMessages(mailbox.email, List.fill(100)(newMessage))
          createdIdsInExpectedOrder = createdIds.sorted.reverse
          count = 20
          getResponse <- client.getMessages(mailbox.email, from = None, count = count)
          responseIds = getResponse.items.map(_.id)
        yield expect(responseIds === createdIdsInExpectedOrder.take(count)) |+|
          expect(getResponse.next === createdIdsInExpectedOrder(count).some)
      },
      routeTest("GET /mailboxes/{email}/messages?count={count}&from={from}") { client =>
        for
          mailbox <- client.createMailbox
          createdIds <- client.parCreateMessages(mailbox.email, List.fill(100)(newMessage))
          createdIdsInExpectedOrder = createdIds.sorted.reverse
          count = 20
          from = createdIdsInExpectedOrder(25)
          getResponse <- client.getMessages(mailbox.email, from = from.some, count = count)
          responseIds = getResponse.items.map(_.id)
        yield expect(responseIds.size === count) |+|
          expect(responseIds === createdIdsInExpectedOrder.dropWhile(_ =!= from).take(count)) |+|
          expect(
            getResponse.next ===
              createdIdsInExpectedOrder.dropWhile(_ =!= from).take(count + 1).last.some
          )
      },
      routeTest("GET /mailboxes/{email}/messages?count={count} with non-existent mailbox") { client =>
        client.run(GET(Uris.messages(nonExistentMailbox, from = None, count = 1))).use {
          expectNotFound
        }
      },
      routeTest("GET /mailboxes/{email}/messages?count={count}&from={from} with non-existent id") { client =>
        for
          mailbox <- client.createMailbox
          createdIds <- client.parCreateMessages(mailbox.email, List.fill(100)(newMessage))
          maxId = createdIds.max
          from = EmailMessage.Id(maxId.value + 100)
          result <- client.run(GET(Uris.messages(mailbox.email, from = from.some, count = 1))).use {
            expectNotFound
          }
        yield result
      },
      routeTest("GET /mailboxes/{email}/messages?count={count} on empty mailbox") { client =>
        for
          mailbox <- client.createMailbox
          result <- client.getMessages(mailbox.email, from = None, count = 1)
        yield expect(result.items.isEmpty) |+|
          expect(result.next.isEmpty)
      },
      routeTest("GET /mailboxes/{email}/messages?count={count} with 0 count") { client =>
        for
          mailbox <- client.createMailbox
          resp <- client.getMessages(mailbox.email, from = None, count = 0)
        yield expect(resp.items.isEmpty)
      },
      routeTest("GET /mailboxes/{email}/messages?count={count} with negative count") { client =>
        for
          mailbox <- client.createMailbox
          result <- client.run(GET(Uris.messages(mailbox.email, from = None, count = -1))).use {
            expectBadRequest
          }
        yield result
      },
      routeTest("DELETE /mailboxes/{email}") { client =>
        for
          mailbox <- client.createMailbox
          _ <- client.parCreateMessages(mailbox.email, List.fill(100)(newMessage))
          deleteStatus <- client.deleteMailbox(mailbox.email)
          result <- client.run(GET(Uris.messages(mailbox.email, from = None, count = 1))).use {
            expectNotFound
          }
        yield result |+| expect(deleteStatus === Status.NoContent)
      },
      routeTest("DELETE /mailboxes/{email} on non-existent email") { client =>
        client.deleteMailbox(nonExistentMailbox).map { status =>
          expect(status === Status.NotFound)
        }
      },
      routeTest("DELETE /mailboxes/{email}/messages/{id}") { client =>
        for
          email <- client.createMailbox.map(_.email)
          id1 <- client.createMessage(email, newMessage).map(_.id)
          id2 <- client.createMessage(email, newMessage).map(_.id)
          deleteStatus <- client.deleteMessage(email, id1)
          messages <- client.getMessages(email, from = None, count = 1000)
          messageIds = messages.items.map(_.id)
        yield expect(deleteStatus === Status.NoContent) |+|
          expect(messageIds === List(id2))
      }
    )
  )

  val newMessage = CreateEmailMessageResource(
    sender = Email("foo@example.com"),
    subject = "Hello There",
    body = "Hi!"
  )

  val nonExistentMailbox = Email("nobody_here@example.com")
  val nonExistentId = EmailMessage.Id(Long.MaxValue)

  def expectSame(in: CreateEmailMessageResource, out: EmailMessageResource): Expectations =
    expect(in.sender === out.sender) |+|
      expect(in.subject === out.subject) |+|
      expect(in.body === out.body)

  def expectNotFound(resp: Response[IO]): IO[Expectations] = expectErrorResponse(resp, Status.NotFound)
  def expectBadRequest(resp: Response[IO]): IO[Expectations] = expectErrorResponse(resp, Status.BadRequest)

  def expectErrorResponse(resp: Response[IO], status: Status): IO[Expectations] =
    resp.as[ErrorResponse] >> expect(resp.status === status).pure[IO]

  object Uris:
    val mailboxes = uri"mailboxes"

    def mailbox(email: Email) = mailboxes / email

    def messages(email: Email): Uri = mailbox(email) / "messages"

    def messages(email: Email, from: Option[EmailMessage.Id], count: Int): Uri =
      messages(email).withQueryParam("count", count).withOptionQueryParam("from", from)

    def message(email: Email, id: EmailMessage.Id): Uri = messages(email) / id

  extension (client: Client[IO])
    def createMailbox: IO[CreateMailboxResponse] = client.expect[CreateMailboxResponse](POST(Uris.mailboxes))

    def createMessage(email: Email, message: CreateEmailMessageResource): IO[CreateEmailMessageResponse] =
      client.expect[CreateEmailMessageResponse](
        POST(Uris.messages(email)).withEntity(message)
      )

    def parCreateMessages(email: Email, messages: List[CreateEmailMessageResource]): IO[List[EmailMessage.Id]] =
      messages
        .parTraverse(m => createMessage(email, m))
        .map(_.map(_.id))

    def getMessage(email: Email, id: EmailMessage.Id): IO[EmailMessageResource] =
      client.expect[EmailMessageResource](
        GET(Uris.message(email, id))
      )

    def getMessages(
      email: Email,
      from: Option[EmailMessage.Id],
      count: Int
    ): IO[PagedResponse[EmailMessage.Id, EmailMessageSummaryResource]] =
      client.expect[PagedResponse[EmailMessage.Id, EmailMessageSummaryResource]](
        GET(Uris.messages(email, from, count))
      )

    def deleteMailbox(email: Email): IO[Status] =
      client.status(DELETE(Uris.mailbox(email)))

    def deleteMessage(email: Email, id: EmailMessage.Id): IO[Status] =
      client.status(DELETE(Uris.message(email, id)))

  given SegmentEncoder[Email] = SegmentEncoder.stringSegmentEncoder
    .contramap[Email](_.value)
  given SegmentEncoder[EmailMessage.Id] = SegmentEncoder.longSegmentEncoder
    .contramap[EmailMessage.Id](_.value)
  given QueryParamEncoder[EmailMessage.Id] =
    QueryParamEncoder[Long].contramap(_.value)

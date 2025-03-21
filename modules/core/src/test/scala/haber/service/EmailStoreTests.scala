package haber.service

import cats.effect.IO
import cats.implicits.*
import fs2.*
import haber.domain.*
import haber.syntax.*
import haber.testkit.Generators
import haber.testkit.syntax.*
import org.scalacheck.Gen
import weaver.pure.*
import weaver.scalacheck.{CheckConfig, Checkers}
import weaver.{Expectations, SourceLocation}

object EmailStoreTests extends Suite with Checkers:

  def storeTest(name: String)(run: EmailStore[IO] => IO[Expectations])(using loc: SourceLocation): IO[Test] =
    EmailStore.make[IO].flatMap(store => test(s"EmailStore - $name")(run(store)))

  val forall_ = forall.withConfig(CheckConfig.default.copy(minimumSuccessful = 1000))

  // NB individual tests are parallelised. Suite is run in sequence so tests don't contend with one another for physical threads.
  // this can be freely substituted with `parSuite` but would give us nothing in terms of bug discovery since each test uses its own store.
  override def suitesStream: Stream[IO, Test] = seqSuite(
    List(
      storeTest("createEmail creates unique emails") { store =>
        val sizeGen = Gen.chooseNum(1, 1000)
        // NB properties (forall) are evaluated in parallel in weaver-scalacheck, default config has parallelism 10
        forall_(sizeGen) { mailboxCount =>
          store.createEmail
            .parReplicateA(mailboxCount)
            .map { emails =>
              expect(emails === emails.distinct) `and` expect(emails.size === mailboxCount)
            }
        }
      },
      storeTest("createMessage is observed by subsequent getMessage") { store =>
        for
          mailboxes <- store.createEmail.parReplicateA(10)
          toGen = Gen.oneOf(mailboxes)
          result <- forall_(Generators.createMessage(toGen)) { createMessage =>
            for
              createdMessage <- store.createMessage(createMessage).orNotFound("unexpected")
              message <- store.getMessage(createMessage.to, createdMessage.id)
            yield expect(message === createdMessage.some)
          }
        yield result
      },
      storeTest("deleteMessage is observed by subsequent getMessage") { store =>
        for
          mailboxes <- store.createEmail.parReplicateA(10)
          toGen = Gen.oneOf(mailboxes)
          result <- forall_(Generators.createMessage(toGen)) { createMessage =>
            for
              createdMessage <- store.createMessage(createMessage).orNotFound("unexpected")
              deleted <- store.deleteMessage(createMessage.to, createdMessage.id)
              message <- store.getMessage(createMessage.to, createdMessage.id)
            yield expect(deleted) `and` expect(message === None)
          }
        yield result
      },
      storeTest("deleteMessages deletes the whole mailbox and only that mailbox") { store =>
        for
          (mailbox1, mailbox2) <- (store.createEmail, store.createEmail).parTupled
          for1 <- Gen.listOfN(100, Generators.createMessage(mailbox1)).sampleIO
          for2 <- Gen.listOfN(100, Generators.createMessage(mailbox2)).sampleIO
          (createdFor1, createdFor2) <- (
            store.parCreateMany(for1),
            store.parCreateMany(for2)
          ).parTupled
          deleted <- store.deleteMessages(mailbox1)
          for1AfterDelete <- store.parGetMany(mailbox1, createdFor1)
          for2AfterDelete <- store.parGetMany(mailbox2, createdFor2)
        yield expect(deleted) `and`
          expect(for1AfterDelete.isEmpty) `and`
          expect(for2AfterDelete.toSet === createdFor2.toSet)
      },
      storeTest("getMessages is consistent with getMessage, and returns results by descending recency") { store =>
        for
          mailbox <- store.createEmail
          xs <- Gen.listOfN(100, Generators.createMessage(mailbox)).sampleIO
          createdMsgs <- store
            .createMany(xs)
            .map(_.reverse)
          messagesViaGetMessage <- store.getMany(mailbox, createdMsgs)
          messagesViaGetMessages <- store.getMessages(mailbox, pageSize = xs.size, cursor = None)
        yield expect(messagesViaGetMessage.some === messagesViaGetMessages.map(_.items.toList)) `and`
          expect(createdMsgs.some === messagesViaGetMessages.map(_.items.toList))
      },
      storeTest("getMessages with no cursor respects page size") { store =>
        for
          mailbox <- store.createEmail
          xs <- Gen.listOfN(100, Generators.createMessage(mailbox)).sampleIO
          createdMsgs <- store
            .createMany(xs)
            .map(_.reverse)
          page <- store.getMessages(mailbox, pageSize = 10, cursor = None)
        yield expect(page.map(_.items) === createdMsgs.take(10).some) `and`
          expect(page.flatMap(_.nextCursor) === createdMsgs.get(10).map(_.id))
      },
      storeTest("getMessages with cursor") { store =>
        for
          mailbox <- store.createEmail
          xs <- Gen.listOfN(100, Generators.createMessage(mailbox)).sampleIO
          createdMsgs <- store
            .createMany(xs)
            .map(_.reverse)
          page <- store.getMessages(mailbox, pageSize = 10, cursor = createdMsgs.get(10).map(_.id))
        yield expect(page.map(_.items) === createdMsgs.slice(10, 20).some) `and`
          expect(page.flatMap(_.nextCursor) === createdMsgs.get(20).map(_.id))
      },
      storeTest("getMessages with cursor on last page") { store =>
        for
          mailbox <- store.createEmail
          xs <- Gen.listOfN(100, Generators.createMessage(mailbox)).sampleIO
          createdMsgs <- store
            .createMany(xs)
            .map(_.reverse)
          page <- store.getMessages(mailbox, pageSize = 10, cursor = createdMsgs.get(98).map(_.id))
        yield expect(page.map(_.items) === createdMsgs.slice(98, 100).some) `and`
          expect(page.flatMap(_.nextCursor) === None)
      },
      storeTest("getMessages with non-existent cursor") { store =>
        for
          mailbox <- store.createEmail
          xs <- Gen.listOfN(100, Generators.createMessage(mailbox)).sampleIO
          _ <- store.createMany(xs)
          nonExistentCursor = EmailMessage.Id(Long.MaxValue)
          page <- store.getMessages(mailbox, pageSize = 10, cursor = nonExistentCursor.some)
        yield expect(page === None)
      },
      storeTest("getMessages with page larger than data set") { store =>
        for
          mailbox <- store.createEmail
          xs <- Gen.listOfN(10, Generators.createMessage(mailbox)).sampleIO
          createdMsgs <- store
            .createMany(xs)
            .map(_.reverse)
          page <- store.getMessages(mailbox, pageSize = 11, cursor = None)
        yield expect(page.map(_.items) === createdMsgs.some) `and`
          expect(page.flatMap(_.nextCursor) === None)
      }
    )
  )

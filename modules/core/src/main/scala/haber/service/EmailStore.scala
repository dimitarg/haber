package haber.service

import cats.Monad
import cats.effect.Ref
import cats.effect.std.UUIDGen
import cats.implicits.*
import cats.kernel.Order
import haber.domain.*

import scala.collection.immutable.TreeMap

// TODO Old messages must eventually be expired, so you'll need to implement an eviction or garbage collection strategy.
trait EmailStore[F[_]]:
  def createEmail: F[Email]
  def createMessage(message: EmailMessage.Create): F[Option[EmailMessage]]
  // in recency order
  def getMessages(
    email: Email,
    pageSize: Int,
    cursor: Option[EmailMessage.Id]
  ): F[Option[Page[EmailMessage.Id, EmailMessage]]]
  def getMessage(mailbox: Email, id: EmailMessage.Id): F[Option[EmailMessage]]
  def deleteMessages(mailbox: Email): F[Boolean]
  def deleteMessage(mailbox: Email, id: EmailMessage.Id): F[Boolean]

object EmailStore:

  private[EmailStore] final case class InboxState(
    byId: Map[EmailMessage.Id, EmailMessage],
    byMostRecent: TreeMap[EmailMessage.Id, EmailMessage],
    nextId: Long
  ):
    def addMessage(x: EmailMessage.Create): (InboxState, EmailMessage) =
      val id = EmailMessage.Id(nextId)
      val msg = x.toMessage(id)
      val nextState = InboxState(
        byId = byId + (id -> msg),
        byMostRecent = byMostRecent + (id -> msg),
        nextId = nextId + 1
      )
      (nextState, msg)

    def deleteMessage(id: EmailMessage.Id): (InboxState, Boolean) =
      val removed = byId.contains(id)

      val nextState =
        if removed then
          InboxState(
            byId = byId - id,
            byMostRecent = byMostRecent - id,
            nextId = nextId
          )
        else this
      (nextState, removed)

    def getMessage(id: EmailMessage.Id): Option[EmailMessage] =
      byId.get(id)

    def getMostRecent(
      mailbox: Email,
      count: Int,
      from: Option[EmailMessage.Id]
    ): Option[(List[EmailMessage], Option[EmailMessage])] =
      // lookup, worst case O(log(mailbox size)))
      val iter = from.fold(byMostRecent.values.iterator) { from =>
        // TODO what operation allows to lookup key and take(count) simultaneously
        if !byMostRecent.contains(from) then Iterator.empty[EmailMessage]
        else byMostRecent.valuesIteratorFrom(from)
      }

      val result = iter.take(count + 1).toList

      if result.isEmpty && from.isDefined then
        // id not found
        None
      else if result.size <= count then
        // last page
        (result, None).some
      else (result.init, result.lastOption).some

  private[EmailStore] object InboxState:
    private val mostRecentOrdering: Ordering[EmailMessage.Id] = Order[EmailMessage.Id].toOrdering.reverse
    val empty = InboxState(Map.empty, TreeMap.empty(mostRecentOrdering), 0)

  def make[F[_]: Monad: UUIDGen: Ref.Make]: F[EmailStore[F]] = Ref[F].of(Map.empty).map(InMemStore(_))

  private[EmailStore] final case class InMemStore[F[_]: Monad: UUIDGen: Ref.Make](
    stateR: Ref[F, Map[Email, Ref[F, InboxState]]]
  ) extends EmailStore[F]:

    def createEmail: F[Email] = for
      email <- UUIDGen.randomString[F].map(uuidStr => Email.apply(s"$uuidStr@example.com"))
      emptyInbox <- Ref[F].of(InboxState.empty)
      _ <- stateR.update { state =>
        state + (email -> emptyInbox)
      }
    yield email

    private def withMailbox[A](email: Email)(f: Ref[F, InboxState] => F[A]): F[Option[A]] = for
      inboxes <- stateR.get
      result <- inboxes.get(email).traverse(f)
    yield result

    def createMessage(message: EmailMessage.Create): F[Option[EmailMessage]] = withMailbox(message.to) { inboxState =>
      inboxState.modify(inbox => inbox.addMessage(message))
    }

    def deleteMessage(mailbox: Email, id: EmailMessage.Id): F[Boolean] = withMailbox(mailbox) { inboxState =>
      inboxState.modify(_.deleteMessage(id))
    }.map(_.getOrElse(false)) // mailbox not found

    def deleteMessages(mailbox: Email): F[Boolean] = stateR.modify { state =>
      (state - mailbox, state.contains(mailbox))
    }

    def getMessage(mailbox: Email, id: EmailMessage.Id): F[Option[EmailMessage]] = withMailbox(mailbox) { inboxState =>
      inboxState.get.map(_.getMessage(id))
    }.map(_.flatten) // mailbox not found

    def getMessages(
      mailbox: Email,
      pageSize: Int,
      cursor: Option[EmailMessage.Id]
    ): F[Option[Page[EmailMessage.Id, EmailMessage]]] = withMailbox(mailbox) { inboxState =>
      inboxState.get.map { inboxState =>
        inboxState.getMostRecent(mailbox, pageSize, cursor).map { (results, next) =>
          Page(results, next.map(_.id))
        }
      }
    }.map(_.flatten) // mailbox not found

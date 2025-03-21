# `haber` - a tiny Mailinator-like service

## TLDR

Data structures / concurrency-related code reside in `EmailStore.scala`. Those use a couple of data types defined in `domain.scala`.

## Running tests

Issue
```
sbt test
```
This will run data store tests and REST service tests.

## Running REST service

JAR assembly / executable is not provided yet. To run, issue

```
sbt haber-rest/run
```

This will start an HTTP server on port 8080. If you wish to
- Change the port, or
- Bind the server to `0.0.0.0` instead of `localhost`, or
- Change some of the default `http4s-ember` settings

, edit `Server.scala`.

## Data store design

The state of the system is represented as 

```scala
Ref[F, Map[Email, Ref[F, InboxState]]]
```

That is, a global atomic reference holds a map from an email address to an atomic reference to that inbox's state. This design has the following properties:

- Creation of a new mailbox and deletion of an entire mailbox contend with one another, but
- They do not contend with writes to individual inboxes (add new message, delete message) and
- Writes to two separate mailboxes do not contend with one another
- Lastly, reads never contend with any type of write - this by virtue of using atomic references

Above, by **contention** we mean an individual operation having to perform a [CAS spin](https://github.com/typelevel/cats-effect/blob/3e17905234106a5641b43513ac36be79b2f035f4/kernel/jvm/src/main/scala/cats/effect/kernel/SyncRef.scala#L86) because another fiber has updated the value in the meantime.

Next, an individual mailbox's state is represented by the following datatype

```scala
final case class InboxState(
  byId: Map[EmailMessage.Id, EmailMessage],
  byMostRecent: TreeMap[EmailMessage.Id, EmailMessage],
  nextId: Long
)
```
- `byId` is an index specifically serving `GET /mailboxes/{email}/messages/{id}`, at the cost of extra memory
- `byMostRecent` is a red-black tree, which is a  self-balancing binary search tree. Adding a message, deletion of message and cursor lookup are `O(log n)` worst-case. `byMostRecent` keeps ids in reversed natural ordering, since we need to retrieve most recent messages first.
- `nextId` keeps track of the next id to be assigned to e a message, and is incremented on message creation. This ensures that ids increase monotonically, or said otherwise, sorting inbox by id descending always results in recency order
- Under this implementation, message ids on their own are not a unique message identifier across mailboxes. This is done on purpose, as otherwise creating messages across two different mailboxes would contend with one another.

## Other design notes

### Effect polymorphism

The program is written in an effect-polymorphic style and only materialised in IO in `Main` and tests.

This is done because we anticipate a real system would need tracing a la `otel4s` or `natchez`, and

- In our experience the tracing implementation obtained via `Kleisli` / `ReaderT` is easier to reason about than the corresponding `IO` implementation, which is based on `IOLocal` and suffers from the same conceptual problems as java `ThreadLocal`-baced tracing implementations;
- It is painful to work in `Kleisli` directly, and so it's more viable to abstract it out via effect polymorphism / mtl-style 

## TODO

This is a list of some of the outstanding work.

- Better http routes error handling in case of invalid request payload
- eviction
- jmh benchmarks
- rest benchmarks
- refinement types for Email and `count`
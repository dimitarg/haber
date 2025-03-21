package haber.testkit

import haber.domain.*
import org.scalacheck.Gen

object Generators:

  def nonEmptyAlphaString(max: Int): Gen[String] =
    Gen
      .nonEmptyStringOf(Gen.alphaChar)
      .map(x => x.take(max))

  val emailGen: Gen[Email] = for
    address <- nonEmptyAlphaString(100)
    domain <- nonEmptyAlphaString(20)
  yield Email(s"$address@$domain.com")

  def createMessage(toGen: Gen[Email]): Gen[EmailMessage.Create] = for
    from <- emailGen
    to <- toGen
    subject <- nonEmptyAlphaString(20)
    body <- nonEmptyAlphaString(100)
  yield EmailMessage.Create(from = from, to = to, subject = subject, body = body)

  def createMessage(to: Email): Gen[EmailMessage.Create] = createMessage(Gen.const(to))

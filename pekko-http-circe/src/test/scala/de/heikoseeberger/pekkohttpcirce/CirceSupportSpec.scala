/*
 * Copyright 2015 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.heikoseeberger.pekkohttpcirce

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.marshalling.Marshal
import org.apache.pekko.http.scaladsl.model.{
  ContentTypeRange,
  HttpCharsets,
  HttpEntity,
  MediaType,
  RequestEntity,
  ResponseEntity
}
import org.apache.pekko.http.scaladsl.model.ContentTypes.{ `application/json`, `text/plain(UTF-8)` }
import org.apache.pekko.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import cats.data.{ NonEmptyList, Validated, ValidatedNel }
import io.circe.{ DecodingFailure, Encoder, ParsingFailure, Printer }
import io.circe.CursorOp.DownField
import org.scalatest.{ BeforeAndAfterAll, EitherValues }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success, Try }

object CirceSupportSpec {

  final case class Foo(bar: String) {
    require(bar startsWith "bar", "bar must start with 'bar'!")
  }

  final case class MultiFoo(a: String, b: String)

  final case class OptionFoo(a: Option[String])
}

final class CirceSupportSpec
    extends AsyncWordSpec
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with EitherValues {

  import CirceSupportSpec._

  private implicit val system: ActorSystem = ActorSystem()

  private val `application/json-home` =
    MediaType.applicationWithFixedCharset("json-home", HttpCharsets.`UTF-8`, "json-home")

  /**
    * Specs common to both [[FailFastCirceSupport]] and [[ErrorAccumulatingCirceSupport]]
    */
  private def commonCirceSupport(support: BaseCirceSupport): Unit = {
    import io.circe.generic.auto._
    import support._

    "enable marshalling and unmarshalling objects for generic derivation" in {
      val foo = Foo("bar")
      Marshal(foo)
        .to[RequestEntity]
        .flatMap(Unmarshal(_).to[Foo])
        .map(_ shouldBe foo)
    }

    // TODO: this test doesn't compile in Scala 3 on line 95 with error
    // [E124] Cyclic Error - value e needs result type because its right-hand side attempts implicit search
    /*"enable streamed marshalling and unmarshalling for json arrays" in {
      val foos = (0 to 100).map(i => Foo(s"bar-$i")).toList

      // Don't know why, the encoder is not resolving alongside the marshaller
      // this only happens if we use the implicits from BaseCirceSupport
      // so, tried to create it before and guess what? it worked.
      // not sure if this is a bug, but, the error is this:
      //  diverging implicit expansion for type io.circe.Encoder[A]
      //  [error] starting with lazy value encodeZoneOffset in object Encoder
      implicit val e = implicitly[Encoder[Foo]]

      Marshal(Source(foos))
        .to[ResponseEntity]
        .flatMap(entity => Unmarshal(entity).to[SourceOf[Foo]])
        .flatMap(_.runWith(Sink.seq))
        .map(_ shouldBe foos)
    }*/

    "provide proper error messages for requirement errors" in {
      val entity = HttpEntity(`application/json`, """{ "bar": "baz" }""")
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(_ should have message "requirement failed: bar must start with 'bar'!")
    }

    "fail with NoContentException when unmarshalling empty entities" in {
      val entity = HttpEntity.empty(`application/json`)
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(_ shouldBe Unmarshaller.NoContentException)
    }

    "fail with UnsupportedContentTypeException when Content-Type is not `application/json`" in {
      val entity = HttpEntity("""{ "bar": "bar" }""")
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(
          _ shouldBe UnsupportedContentTypeException(Some(`text/plain(UTF-8)`), `application/json`)
        )
    }

    "write None as null by default" in {
      val optionFoo = OptionFoo(None)
      Marshal(optionFoo)
        .to[RequestEntity]
        .map(_.asInstanceOf[HttpEntity.Strict].data.decodeString("UTF-8") shouldBe "{\"a\":null}")
    }

    "not write None" in {
      implicit val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)
      val optionFoo                 = OptionFoo(None)
      Marshal(optionFoo)
        .to[RequestEntity]
        .map(_.asInstanceOf[HttpEntity.Strict].data.decodeString("UTF-8") shouldBe "{}")
    }
  }

  "FailFastCirceSupport" should {
    import FailFastCirceSupport._
    import io.circe.generic.auto._

    behave like commonCirceSupport(FailFastCirceSupport)

    "fail with a ParsingFailure when unmarshalling empty entities with safeUnmarshaller" in {
      val entity = HttpEntity.empty(`application/json`)
      Unmarshal(entity)
        .to[Either[io.circe.Error, Foo]]
        .futureValue
        .left
        .value shouldBe a[ParsingFailure]
    }

    "fail-fast and return only the first unmarshalling error" in {
      val entity = HttpEntity(`application/json`, """{ "a": 1, "b": 2 }""")
      Try(Unmarshal(entity).to[MultiFoo].failed.futureValue) match {
        case Failure(ex) => fail(ex)
        case Success(value) =>
          value.getMessage shouldBe "DecodingFailure at .a: Got value '1' with wrong type, expecting string"
      }
    }

    "fail-fast and return only the first unmarshalling error with safeUnmarshaller" in {
      val entity = HttpEntity(`application/json`, """{ "a": 1, "b": 2 }""")
      Unmarshal(entity)
        .to[Either[io.circe.Error, MultiFoo]]
        .futureValue
        .left
        .value
        .getMessage shouldBe "DecodingFailure at .a: Got value '1' with wrong type, expecting string"
    }

    "allow unmarshalling with passed in Content-Types" in {
      val foo = Foo("bar")

      object CustomCirceSupport extends FailFastCirceSupport {
        override def unmarshallerContentTypes: List[ContentTypeRange] =
          List(`application/json`, `application/json-home`)
      }
      import CustomCirceSupport._

      val entity = HttpEntity(`application/json`, """{ "bar": "bar" }""")
      Unmarshal(entity).to[Foo].map(_ shouldBe foo)
    }
  }

  "ErrorAccumulatingCirceSupport" should {
    import ErrorAccumulatingCirceSupport._
    import io.circe.generic.auto._

    behave like commonCirceSupport(ErrorAccumulatingCirceSupport)

    "fail with a NonEmptyList of Errors when unmarshalling empty entities with safeUnmarshaller" in {
      val entity = HttpEntity.empty(`application/json`)
      Unmarshal(entity)
        .to[ValidatedNel[io.circe.Error, Foo]]
        .futureValue
        .toEither
        .left
        .value shouldBe a[NonEmptyList[_]]
    }

    "accumulate and return all unmarshalling errors" in {
      val entity = HttpEntity(`application/json`, """{ "a": 1, "b": 2 }""")
      val errors =
        NonEmptyList.of(
          DecodingFailure("Got value '1' with wrong type, expecting string", List(DownField("a"))),
          DecodingFailure("Got value '2' with wrong type, expecting string", List(DownField("b")))
        )
      val errorMessage = ErrorAccumulatingCirceSupport.DecodingFailures(errors).getMessage
      Try(Unmarshal(entity).to[MultiFoo].failed.futureValue) match {
        case Failure(ex)    => fail(ex)
        case Success(value) => value.getMessage shouldBe errorMessage
      }
    }

    "accumulate and return all unmarshalling errors with safeUnmarshaller" in {
      val entity = HttpEntity(`application/json`, """{ "a": 1, "b": 2 }""")
      val errors =
        NonEmptyList.of(
          DecodingFailure("Got value '1' with wrong type, expecting string", List(DownField("a"))),
          DecodingFailure("Got value '2' with wrong type, expecting string", List(DownField("b")))
        )
      val errorMessage = ErrorAccumulatingCirceSupport.DecodingFailures(errors).getMessage
      Try(Unmarshal(entity).to[ValidatedNel[io.circe.Error, MultiFoo]].futureValue) match {
        case Failure(ex) => fail(ex)
        case Success(value) =>
          println(value)
          value match {
            case Validated.Valid(a) => fail(s"Got $a")
            case Validated.Invalid(e) =>
              val value1 = e.toList.map(_.getMessage).mkString(System.lineSeparator())
              value1 shouldBe errorMessage
          }
      }
    }

    "allow unmarshalling with passed in Content-Types" in {
      val foo = Foo("bar")

      object CustomCirceSupport extends ErrorAccumulatingCirceSupport {
        override def unmarshallerContentTypes: List[ContentTypeRange] =
          List(`application/json`, `application/json-home`)
      }
      import CustomCirceSupport._

      val entity = HttpEntity(`application/json-home`, """{ "bar": "bar" }""")
      Unmarshal(entity).to[Foo].map(_ shouldBe foo)
    }
  }

  override protected def afterAll(): Unit = {
    Await.ready(system.terminate(), 42.seconds)
    super.afterAll()
  }
}

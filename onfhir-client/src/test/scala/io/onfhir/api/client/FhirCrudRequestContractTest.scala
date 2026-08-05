package io.onfhir.api.client

import io.onfhir.client.OnFhirNetworkClient
import io.onfhir.client.testutil.{CannedResponses, RecordedRequest, WithMockFhirServer}
import io.onfhir.util.DateTimeUtil
import org.json4s.JsonAST.{JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
 * Wire level contract of the CRUD request builders: HTTP method, path, query and
 * conditional headers as asserted on the mock FHIR server.
 */
@RunWith(classOf[JUnitRunner])
class FhirCrudRequestContractTest extends Specification with WithMockFhirServer {
  sequential

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def client: OnFhirNetworkClient = OnFhirNetworkClient(mockServer.baseUrl)

  private def send(builder: FhirRequestBuilder): RecordedRequest = {
    Await.result(builder.execute(), 5.seconds)
    mockServer.lastRequest
  }

  private val patient = CannedResponses.patient("p1", "gender" -> JString("male"))
  private val patientWithVersion =
    CannedResponses.patient("p1", CannedResponses.meta("3"), "gender" -> JString("male"))
  private val patientWithoutId =
    JObject(List("resourceType" -> JString("Patient"), "gender" -> JString("male")))

  "FHIR create requests" should {
    "POST the resource to the type endpoint with the FHIR JSON content type" in {
      val recorded = send(client.create(patient))

      recorded.method mustEqual "POST"
      recorded.relativePath mustEqual "/Patient"
      recorded.rawQuery must beNone
      recorded.header("Content-Type") must beSome("application/fhir+json; charset=UTF-8")
      recorded.bodyResource mustEqual patient
    }

    "put conditional create parameters into If-None-Exist and never into the url" in {
      val recorded = send(client.create(patient).where("identifier", "urn:oid:1|123"))

      recorded.method mustEqual "POST"
      recorded.relativePath mustEqual "/Patient"
      recorded.rawQuery must beNone
      recorded.header("If-None-Exist") must beSome("identifier=urn:oid:1|123")
    }

    "join multiple values of a single conditional create parameter with a comma" in {
      val recorded = send(client.create(patient).where("identifier", "urn:oid:1|123", "urn:oid:2|456"))

      recorded.header("If-None-Exist") must beSome("identifier=urn:oid:1|123,urn:oid:2|456")
    }

    "render return preferences into the Prefer header" in {
      send(client.create(patient).returnMinimal()).header("Prefer") must beSome("return=minimal")
      send(client.create(patient).returnOperationOutcome()).header("Prefer") must beSome("return=OperationOutcome")
    }
  }

  "FHIR update requests" should {
    "PUT the resource to the instance endpoint" in {
      val recorded = send(client.update(patient))

      recorded.method mustEqual "PUT"
      recorded.relativePath mustEqual "/Patient/p1"
      recorded.rawQuery must beNone
      recorded.bodyResource mustEqual patient
      recorded.header("Content-Type") must beSome("application/fhir+json; charset=UTF-8")
    }

    "send a weak If-Match when the resource carries a version and version control is on" in {
      val recorded = send(client.update(patientWithVersion))

      recorded.header("If-Match") must beSome("W/\"3\"")
    }

    "not send If-Match when version control is switched off" in {
      val recorded = send(client.update(patientWithVersion, forceVersionControl = false))

      recorded.header("If-Match") must beNone
    }

    "not send If-Match when the resource carries no version" in {
      val recorded = send(client.update(patient))

      recorded.header("If-Match") must beNone
    }

    "PUT to the type endpoint with a query for a conditional update" in {
      val recorded = send(client.update(patientWithoutId).where("identifier", "urn:oid:1|123"))

      recorded.method mustEqual "PUT"
      recorded.relativePath mustEqual "/Patient"
      recorded.rawQuery must beSome("identifier=urn%3Aoid%3A1%7C123")
    }

    "build the logged request uri without a doubled slash" in {
      client.update(patient).request.requestUri mustEqual s"${mockServer.baseUrl}/Patient/p1"
      client.update(patientWithoutId).request.requestUri mustEqual s"${mockServer.baseUrl}/Patient"
    }
  }

  "FHIR read requests" should {
    "GET the instance endpoint" in {
      val recorded = send(client.read("Patient", "p1"))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/Patient/p1"
      recorded.rawQuery must beNone
      recorded.header("Content-Type") must beNone
    }

    "render ifModifiedSince as an HTTP date" in {
      val instant = Instant.parse("1994-11-06T08:49:37Z")
      val recorded = send(client.read("Patient", "p1").ifModifiedSince(instant))

      recorded.header("If-Modified-Since") must beSome(DateTimeUtil.formatHttpDate(instant))
      recorded.header("If-Modified-Since") must beSome("Sun, 06 Nov 1994 08:49:37 GMT")
    }

    "render ifNoneMatch as a weak entity tag" in {
      val recorded = send(client.read("Patient", "p1").ifNoneMatch(7))

      recorded.header("If-None-Match") must beSome("W/\"7\"")
    }

    "render the summary parameter" in {
      val recorded = send(client.read("Patient", "p1").summary("text"))

      recorded.queryParams mustEqual Map("_summary" -> List("text"))
    }

    "render the elements parameter as a comma separated list" in {
      val recorded = send(client.read("Patient", "p1").elements("gender", "birthDate"))

      recorded.queryParams mustEqual Map("_elements" -> List("gender,birthDate"))
      recorded.rawQuery must beSome("_elements=gender%2CbirthDate")
    }
  }

  "FHIR vread requests" should {
    "GET the versioned instance endpoint" in {
      val recorded = send(client.vread("Patient", "p1", "2"))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/Patient/p1/_history/2"
      recorded.rawQuery must beNone
    }
  }

  "FHIR delete requests" should {
    "DELETE the instance endpoint" in {
      val recorded = send(client.delete("Patient", "p1"))

      recorded.method mustEqual "DELETE"
      recorded.relativePath mustEqual "/Patient/p1"
      recorded.rawQuery must beNone
    }

    "DELETE the instance endpoint derived from a resource" in {
      val recorded = send(client.delete(patient))

      recorded.method mustEqual "DELETE"
      recorded.relativePath mustEqual "/Patient/p1"
    }

    "DELETE the type endpoint with a query for a conditional delete" in {
      val recorded = send(client.delete("Patient").where("identifier", "urn:oid:1|123"))

      recorded.method mustEqual "DELETE"
      recorded.relativePath mustEqual "/Patient"
      recorded.rawQuery must beSome("identifier=urn%3Aoid%3A1%7C123")
    }
  }

  "FHIR capabilities requests" should {
    "GET the metadata endpoint" in {
      val recorded = send(client.capabilities())

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/metadata"
      recorded.rawQuery must beNone
    }

    "render the mode parameter" in {
      val recorded = send(client.capabilities().mode("normative"))

      recorded.relativePath mustEqual "/metadata"
      recorded.queryParams mustEqual Map("mode" -> List("normative"))
    }
  }

  "Every FHIR request" should {
    "carry the FHIR JSON Accept header and a non empty X-Request-Id" in {
      val builders = Seq(
        client.create(patient),
        client.read("Patient", "p1"),
        client.update(patient),
        client.delete("Patient", "p1"),
        client.vread("Patient", "p1", "2"),
        client.capabilities()
      )

      val recorded = builders.map(send)

      recorded.map(_.header("Accept")).distinct mustEqual Seq(Some("application/fhir+json"))
      recorded.flatMap(_.header("X-Request-Id")).filter(_.nonEmpty) must haveSize(builders.size)
      recorded.flatMap(_.header("X-Request-Id")).distinct must haveSize(builders.size)
    }
  }
}

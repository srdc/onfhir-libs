package io.onfhir.client

import io.onfhir.api.Resource
import io.onfhir.api.service.IFhirIdentityCache
import io.onfhir.client.testutil.{CannedResponses, WithMockFhirServer}
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.Source

/**
 * Request and response contract of the identity resolution facade built on the
 * onFHIR client, including the optional identity cache.
 */
@RunWith(classOf[JUnitRunner])
class IdentityServiceClientContractTest extends Specification with WithMockFhirServer {
  sequential

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def identityService(cache: Option[IFhirIdentityCache] = None): IdentityServiceClient =
    new IdentityServiceClient(OnFhirNetworkClient(mockServer.baseUrl), cache)

  private def loadResource(name: String): Resource =
    Source.fromInputStream(getClass.getResourceAsStream(name)).mkString.parseJson

  private def patientWithLink(id: String, linkType: String): JObject =
    CannedResponses.patient(
      id,
      "link" -> JArray(List(JObject(List(
        "other" -> JObject(List("reference" -> JString("Patient/other"))),
        "type" -> JString(linkType)
      ))))
    )

  /** In memory identity cache recording how often identities are stored */
  private class RecordingIdentityCache extends IFhirIdentityCache {
    private val entries: mutable.Map[(String, String, Option[String]), String] = mutable.Map.empty
    var storeCount: Int = 0

    override def findMatching(
      resourceType: String,
      identifier: String,
      system: Option[String]): Future[Option[String]] =
      Future.successful(entries.get((resourceType, identifier, system)))

    override def storeIdentity(
      resourceType: String,
      identifier: String,
      system: Option[String],
      correspondingId: String): Future[Unit] = {
      storeCount += 1
      entries.update((resourceType, identifier, system), correspondingId)
      Future.successful(())
    }
  }

  "IdentityServiceClient.findMatching for a Patient" should {
    "search on the identifier and ask only for the link element" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(
        200,
        CannedResponses.searchSetBundle(1, matches = Seq(CannedResponses.patient("p1"))).toJson)

      val found = Await.result(
        identityService().findMatching("Patient", "12345", Some("urn:oid:1.2.3")),
        5.seconds)

      mockServer.lastRequest.method mustEqual "GET"
      mockServer.lastRequest.relativePath mustEqual "/Patient"
      mockServer.lastRequest.queryParams mustEqual Map(
        "identifier" -> List("urn:oid:1.2.3|12345"),
        "_elements" -> List("link")
      )
      found must beSome("p1")
    }

    "search without a system prefix when no system is given" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(
        200,
        CannedResponses.searchSetBundle(1, matches = Seq(CannedResponses.patient("p1"))).toJson)

      Await.result(identityService().findMatching("Patient", "12345"), 5.seconds) must beSome("p1")

      mockServer.lastRequest.queryParams mustEqual Map(
        "identifier" -> List("12345"),
        "_elements" -> List("link")
      )
    }

    "return None for an empty search set" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(200, CannedResponses.searchSetBundle(0).toJson)

      Await.result(identityService().findMatching("Patient", "12345"), 5.seconds) must beNone
    }

    "skip a patient whose link disqualifies it and pick the replacing one" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(
        200,
        CannedResponses.searchSetBundle(
          2,
          matches = Seq(loadResource("/patient-with-link.json"), loadResource("/patient-with-link2.json"))
        ).toJson)

      Await.result(identityService().findMatching("Patient", "654321", Some("urn:oid:0.1.2.3.4.5.6.7")), 5.seconds) must
        beSome("pat2")
    }

    "accept a patient that carries a seealso link" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(
        200,
        CannedResponses.searchSetBundle(
          2,
          matches = Seq(patientWithLink("p1", "replaced-by"), patientWithLink("p2", "seealso"))
        ).toJson)

      Await.result(identityService().findMatching("Patient", "12345"), 5.seconds) must beSome("p2")
    }

    "accept a patient without any link" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(
        200,
        CannedResponses.searchSetBundle(
          2,
          matches = Seq(patientWithLink("p1", "refer"), CannedResponses.patient("p2"))
        ).toJson)

      Await.result(identityService().findMatching("Patient", "12345"), 5.seconds) must beSome("p2")
    }

    "return None when every match is disqualified by its link" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(
        200,
        CannedResponses.searchSetBundle(
          1,
          matches = Seq(patientWithLink("p1", "replaced-by"))
        ).toJson)

      Await.result(identityService().findMatching("Patient", "12345"), 5.seconds) must beNone
    }
  }

  "IdentityServiceClient.findMatching for a non Patient type" should {
    "ask for the text summary and take the first match" in {
      mockServer.reset()
      mockServer.stub("GET", "/Encounter")(
        200,
        CannedResponses.searchSetBundle(
          2,
          matches = Seq(CannedResponses.resource("Encounter", "e1"), CannedResponses.resource("Encounter", "e2"))
        ).toJson)

      val found = Await.result(
        identityService().findMatching("Encounter", "enc-1", Some("urn:oid:9.9")),
        5.seconds)

      mockServer.lastRequest.relativePath mustEqual "/Encounter"
      mockServer.lastRequest.queryParams mustEqual Map(
        "identifier" -> List("urn:oid:9.9|enc-1"),
        "_summary" -> List("text")
      )
      found must beSome("e1")
    }
  }

  "IdentityServiceClient with an identity cache" should {
    "store a resolved identity once and answer the second call from the cache" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(
        200,
        CannedResponses.searchSetBundle(1, matches = Seq(CannedResponses.patient("p1"))).toJson)
      val cache = new RecordingIdentityCache
      val service = identityService(Some(cache))

      Await.result(service.findMatching("Patient", "12345", Some("urn:oid:1.2.3")), 5.seconds) must beSome("p1")
      mockServer.requestCount mustEqual 1
      cache.storeCount mustEqual 1

      Await.result(service.findMatching("Patient", "12345", Some("urn:oid:1.2.3")), 5.seconds) must beSome("p1")
      mockServer.requestCount mustEqual 1
      cache.storeCount mustEqual 1
    }

    "not store anything when the identity cannot be resolved" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(200, CannedResponses.searchSetBundle(0).toJson)
      val cache = new RecordingIdentityCache
      val service = identityService(Some(cache))

      Await.result(service.findMatching("Patient", "missing"), 5.seconds) must beNone
      cache.storeCount mustEqual 0
      mockServer.requestCount mustEqual 1

      Await.result(service.findMatching("Patient", "missing"), 5.seconds) must beNone
      // NOTE: documents current behavior, see plan Findings - negative results are
      // not cached, so every unresolved lookup hits the FHIR server again.
      mockServer.requestCount mustEqual 2
    }
  }
}

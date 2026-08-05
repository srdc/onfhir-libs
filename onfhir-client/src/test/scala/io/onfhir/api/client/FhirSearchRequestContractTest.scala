package io.onfhir.api.client

import io.onfhir.client.OnFhirNetworkClient
import io.onfhir.client.testutil.{RecordedRequest, WithMockFhirServer}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
 * Wire level contract of the FHIR search request builder.
 */
@RunWith(classOf[JUnitRunner])
class FhirSearchRequestContractTest extends Specification with WithMockFhirServer {
  sequential

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def client: OnFhirNetworkClient = OnFhirNetworkClient(mockServer.baseUrl)

  private def send(builder: FhirRequestBuilder): RecordedRequest = {
    Await.result(builder.execute(), 5.seconds)
    mockServer.lastRequest
  }

  "FHIR search where semantics" should {
    "repeat the query parameter for repeated where calls (AND)" in {
      val recorded = send(
        client.search("Observation")
          .where("code", "http://loinc.org|15074-8")
          .where("code", "http://loinc.org|1975-2"))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/Observation"
      recorded.queryParams mustEqual Map("code" -> List("http://loinc.org|15074-8", "http://loinc.org|1975-2"))
      recorded.rawQuery must beSome("code=http%3A%2F%2Floinc.org%7C15074-8&code=http%3A%2F%2Floinc.org%7C1975-2")
    }

    "join the values of a single where call with a comma (OR)" in {
      val recorded = send(client.search("Observation").where("status", "final", "amended"))

      recorded.queryParams mustEqual Map("status" -> List("final,amended"))
      recorded.rawQuery must beSome("status=final%2Camended")
    }

    "accept a parsed query map and split its comma separated values" in {
      val recorded = send(client.search("Observation").where(Map("status" -> List("final,amended"))))

      recorded.queryParams mustEqual Map("status" -> List("final,amended"))
    }
  }

  "FHIR search result parameters" should {
    "render the page size as _count" in {
      val recorded = send(client.search("Patient", 25))

      recorded.queryParams mustEqual Map("_count" -> List("25"))
    }

    "render ascending and descending sorts as one comma separated _sort parameter" in {
      val recorded = send(client.search("Patient").sortOnAsc("birthdate").sortOnDesc("name"))

      recorded.queryParams mustEqual Map("_sort" -> List("birthdate,-name"))
      recorded.rawQuery must beSome("_sort=birthdate%2C-name")
    }

    "combine where, sort and count parameters" in {
      val recorded = send(client.search("Patient", 5).where("gender", "male").sortOnDesc("birthdate"))

      recorded.queryParams mustEqual Map(
        "gender" -> List("male"),
        "_sort" -> List("-birthdate"),
        "_count" -> List("5")
      )
    }
  }

  "FHIR compartment search" should {
    "GET the compartment scoped type endpoint" in {
      val recorded = send(client.search("Observation").forCompartment("Patient", "p1").where("code", "1975-2"))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/Patient/p1/Observation"
      recorded.queryParams mustEqual Map("code" -> List("1975-2"))
    }
  }

  "FHIR search handling preferences" should {
    "render strict handling into the Prefer header" in {
      send(client.search("Patient").strictHandling()).header("Prefer") must beSome("handling=strict")
    }

    "render lenient handling into the Prefer header" in {
      send(client.search("Patient").lenientHandling()).header("Prefer") must beSome("handling=lenient")
    }
  }

  "FHIR search pagination parameters" should {
    "render setPage as _page" in {
      send(client.search("Patient").setPage(3)).queryParams mustEqual Map("_page" -> List("3"))
    }

    "render setSearchAfter as _searchafter" in {
      send(client.search("Patient").setSearchAfter("abc")).queryParams mustEqual Map("_searchafter" -> List("abc"))
    }

    "render setSearchBefore as _searchbefore" in {
      send(client.search("Patient").setSearchBefore("xyz")).queryParams mustEqual Map("_searchbefore" -> List("xyz"))
    }

    "render a custom pagination parameter" in {
      val recorded = send(client.search("Patient").setPaginationParam("_getpagesoffset", 20))

      recorded.queryParams mustEqual Map("_getpagesoffset" -> List("20"))
    }
  }

  "FHIR search by HTTP POST" should {
    "POST a form encoded body to the _search endpoint and leave the url query empty" in {
      val recorded = send(
        client.search("Patient")
          .where("identifier", "urn:oid:1|123")
          .where("gender", "male")
          .byHttpPost())

      recorded.method mustEqual "POST"
      recorded.relativePath mustEqual "/Patient/_search"
      recorded.rawQuery must beNone
      recorded.header("Content-Type") must beSome("application/x-www-form-urlencoded; charset=UTF-8")
      recorded.body.split('&').toSet mustEqual Set("identifier=urn%3Aoid%3A1%7C123", "gender=male")
    }

    "POST to the compartment scoped _search endpoint" in {
      val recorded = send(
        client.search("Observation")
          .forCompartment("Patient", "p1")
          .where("code", "1975-2")
          .byHttpPost())

      recorded.method mustEqual "POST"
      recorded.relativePath mustEqual "/Patient/p1/Observation/_search"
      recorded.rawQuery must beNone
      recorded.body mustEqual "code=1975-2"
    }

    "send no body when a POST search has no parameters" in {
      val recorded = send(client.search("Patient").byHttpPost())

      recorded.method mustEqual "POST"
      recorded.relativePath mustEqual "/Patient/_search"
      recorded.body mustEqual ""
      recorded.header("Content-Type") must beNone
    }
  }

  "FHIR history requests" should {
    "GET the instance level _history endpoint" in {
      val recorded = send(client.history("Patient", "p1"))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/Patient/p1/_history"
      recorded.rawQuery must beNone
    }

    "GET the type level _history endpoint with a page size" in {
      val recorded = send(client.history("Patient", 10))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/Patient/_history"
      recorded.queryParams mustEqual Map("_count" -> List("10"))
    }
  }
}

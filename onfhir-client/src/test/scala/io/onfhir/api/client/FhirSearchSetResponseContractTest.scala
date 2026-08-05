package io.onfhir.api.client

import io.onfhir.api.util.FHIRUtil
import io.onfhir.client.OnFhirNetworkClient
import io.onfhir.client.testutil.{CannedResponses, MockResponse, WithMockFhirServer}
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonAST.JString
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

/**
 * Parsing of canned searchset bundles and the pagination contract of the client.
 */
@RunWith(classOf[JUnitRunner])
class FhirSearchSetResponseContractTest extends Specification with WithMockFhirServer {
  sequential

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def client: OnFhirNetworkClient = OnFhirNetworkClient(mockServer.baseUrl)

  private def observation(id: String, subject: String): org.json4s.JsonAST.JObject =
    CannedResponses.resource(
      "Observation",
      id,
      "status" -> JString("final"),
      "subject" -> org.json4s.JsonAST.JObject(List("reference" -> JString(subject)))
    )

  private def idsOf(resources: Seq[org.json4s.JsonAST.JObject]): Seq[String] =
    resources.map(FHIRUtil.extractIdFromResource)

  "A canned searchset bundle" should {
    "split match and include entries and expose the total" in {
      mockServer.reset()
      mockServer.stub("GET", "/Observation")(
        200,
        CannedResponses.searchSetBundle(
          total = 2,
          matches = Seq(observation("o1", "Patient/p1"), observation("o2", "Patient/p2")),
          includes = Seq(CannedResponses.patient("p1"), CannedResponses.patient("p2"))
        ).toJson)

      val bundle = Await.result(client.search("Observation").executeAndReturnBundle(), 5.seconds)

      bundle.total must beSome(2L)
      idsOf(bundle.searchResults) mustEqual Seq("o1", "o2")
      bundle.includedResults.keySet mustEqual Set("Patient/p1", "Patient/p2")
      FHIRUtil.extractIdFromResource(bundle.includedResults("Patient/p1")) mustEqual "p1"
      bundle.hasNext() must beFalse
    }

    "filter the match results by resource type" in {
      mockServer.reset()
      mockServer.stub("GET", "/Observation")(
        200,
        CannedResponses.searchSetBundle(
          total = 3,
          matches = Seq(observation("o1", "Patient/p1"), CannedResponses.patient("p9"), observation("o2", "Patient/p2"))
        ).toJson)

      val bundle = Await.result(client.search("Observation").executeAndReturnBundle(), 5.seconds)

      idsOf(bundle.getSearchResultsWithResourceType("Observation")) mustEqual Seq("o1", "o2")
      idsOf(bundle.getSearchResultsWithResourceType("Patient")) mustEqual Seq("p9")
    }

    "expose the next link" in {
      mockServer.reset()
      val nextLink = s"${mockServer.baseUrl}/Observation?_page=2"
      mockServer.stub("GET", "/Observation")(
        200,
        CannedResponses.searchSetBundle(
          total = 10,
          matches = Seq(observation("o1", "Patient/p1")),
          nextLink = Some(nextLink),
          selfLink = Some(s"${mockServer.baseUrl}/Observation?_page=1")
        ).toJson)

      val bundle = Await.result(client.search("Observation").executeAndReturnBundle(), 5.seconds)

      bundle.hasNext() must beTrue
      bundle.getNext() mustEqual nextLink
      bundle.getNextPage("_page") must beSome("2")
    }
  }

  "OnFhirNetworkClient.next with an explicit pagination parameter" should {
    "re-issue the original query with the page value taken from the next link" in {
      mockServer.reset()
      mockServer.stubSequence("GET", "/Patient")(
        MockResponse(200, CannedResponses.searchSetBundle(
          total = 4,
          matches = Seq(CannedResponses.patient("p1")),
          nextLink = Some(s"${mockServer.baseUrl}/Patient?gender=male&_page=2")
        ).toJson),
        MockResponse(200, CannedResponses.searchSetBundle(
          total = 4,
          matches = Seq(CannedResponses.patient("p2"))
        ).toJson)
      )

      val builder = client.search("Patient").where("gender", "male").setPage(1)
      val firstPage = Await.result(builder.executeAndReturnBundle(), 5.seconds)
      mockServer.lastRequest.queryParams mustEqual Map("gender" -> List("male"), "_page" -> List("1"))

      val secondPage = Await.result(client.next(firstPage), 5.seconds)

      mockServer.lastRequest.relativePath mustEqual "/Patient"
      mockServer.lastRequest.queryParams mustEqual Map("gender" -> List("male"), "_page" -> List("2"))
      idsOf(secondPage.searchResults) mustEqual Seq("p2")
    }
  }

  "OnFhirNetworkClient.next without an explicit pagination parameter" should {
    "follow the next link as a search page request" in {
      mockServer.reset()
      mockServer.stubSequence("GET", "/Patient")(
        MockResponse(200, CannedResponses.searchSetBundle(
          total = 4,
          matches = Seq(CannedResponses.patient("p1")),
          nextLink = Some(s"${mockServer.baseUrl}/Patient?gender=male&_getpagesoffset=2")
        ).toJson),
        MockResponse(200, CannedResponses.searchSetBundle(
          total = 4,
          matches = Seq(CannedResponses.patient("p2"))
        ).toJson)
      )

      val firstPage = Await.result(client.search("Patient").where("gender", "male").executeAndReturnBundle(), 5.seconds)
      val secondPage = Await.result(client.next(firstPage), 5.seconds)

      mockServer.lastRequest.method mustEqual "GET"
      mockServer.lastRequest.relativePath mustEqual "/Patient"
      mockServer.lastRequest.rawQuery must beSome("gender=male&_getpagesoffset=2")
      idsOf(secondPage.searchResults) mustEqual Seq("p2")
    }
  }

  "getSearchPage" should {
    "accept a whole link starting with the server base url" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(200, CannedResponses.searchSetBundle(0).toJson)

      Await.result(client.getSearchPage(s"${mockServer.baseUrl}/Patient?_page=3").executeAndReturnBundle(), 5.seconds)

      mockServer.lastRequest.relativePath mustEqual "/Patient"
      mockServer.lastRequest.rawQuery must beSome("_page=3")
    }

    "accept a server relative link starting with a path" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(200, CannedResponses.searchSetBundle(0).toJson)

      Await.result(client.getSearchPage("/Patient?_page=4").executeAndReturnBundle(), 5.seconds)

      mockServer.lastRequest.relativePath mustEqual "/Patient"
      mockServer.lastRequest.rawQuery must beSome("_page=4")
    }

    "accept a link that is only a query" in {
      mockServer.reset()
      mockServer.stub("GET", "/")(200, CannedResponses.searchSetBundle(0).toJson)

      Await.result(client.getSearchPage("?_getpages=abc&_getpagesoffset=10").executeAndReturnBundle(), 5.seconds)

      mockServer.lastRequest.relativePath mustEqual "/"
      mockServer.lastRequest.rawQuery must beSome("_getpages=abc&_getpagesoffset=10")
    }

    "reject a link that is neither absolute for this server nor relative" in {
      client.getSearchPage("http://other.example.com/fhir/Patient?_page=1") must
        throwAn[IllegalArgumentException]
    }

    "reject where(..) because the page link already carries the query" in {
      client.getSearchPage("/Patient?_page=1").where("gender", "male") must throwAn[IllegalAccessError]
    }
  }

  "toIterator" should {
    "walk the pages of a result set" in {
      mockServer.reset()
      mockServer.stubSequence("GET", "/Patient")(
        MockResponse(200, CannedResponses.searchSetBundle(
          total = 2,
          matches = Seq(CannedResponses.patient("p1")),
          nextLink = Some(s"${mockServer.baseUrl}/Patient?_page=2")
        ).toJson),
        MockResponse(200, CannedResponses.searchSetBundle(
          total = 2,
          matches = Seq(CannedResponses.patient("p2"))
        ).toJson)
      )

      val iterator = client.search("Patient").toIterator().asInstanceOf[SearchSetIterator]

      iterator.hasNext must beTrue
      val firstPage = Await.result(iterator.next(), 5.seconds)
      idsOf(firstPage.searchResults) mustEqual Seq("p1")
      //The page is recorded before the returned future completes
      iterator.latestBundle.map(bundle => idsOf(bundle.searchResults)) must beSome(Seq("p1"))

      iterator.hasNext must beTrue
      val secondPage = Await.result(iterator.next(), 5.seconds)
      idsOf(secondPage.searchResults) mustEqual Seq("p2")
      iterator.latestBundle.map(_.hasNext()) must beSome(false)

      iterator.hasNext must beFalse
    }
  }

  "executeAndMergeBundle" should {
    "merge the match and include results of every page" in {
      mockServer.reset()
      mockServer.stubSequence("GET", "/Observation")(
        MockResponse(200, CannedResponses.searchSetBundle(
          total = 3,
          matches = Seq(observation("o1", "Patient/p1")),
          includes = Seq(CannedResponses.patient("p1")),
          nextLink = Some(s"${mockServer.baseUrl}/Observation?_page=2")
        ).toJson),
        MockResponse(200, CannedResponses.searchSetBundle(
          total = 3,
          matches = Seq(observation("o2", "Patient/p2")),
          includes = Seq(CannedResponses.patient("p2")),
          nextLink = Some(s"${mockServer.baseUrl}/Observation?_page=3")
        ).toJson),
        MockResponse(200, CannedResponses.searchSetBundle(
          total = 3,
          matches = Seq(observation("o3", "Patient/p3")),
          includes = Seq(CannedResponses.patient("p3"))
        ).toJson)
      )

      val merged = Await.result(client.search("Observation").executeAndMergeBundle(), 5.seconds)

      idsOf(merged.searchResults) mustEqual Seq("o1", "o2", "o3")
      merged.includedResults.keySet mustEqual Set("Patient/p1", "Patient/p2", "Patient/p3")
      mockServer.requests.map(_.rawQuery) mustEqual Seq(None, Some("_page=2"), Some("_page=3"))
      //The merged bundle represents the whole result set, so there is no further page
      merged.hasNext() must beFalse
    }
  }

  "A failed search" should {
    "fail executeAndReturnBundle with a FhirClientException carrying the server response" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(
        400,
        CannedResponses.operationOutcome(
          CannedResponses.issue("error", "invalid", "Unknown search parameter", Seq("Patient.foo"))).toJson)

      val thrown = Try(Await.result(client.search("Patient").executeAndReturnBundle(), 5.seconds)).failed.get

      thrown must beAnInstanceOf[FhirClientException]
      val serverResponse = thrown.asInstanceOf[FhirClientException].serverResponse
      serverResponse.map(_.httpStatus.intValue()) must beSome(400)
      serverResponse.toSeq.flatMap(_.outcomeIssues).map(_.code) mustEqual Seq("invalid")
    }

    "let execute() succeed with an error response carrying parsed outcome issues" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(
        400,
        CannedResponses.operationOutcome(
          org.json4s.JsonAST.JObject(List(
            "severity" -> JString("error"),
            "code" -> JString("invalid"),
            "details" -> org.json4s.JsonAST.JObject(List(
              "coding" -> org.json4s.JsonAST.JArray(List(org.json4s.JsonAST.JObject(List(
                "system" -> JString("http://example.com/codes"),
                "code" -> JString("MSG_PARAM_UNKNOWN")
              )))),
              "text" -> JString("Unknown search parameter")
            )),
            "location" -> org.json4s.JsonAST.JArray(List(JString("Patient.foo")))
          ))).toJson)

      val response = Await.result(client.search("Patient").execute(), 5.seconds)

      response.isError must beTrue
      response.httpStatus.intValue() mustEqual 400
      response.outcomeIssues must haveSize(1)
      response.outcomeIssues.head.severity mustEqual "error"
      response.outcomeIssues.head.details must beSome("MSG_PARAM_UNKNOWN")
      response.outcomeIssues.head.diagnostics must beSome("Unknown search parameter")
      //'location' (DSTU2/STU3 style) is the fallback when 'expression' is absent
      response.outcomeIssues.head.expression mustEqual Seq("Patient.foo")
    }

    "parse the expression element of an outcome issue" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient")(
        422,
        CannedResponses.operationOutcome(
          CannedResponses.issue("error", "invalid", "Unknown search parameter", Seq("Patient.foo", "Patient.bar"))).toJson)

      val response = Await.result(client.search("Patient").execute(), 5.seconds)

      response.outcomeIssues.head.expression mustEqual Seq("Patient.foo", "Patient.bar")
      response.outcomeIssues.head.diagnostics must beSome("Unknown search parameter")
      response.outcomeIssues.head.details must beNone
    }
  }
}

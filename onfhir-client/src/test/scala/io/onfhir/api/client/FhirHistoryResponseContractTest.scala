package io.onfhir.api.client

import io.onfhir.api.util.FHIRUtil
import io.onfhir.client.OnFhirNetworkClient
import io.onfhir.client.testutil.CannedResponses.HistoryEntry
import io.onfhir.client.testutil.{CannedResponses, MockResponse, WithMockFhirServer}
import io.onfhir.util.DateTimeUtil
import io.onfhir.util.JsonFormatter._
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
 * Request and response contract of the FHIR history interaction.
 */
@RunWith(classOf[JUnitRunner])
class FhirHistoryResponseContractTest extends Specification with WithMockFhirServer {
  sequential

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def client: OnFhirNetworkClient = OnFhirNetworkClient(mockServer.baseUrl)

  private val firstVersionTime = Instant.parse("2020-01-01T10:00:00Z")
  private val secondVersionTime = Instant.parse("2020-02-02T11:30:00Z")

  private def patientHistory(rid: String): Seq[HistoryEntry] = Seq(
    HistoryEntry(
      method = "PUT",
      url = s"Patient/$rid",
      status = "200 OK",
      lastModified = DateTimeUtil.serializeInstant(secondVersionTime),
      etag = Some("W/\"2\""),
      resource = Some(CannedResponses.patient(rid, CannedResponses.meta("2")))
    ),
    HistoryEntry(
      method = "PUT",
      url = s"Patient/$rid",
      status = "200 OK",
      lastModified = DateTimeUtil.serializeInstant(firstVersionTime),
      etag = Some("W/\"1\""),
      resource = Some(CannedResponses.patient(rid, CannedResponses.meta("1")))
    )
  )

  "FHIR history requests" should {
    "GET the instance level history endpoint with _since" in {
      val since = Instant.parse("2020-01-01T00:00:00Z")
      mockServer.stub("GET", "/Patient/p1/_history")(
        200, CannedResponses.historyBundle(patientHistory("p1")).toJson)

      Await.result(client.history("Patient", "p1").since(since).executeAndReturnBundle(), 5.seconds)

      mockServer.lastRequest.method mustEqual "GET"
      mockServer.lastRequest.relativePath mustEqual "/Patient/p1/_history"
      mockServer.lastRequest.queryParams mustEqual Map("_since" -> List(DateTimeUtil.serializeInstant(since)))
    }

    "render _at from a zoned date time" in {
      val at = ZonedDateTime.of(2021, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC)
      mockServer.stub("GET", "/Patient/p1/_history")(
        200, CannedResponses.historyBundle(patientHistory("p1")).toJson)

      Await.result(client.history("Patient", "p1").at(at).executeAndReturnBundle(), 5.seconds)

      mockServer.lastRequest.queryParams mustEqual
        Map("_at" -> List(DateTimeUtil.serializeInstant(at.toInstant)))
    }

    "render _list as a comma separated list and _count from the builder" in {
      mockServer.stub("GET", "/Patient/p1/_history")(
        200, CannedResponses.historyBundle(patientHistory("p1")).toJson)

      Await.result(client.history("Patient", "p1", 5).list("list-a", "list-b").execute(), 5.seconds)

      mockServer.lastRequest.queryParams mustEqual
        Map("_list" -> List("list-a,list-b"), "_count" -> List("5"))
    }

    "GET the type level history endpoint" in {
      mockServer.stub("GET", "/Patient/_history")(
        200, CannedResponses.historyBundle(patientHistory("p1")).toJson)

      Await.result(client.history("Patient").executeAndReturnBundle(), 5.seconds)

      mockServer.lastRequest.relativePath mustEqual "/Patient/_history"
      mockServer.lastRequest.rawQuery must beNone
    }
  }

  "A canned instance level history bundle" should {
    "expose the versions, contents and update times in bundle order" in {
      mockServer.stub("GET", "/Patient/p1/_history")(
        200, CannedResponses.historyBundle(patientHistory("p1")).toJson)

      val bundle = Await.result(client.history("Patient", "p1").executeAndReturnBundle(), 5.seconds)

      bundle.getHistory().map(_._1) mustEqual Seq(2L, 1L)
      bundle.getHistory().map(_._3) mustEqual Seq(secondVersionTime, firstVersionTime)
      bundle.getHistory().flatMap(_._2).map(FHIRUtil.extractIdFromResource) mustEqual Seq("p1", "p1")
      bundle.getHistory("p1").map(_._1) mustEqual Seq(2L, 1L)
      bundle.getHistory("unknown") must beEmpty
    }

    "fall back to the resource version when the entry carries no etag" in {
      mockServer.stub("GET", "/Patient/p1/_history")(
        200,
        CannedResponses.historyBundle(Seq(
          HistoryEntry(
            method = "PUT",
            url = "Patient/p1",
            status = "200 OK",
            lastModified = DateTimeUtil.serializeInstant(secondVersionTime),
            etag = None,
            resource = Some(CannedResponses.patient("p1", CannedResponses.meta("7")))
          )
        )).toJson)

      val bundle = Await.result(client.history("Patient", "p1").executeAndReturnBundle(), 5.seconds)

      bundle.getHistory().map(_._1) mustEqual Seq(7L)
    }
  }

  "A canned type level history bundle" should {
    "group the versions per resource id taken from the entry url" in {
      mockServer.stub("GET", "/Patient/_history")(
        200, CannedResponses.historyBundle(patientHistory("p1") ++ patientHistory("p2")).toJson)

      val bundle = Await.result(client.history("Patient").executeAndReturnBundle(), 5.seconds)

      bundle.getHistories.keySet mustEqual Set("p1", "p2")
      bundle.getHistories("p1").map(_._1) mustEqual Seq(2L, 1L)
      bundle.getHistory("p2").map(_._3) mustEqual Seq(secondVersionTime, firstVersionTime)
    }

    "group create entries by the id of the contained resource" in {
      mockServer.stub("GET", "/Patient/_history")(
        200,
        CannedResponses.historyBundle(Seq(
          HistoryEntry(
            method = "POST",
            url = "Patient",
            status = "201 Created",
            lastModified = DateTimeUtil.serializeInstant(firstVersionTime),
            etag = Some("W/\"1\""),
            resource = Some(CannedResponses.patient("p3", CannedResponses.meta("1")))
          ),
          HistoryEntry(
            method = "CREATE",
            url = "Patient",
            status = "201 Created",
            lastModified = DateTimeUtil.serializeInstant(firstVersionTime),
            etag = Some("W/\"1\""),
            resource = Some(CannedResponses.patient("p4", CannedResponses.meta("1")))
          )
        )).toJson)

      val bundle = Await.result(client.history("Patient").executeAndReturnBundle(), 5.seconds)

      bundle.getHistories.keySet mustEqual Set("p3", "p4")
    }
  }

  "History pagination" should {
    "re-issue the history request with the page value taken from the next link" in {
      mockServer.reset()
      mockServer.stubSequence("GET", "/Patient/_history")(
        MockResponse(200, CannedResponses.historyBundle(
          patientHistory("p1"),
          nextLink = Some(s"${mockServer.baseUrl}/Patient/_history?_page=2")
        ).toJson),
        MockResponse(200, CannedResponses.historyBundle(patientHistory("p2")).toJson)
      )

      val builder = client.history("Patient").setPaginationParam("_page", 1)
      val firstPage = Await.result(builder.executeAndReturnBundle(), 5.seconds)

      firstPage.hasNext() must beTrue
      mockServer.lastRequest.queryParams mustEqual Map("_page" -> List("1"))

      val secondPage = Await.result(client.next(firstPage), 5.seconds)

      mockServer.lastRequest.relativePath mustEqual "/Patient/_history"
      mockServer.lastRequest.queryParams mustEqual Map("_page" -> List("2"))
      secondPage.getHistories.keySet mustEqual Set("p2")
      secondPage.hasNext() must beFalse
    }
  }

  "A malformed history bundle" should {
    "fail with a FhirClientException" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient/p1/_history")(
        200,
        org.json4s.JsonAST.JObject(List(
          "resourceType" -> org.json4s.JsonAST.JString("Bundle"),
          "type" -> org.json4s.JsonAST.JString("history"),
          "entry" -> org.json4s.JsonAST.JArray(List(
            org.json4s.JsonAST.JObject(List("resource" -> CannedResponses.patient("p1")))
          ))
        )).toJson)

      val thrown = scala.util.Try(
        Await.result(client.history("Patient", "p1").executeAndReturnBundle(), 5.seconds)).failed.get

      thrown must beAnInstanceOf[FhirClientException]
    }
  }
}

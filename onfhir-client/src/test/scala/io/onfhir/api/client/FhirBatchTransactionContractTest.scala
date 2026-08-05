package io.onfhir.api.client

import io.onfhir.client.OnFhirNetworkClient
import io.onfhir.client.testutil.CannedResponses.TransactionEntry
import io.onfhir.client.testutil.{CannedResponses, RecordedRequest, WithMockFhirServer}
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonAST.{JArray, JObject, JString, JValue}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

/**
 * Wire level contract of the FHIR batch and transaction request builder plus the
 * parsing of a canned batch/transaction response bundle.
 */
@RunWith(classOf[JUnitRunner])
class FhirBatchTransactionContractTest extends Specification with WithMockFhirServer {
  sequential

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def client: OnFhirNetworkClient = OnFhirNetworkClient(mockServer.baseUrl)

  private val patient = CannedResponses.patient("p1", "gender" -> JString("male"))
  private val newPatient = JObject(List("resourceType" -> JString("Patient"), "gender" -> JString("female")))

  private def send(builder: FhirRequestBuilder): RecordedRequest = {
    Await.result(builder.execute(), 5.seconds)
    mockServer.lastRequest
  }

  private def entries(recorded: RecordedRequest): List[JValue] =
    (recorded.bodyResource \ "entry").asInstanceOf[JArray].arr

  private def requestOf(entry: JValue): JObject = (entry \ "request").asInstanceOf[JObject]

  "FHIR batch requests" should {
    "POST a Bundle of type batch to the server base" in {
      val recorded = send(client.batch().entry(_.read("Patient", "p1")))

      recorded.method mustEqual "POST"
      recorded.relativePath mustEqual "/"
      recorded.rawQuery must beNone
      recorded.header("Content-Type") must beSome("application/fhir+json; charset=UTF-8")
      (recorded.bodyResource \ "resourceType").extract[String] mustEqual "Bundle"
      (recorded.bodyResource \ "type").extract[String] mustEqual "batch"
    }
  }

  "FHIR transaction requests" should {
    "POST a Bundle of type transaction to the server base" in {
      val recorded = send(client.transaction().entry(_.read("Patient", "p1")))

      recorded.method mustEqual "POST"
      recorded.relativePath mustEqual "/"
      (recorded.bodyResource \ "type").extract[String] mustEqual "transaction"
    }
  }

  "FHIR bundle entries" should {
    "render the method and a base relative url for each child interaction" in {
      val recorded = send(
        client.transaction()
          .entry(_.create(newPatient))
          .entry(_.update(patient))
          .entry(_.read("Patient", "p1"))
          .entry(_.delete("Patient").where("identifier", "urn:oid:1|123"))
          .entry(_.search("Observation").where("code", "1975-2")))

      val bundleEntries = entries(recorded)
      bundleEntries must haveSize(5)
      bundleEntries.map(entry => (requestOf(entry) \ "method").extract[String]) mustEqual
        List("POST", "PUT", "GET", "DELETE", "GET")
      bundleEntries.map(entry => (requestOf(entry) \ "url").extract[String]) mustEqual
        List("Patient", "Patient/p1", "Patient/p1", "Patient?identifier=urn%3Aoid%3A1%7C123", "Observation?code=1975-2")
    }

    "carry the child resource only for entries that have a body" in {
      val recorded = send(
        client.transaction()
          .entry(_.create(newPatient))
          .entry(_.read("Patient", "p1")))

      val bundleEntries = entries(recorded)
      (bundleEntries.head \ "resource") mustEqual newPatient
      (bundleEntries(1) \ "resource") mustEqual org.json4s.JsonAST.JNothing
    }

    "add fullUrl only for entries created with a urn:uuid identifier" in {
      val recorded = send(
        client.transaction()
          .entry("urn:uuid:0f4c1b1c-1111-4444-8888-000000000001", _.create(newPatient))
          .entry(_.create(newPatient)))

      val bundleEntries = entries(recorded)
      (bundleEntries.head \ "fullUrl").extract[String] mustEqual "urn:uuid:0f4c1b1c-1111-4444-8888-000000000001"
      (bundleEntries(1) \ "fullUrl") mustEqual org.json4s.JsonAST.JNothing
    }

    "reject a fullUrl that is not in urn:uuid form immediately" in {
      client.transaction().entry("http://example.com/Patient/p1", _.create(newPatient)) must
        throwA[FhirClientException]
    }

    "render child conditional create parameters as request.ifNoneExist" in {
      val recorded = send(
        client.transaction()
          .entry(_.create(newPatient).where("identifier", "urn:oid:1|123")))

      val request = requestOf(entries(recorded).head)
      (request \ "method").extract[String] mustEqual "POST"
      (request \ "url").extract[String] mustEqual "Patient"
      (request \ "ifNoneExist").extract[String] mustEqual "identifier=urn:oid:1|123"
    }

    "render a child version aware update as request.ifMatch" in {
      val versioned = CannedResponses.patient("p1", CannedResponses.meta("4"))

      val recorded = send(client.transaction().entry(_.update(versioned)))

      val request = requestOf(entries(recorded).head)
      (request \ "method").extract[String] mustEqual "PUT"
      (request \ "ifMatch").extract[String] mustEqual "W/\"4\""
    }
  }

  "FHIR entriesFromBundle" should {
    "reproduce the child entries of an existing request bundle" in {
      val requestBundle = JObject(List(
        "resourceType" -> JString("Bundle"),
        "type" -> JString("transaction"),
        "entry" -> JArray(List(
          JObject(List(
            "fullUrl" -> JString("urn:uuid:0f4c1b1c-1111-4444-8888-000000000002"),
            "resource" -> newPatient,
            "request" -> JObject(List("method" -> JString("POST"), "url" -> JString("Patient")))
          )),
          JObject(List(
            "request" -> JObject(List("method" -> JString("GET"), "url" -> JString("Patient/p1")))
          )),
          // entries without a request are skipped by the builder
          JObject(List("resource" -> patient))
        ))
      ))

      val recorded = send(client.transaction().entriesFromBundle(requestBundle))

      val bundleEntries = entries(recorded)
      bundleEntries must haveSize(2)
      bundleEntries.map(entry => (requestOf(entry) \ "method").extract[String]) mustEqual List("POST", "GET")
      bundleEntries.map(entry => (requestOf(entry) \ "url").extract[String]) mustEqual List("Patient", "Patient/p1")
      (bundleEntries.head \ "fullUrl").extract[String] mustEqual "urn:uuid:0f4c1b1c-1111-4444-8888-000000000002"
      (bundleEntries.head \ "resource") mustEqual newPatient
    }
  }

  "FHIR transaction responses" should {
    "correlate the individual responses through fullUrl" in {
      val created = "urn:uuid:0f4c1b1c-1111-4444-8888-00000000000a"
      val updated = "urn:uuid:0f4c1b1c-1111-4444-8888-00000000000b"
      mockServer.stub("POST", "/")(
        200,
        CannedResponses.transactionResponseBundle("transaction-response", Seq(
          TransactionEntry(
            fullUrl = Some(created),
            status = "201 Created",
            resource = Some(CannedResponses.patient("created-1")),
            location = Some("Patient/created-1"),
            etag = Some("W/\"1\"")
          ),
          TransactionEntry(
            fullUrl = Some(updated),
            status = "200 OK",
            resource = Some(CannedResponses.patient("p1")),
            etag = Some("W/\"5\"")
          )
        )).toJson)

      val bundle = Await.result(
        client.transaction()
          .entry(created, _.create(newPatient))
          .entry(updated, _.update(patient))
          .executeAndReturnBundle(),
        5.seconds)

      bundle.responses must haveSize(2)
      bundle.getResponse(created).httpStatus.intValue() mustEqual 201
      bundle.getResponse(created).newVersion must beSome("1")
      bundle.getResponse(created).responseBody.map(body => (body \ "id").extract[String]) must beSome("created-1")
      bundle.getResponse(updated).httpStatus.intValue() mustEqual 200
      bundle.hasAnyError() must beFalse
      bundle.hasAnyNonTransientError() must beFalse
      bundle.getUUIDsOfTransientErrors() must beEmpty
    }

    "report errors and classify 409 conflicts as transient" in {
      val conflicting = "urn:uuid:0f4c1b1c-1111-4444-8888-00000000000c"
      val invalid = "urn:uuid:0f4c1b1c-1111-4444-8888-00000000000d"
      mockServer.stub("POST", "/")(
        200,
        CannedResponses.transactionResponseBundle("batch-response", Seq(
          TransactionEntry(
            fullUrl = Some(conflicting),
            status = "409 Conflict",
            outcome = Some(CannedResponses.operationOutcome(
              CannedResponses.issue("error", "conflict", "Version conflict", Seq("Patient.meta.versionId"))))
          ),
          TransactionEntry(
            fullUrl = Some(invalid),
            status = "400 Bad Request",
            outcome = Some(CannedResponses.operationOutcome(
              CannedResponses.issue("error", "invalid", "Invalid resource", Seq("Patient.gender"))))
          )
        )).toJson)

      val bundle = Await.result(
        client.batch()
          .entry(conflicting, _.update(patient))
          .entry(invalid, _.create(newPatient))
          .executeAndReturnBundle(),
        5.seconds)

      bundle.hasAnyError() must beTrue
      bundle.hasAnyNonTransientError() must beTrue
      bundle.getUUIDsOfTransientErrors() mustEqual Seq(conflicting)
      bundle.getResponse(conflicting).outcomeIssues.map(_.code) mustEqual Seq("conflict")
      bundle.getResponse(invalid).outcomeIssues.flatMap(_.diagnostics) mustEqual Seq("Invalid resource")
    }

    "classify a bundle whose only error is a 409 as transient only" in {
      val conflicting = "urn:uuid:0f4c1b1c-1111-4444-8888-00000000000e"
      mockServer.stub("POST", "/")(
        200,
        CannedResponses.transactionResponseBundle("batch-response", Seq(
          TransactionEntry(
            fullUrl = Some(conflicting),
            status = "409 Conflict",
            outcome = Some(CannedResponses.operationOutcome(
              CannedResponses.issue("error", "conflict", "Version conflict", Seq("Patient.meta.versionId"))))
          )
        )).toJson)

      val bundle = Await.result(
        client.batch().entry(conflicting, _.update(patient)).executeAndReturnBundle(),
        5.seconds)

      bundle.hasAnyError() must beTrue
      bundle.hasAnyNonTransientError() must beFalse
      bundle.getUUIDsOfTransientErrors() mustEqual Seq(conflicting)
    }

    "fail with a FhirClientException when the server rejects the whole bundle" in {
      mockServer.stub("POST", "/")(
        400,
        CannedResponses.operationOutcome(
          CannedResponses.issue("error", "invalid", "Malformed bundle", Seq("Bundle.entry"))).toJson)

      val thrown = Try(Await.result(
        client.batch().entry(_.create(newPatient)).executeAndReturnBundle(),
        5.seconds)).failed.get

      thrown must beAnInstanceOf[FhirClientException]
      thrown.asInstanceOf[FhirClientException].serverResponse.map(_.httpStatus.intValue()) must beSome(400)
      thrown.asInstanceOf[FhirClientException].serverResponse.toSeq.flatMap(_.outcomeIssues).map(_.code) mustEqual
        Seq("invalid")
    }

    "parse a spec conformant details element of a child outcome" in {
      val failing = "urn:uuid:0f4c1b1c-1111-4444-8888-00000000000f"
      mockServer.stub("POST", "/")(
        200,
        CannedResponses.transactionResponseBundle("batch-response", Seq(
          TransactionEntry(
            fullUrl = Some(failing),
            status = "400 Bad Request",
            outcome = Some(JObject(List(
              "resourceType" -> JString("OperationOutcome"),
              "issue" -> JArray(List(JObject(List(
                "severity" -> JString("error"),
                "code" -> JString("invalid"),
                "details" -> JObject(List(
                  "coding" -> JArray(List(JObject(List(
                    "system" -> JString("http://example.com/codes"),
                    "code" -> JString("bad-request")
                  )))),
                  "text" -> JString("Invalid resource")
                )),
                "expression" -> JArray(List(JString("Patient.gender")))
              ))))
            )))
          )
        )).toJson)

      val bundle = Await.result(
        client.batch().entry(failing, _.create(newPatient)).executeAndReturnBundle(),
        5.seconds)

      val issues = bundle.getResponse(failing).outcomeIssues
      issues must haveSize(1)
      issues.head.severity mustEqual "error"
      issues.head.code mustEqual "invalid"
      issues.head.details must beSome("bad-request")
      //details.text is the diagnostics fallback when no diagnostics element is given
      issues.head.diagnostics must beSome("Invalid resource")
      issues.head.expression mustEqual Seq("Patient.gender")
    }
  }
}

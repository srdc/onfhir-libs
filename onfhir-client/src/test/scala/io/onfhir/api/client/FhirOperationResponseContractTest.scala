package io.onfhir.api.client

import io.onfhir.api.model.{FHIRMultiOperationParam, FHIRSimpleOperationParam}
import io.onfhir.client.OnFhirNetworkClient
import io.onfhir.client.testutil.{CannedResponses, WithMockFhirServer}
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonAST.{JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

/**
 * Parsing of canned FHIR operation responses into FHIROperationResponse.
 */
@RunWith(classOf[JUnitRunner])
class FhirOperationResponseContractTest extends Specification with WithMockFhirServer {
  sequential

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def client: OnFhirNetworkClient = OnFhirNetworkClient(mockServer.baseUrl)

  private def simpleParam(response: io.onfhir.api.model.FHIROperationResponse, name: String): FHIRSimpleOperationParam =
    response.getOutputParam(name) match {
      case Some(param: FHIRSimpleOperationParam) => param
      case other => throw new AssertionError(s"Expected a simple output param named $name but got $other")
    }

  "A canned Parameters operation output" should {
    "expose a resource valued return parameter" in {
      mockServer.reset()
      val returned = CannedResponses.patient("p1", "gender" -> JString("male"))
      mockServer.stub("POST", "/Patient/p1/$everything")(
        200,
        CannedResponses.parametersResource(CannedResponses.resourceParam("return", returned)).toJson)

      val response = Await.result(
        client.operation("everything")
          .on("Patient", Some("p1"))
          .addParam("start", "Date" -> JString("2020-01-01"))
          .executeAndReturnOperationOutcome(),
        5.seconds)

      response.httpStatus.intValue() mustEqual 200
      simpleParam(response, "return").value mustEqual returned
    }

    "expose primitive valued parameters" in {
      mockServer.reset()
      mockServer.stub("GET", "/ValueSet/$validate-code")(
        200,
        CannedResponses.parametersResource(
          CannedResponses.valueParam("result", "Boolean", org.json4s.JsonAST.JBool(true)),
          CannedResponses.valueParam("message", "String", JString("Code is valid"))
        ).toJson)

      val response = Await.result(
        client.operation("validate-code")
          .on("ValueSet")
          .addSimpleParam("code", "male")
          .executeAndReturnOperationOutcome(),
        5.seconds)

      simpleParam(response, "result").extractValue[Boolean]() must beTrue
      simpleParam(response, "message").extractValue[String]() mustEqual "Code is valid"
    }

    "expose a multi part parameter" in {
      mockServer.reset()
      mockServer.stub("GET", "/ConceptMap/$translate")(
        200,
        CannedResponses.parametersResource(
          CannedResponses.valueParam("result", "Boolean", org.json4s.JsonAST.JBool(true)),
          CannedResponses.multiParam(
            "match",
            CannedResponses.valueParam("equivalence", "Code", JString("equivalent")),
            CannedResponses.valueParam("concept", "Coding", JObject(List(
              "system" -> JString("http://snomed.info/sct"),
              "code" -> JString("38341003")
            )))
          )
        ).toJson)

      val response = Await.result(
        client.operation("translate")
          .on("ConceptMap")
          .addSimpleParam("code", "hypertension")
          .executeAndReturnOperationOutcome(),
        5.seconds)

      response.getOutputParam("match").isDefined must beTrue
      val matched = response.getOutputParam("match").get.asInstanceOf[FHIRMultiOperationParam]
      matched.extractParamValue[String]("equivalence") must beSome("equivalent")
      matched.getParam("concept").map(_.asInstanceOf[FHIRSimpleOperationParam].value) must
        beSome(JObject(List("system" -> JString("http://snomed.info/sct"), "code" -> JString("38341003"))))
    }
  }

  "A canned non Parameters operation output" should {
    "expose the whole resource as the return parameter" in {
      mockServer.reset()
      val bundle = CannedResponses.searchSetBundle(1, matches = Seq(CannedResponses.patient("p1")))
      mockServer.stub("GET", "/Patient/p1/$everything")(200, bundle.toJson)

      val response = Await.result(
        client.operation("everything").on("Patient", Some("p1")).executeAndReturnOperationOutcome(),
        5.seconds)

      simpleParam(response, "return").value mustEqual bundle
    }
  }

  "A failed operation" should {
    "fail executeAndReturnOperationOutcome with a FhirClientException on an error status" in {
      mockServer.reset()
      val outcome = CannedResponses.operationOutcome(
        CannedResponses.issue("error", "processing", "Operation not supported", Seq("Patient")))
      mockServer.stub("GET", "/Patient/p1/$everything")(400, outcome.toJson)

      val thrown = Try(Await.result(
        client.operation("everything").on("Patient", Some("p1")).executeAndReturnOperationOutcome(),
        5.seconds)).failed.get

      thrown must beAnInstanceOf[FhirClientException]
      val serverResponse = thrown.asInstanceOf[FhirClientException].serverResponse
      serverResponse.map(_.httpStatus.intValue()) must beSome(400)
      serverResponse.toSeq.flatMap(_.outcomeIssues).map(_.code) mustEqual Seq("processing")
    }

    "fail executeAndReturnResource with a FhirClientException" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient/p1/$everything")(
        404,
        CannedResponses.operationOutcome(
          CannedResponses.issue("error", "not-found", "Patient not found", Seq("Patient"))).toJson)

      val thrown = Try(Await.result(
        client.operation("everything").on("Patient", Some("p1")).executeAndReturnResource(),
        5.seconds)).failed.get

      thrown must beAnInstanceOf[FhirClientException]
      thrown.asInstanceOf[FhirClientException].serverResponse.map(_.httpStatus.intValue()) must beSome(404)
    }

    "fail with a FhirClientException when the body is not a FHIR resource" in {
      mockServer.reset()
      mockServer.stub("GET", "/Patient/p1/$everything")(200, """{"notAResource":true}""")

      val thrown = Try(Await.result(
        client.operation("everything").on("Patient", Some("p1")).executeAndReturnOperationOutcome(),
        5.seconds)).failed.get

      thrown must beAnInstanceOf[FhirClientException]
      thrown.getMessage mustEqual "Invalid operation response!"
    }
  }
}

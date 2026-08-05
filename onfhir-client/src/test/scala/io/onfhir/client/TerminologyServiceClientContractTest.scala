package io.onfhir.client

import io.onfhir.api.client.FhirClientException
import io.onfhir.client.testutil.{CannedResponses, RecordedRequest, WithMockFhirServer}
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * Request and response contract of the terminology service facade built on the
 * onFHIR client. Expectations are derived from TerminologyServiceClient itself.
 */
@RunWith(classOf[JUnitRunner])
class TerminologyServiceClientContractTest extends Specification with WithMockFhirServer {
  sequential

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def terminologyService: TerminologyServiceClient =
    new TerminologyServiceClient(OnFhirNetworkClient(mockServer.baseUrl))

  private val coding = JObject(List(
    "system" -> JString("http://loinc.org"),
    "code" -> JString("15074-8")
  ))

  private val codeableConcept = JObject(List("coding" -> JArray(List(coding))))

  private val lookupResult = CannedResponses.parametersResource(
    CannedResponses.valueParam("name", "String", JString("LOINC")),
    CannedResponses.valueParam("display", "String", JString("Glucose [Moles/volume] in Blood"))
  )

  private def stubOperation(method: String, path: String, body: JObject = lookupResult): Unit =
    mockServer.stub(method, path)(200, body.toJson)

  private def run[T](operation: => Future[T]): (T, RecordedRequest) = {
    val result = Await.result(operation, 5.seconds)
    result -> mockServer.lastRequest
  }

  "TerminologyServiceClient.lookup by code and system" should {
    "GET the CodeSystem lookup operation and wrap the result" in {
      mockServer.reset()
      stubOperation("GET", "/CodeSystem/$lookup")

      val (result, recorded) = run(terminologyService.lookup("15074-8", "http://loinc.org"))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/CodeSystem/$lookup"
      recorded.queryParams mustEqual Map("code" -> List("15074-8"), "system" -> List("http://loinc.org"))
      result must beSome(lookupResult)
    }

    "add the optional version, date, display language and property parameters" in {
      mockServer.reset()
      stubOperation("GET", "/CodeSystem/$lookup")

      val (_, recorded) = run(terminologyService.lookup(
        "15074-8",
        "http://loinc.org",
        version = Some("2.72"),
        date = Some("2020-01-01"),
        displayLanguage = Some("en"),
        properties = Seq("definition", "designation")))

      recorded.queryParams mustEqual Map(
        "code" -> List("15074-8"),
        "system" -> List("http://loinc.org"),
        "version" -> List("2.72"),
        "date" -> List("2020-01-01"),
        "displayLanguage" -> List("en"),
        "property" -> List("definition", "designation")
      )
    }

    "return None when the server reports the code as not found" in {
      mockServer.reset()
      mockServer.stub("GET", "/CodeSystem/$lookup")(
        404,
        CannedResponses.operationOutcome(
          CannedResponses.issue("error", "not-found", "Unknown code", Seq("code"))).toJson)

      Await.result(terminologyService.lookup("unknown", "http://loinc.org"), 5.seconds) must beNone
    }

    "return None when the server rejects the request as invalid" in {
      mockServer.reset()
      mockServer.stub("GET", "/CodeSystem/$lookup")(
        400,
        CannedResponses.operationOutcome(
          CannedResponses.issue("error", "invalid", "Unknown system", Seq("system"))).toJson)

      Await.result(terminologyService.lookup("15074-8", "http://unknown"), 5.seconds) must beNone
    }

    "propagate server side failures other than 400 and 404" in {
      mockServer.reset()
      mockServer.stub("GET", "/CodeSystem/$lookup")(
        500,
        CannedResponses.operationOutcome(
          CannedResponses.issue("fatal", "exception", "Boom", Seq("CodeSystem"))).toJson)

      val thrown = Try(Await.result(terminologyService.lookup("15074-8", "http://loinc.org"), 5.seconds)).failed.get

      thrown must beAnInstanceOf[FhirClientException]
    }
  }

  "TerminologyServiceClient.lookup by coding" should {
    "POST a Parameters body carrying the coding" in {
      mockServer.reset()
      stubOperation("POST", "/CodeSystem/$lookup")

      val (result, recorded) = run(terminologyService.lookup(coding))

      recorded.method mustEqual "POST"
      recorded.relativePath mustEqual "/CodeSystem/$lookup"
      recorded.rawQuery must beNone
      (recorded.bodyResource \ "parameter").asInstanceOf[JArray].arr mustEqual List(
        JObject(List("name" -> JString("coding"), "valueCoding" -> coding))
      )
      result must beSome(lookupResult)
    }
  }

  "TerminologyServiceClient.translate" should {
    "GET the ConceptMap translate operation for a code with a concept map url" in {
      mockServer.reset()
      stubOperation("GET", "/ConceptMap/$translate")

      val (_, recorded) = run(terminologyService.translate("15074-8", "http://loinc.org", "http://example.com/cm"))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/ConceptMap/$translate"
      recorded.queryParams mustEqual Map(
        "code" -> List("15074-8"),
        "system" -> List("http://loinc.org"),
        "url" -> List("http://example.com/cm")
      )
    }

    "add version, conceptMapVersion and reverse when given" in {
      mockServer.reset()
      stubOperation("GET", "/ConceptMap/$translate")

      val (_, recorded) = run(terminologyService.translate(
        "15074-8",
        "http://loinc.org",
        "http://example.com/cm",
        version = Some("2.72"),
        conceptMapVersion = Some("1.0"),
        reverse = true))

      recorded.queryParams mustEqual Map(
        "code" -> List("15074-8"),
        "system" -> List("http://loinc.org"),
        "url" -> List("http://example.com/cm"),
        "version" -> List("2.72"),
        "conceptMapVersion" -> List("1.0"),
        "reverse" -> List("true")
      )
    }

    "GET the translate operation for a code with source and target value sets" in {
      mockServer.reset()
      stubOperation("GET", "/ConceptMap/$translate")

      val (_, recorded) = run(terminologyService.translate(
        "15074-8",
        "http://loinc.org",
        source = Some("http://example.com/source"),
        target = Some("http://example.com/target")))

      recorded.method mustEqual "GET"
      recorded.queryParams mustEqual Map(
        "code" -> List("15074-8"),
        "system" -> List("http://loinc.org"),
        "source" -> List("http://example.com/source"),
        "target" -> List("http://example.com/target")
      )
    }

    "POST a coding as a complex parameter with the concept map url in the query" in {
      mockServer.reset()
      stubOperation("POST", "/ConceptMap/$translate")

      val (_, recorded) = run(terminologyService.translate(coding, "http://example.com/cm"))

      recorded.method mustEqual "POST"
      recorded.queryParams mustEqual Map("url" -> List("http://example.com/cm"))
      (recorded.bodyResource \ "parameter").asInstanceOf[JArray].arr mustEqual List(
        JObject(List("name" -> JString("coding"), "valueCoding" -> coding))
      )
    }

    "POST a codeable concept under the codeableConcept parameter" in {
      mockServer.reset()
      stubOperation("POST", "/ConceptMap/$translate")

      val (_, recorded) = run(terminologyService.translate(codeableConcept, "http://example.com/cm"))

      (recorded.bodyResource \ "parameter").asInstanceOf[JArray].arr mustEqual List(
        JObject(List("name" -> JString("codeableConcept"), "valueCodeableConcept" -> codeableConcept))
      )
    }
  }

  "TerminologyServiceClient.validateCode" should {
    "GET the ValueSet validate-code operation" in {
      mockServer.reset()
      val validationResult = CannedResponses.parametersResource(
        CannedResponses.valueParam("result", "Boolean", org.json4s.JsonAST.JBool(true)))
      stubOperation("GET", "/ValueSet/$validate-code", validationResult)

      val (result, recorded) = run(terminologyService.validateCode(
        url = "http://example.com/vs",
        code = "15074-8",
        system = Some("http://loinc.org")))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/ValueSet/$validate-code"
      recorded.queryParams mustEqual Map(
        "url" -> List("http://example.com/vs"),
        "code" -> List("15074-8"),
        "system" -> List("http://loinc.org")
      )
      result mustEqual validationResult
    }

    "add the optional version and display parameters" in {
      mockServer.reset()
      stubOperation("GET", "/ValueSet/$validate-code")

      val (_, recorded) = run(terminologyService.validateCode(
        url = "http://example.com/vs",
        valueSetVersion = Some("1.0"),
        code = "15074-8",
        system = Some("http://loinc.org"),
        systemVersion = Some("2.72"),
        display = Some("Glucose")))

      recorded.queryParams mustEqual Map(
        "url" -> List("http://example.com/vs"),
        "code" -> List("15074-8"),
        "valueSetVersion" -> List("1.0"),
        "system" -> List("http://loinc.org"),
        "systemVersion" -> List("2.72"),
        "display" -> List("Glucose")
      )
    }
  }

  "TerminologyServiceClient.expand" should {
    "GET the instance level expand operation for a ValueSet id" in {
      mockServer.reset()
      stubOperation("GET", "/ValueSet/vs1/$expand")

      val (_, recorded) = run(terminologyService.expandWithId(
        "vs1",
        filter = Some("glucose"),
        offset = Some(10L),
        count = Some(20L)))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/ValueSet/vs1/$expand"
      recorded.queryParams mustEqual Map(
        "filter" -> List("glucose"),
        "offset" -> List("10"),
        "count" -> List("20")
      )
    }

    "send the canonical url and version when expanding by url" in {
      mockServer.reset()
      stubOperation("GET", "/ValueSet/$expand")

      val (_, recorded) = run(terminologyService.expand(
        url = "http://example.com/vs",
        version = Some("1.0"),
        filter = Some("glucose")))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/ValueSet/$expand"
      recorded.queryParams mustEqual Map(
        "url" -> List("http://example.com/vs"),
        "valueSetVersion" -> List("1.0"),
        "filter" -> List("glucose")
      )
    }

    "POST the ValueSet definition when expanding an inline value set" in {
      mockServer.reset()
      stubOperation("POST", "/ValueSet/$expand")
      val valueSet = CannedResponses.resource("ValueSet", "vs1", "url" -> JString("http://example.com/vs"))

      val (_, recorded) = run(terminologyService.expandWithValueSet(valueSet, count = Some(5L)))

      recorded.method mustEqual "POST"
      recorded.relativePath mustEqual "/ValueSet/$expand"
      recorded.queryParams mustEqual Map("count" -> List("5"))
      (recorded.bodyResource \ "parameter").asInstanceOf[JArray].arr mustEqual List(
        JObject(List("name" -> JString("valueSet"), "resource" -> valueSet))
      )
    }
  }
}

package io.onfhir.api.client

import io.onfhir.client.OnFhirNetworkClient
import io.onfhir.client.testutil.{CannedResponses, RecordedRequest, WithMockFhirServer}
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
 * Wire level contract of the FHIR operation request builder.
 */
@RunWith(classOf[JUnitRunner])
class FhirOperationRequestContractTest extends Specification with WithMockFhirServer {
  sequential

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def client: OnFhirNetworkClient = OnFhirNetworkClient(mockServer.baseUrl)

  private def send(builder: FhirRequestBuilder): RecordedRequest = {
    Await.result(builder.execute(), 5.seconds)
    mockServer.lastRequest
  }

  "FHIR operations with simple parameters only" should {
    "GET the operation endpoint with a query and no body" in {
      val recorded = send(
        client.operation("validate")
          .on("Patient")
          .addSimpleParam("mode", "create"))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/Patient/$validate"
      recorded.queryParams mustEqual Map("mode" -> List("create"))
      recorded.body mustEqual ""
      recorded.header("Content-Type") must beNone
    }

    "repeat a simple parameter given several values" in {
      val recorded = send(
        client.operation("lookup")
          .on("CodeSystem")
          .addSimpleParam("property", "definition", "designation"))

      recorded.method mustEqual "GET"
      recorded.queryParams mustEqual Map("property" -> List("definition", "designation"))
    }

    "GET the operation endpoint with no query when there is no parameter at all" in {
      val recorded = send(client.operation("meta").on("Patient", Some("p1")))

      recorded.method mustEqual "GET"
      recorded.relativePath mustEqual "/Patient/p1/$meta"
      recorded.rawQuery must beNone
    }
  }

  "FHIR operation paths" should {
    "target the server base for a system level operation" in {
      val recorded = send(client.operation("export"))

      recorded.relativePath mustEqual "/$export"
    }

    "target the resource type for a type level operation" in {
      val recorded = send(client.operation("export").on("Patient"))

      recorded.relativePath mustEqual "/Patient/$export"
    }

    "target the resource instance for an instance level operation" in {
      val recorded = send(client.operation("everything").on("Patient", Some("p1")))

      recorded.relativePath mustEqual "/Patient/p1/$everything"
    }
  }

  "FHIR operations with complex parameters" should {
    "POST a Parameters body with value[x] entries" in {
      val recorded = send(
        client.operation("translate")
          .on("ConceptMap")
          .addParam("coding", "Coding" -> JObject(List(
            "system" -> JString("http://loinc.org"),
            "code" -> JString("15074-8")
          )))
          .addParam("reverse", "Boolean" -> org.json4s.JsonAST.JBool(true)))

      recorded.method mustEqual "POST"
      recorded.relativePath mustEqual "/ConceptMap/$translate"
      recorded.header("Content-Type") must beSome("application/fhir+json; charset=UTF-8")

      val body = recorded.bodyResource
      (body \ "resourceType").extract[String] mustEqual "Parameters"
      (body \ "parameter").asInstanceOf[JArray].arr mustEqual List(
        JObject(List(
          "name" -> JString("coding"),
          "valueCoding" -> JObject(List("system" -> JString("http://loinc.org"), "code" -> JString("15074-8")))
        )),
        JObject(List("name" -> JString("reverse"), "valueBoolean" -> org.json4s.JsonAST.JBool(true)))
      )
    }

    "POST a Parameters body with a resource entry" in {
      val valueSet = CannedResponses.resource("ValueSet", "vs1", "url" -> JString("http://example.com/vs"))

      val recorded = send(
        client.operation("expand")
          .on("ValueSet")
          .addResourceParam("valueSet", valueSet))

      recorded.method mustEqual "POST"
      (recorded.bodyResource \ "parameter").asInstanceOf[JArray].arr mustEqual List(
        JObject(List("name" -> JString("valueSet"), "resource" -> valueSet))
      )
    }

    "POST a Parameters body with a multi part entry" in {
      val parts = JArray(List(
        JObject(List("name" -> JString("concept"), "valueCode" -> JString("a"))),
        JObject(List("name" -> JString("concept"), "valueCode" -> JString("b")))
      ))

      val recorded = send(client.operation("closure").addMultiParam("concepts", parts))

      recorded.method mustEqual "POST"
      recorded.relativePath mustEqual "/$closure"
      (recorded.bodyResource \ "parameter").asInstanceOf[JArray].arr mustEqual List(
        JObject(List("name" -> JString("concepts"), "part" -> parts))
      )
    }

    "keep simple parameters in the url while complex ones go into the body" in {
      val recorded = send(
        client.operation("translate")
          .on("ConceptMap")
          .addSimpleParam("url", "http://example.com/cm")
          .addParam("coding", "Coding" -> JObject(List("code" -> JString("15074-8")))))

      recorded.method mustEqual "POST"
      recorded.queryParams mustEqual Map("url" -> List("http://example.com/cm"))
      (recorded.bodyResource \ "parameter").asInstanceOf[JArray].arr
        .map(parameter => (parameter \ "name").extract[String]) mustEqual List("coding")
    }
  }
}

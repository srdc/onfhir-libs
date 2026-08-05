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
 * Wire level contract of the FHIR patch request builders (JSON Patch and FHIRPath Patch).
 */
@RunWith(classOf[JUnitRunner])
class FhirPatchRequestContractTest extends Specification with WithMockFhirServer {
  sequential

  private val jsonPatchContentType = "application/json-patch+json; charset=UTF-8"
  private val fhirJsonContentType = "application/fhir+json; charset=UTF-8"

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def client: OnFhirNetworkClient = OnFhirNetworkClient(mockServer.baseUrl)

  private def send(builder: FhirRequestBuilder): RecordedRequest = {
    Await.result(builder.execute(), 5.seconds)
    mockServer.lastRequest
  }

  "FHIR JSON Patch requests" should {
    "PATCH the instance endpoint with the raw patch array as the body" in {
      val recorded = send(
        client.patch("Patient", "p1")
          .jsonPatch()
          .patchReplace("/gender", JString("female"))
          .patchRemove("/birthDate"))

      recorded.method mustEqual "PATCH"
      recorded.relativePath mustEqual "/Patient/p1"
      recorded.rawQuery must beNone
      recorded.header("Content-Type") must beSome(jsonPatchContentType)
      recorded.bodyJson mustEqual JArray(List(
        JObject(List("op" -> JString("replace"), "path" -> JString("/gender"), "value" -> JString("female"))),
        JObject(List("op" -> JString("remove"), "path" -> JString("/birthDate")))
      ))
    }

    "render add, copy and move operations" in {
      val recorded = send(
        client.patch("Patient", "p1")
          .jsonPatch()
          .patchAdd("/name/0/given/-", JString("Jim"))
          .patchCopy("/name/0", "/name/1")
          .patchMove("/telecom/0", "/telecom/1"))

      recorded.bodyJson mustEqual JArray(List(
        JObject(List("op" -> JString("add"), "path" -> JString("/name/0/given/-"), "value" -> JString("Jim"))),
        JObject(List("op" -> JString("copy"), "from" -> JString("/name/0"), "path" -> JString("/name/1"))),
        JObject(List("op" -> JString("move"), "from" -> JString("/telecom/0"), "path" -> JString("/telecom/1")))
      ))
    }

    "PATCH the type endpoint with a query for a conditional patch" in {
      val recorded = send(
        client.patch("Patient")
          .where("identifier", "urn:oid:1|123")
          .jsonPatch()
          .patchReplace("/gender", JString("female")))

      recorded.method mustEqual "PATCH"
      recorded.relativePath mustEqual "/Patient"
      recorded.rawQuery must beSome("identifier=urn%3Aoid%3A1%7C123")
      recorded.header("Content-Type") must beSome(jsonPatchContentType)
    }

    "build the logged request uri without a doubled slash" in {
      client.patch("Patient", "p1").request.requestUri mustEqual s"${mockServer.baseUrl}/Patient/p1"
      client.patch("Patient").request.requestUri mustEqual s"${mockServer.baseUrl}/Patient"
    }
  }

  "FHIR Path Patch requests" should {
    "PATCH a Parameters resource holding the operation parts" in {
      val recorded = send(
        client.patch("Patient", "p1")
          .fhirPathPatch()
          .patchAdd("Patient", "gender", "code" -> JString("female"))
          .patchDelete("Patient.birthDate")
          .patchReplace("Patient.active", "boolean" -> org.json4s.JsonAST.JBool(false))
          .patchInsert("Patient.name", 0, "HumanName" -> JObject(List("family" -> JString("Doe"))))
          .patchMove("Patient.telecom", 1, 0))

      recorded.method mustEqual "PATCH"
      recorded.relativePath mustEqual "/Patient/p1"
      recorded.header("Content-Type") must beSome(fhirJsonContentType)

      val body = recorded.bodyResource
      (body \ "resourceType").extract[String] mustEqual "Parameters"
      val parameters = (body \ "parameter").asInstanceOf[JArray].arr
      parameters must haveSize(5)
      parameters.map(parameter => (parameter \ "name").extract[String]).distinct mustEqual List("operation")

      val addParts = (parameters.head \ "part").asInstanceOf[JArray].arr
      addParts mustEqual List(
        JObject(List("name" -> JString("type"), "valueCode" -> JString("add"))),
        JObject(List("name" -> JString("path"), "valueString" -> JString("Patient"))),
        JObject(List("name" -> JString("name"), "valueString" -> JString("gender"))),
        JObject(List("name" -> JString("value"), "valueCode" -> JString("female")))
      )

      (parameters(1) \ "part").asInstanceOf[JArray].arr mustEqual List(
        JObject(List("name" -> JString("type"), "valueCode" -> JString("delete"))),
        JObject(List("name" -> JString("path"), "valueString" -> JString("Patient.birthDate")))
      )

      (parameters(2) \ "part").asInstanceOf[JArray].arr mustEqual List(
        JObject(List("name" -> JString("type"), "valueCode" -> JString("replace"))),
        JObject(List("name" -> JString("path"), "valueString" -> JString("Patient.active"))),
        JObject(List("name" -> JString("value"), "valueBoolean" -> org.json4s.JsonAST.JBool(false)))
      )

      (parameters(3) \ "part").asInstanceOf[JArray].arr mustEqual List(
        JObject(List("name" -> JString("type"), "valueCode" -> JString("insert"))),
        JObject(List("name" -> JString("path"), "valueString" -> JString("Patient.name"))),
        JObject(List("name" -> JString("index"), "valueInteger" -> org.json4s.JsonAST.JInt(0))),
        JObject(List("name" -> JString("value"), "valueHumanName" -> JObject(List("family" -> JString("Doe")))))
      )

      (parameters(4) \ "part").asInstanceOf[JArray].arr mustEqual List(
        JObject(List("name" -> JString("type"), "valueCode" -> JString("move"))),
        JObject(List("name" -> JString("path"), "valueString" -> JString("Patient.telecom"))),
        JObject(List("name" -> JString("source"), "valueInteger" -> org.json4s.JsonAST.JInt(1))),
        JObject(List("name" -> JString("destination"), "valueInteger" -> org.json4s.JsonAST.JInt(0)))
      )
    }
  }

  "FHIR patchContent" should {
    "send a Parameters resource as FHIR JSON" in {
      val parameters = CannedResponses.parametersResource(
        CannedResponses.multiParam(
          "operation",
          CannedResponses.valueParam("type", "Code", JString("delete")),
          CannedResponses.valueParam("path", "String", JString("Patient.birthDate"))
        )
      )

      val recorded = send(client.patch("Patient", "p1").patchContent(parameters))

      recorded.method mustEqual "PATCH"
      recorded.header("Content-Type") must beSome(fhirJsonContentType)
      recorded.bodyResource mustEqual parameters
    }

    "wrap a single JSON Patch object into an array" in {
      val patch = JObject(List("op" -> JString("remove"), "path" -> JString("/birthDate")))

      val recorded = send(client.patch("Patient", "p1").patchContent(patch))

      recorded.header("Content-Type") must beSome(jsonPatchContentType)
      recorded.bodyJson mustEqual JArray(List(patch))
    }

    "send a JSON Patch array as it is" in {
      val patches = JArray(List(
        JObject(List("op" -> JString("remove"), "path" -> JString("/birthDate"))),
        JObject(List("op" -> JString("replace"), "path" -> JString("/gender"), "value" -> JString("other")))
      ))

      val recorded = send(client.patch("Patient", "p1").patchContent(patches))

      recorded.header("Content-Type") must beSome(jsonPatchContentType)
      recorded.bodyJson mustEqual patches
    }

    "reject content that is neither an object nor an array" in {
      client.patch("Patient", "p1").patchContent(JString("nonsense")) must
        throwA[java.security.InvalidParameterException]
    }

    "PATCH the type endpoint with a query when patchContent is used conditionally" in {
      val patch = JObject(List("op" -> JString("remove"), "path" -> JString("/birthDate")))

      val recorded = send(
        client.patch("Patient")
          .where("identifier", "urn:oid:1|123")
          .patchContent(patch))

      recorded.relativePath mustEqual "/Patient"
      recorded.rawQuery must beSome("identifier=urn%3Aoid%3A1%7C123")
    }
  }
}

package io.onfhir.template

import io.onfhir.expression.{FhirExpression, FhirExpressionException}
import org.json4s.JsonAST.{JBool, JNothing, JNull, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

/**
 * The expression-handler contract; validation, unsupported operations, context handling and output cleanup
 */
@RunWith(classOf[JUnitRunner])
class FhirTemplateHandlerContractTest extends Specification with TemplateRenderSupport {
  sequential

  val patient = json("""{"resourceType":"Patient","id":"p1","active":true}""")

  "The handler" should {

    "declare the FHIR template language mime type" in {
      handler.languageSupported mustEqual "application/fhir-template+json"
    }

    "reject applicability checks" in {
      Try(handler.satisfies(templateOf("""{"id":"{{ Patient.id }}"}"""), Map.empty, patient)).failed.get must
        haveClass[FhirExpressionException]
    }

    "be usable again after a failed render" in {
      renderFailure("""{"id":"{{ %missing }}"}""") must haveClass[FhirExpressionException]
      render("""{"id":"{{ Patient.id }}"}""", patient) \ "id" mustEqual JString("p1")
    }

    "survive a Java serialization round trip" in {
      val serialized = new ByteArrayOutputStream()
      val out = new ObjectOutputStream(serialized)
      out.writeObject(handler)
      out.close()
      val restored = new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray))
        .readObject().asInstanceOf[FhirTemplateExpressionHandler]

      render("""{"id":"{{ Patient.id }}"}""", patient, withHandler = restored) \ "id" mustEqual JString("p1")
    }
  }

  "Expression validation" should {

    "accept a parsed JSON template" in {
      handler.validateExpression(templateOf("""{"resourceType":"Communication","id":"{{ Patient.id }}"}""")) must not(throwAn[Exception])
    }

    "accept a template string" in {
      val expression = FhirExpression("t", handler.languageSupported, expression = Some("""Patient {{ Patient.id }}"""))
      handler.validateExpression(expression) must not(throwAn[Exception])
    }

    "reject an expression without template content" in {
      val expression = FhirExpression("t", handler.languageSupported)
      handler.validateExpression(expression) must throwA[FhirExpressionException]
    }

    "reject an expression whose template content is null" in {
      val expression = FhirExpression("t", handler.languageSupported, value = Some(JNull))
      handler.validateExpression(expression) must throwA[FhirExpressionException]
    }

    "report a missing template as a FhirExpressionException at evaluation time" in {
      val expression = FhirExpression("t", handler.languageSupported)
      Try(Await.result(handler.evaluateExpression(expression, Map.empty, patient), 5.seconds)).failed.get must
        haveClass[FhirExpressionException]
    }
  }

  "A template given as a string" should {

    "render its placeholders into a JSON string result" in {
      val expression = FhirExpression("t", handler.languageSupported, expression = Some("""Patient {{ Patient.id }} updated"""))
      Await.result(handler.evaluateExpression(expression, Map.empty, patient), 5.seconds) mustEqual
        JString("Patient p1 updated")
    }
  }

  "Context parameters" should {

    "be available from the static context of the handler" in {
      val staticHandler = new FhirTemplateExpressionHandler(
        staticContextParams = Map("tenant" -> JString("hospital-a")),
        isSourceContentFhir = true)
      render("""{"id":"{{ %tenant }}"}""", withHandler = staticHandler) \ "id" mustEqual JString("hospital-a")
    }

    "override a static context value of the same name for a single render" in {
      val staticHandler = new FhirTemplateExpressionHandler(
        staticContextParams = Map("tenant" -> JString("hospital-a")),
        isSourceContentFhir = true)
      render("""{"id":"{{ %tenant }}"}""", JNothing, Map("tenant" -> JString("hospital-b")), staticHandler) \ "id" mustEqual
        JString("hospital-b")
    }

    "not leak a single-render value into the next render" in {
      val staticHandler = new FhirTemplateExpressionHandler(
        staticContextParams = Map("tenant" -> JString("hospital-a")),
        isSourceContentFhir = true)
      render("""{"id":"{{ %tenant }}"}""", JNothing, Map("tenant" -> JString("hospital-b")), staticHandler)
      render("""{"id":"{{ %tenant }}"}""", withHandler = staticHandler) \ "id" mustEqual JString("hospital-a")
    }
  }

  "Output cleanup" should {

    "keep literal template content unchanged" in {
      render("""{"resourceType":"Communication","status":"completed","priority":2,"flag":false}""") mustEqual
        json("""{"resourceType":"Communication","status":"completed","priority":2,"flag":false}""")
    }

    "remove fields that are null in the template itself" in {
      render("""{"status":"completed","priority":null}""") mustEqual json("""{"status":"completed"}""")
    }

    "remove an object that became empty" in {
      render("""{"status":"completed","subject":{"reference":"{{? %missing }}"}}""") mustEqual
        json("""{"status":"completed"}""")
    }

    "remove nested structures that became empty" in {
      render("""{"status":"completed","payload":[{"content":{"reference":"{{? %missing }}"}}]}""") mustEqual
        json("""{"status":"completed"}""")
    }

    "keep false as a value" in {
      render("""{"active":"{{ %flag }}"}""", contextParams = Map("flag" -> JBool(false))) \ "active" mustEqual JBool(false)
    }

    "return null when every field of the template was removed" in {
      render("""{"subject":{"reference":"{{? %missing }}"}}""") mustEqual JNull
    }
  }
}

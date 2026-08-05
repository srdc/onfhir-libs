package io.onfhir.expression

import org.json4s.jackson.JsonMethods.parse
import org.json4s.{JNothing, JValue}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * Executable version of the custom language handler and the facade usage shown in the module README
 */
@RunWith(classOf[JUnitRunner])
class FhirExpressionReadmeExampleTest extends Specification {
  sequential

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  val patient: JValue = parse("""{"resourceType":"Patient","id":"p1","active":true}""")

  "the README example" should {

    val myLanguageHandler = new MyExpressionHandler
    val evaluator = new FhirExpressionEvaluator(Seq(myLanguageHandler))

    val expression = FhirExpression(
      name = "eligible",
      language = myLanguageHandler.languageSupported,
      expression = Some("status = 'active'")
    )

    "validate an expression through the facade" in {
      evaluator.validateExpression(expression) must not(throwAn[Exception])
    }

    "evaluate an expression through the facade" in {
      val result = evaluator.evaluateExpression(
        expression,
        contextParams = Map.empty[String, JValue],
        input = patient
      )

      Await.result(result, 5.seconds) mustEqual patient
    }

    "evaluate an expression as a boolean condition through the facade" in {
      val applicable = evaluator.satisfies(
        expression,
        contextParams = Map.empty[String, JValue],
        input = patient
      )

      Await.result(applicable, 5.seconds) must beTrue
    }

    "report the handler's own validation rule" in {
      val withoutContent = FhirExpression("eligible", myLanguageHandler.languageSupported)

      evaluator.validateExpression(withoutContent) must throwA[FhirExpressionException]
    }
  }
}

/**
 * The custom language handler exactly as the README presents it; kept top level so that the handler's own implicit
 * ExecutionContext parameter is the only one in scope, as it would be in a consuming application
 */
final class MyExpressionHandler extends IFhirExpressionLanguageHandler {
  override val languageSupported: String = "application/x-my-expression"

  override def validateExpression(expression: FhirExpression): Unit = {
    if (expression.expression.isEmpty)
      throw FhirExpressionException("Missing expression content")
  }

  override def evaluateExpression(
      expression: FhirExpression,
      contextParams: Map[String, JValue],
      input: JValue = JNothing
  )(implicit ec: ExecutionContext): Future[JValue] = {
    validateExpression(expression)
    Future {
      // Parse and evaluate the language-specific content.
      input
    }
  }

  override def satisfies(
      expression: FhirExpression,
      contextParams: Map[String, JValue],
      input: JValue = JNothing
  )(implicit ec: ExecutionContext): Future[Boolean] = {
    validateExpression(expression)
    Future {
      // Evaluate the expression using boolean result semantics.
      true
    }
  }
}

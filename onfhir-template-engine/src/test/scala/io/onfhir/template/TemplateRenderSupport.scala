package io.onfhir.template

import io.onfhir.expression.FhirExpression
import org.json4s.JValue
import org.json4s.JsonAST.JNothing
import org.json4s.jackson.JsonMethods.parse

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success, Try}

/**
 * Helpers to render inline templates synchronously within the tests
 */
trait TemplateRenderSupport {
  protected implicit val executionContext: ExecutionContext = ExecutionContext.global

  protected val handler: FhirTemplateExpressionHandler = new FhirTemplateExpressionHandler(isSourceContentFhir = true)

  protected def json(content: String): JValue = parse(content)

  protected def templateOf(templateJson: String): FhirExpression =
    FhirExpression("test-template", handler.languageSupported, value = Some(parse(templateJson)))

  /**
   * Render the given inline template and wait for the result
   */
  protected def render(templateJson: String,
                       input: JValue = JNothing,
                       contextParams: Map[String, JValue] = Map.empty,
                       withHandler: FhirTemplateExpressionHandler = handler): JValue =
    Await.result(withHandler.evaluateExpression(templateOf(templateJson), contextParams, input), 5.seconds)

  /**
   * Render the given inline template expecting it to fail, and return the thrown exception
   */
  protected def renderFailure(templateJson: String,
                              input: JValue = JNothing,
                              contextParams: Map[String, JValue] = Map.empty): Throwable =
    Try(render(templateJson, input, contextParams)) match {
      case Success(result) => throw new AssertionError(s"Expected the render to fail, but it produced: $result")
      case Failure(t) => t
    }
}

package io.onfhir.expression

import org.json4s.JsonAST.{JBool, JNothing, JString}
import org.json4s.{JObject, JValue}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * The dispatch contract of the expression facade; handler selection, delegation, and error reporting
 */
@RunWith(classOf[JUnitRunner])
class FhirExpressionEvaluatorTest extends Specification {
  sequential

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  /**
   * Handler that records what it was called with and returns preset results
   */
  class RecordingHandler(override val languageSupported: String,
                         result: JValue = JString("evaluated"),
                         booleanResult: Boolean = true) extends IFhirExpressionLanguageHandler {
    var validatedExpressions: Seq[FhirExpression] = Nil
    var seenContextParams: Map[String, JValue] = Map.empty
    var seenInput: JValue = JNothing

    override def validateExpression(expression: FhirExpression): Unit =
      validatedExpressions = validatedExpressions :+ expression

    override def evaluateExpression(expression: FhirExpression, contextParams: Map[String, JValue], input: JValue)
                                  (implicit ex: ExecutionContext): Future[JValue] = {
      seenContextParams = contextParams
      seenInput = input
      Future.successful(result)
    }

    override def satisfies(expression: FhirExpression, contextParams: Map[String, JValue], input: JValue)
                          (implicit ex: ExecutionContext): Future[Boolean] = {
      seenContextParams = contextParams
      seenInput = input
      Future.successful(booleanResult)
    }
  }

  val fhirPathLanguage = "text/fhirpath"

  def expressionFor(language: String): FhirExpression =
    FhirExpression("test", language, expression = Some("status = 'active'"))

  def await[T](future: Future[T]): T = Await.result(future, 5.seconds)

  def failureOf[T](future: Future[T]): Throwable =
    Try(await(future)) match {
      case Success(value) => throw new AssertionError(s"Expected a failed Future but it produced: $value")
      case Failure(t) => t
    }

  "Handler selection" should {

    "select the handler whose supported language matches the expression" in {
      val fhirPath = new RecordingHandler(fhirPathLanguage)
      val template = new RecordingHandler("application/fhir-template+json")
      val evaluator = new FhirExpressionEvaluator(Seq(template, fhirPath))

      evaluator.findHandler(expressionFor(fhirPathLanguage)) must beTheSameAs(fhirPath)
    }

    "select the first registered handler when several support the same language" in {
      val first = new RecordingHandler(fhirPathLanguage)
      val second = new RecordingHandler(fhirPathLanguage)
      val evaluator = new FhirExpressionEvaluator(Seq(first, second))

      evaluator.findHandler(expressionFor(fhirPathLanguage)) must beTheSameAs(first)
    }

    "match the language exactly, without case folding" in {
      val evaluator = new FhirExpressionEvaluator(Seq(new RecordingHandler(fhirPathLanguage)))

      evaluator.findHandler(expressionFor("text/FHIRPath")) must throwA[FhirExpressionException]
    }

    "reject an unregistered language, naming it in the message" in {
      val evaluator = new FhirExpressionEvaluator(Seq(new RecordingHandler(fhirPathLanguage)))

      evaluator.findHandler(expressionFor("application/x-fhir-query")) must
        throwA[FhirExpressionException].like { case e => e.getMessage must contain("application/x-fhir-query") }
    }

    "reject every expression when no handler is registered" in {
      new FhirExpressionEvaluator(Nil).findHandler(expressionFor(fhirPathLanguage)) must
        throwA[FhirExpressionException]
    }

    "not treat its own '*' language as a wildcard for nested facades" in {
      val inner = new FhirExpressionEvaluator(Seq(new RecordingHandler(fhirPathLanguage)))
      val outer = new FhirExpressionEvaluator(Seq(inner))

      //The nested facade only answers for the literal language '*'
      outer.findHandler(expressionFor(fhirPathLanguage)) must throwA[FhirExpressionException]
      outer.findHandler(expressionFor("*")) must beTheSameAs(inner)
    }
  }

  "Delegation" should {

    "pass validation to the selected handler" in {
      val handler = new RecordingHandler(fhirPathLanguage)
      val evaluator = new FhirExpressionEvaluator(Seq(handler))
      val expression = expressionFor(fhirPathLanguage)

      evaluator.validateExpression(expression)

      handler.validatedExpressions mustEqual Seq(expression)
    }

    "surface a validation failure raised by the selected handler" in {
      val strictHandler = new RecordingHandler(fhirPathLanguage) {
        override def validateExpression(expression: FhirExpression): Unit =
          throw FhirExpressionException("Missing expression content", Some("status"))
      }
      val evaluator = new FhirExpressionEvaluator(Seq(strictHandler))

      evaluator.validateExpression(expressionFor(fhirPathLanguage)) must
        throwA[FhirExpressionException].like { case e: FhirExpressionException => e.expression must beSome("status") }
    }

    "return the value produced by the selected handler" in {
      val handler = new RecordingHandler(fhirPathLanguage, result = JString("active"))
      val evaluator = new FhirExpressionEvaluator(Seq(handler))

      await(evaluator.evaluateExpression(expressionFor(fhirPathLanguage), Map.empty, JNothing)) mustEqual
        JString("active")
    }

    "return the boolean produced by the selected handler" in {
      val handler = new RecordingHandler(fhirPathLanguage, booleanResult = false)
      val evaluator = new FhirExpressionEvaluator(Seq(handler))

      await(evaluator.satisfies(expressionFor(fhirPathLanguage), Map.empty, JNothing)) must beFalse
    }

    "pass the context parameters and input through unchanged" in {
      val handler = new RecordingHandler(fhirPathLanguage)
      val evaluator = new FhirExpressionEvaluator(Seq(handler))
      val contextParams = Map("subjectRef" -> JString("Patient/p1"), "flag" -> JBool(true))
      val input = JObject("resourceType" -> JString("Patient"), "id" -> JString("p1"))

      await(evaluator.evaluateExpression(expressionFor(fhirPathLanguage), contextParams, input))

      handler.seenContextParams mustEqual contextParams
      handler.seenInput mustEqual input
    }

    "propagate a failed evaluation from the selected handler" in {
      val failingHandler = new RecordingHandler(fhirPathLanguage) {
        override def evaluateExpression(expression: FhirExpression, contextParams: Map[String, JValue], input: JValue)
                                      (implicit ex: ExecutionContext): Future[JValue] =
          Future.failed(FhirExpressionException("Evaluation failed"))
      }
      val evaluator = new FhirExpressionEvaluator(Seq(failingHandler))

      failureOf(evaluator.evaluateExpression(expressionFor(fhirPathLanguage), Map.empty, JNothing)) must
        haveClass[FhirExpressionException]
    }
  }

  "An unsupported language" should {

    "fail the returned Future of evaluateExpression rather than throwing" in {
      val evaluator = new FhirExpressionEvaluator(Seq(new RecordingHandler(fhirPathLanguage)))

      //The call itself must return normally, so a caller can rely on recover/onComplete alone
      val evaluated = Try(evaluator.evaluateExpression(expressionFor("application/unknown"), Map.empty, JNothing))
      evaluated must beASuccessfulTry
      failureOf(evaluated.get) must haveClass[FhirExpressionException]
    }

    "fail the returned Future of satisfies rather than throwing" in {
      val evaluator = new FhirExpressionEvaluator(Seq(new RecordingHandler(fhirPathLanguage)))

      val satisfied = Try(evaluator.satisfies(expressionFor("application/unknown"), Map.empty, JNothing))
      satisfied must beASuccessfulTry
      failureOf(satisfied.get) must haveClass[FhirExpressionException]
    }

    "be recoverable through the Future for evaluateExpression" in {
      val evaluator = new FhirExpressionEvaluator(Seq(new RecordingHandler(fhirPathLanguage)))

      val recovered = evaluator
        .evaluateExpression(expressionFor("application/unknown"), Map.empty, JNothing)
        .recover { case _: FhirExpressionException => JString("fallback") }

      await(recovered) mustEqual JString("fallback")
    }

    "still be reported by throwing from the synchronous validateExpression" in {
      val evaluator = new FhirExpressionEvaluator(Seq(new RecordingHandler(fhirPathLanguage)))

      evaluator.validateExpression(expressionFor("application/unknown")) must throwA[FhirExpressionException]
    }
  }

  "FhirExpressionException" should {

    "carry the failing expression and the causing exception" in {
      val cause = new IllegalStateException("boom")
      val exception = FhirExpressionException("Problem while evaluating", Some("Patient.name"), Some(cause))

      exception.getMessage mustEqual "Problem while evaluating"
      exception.expression must beSome("Patient.name")
      exception.getCause must beTheSameAs(cause)
    }

    "leave the cause unset when none is supplied" in {
      FhirExpressionException("Problem").getCause must beNull
    }
  }

  "The facade" should {

    "declare itself as a handler so it can be supplied where one handler is expected" in {
      val evaluator: IFhirExpressionLanguageHandler = new FhirExpressionEvaluator(Nil)

      evaluator.languageSupported mustEqual "*"
    }
  }
}

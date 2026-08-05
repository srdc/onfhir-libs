package io.onfhir.validation

import io.onfhir.api.validation.ConstraintKeys
import io.onfhir.path.FhirPathEvaluator
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Tests for ConstraintsRestriction / FhirConstraint, the FHIR path invariants
 * (StructureDefinition constraint elements) attached to a profile or an element.
 */
@RunWith(classOf[JUnitRunner])
class ConstraintValidationTest extends Specification {
  import ValidationTestFixtures._

  //An expression that always raises during evaluation; 'single' rejects multi item collections
  private val throwingExpression = "(1 | 2).single()"

  private val statusElement = element("status", Map(
    ConstraintKeys.DATATYPE -> TypeRestriction(Seq("code" -> Nil))
  ))

  private def constraint(key: String, desc: String, expression: String, isWarning: Boolean = false): ConstraintsRestriction =
    ConstraintsRestriction(Seq(FhirConstraint(key, desc, FhirPathEvaluator.parse(expression), isWarning)))

  /** Validator whose profile carries the invariant at profile level (StructureDefinition.constraint) */
  private def profileLevelValidator(restriction: ConstraintsRestriction): FhirContentValidator =
    FhirContentValidator(
      config(Seq(profile(elementRestrictions = Seq(statusElement), constraints = Some(restriction)))),
      TestProfileUrl
    )

  "ConstraintsRestriction" should {
    "not report anything when a profile level invariant is satisfied" in {
      val validator = profileLevelValidator(constraint("inv-1", "Status is mandatory", "status.exists()"))

      awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"active"}"""
      ))) must beEmpty
    }

    "report a violated invariant as an error naming its key and description" in {
      val validator = profileLevelValidator(constraint("inv-1", "Status is mandatory", "status.exists()"))

      val issues = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource"}"""
      )))

      issues must haveSize(1)
      issues.head.severity mustEqual "error"
      issues.head.expression mustEqual Seq("$this")
      issues.head.diagnostics.exists(_.contains("inv-1")) must beTrue
      issues.head.diagnostics.exists(_.contains("Status is mandatory")) must beTrue
      issues.head.diagnostics.exists(_.contains("status.exists()")) must beTrue
    }

    "report a violated invariant flagged as warning with warning severity" in {
      val validator = profileLevelValidator(constraint("inv-2", "Status should exist", "status.exists()", isWarning = true))

      val issues = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource"}"""
      )))

      issues must haveSize(1)
      issues.head.severity mustEqual "warning"
      issues.head.diagnostics.exists(_.contains("inv-2")) must beTrue
    }

    "evaluate invariants attached to a single element on that element content" in {
      val validator = FhirContentValidator(
        config(Seq(profile(elementRestrictions = Seq(element("status", Map(
          ConstraintKeys.DATATYPE -> TypeRestriction(Seq("code" -> Nil)),
          ConstraintKeys.CONSTRAINT -> constraint("inv-el", "Status shall be active", "$this = 'active'")
        )))))),
        TestProfileUrl
      )

      awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"active"}"""
      ))) must beEmpty

      val issues = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"retired"}"""
      )))

      issues must haveSize(1)
      issues.head.severity mustEqual "error"
      issues.head.expression mustEqual Seq("status")
      issues.head.diagnostics.exists(_.contains("inv-el")) must beTrue
      issues.head.diagnostics.exists(_.contains("Status shall be active")) must beTrue
    }

    "turn a FHIR path evaluation exception into a failure that keeps the warning flag" in {
      val validator = FhirContentValidator(config(Seq(profile(elementRestrictions = Seq(statusElement)))), TestProfileUrl)
      val content = resource("""{"resourceType":"TestResource","status":"active"}""")

      val errorFailures = constraint("inv-throw", "Broken invariant", throwingExpression).evaluate(content, validator)
      errorFailures must haveSize(1)
      errorFailures.head.errorOrWarningMessage must startWith("Problem [exception")
      errorFailures.head.errorOrWarningMessage must contain("inv-throw")
      errorFailures.head.isWarning must beFalse

      val warningFailures = constraint("inv-throw", "Broken invariant", throwingExpression, isWarning = true).evaluate(content, validator)
      warningFailures must haveSize(1)
      warningFailures.head.errorOrWarningMessage must startWith("Problem [exception")
      warningFailures.head.isWarning must beTrue
    }

    "surface an invariant that raises during evaluation with the severity of its warning flag" in {
      val errorIssues = awaitResult(
        profileLevelValidator(constraint("inv-throw", "Broken invariant", throwingExpression))
          .validateComplexContent(resource("""{"resourceType":"TestResource","status":"active"}"""))
      )
      val warningIssues = awaitResult(
        profileLevelValidator(constraint("inv-throw", "Broken invariant", throwingExpression, isWarning = true))
          .validateComplexContent(resource("""{"resourceType":"TestResource","status":"active"}"""))
      )

      errorIssues must haveSize(1)
      errorIssues.head.severity mustEqual "error"
      errorIssues.head.diagnostics.exists(_.startsWith("Problem [exception")) must beTrue

      warningIssues must haveSize(1)
      warningIssues.head.severity mustEqual "warning"
      warningIssues.head.diagnostics.exists(_.startsWith("Problem [exception")) must beTrue
    }
  }
}

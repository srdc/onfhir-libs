package io.onfhir.validation

import io.onfhir.api.validation.ConstraintKeys
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global

@RunWith(classOf[JUnitRunner])
class FhirContentValidatorTest extends Specification {
  import ValidationTestFixtures._

  private val baseProfile = profile(
    elementRestrictions = Seq(
      element("status", Map(
        ConstraintKeys.MIN -> CardinalityMinRestriction(1),
        ConstraintKeys.DATATYPE -> TypeRestriction(Seq("code" -> Nil))
      )),
      element("tag", Map(
        ConstraintKeys.MIN -> CardinalityMinRestriction(1),
        ConstraintKeys.MAX -> CardinalityMaxRestriction(2),
        ConstraintKeys.ARRAY -> ArrayRestriction(),
        ConstraintKeys.DATATYPE -> TypeRestriction(Seq("string" -> Nil))
      )),
      element("value[x]", Map(
        ConstraintKeys.DATATYPE -> TypeRestriction(Seq("string" -> Nil, "integer" -> Nil))
      ))
    )
  )

  "FhirContentValidator" should {
    "validate a synthetic resource with required, array, and choice elements" in {
      val validator = FhirContentValidator(config(Seq(baseProfile)), TestProfileUrl)
      val issues = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"active","tag":["one"],"valueString":"ok"}"""
      )))

      issues must beEmpty
    }

    "report missing required elements, invalid cardinality, and unrecognized fields" in {
      val validator = FhirContentValidator(config(Seq(baseProfile)), TestProfileUrl)
      val issues = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","tag":"one","extra":"not allowed","valueInteger":"not an integer"}"""
      )))

      issues.map(_.expression.head) must contain("status")
      issues.map(_.expression.head) must contain("tag")
      issues.map(_.expression.head) must contain("extra")
      issues.map(_.expression.head) must contain("valueInteger")
      issues.forall(_.severity == "error") must beTrue
    }

    "combine a derived profile with its base profile" in {
      val derivedUrl = "http://example.org/fhir/StructureDefinition/DerivedTestResource"
      val derivedProfile = profile(
        url = derivedUrl,
        baseUrl = Some(TestProfileUrl -> None),
        elementRestrictions = Seq(element("category", Map(
          ConstraintKeys.MIN -> CardinalityMinRestriction(1),
          ConstraintKeys.DATATYPE -> TypeRestriction(Seq("code" -> Nil))
        ), profileDefinedIn = derivedUrl))
      )
      val validator = FhirContentValidator(config(Seq(baseProfile, derivedProfile)), derivedUrl)

      val valid = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"active","tag":["one"],"category":"laboratory"}"""
      )))
      val invalid = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"active","tag":["one"]}"""
      )))

      valid must beEmpty
      invalid.map(_.expression.head) must contain("category")
    }
  }
}

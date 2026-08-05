package io.onfhir.validation

import io.onfhir.api.FHIR_ROOT_URL_FOR_DEFINITIONS
import io.onfhir.api.validation.{ConstraintKeys, FhirSlicing}
import org.json4s.JsonAST.JString
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global

@RunWith(classOf[JUnitRunner])
class SlicingValidationTest extends Specification {
  import ValidationTestFixtures._

  private val componentProfileUrl = s"$FHIR_ROOT_URL_FOR_DEFINITIONS/StructureDefinition/Component"
  private val componentProfile = profile(
    url = componentProfileUrl,
    resourceType = "Component",
    elementRestrictions = Seq(element("code", Map(
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("code" -> Nil))
    ), profileDefinedIn = componentProfileUrl))
  )
  private val slicedProfile = profile(elementRestrictions = Seq(
    element("component", Map(
      ConstraintKeys.ARRAY -> ArrayRestriction(),
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("Component" -> Nil))
    ), slicing = Some(FhirSlicing(Seq("value" -> "code"), ordered = false, rule = "closed"))),
    element("component:alpha", Map(
      ConstraintKeys.MIN -> CardinalityMinRestriction(1),
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("Component" -> Nil))
    ), sliceName = Some("alpha")),
    element("component:alpha.code", Map(
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("code" -> Nil)),
      ConstraintKeys.PATTERN -> FixedOrPatternRestriction(JString("alpha"), isFixed = true)
    )),
    element("component:beta", Map(
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("Component" -> Nil))
    ), sliceName = Some("beta")),
    element("component:beta.code", Map(
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("code" -> Nil)),
      ConstraintKeys.PATTERN -> FixedOrPatternRestriction(JString("beta"), isFixed = true)
    ))
  ))

  private def validator = FhirContentValidator(
    config(Seq(slicedProfile, componentProfile), complexTypes = Set("Component")),
    TestProfileUrl
  )

  private def validatorWithSlicing(rule: String, ordered: Boolean = false) = {
    val adjustedProfile = slicedProfile.copy(elementRestrictions = slicedProfile.elementRestrictions.map {
      case ("component", restriction) =>
        "component" -> restriction.copy(slicing = Some(FhirSlicing(Seq("value" -> "code"), ordered, rule)))
      case other => other
    })
    FhirContentValidator(config(Seq(adjustedProfile, componentProfile), complexTypes = Set("Component")), TestProfileUrl)
  }

  "FhirContentValidator slicing" should {
    "accept values matching each closed value-discriminator slice" in {
      val issues = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","component":[{"code":"alpha"},{"code":"beta"}]}"""
      )))

      issues must beEmpty
    }

    "reject a value that does not match any closed slice" in {
      val issues = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","component":[{"code":"gamma"}]}"""
      )))

      issues.map(_.expression.head) must contain("component")
      issues.exists(_.diagnostics.exists(_.contains("does not match any slice"))) must beTrue
    }

    "allow an unmatched value when the slicing rule is open" in {
      val issues = awaitResult(validatorWithSlicing("open").validateComplexContent(resource(
        """{"resourceType":"TestResource","component":[{"code":"alpha"},{"code":"gamma"}]}"""
      )))

      issues must beEmpty
    }

    "enforce required slices and declared slice order" in {
      val missingRequiredSlice = awaitResult(validator.validateComplexContent(resource(
        """{"resourceType":"TestResource","component":[{"code":"beta"}]}"""
      )))
      val unorderedSlices = awaitResult(validatorWithSlicing("closed", ordered = true).validateComplexContent(resource(
        """{"resourceType":"TestResource","component":[{"code":"beta"},{"code":"alpha"}]}"""
      )))

      missingRequiredSlice.exists(_.diagnostics.exists(_.contains("Based on the slice definition 'alpha'"))) must beTrue
      unorderedSlices.exists(_.diagnostics.exists(_.contains("Problem in order of values matched to slice"))) must beTrue
    }
  }
}

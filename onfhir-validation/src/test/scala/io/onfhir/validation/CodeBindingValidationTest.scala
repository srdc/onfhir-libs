package io.onfhir.validation

import io.onfhir.api.validation.{ConstraintKeys, ValueSetDef, ValueSetRestrictions}
import org.json4s.JsonAST.{JArray, JInt, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Tests for CodeBindingRestriction, the ValueSet binding of an element.
 */
@RunWith(classOf[JUnitRunner])
class CodeBindingValidationTest extends Specification {
  import ValidationTestFixtures._

  private val systemUrl = "http://example.org/CodeSystem/test"
  private val otherSystemUrl = "http://example.org/CodeSystem/other"
  private val valueSetUrl = "http://example.org/ValueSet/test"
  private val unknownValueSetUrl = "http://example.org/ValueSet/unknown"
  private val multiVersionValueSetUrl = "http://example.org/ValueSet/multi-version"
  private val valueSets = Map(
    valueSetUrl -> Map("1" -> ValueSetRestrictions(ValueSetDef(Map(systemUrl -> Set("allowed"))))),
    multiVersionValueSetUrl -> Map(
      "1" -> ValueSetRestrictions(ValueSetDef(Map(systemUrl -> Set("in-version-1")))),
      "2" -> ValueSetRestrictions(ValueSetDef(Map(systemUrl -> Set("in-version-2"))))
    )
  )

  private def bindingProfile(strength: String, vsUrl: String = valueSetUrl) =
    profile(elementRestrictions = Seq(element("status", Map(
      ConstraintKeys.DATATYPE -> TypeRestriction(Seq("code" -> Nil)),
      ConstraintKeys.BINDING -> CodeBindingRestriction(vsUrl, Some("1"), strength)
    ))))

  private def validatorFor(strength: String, vsUrl: String = valueSetUrl): FhirContentValidator =
    FhirContentValidator(config(Seq(bindingProfile(strength, vsUrl)), valueSets = valueSets), TestProfileUrl)

  /** Only used as the terminology validator holder for direct restriction evaluation */
  private val directValidator = FhirContentValidator(config(Seq(profile()), valueSets = valueSets), TestProfileUrl)

  private def coding(system: Option[String], code: Option[String]): JObject =
    JObject(
      system.map(s => "system" -> JString(s)).toList ++ code.map(c => "code" -> JString(c)).toList
    )

  private def codeableConcept(codings: JObject*): JObject = JObject("coding" -> JArray(codings.toList))

  "CodeBindingRestriction" should {
    "accept a code that is a member of the bound value set" in {
      val issues = awaitResult(validatorFor("required").validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"allowed"}"""
      )))

      issues must beEmpty
    }

    "report a required binding violation as an error" in {
      val issues = awaitResult(validatorFor("required").validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"other"}"""
      )))

      issues must haveSize(1)
      issues.head.severity mustEqual "error"
      issues.head.expression mustEqual Seq("status")
      issues.head.diagnostics.exists(_.contains("Code binding failure")) must beTrue
      issues.head.diagnostics.exists(_.contains(valueSetUrl)) must beTrue
    }

    "report non required binding violations as warnings and skip example bindings" in {
      val extensibleIssues = awaitResult(validatorFor("extensible").validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"other"}"""
      )))
      val preferredIssues = awaitResult(validatorFor("preferred").validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"other"}"""
      )))
      val exampleIssues = awaitResult(validatorFor("example").validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"other"}"""
      )))

      extensibleIssues must haveSize(1)
      extensibleIssues.head.severity mustEqual "warning"
      preferredIssues must haveSize(1)
      preferredIssues.head.severity mustEqual "warning"
      exampleIssues must beEmpty
    }

    "accept a CodeableConcept when any of its codings is in the value set" in {
      val restriction = CodeBindingRestriction(valueSetUrl, Some("1"), "required")

      restriction.evaluate(codeableConcept(
        coding(Some(otherSystemUrl), Some("other")),
        coding(Some(systemUrl), Some("allowed"))
      ), directValidator) must beEmpty

      val failures = restriction.evaluate(codeableConcept(
        coding(Some(otherSystemUrl), Some("other")),
        coding(Some(systemUrl), Some("not-allowed"))
      ), directValidator)

      failures must haveSize(1)
      failures.head.isWarning must beFalse
      failures.head.errorOrWarningMessage must contain(s"($otherSystemUrl,other)")
      failures.head.errorOrWarningMessage must contain(s"($systemUrl,not-allowed)")

      //Current behaviour: a CodeableConcept with an empty coding list is not reported
      restriction.evaluate(codeableConcept(), directValidator) must beEmpty
    }

    "require a resolvable system and code for Coding or Quantity shaped values" in {
      val required = CodeBindingRestriction(valueSetUrl, Some("1"), "required")
      val preferred = CodeBindingRestriction(valueSetUrl, Some("1"), "preferred")

      required.evaluate(coding(Some(systemUrl), Some("allowed")), directValidator) must beEmpty

      required.evaluate(coding(None, Some("allowed")), directValidator) must haveSize(1)
      required.evaluate(coding(Some(systemUrl), None), directValidator) must haveSize(1)

      val failures = required.evaluate(coding(Some(systemUrl), Some("not-allowed")), directValidator)
      failures must haveSize(1)
      failures.head.isWarning must beFalse
      failures.head.errorOrWarningMessage must contain(s"($systemUrl,not-allowed)")

      preferred.evaluate(coding(Some(systemUrl), Some("not-allowed")), directValidator).head.isWarning must beTrue

      //Values that are neither a code nor an object are out of scope for this restriction
      required.evaluate(JInt(1), directValidator) must beEmpty
    }

    "resolve the value set version, falling back to a single available version when none is given" in {
      val restriction = CodeBindingRestriction(valueSetUrl, None, "required")

      restriction.evaluate(JString("allowed"), directValidator) must beEmpty
      restriction.evaluate(JString("other"), directValidator) must haveSize(1)
    }

    "pick a version by its key ordering when a value set has several versions and none is requested" in {
      val explicitlyVersioned = CodeBindingRestriction(multiVersionValueSetUrl, Some("2"), "required")
      val unversioned = CodeBindingRestriction(multiVersionValueSetUrl, None, "required")

      explicitlyVersioned.evaluate(JString("in-version-2"), directValidator) must beEmpty
      explicitlyVersioned.evaluate(JString("in-version-1"), directValidator) must haveSize(1)

      //Current behaviour: FhirTerminologyValidator.getValueSet sorts the version keys and takes the
      //first one, so the lowest key wins here rather than the newest version its scaladoc promises
      unversioned.evaluate(JString("in-version-1"), directValidator) must beEmpty
      unversioned.evaluate(JString("in-version-2"), directValidator) must haveSize(1)
    }

    "warn about an unsupported value set whatever the binding strength" in {
      Seq("required", "extensible", "preferred").foreach(strength => {
        val failures = CodeBindingRestriction(unknownValueSetUrl, None, strength).evaluate(JString("allowed"), directValidator)
        failures must haveSize(1)
        failures.head.isWarning must beTrue
        failures.head.errorOrWarningMessage must contain("Unknown or not processable ValueSet")
      })

      //A known value set requested with an unknown business version is unsupported as well
      val versionMismatch = CodeBindingRestriction(valueSetUrl, Some("2"), "required").evaluate(JString("allowed"), directValidator)
      versionMismatch must haveSize(1)
      versionMismatch.head.isWarning must beTrue
      versionMismatch.head.errorOrWarningMessage must contain("Unknown or not processable ValueSet")

      val issues = awaitResult(validatorFor("required", unknownValueSetUrl).validateComplexContent(resource(
        """{"resourceType":"TestResource","status":"allowed"}"""
      )))
      issues must haveSize(1)
      issues.head.severity mustEqual "warning"
      issues.head.diagnostics.exists(_.contains("Unknown or not processable ValueSet")) must beTrue
    }
  }
}

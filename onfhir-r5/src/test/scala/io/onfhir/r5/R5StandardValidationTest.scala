package io.onfhir.r5

import io.onfhir.api.model.OutcomeIssue
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Integration suite: validate resources through `FhirValidator` against the
 * real R5 5.0.0 definitions parsed by [[R5IntegrationFixtures]] with
 * [[io.onfhir.r5.parsers.R5Parser]].
 *
 * Deliberately more compact than the R4 validation suite: validator mechanics
 * are already covered there, so this suite asserts one case per category to
 * prove the chain works over R5-parsed profiles, plus the R5-specific
 * consequences (widened Observation.subject targets, and the terminology gap
 * left by the core package no longer shipping THO content).
 */
@RunWith(classOf[JUnitRunner])
class R5StandardValidationTest extends Specification {
  import R5IntegrationFixtures._

  private val narrative =
    """"text":{"status":"generated","div":"<div xmlns='http://www.w3.org/1999/xhtml'><p>Narrative</p></div>"}"""

  private val validPatient =
    s"""{
       |  "resourceType":"Patient",
       |  "id":"pat1",
       |  $narrative,
       |  "active":true,
       |  "name":[{"use":"official","family":"Chalmers","given":["Peter","James"]}],
       |  "gender":"male",
       |  "birthDate":"1974-12-25"
       |}""".stripMargin

  // No category: the R5 core package cannot expand the observation-category
  // ValueSet (see the terminology-gap test below), so a zero-issue Observation
  // must not carry one.
  private val validObservation =
    s"""{
       |  "resourceType":"Observation",
       |  "id":"obs1",
       |  $narrative,
       |  "status":"final",
       |  "code":{"coding":[{"system":"http://loinc.org","code":"29463-7","display":"Body Weight"}]},
       |  "subject":{"reference":"Patient/pat1"},
       |  "effectiveDateTime":"2024-03-28",
       |  "valueQuantity":{"value":85,"unit":"kg","system":"http://unitsofmeasure.org","code":"kg"}
       |}""".stripMargin

  private def validate(json: String): Seq[OutcomeIssue] =
    awaitResult(validator.validateResource(resource(json)))

  private def errorsAt(issues: Seq[OutcomeIssue], expression: String): Seq[OutcomeIssue] =
    issues.filter(issue => issue.severity == "error" && issue.expression.contains(expression))

  private def diagnosticsAt(issues: Seq[OutcomeIssue], expression: String): String =
    errorsAt(issues, expression).flatMap(_.diagnostics).mkString(" ")

  /** Issues that are not the ubiquitous dom-6 narrative warning. */
  private def significant(issues: Seq[OutcomeIssue]): Seq[OutcomeIssue] =
    issues.filterNot(_.diagnostics.exists(_.contains("'dom-6'")))

  "FhirValidator over the R5 standard package" should {

    "report no issues for a conformant Patient" in {
      validate(validPatient) must beEmpty
    }

    "report no issues for a conformant Observation" in {
      validate(validObservation) must beEmpty
    }

    "warn that the observation-category ValueSet is not expandable from the core package" in {
      // NOTE: documents current behavior, see plan Findings. The R5 core
      // definitions ZIP no longer carries the THO terminology content backing
      // http://hl7.org/fhir/ValueSet/observation-category, so the preferred
      // binding cannot be checked and the validator reports it as an
      // unprocessable ValueSet warning rather than validating the code.
      val withCategory = validObservation.replace(
        "\"status\":\"final\",",
        "\"status\":\"final\",\n  \"category\":[{\"coding\":[{\"system\":\"http://terminology.hl7.org/CodeSystem/observation-category\",\"code\":\"vital-signs\"}]}],")
      val issues = validate(withCategory)

      issues must haveSize(1)
      issues.head.severity mustEqual "warning"
      issues.head.expression must contain("category[0]")
      issues.head.diagnostics.exists(_.contains(
        "Unknown or not processable ValueSet 'http://hl7.org/fhir/ValueSet/observation-category'")) must beTrue
    }

    "report every missing required element" in {
      val issues = validate("""{"resourceType":"Observation"}""")

      diagnosticsAt(issues, "status") must contain("is required")
      diagnosticsAt(issues, "code") must contain("is required")
      significant(issues) must haveSize(2)
    }

    "report a lexically invalid primitive value" in {
      val issues = validate("""{"resourceType":"Patient","birthDate":"2026-13-99"}""")

      diagnosticsAt(issues, "birthDate") must contain("Invalid value '2026-13-99' for FHIR primitive type 'date'")
      significant(issues) must haveSize(1)
    }

    "report a code outside a required binding" in {
      val issues = validate(
        """{"resourceType":"Observation","status":"bogus","code":{"text":"body weight"}}""")

      diagnosticsAt(issues, "status") must contain("Code binding failure")
      diagnosticsAt(issues, "status") must contain("http://hl7.org/fhir/ValueSet/observation-status")
      significant(issues) must haveSize(1)
    }

    "enforce the widened R5 reference targets" in {
      // Medication is a valid Observation.subject target in R5 (it was not in
      // R4), while Encounter is not a subject target in either release.
      val medicationSubject = validate(
        """{"resourceType":"Observation","status":"final","code":{"text":"x"},
          |"subject":{"reference":"Medication/med1"}}""".stripMargin)
      errorsAt(medicationSubject, "subject") must beEmpty

      val encounterSubject = validate(
        """{"resourceType":"Observation","status":"final","code":{"text":"x"},
          |"subject":{"reference":"Encounter/enc1"}}""".stripMargin)
      errorsAt(encounterSubject, "subject") must not(beEmpty)
      diagnosticsAt(encounterSubject, "subject") must contain("Referenced type 'Encounter' does not match")
      diagnosticsAt(encounterSubject, "subject") must contain("Medication")
    }

    "report a violated element-level invariant" in {
      val issues = validate("""{"resourceType":"Patient","contact":[{"gender":"male"}]}""")

      diagnosticsAt(issues, "contact[0]") must contain("'pat-1'")
      significant(issues) must haveSize(1)
    }

    "warn about an unknown claimed meta.profile while still validating the base profile" in {
      val issues = validate(
        """{"resourceType":"Patient","meta":{"profile":["http://example.org/StructureDefinition/Unknown"]},
          |"birthDate":"2026-13-99"}""".stripMargin)

      val unknownProfileWarnings = issues.filter(issue =>
        issue.severity == "warning" && issue.expression.contains("meta.profile"))
      unknownProfileWarnings must haveSize(1)
      unknownProfileWarnings.head.code mustEqual "not-supported"

      errorsAt(issues, "birthDate") must not(beEmpty)
    }
  }
}

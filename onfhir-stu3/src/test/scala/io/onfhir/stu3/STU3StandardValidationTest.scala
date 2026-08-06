package io.onfhir.stu3

import io.onfhir.api.model.OutcomeIssue
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Integration suite: validate resources through `FhirValidator` against the
 * real STU3 3.0.2 definitions parsed by [[STU3IntegrationFixtures]].
 *
 * Note on narrative: unlike R4 and R5, STU3's DomainResource has no `dom-6`
 * ("should have narrative") invariant, so conformant STU3 resources yield an
 * empty issue list without carrying a `text.div`.
 *
 * Two of these tests pin defects rather than desired behavior; both carry a
 * `// NOTE: documents current behavior` comment and are explained in
 * [[STU3StandardPackageParsingTest]] and the plan Findings.
 */
@RunWith(classOf[JUnitRunner])
class STU3StandardValidationTest extends Specification {
  import STU3IntegrationFixtures._

  private val validPatient =
    """{
      |  "resourceType":"Patient",
      |  "id":"pat1",
      |  "active":true,
      |  "name":[{"use":"official","family":"Chalmers","given":["Peter","James"]}],
      |  "gender":"male",
      |  "birthDate":"1974-12-25"
      |}""".stripMargin

  private val validObservation =
    """{
      |  "resourceType":"Observation",
      |  "id":"obs1",
      |  "status":"final",
      |  "code":{"coding":[{"system":"http://loinc.org","code":"29463-7","display":"Body Weight"}]},
      |  "subject":{"reference":"Patient/pat1"},
      |  "effectiveDateTime":"2017-03-28",
      |  "valueQuantity":{"value":85,"unit":"kg","system":"http://unitsofmeasure.org","code":"kg"}
      |}""".stripMargin

  private def validate(json: String): Seq[OutcomeIssue] =
    awaitResult(validator.validateResource(resource(json)))

  private def errorsAt(issues: Seq[OutcomeIssue], expression: String): Seq[OutcomeIssue] =
    issues.filter(issue => issue.severity == "error" && issue.expression.contains(expression))

  private def diagnosticsAt(issues: Seq[OutcomeIssue], expression: String): String =
    errorsAt(issues, expression).flatMap(_.diagnostics).mkString(" ")

  "FhirValidator over the STU3 standard package" should {

    "report no issues for a conformant Patient" in {
      // No narrative needed: STU3 has no dom-6 invariant.
      validate(validPatient) must beEmpty
    }

    "report no issues for a conformant Observation" in {
      validate(validObservation) must beEmpty
    }

    "report every missing required element" in {
      val issues = validate("""{"resourceType":"Observation"}""")

      diagnosticsAt(issues, "status") must contain("is required")
      diagnosticsAt(issues, "code") must contain("is required")
      issues must haveSize(2)
    }

    "report a lexically invalid primitive value" in {
      val issues = validate("""{"resourceType":"Patient","birthDate":"2026-13-99"}""")

      diagnosticsAt(issues, "birthDate") must contain("Invalid value '2026-13-99' for FHIR primitive type 'date'")
      issues must haveSize(1)
    }

    "report an unrecognized element" in {
      val issues = validate("""{"resourceType":"Patient","deceasedBogus":true}""")

      diagnosticsAt(issues, "deceasedBogus") must contain("Unrecognized element")
      issues must haveSize(1)
    }

    "report a violated element-level invariant" in {
      val issues = validate("""{"resourceType":"Patient","contact":[{"gender":"male"}]}""")

      diagnosticsAt(issues, "contact[0]") must contain("'pat-1'")
      issues must haveSize(1)
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

    "not enforce required code bindings" in {
      // NOTE: documents current behavior, see plan Findings.
      // Every STU3 binding parses to the "$parent" sentinel because
      // AbstractStructureDefinitionParser reads only R4's canonical
      // binding.valueSet, never STU3's valueSetReference. The validator
      // resolves "$parent" by searching the profile chain for a concrete
      // ValueSet, finds none (the whole chain is STU3), and returns no issues.
      // A bogus Observation.status is therefore accepted, where R4 and R5
      // both report a code binding failure.
      val issues = validate(
        """{"resourceType":"Observation","status":"bogus","code":{"text":"body weight"}}""")

      issues must beEmpty
    }

    "reject reference targets that STU3 actually allows" in {
      // NOTE: documents current behavior, see plan Findings.
      // STU3 repeats ElementDefinition.type once per target profile and the
      // parser keeps only the first, so Observation.subject is understood as
      // Patient-only. Group is a legitimate STU3 subject target but is
      // rejected; Medication is correctly rejected. Both assertions therefore
      // describe the same underlying truncation.
      val groupSubject = validate(
        """{"resourceType":"Observation","status":"final","code":{"text":"x"},
          |"subject":{"reference":"Group/grp1"}}""".stripMargin)
      errorsAt(groupSubject, "subject") must not(beEmpty)
      diagnosticsAt(groupSubject, "subject") must contain("Referenced type 'Group' does not match")
      diagnosticsAt(groupSubject, "subject") must contain("'Patient'")

      val medicationSubject = validate(
        """{"resourceType":"Observation","status":"final","code":{"text":"x"},
          |"subject":{"reference":"Medication/med1"}}""".stripMargin)
      errorsAt(medicationSubject, "subject") must not(beEmpty)
    }
  }
}

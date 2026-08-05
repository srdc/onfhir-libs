package io.onfhir.r4

import io.onfhir.api.model.OutcomeIssue
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Integration suite: validate realistic FHIR resources through `FhirValidator`
 * against the real R4 4.0.1 definitions parsed by [[R4IntegrationFixtures]].
 *
 * One negative case per validation category, each asserting the severity and
 * the expression path so a regression that merely stops reporting, or reports
 * at the wrong path, still fails.
 *
 * Note on narrative: the base FHIR `dom-6` invariant ("A resource should have
 * narrative for robust management") is a WARNING on every DomainResource. The
 * positive fixtures below therefore carry a narrative so that "conformant"
 * really means an empty issue list rather than "no errors".
 */
@RunWith(classOf[JUnitRunner])
class R4StandardValidationTest extends Specification {
  import R4IntegrationFixtures._

  private val narrative =
    """"text":{"status":"generated","div":"<div xmlns='http://www.w3.org/1999/xhtml'><p>Narrative</p></div>"}"""

  private val validPatient =
    s"""{
       |  "resourceType":"Patient",
       |  "id":"pat1",
       |  $narrative,
       |  "active":true,
       |  "name":[{"use":"official","family":"Chalmers","given":["Peter","James"]}],
       |  "telecom":[{"system":"phone","value":"(03) 5555 6473","use":"work"}],
       |  "gender":"male",
       |  "birthDate":"1974-12-25",
       |  "address":[{"use":"home","line":["534 Erewhon St"],"city":"PleasantVille","state":"Vic","postalCode":"3999"}]
       |}""".stripMargin

  private val validObservation =
    s"""{
       |  "resourceType":"Observation",
       |  "id":"obs1",
       |  $narrative,
       |  "status":"final",
       |  "category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"vital-signs"}]}],
       |  "code":{"coding":[{"system":"http://loinc.org","code":"29463-7","display":"Body Weight"}]},
       |  "subject":{"reference":"Patient/pat1"},
       |  "effectiveDateTime":"2016-03-28",
       |  "valueQuantity":{"value":185,"unit":"lbs","system":"http://unitsofmeasure.org","code":"[lb_av]"}
       |}""".stripMargin

  /** The same measurement, additionally conformant to the vitalsigns profile. */
  private val validVitalSignsObservation =
    s"""{
       |  "resourceType":"Observation",
       |  "id":"obs2",
       |  "meta":{"profile":["http://hl7.org/fhir/StructureDefinition/vitalsigns"]},
       |  $narrative,
       |  "status":"final",
       |  "category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"vital-signs"}]}],
       |  "code":{"coding":[{"system":"http://loinc.org","code":"29463-7"}]},
       |  "subject":{"reference":"Patient/pat1"},
       |  "effectiveDateTime":"2016-03-28",
       |  "valueQuantity":{"value":185,"unit":"lbs","system":"http://unitsofmeasure.org","code":"[lb_av]"}
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

  "FhirValidator over the R4 standard package" should {

    "report no issues for a conformant Patient" in {
      validate(validPatient) must beEmpty
    }

    "report no issues for a conformant Observation" in {
      validate(validObservation) must beEmpty
    }

    "report every missing required element" in {
      val issues = validate("""{"resourceType":"Observation"}""")

      errorsAt(issues, "status") must not(beEmpty)
      errorsAt(issues, "code") must not(beEmpty)
      diagnosticsAt(issues, "status") must contain("is required")
      diagnosticsAt(issues, "code") must contain("is required")
      significant(issues) must haveSize(2)
    }

    "report a lexically invalid primitive value" in {
      val issues = validate("""{"resourceType":"Patient","birthDate":"2026-13-99"}""")

      errorsAt(issues, "birthDate") must not(beEmpty)
      diagnosticsAt(issues, "birthDate") must contain("Invalid value '2026-13-99' for FHIR primitive type 'date'")
      significant(issues) must haveSize(1)
    }

    "report a choice element whose value does not match the chosen type" in {
      val issues = validate("""{"resourceType":"Patient","deceasedDateTime":"not-a-date"}""")

      errorsAt(issues, "deceasedDateTime") must not(beEmpty)
      diagnosticsAt(issues, "deceasedDateTime") must contain("for FHIR primitive type 'dateTime'")
      significant(issues) must haveSize(1)
    }

    "report an unrecognized choice element name" in {
      val issues = validate("""{"resourceType":"Patient","deceasedBogus":true}""")

      errorsAt(issues, "deceasedBogus") must not(beEmpty)
      diagnosticsAt(issues, "deceasedBogus") must contain("Unrecognized element")
      significant(issues) must haveSize(1)
    }

    "report a code outside a required binding" in {
      val issues = validate(
        """{"resourceType":"Observation","status":"bogus","code":{"text":"body weight"}}""")

      errorsAt(issues, "status") must not(beEmpty)
      diagnosticsAt(issues, "status") must contain("Code binding failure")
      diagnosticsAt(issues, "status") must contain("http://hl7.org/fhir/ValueSet/observation-status")
      significant(issues) must haveSize(1)
    }

    "report a violated element-level invariant" in {
      // pat-1: a Patient.contact SHALL have a contact's details or a
      // reference to an organization.
      val issues = validate("""{"resourceType":"Patient","contact":[{"gender":"male"}]}""")

      errorsAt(issues, "contact[0]") must not(beEmpty)
      diagnosticsAt(issues, "contact[0]") must contain("'pat-1'")
      significant(issues) must haveSize(1)
    }

    "report a reference pointing at a disallowed target type" in {
      val issues = validate(
        """{"resourceType":"Observation","status":"final","code":{"text":"body weight"},
          |"subject":{"reference":"Medication/med1"}}""".stripMargin)

      errorsAt(issues, "subject") must not(beEmpty)
      diagnosticsAt(issues, "subject") must contain("Referenced type 'Medication' does not match one of the expected target types")
      significant(issues) must haveSize(1)
    }

    "validate the value of a standard extension" in {
      val issues = validate(
        """{"resourceType":"Patient","extension":[
          |{"url":"http://hl7.org/fhir/StructureDefinition/patient-birthTime","valueDateTime":"not-a-datetime"}]}""".stripMargin)

      errorsAt(issues, "extension[0].valueDateTime") must not(beEmpty)
      diagnosticsAt(issues, "extension[0].valueDateTime") must contain("for FHIR primitive type 'dateTime'")
      significant(issues) must haveSize(1)
    }

    "report issues of an invalid Bundle entry at the entry path" in {
      val issues = validate(
        """{"resourceType":"Bundle","type":"collection","entry":[
          |{"fullUrl":"urn:uuid:1","resource":{"resourceType":"Observation"}}]}""".stripMargin)

      errorsAt(issues, "entry[0].resource.status") must not(beEmpty)
      errorsAt(issues, "entry[0].resource.code") must not(beEmpty)
      // The entry resource is validated against its own base profile, reached
      // through the Bundle profile.
      diagnosticsAt(issues, "entry[0].resource.status") must contain("StructureDefinition/Observation")
      significant(issues) must haveSize(2)
    }

    "warn about an unknown claimed meta.profile while still validating the base profile" in {
      val issues = validate(
        """{"resourceType":"Patient","meta":{"profile":["http://example.org/StructureDefinition/Unknown"]},
          |"birthDate":"2026-13-99"}""".stripMargin)

      val unknownProfileWarnings = issues.filter(issue =>
        issue.severity == "warning" && issue.expression.contains("meta.profile"))
      unknownProfileWarnings must haveSize(1)
      unknownProfileWarnings.head.code mustEqual "not-supported"
      unknownProfileWarnings.head.diagnostics.exists(_.contains("is not known to this validator")) must beTrue

      // Base validation still ran: the bad birthDate is reported too.
      errorsAt(issues, "birthDate") must not(beEmpty)
    }

    "enforce a known claimed meta.profile instead of the base profile it derives from" in {
      // The vitalsigns profile ships in the standard package, so claiming it is
      // not "unknown", and because its profile chain already contains the base
      // Observation profile the validator must not evaluate that base profile
      // a second time.
      val issues = validate(
        """{"resourceType":"Observation",
          |"meta":{"profile":["http://hl7.org/fhir/StructureDefinition/vitalsigns"]},
          |"status":"final","code":{"text":"weight"}}""".stripMargin)

      issues.filter(_.expression.contains("meta.profile")) must beEmpty
      issues must not(beEmpty)
      issues.exists(_.diagnostics.exists(_.contains(
        "[Validating against 'http://hl7.org/fhir/StructureDefinition/Observation']"))) must beFalse
      issues.forall(_.diagnostics.exists(_.contains(
        "StructureDefinition/vitalsigns"))) must beTrue

      // Elements the base Observation profile leaves optional are mandatory in
      // vitalsigns, and its own invariant vs-2 is evaluated.
      errorsAt(issues, "category") must not(beEmpty)
      errorsAt(issues, "subject") must not(beEmpty)
      errorsAt(issues, "effective[x]") must not(beEmpty)
      issues.exists(issue => issue.severity == "error" && issue.diagnostics.exists(_.contains("'vs-2'"))) must beTrue

      // NOTE: documents current behavior, see plan Findings. A CodeableConcept
      // carrying only 'text' is evaluated through the Coding branch of
      // CodeBindingRestriction, so the diagnostics render an empty system-code
      // pair instead of naming the missing coding.
      val codeBindingIssues = issues.filter(issue => issue.severity == "warning" && issue.expression.contains("code"))
      codeBindingIssues must haveSize(1)
      codeBindingIssues.head.diagnostics.exists(_.contains("system-code pairing '(' ',' ')'")) must beTrue
    }

    "report no issues for an Observation conformant to the claimed vitalsigns profile" in {
      validate(validVitalSignsObservation) must beEmpty
    }
  }

  private def resourceHasNarrative(json: String): Boolean = json.contains("\"div\"")

  "The positive fixtures" should {
    "carry a narrative so that an empty issue list is achievable" in {
      resourceHasNarrative(validPatient) must beTrue
      resourceHasNarrative(validObservation) must beTrue

      // Without the narrative the same Patient yields exactly the dom-6 warning
      // and nothing else, which is what makes `significant(...)` filtering safe
      // in the negative cases above.
      val withoutNarrative = validPatient.replace(s"  $narrative,\n", "")
      val issues = validate(withoutNarrative)
      issues must haveSize(1)
      issues.head.severity mustEqual "warning"
      issues.head.diagnostics.exists(_.contains("'dom-6'")) must beTrue
    }
  }
}

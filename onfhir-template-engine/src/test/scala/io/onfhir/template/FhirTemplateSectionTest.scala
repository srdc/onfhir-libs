package io.onfhir.template

import io.onfhir.expression.FhirExpressionException
import io.onfhir.path.FhirPathEvaluator
import org.json4s.JValue
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Template sections; repeating a sub-template per selected item and rendering optional structures
 */
@RunWith(classOf[JUnitRunner])
class FhirTemplateSectionTest extends Specification with TemplateRenderSupport {
  sequential

  val careTeam: JValue = json(
    """{"resourceType":"CareTeam","id":"ct1","status":"active",
      |"subject":{"reference":"Patient/f001","display":"P. van de Heuvel"},
      |"participant":[
      |  {"role":[{"text":"adviser"}],"member":{"reference":"Practitioner/pr1","display":"Dorothy Dietition"}},
      |  {"role":[{"text":"responsiblePerson"},{"text":"contact"}],"member":{"reference":"Patient/example"}}
      |]}""".stripMargin)

  val emptyCareTeam: JValue = json("""{"resourceType":"CareTeam","id":"ct2","status":"active"}""")

  "A section marked with '*'" should {

    "render the sub-template once per selected item" in {
      val template =
        """{"recipient":{"{{#member}}":"{{ CareTeam.participant.member }}","{{*}}":{"reference":"{{ %member.reference }}"}}}"""
      render(template, careTeam) \ "recipient" mustEqual JArray(List(
        JObject("reference" -> JString("Practitioner/pr1")),
        JObject("reference" -> JString("Patient/example"))
      ))
    }

    "expose the one-based section index" in {
      val template =
        """{"recipient":{"{{#member}}":"{{ CareTeam.participant.member }}","{{*}}":{"id":"r{{ %sectionIndex }}"}}}"""
      render(template, careTeam) \ "recipient" mustEqual JArray(List(
        JObject("id" -> JString("r1")),
        JObject("id" -> JString("r2"))
      ))
    }

    "apply optional placeholders per item" in {
      val template =
        """{"recipient":{"{{#member}}":"{{ CareTeam.participant.member }}","{{*}}":{"reference":"{{ %member.reference }}","display":"{{? %member.display }}"}}}"""
      render(template, careTeam) \ "recipient" mustEqual JArray(List(
        JObject("reference" -> JString("Practitioner/pr1"), "display" -> JString("Dorothy Dietition")),
        JObject("reference" -> JString("Patient/example"))
      ))
    }

    "remove the field when the section selects nothing" in {
      val template =
        """{"status":"completed","recipient":{"{{#member}}":"{{ CareTeam.participant.member }}","{{*}}":{"reference":"{{ %member.reference }}"}}}"""
      render(template, emptyCareTeam) mustEqual json("""{"status":"completed"}""")
    }

    "keep working when the section variable is declared after the section value" in {
      val template =
        """{"recipient":{"{{*}}":{"reference":"{{ %member.reference }}"},"{{#member}}":"{{ CareTeam.participant.member }}"}}"""
      render(template, careTeam) \ "recipient" mustEqual JArray(List(
        JObject("reference" -> JString("Practitioner/pr1")),
        JObject("reference" -> JString("Patient/example"))
      ))
    }
  }

  "A section marked with '+'" should {

    "render the sub-template once per selected item" in {
      val template =
        """{"recipient":{"{{#member}}":"{{ CareTeam.participant.member }}","{{+}}":{"reference":"{{ %member.reference }}"}}}"""
      (render(template, careTeam) \ "recipient").asInstanceOf[JArray].arr must haveSize(2)
    }

    "fail when the section selects nothing" in {
      val template =
        """{"recipient":{"{{#member}}":"{{ CareTeam.participant.member }}","{{+}}":{"reference":"{{ %member.reference }}"}}}"""
      val t = renderFailure(template, emptyCareTeam)
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("1-n cardinality")
    }
  }

  "A section marked with '?'" should {

    "render the sub-template once, binding a single result directly" in {
      val template =
        """{"about":{"{{#s}}":"{{ CareTeam.subject }}","{{?}}":{"display":"{{ %s.display }}"}}}"""
      render(template, careTeam) \ "about" mustEqual JObject("display" -> JString("P. van de Heuvel"))
    }

    "bind all results as an array when the section selects several items" in {
      val template =
        """{"about":{"{{#m}}":"{{ CareTeam.participant.member }}","{{?}}":{"count":"{{ %m.count() }}"}}}"""
      val result = render(template, careTeam)
      FhirPathEvaluator().evaluateNumerical("about.count", result).map(_.toLong) mustEqual Seq(2L)
    }

    "remove the field when the section selects nothing" in {
      val template =
        """{"status":"completed","about":{"{{#s}}":"{{ CareTeam.participant.member }}","{{?}}":{"display":"{{? %s.display }}"}}}"""
      render(template, emptyCareTeam) mustEqual json("""{"status":"completed"}""")
    }
  }

  "Nested sections" should {

    "bind each level to its own variable" in {
      val template =
        """{"recipient":{"{{#p}}":"{{ CareTeam.participant }}","{{*}}":{
          |  "reference":"{{ %p.member.reference }}",
          |  "extension":{"{{#r}}":"{{ %p.role.text }}","{{*}}":{"url":"http://example.org/role","valueString":"{{ %r }}"}}
          |}}}""".stripMargin
      val result = render(template, careTeam)
      FhirPathEvaluator().evaluateString("recipient.extension.valueString", result) mustEqual
        Seq("adviser", "responsiblePerson", "contact")
    }
  }

  "A section used as an array item" should {

    "flatten its items into the surrounding array" in {
      val template =
        """{"recipient":[{"reference":"CareTeam/ct1"},
          |{"{{#member}}":"{{ CareTeam.participant.member }}","{{*}}":{"reference":"{{ %member.reference }}"}}]}""".stripMargin
      FhirPathEvaluator().evaluateString("recipient.reference", render(template, careTeam)) mustEqual
        Seq("CareTeam/ct1", "Practitioner/pr1", "Patient/example")
    }
  }

  "An invalid section" should {

    "be rejected when the section variable name is not a plain word" in {
      val template =
        """{"recipient":{"{{#my-member}}":"{{ CareTeam.participant.member }}","{{*}}":{"reference":"{{ %x.reference }}"}}}"""
      val t = renderFailure(template, careTeam)
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("Invalid FHIR template section field")
    }

    "be rejected when the section statement is not a placeholder" in {
      val template =
        """{"recipient":{"{{#member}}":"CareTeam.participant.member","{{*}}":{"reference":"{{ %member.reference }}"}}}"""
      val t = renderFailure(template, careTeam)
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("Invalid FHIR template section statement")
    }

    "be rejected when the section value field is not a cardinality marker" in {
      val template =
        """{"recipient":{"{{#member}}":"{{ CareTeam.participant.member }}","items":{"reference":"{{ %member.reference }}"}}}"""
      val t = renderFailure(template, careTeam)
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("Invalid FHIR template section value field")
    }

    "be rejected when the section object does not have exactly two fields" in {
      val template =
        """{"recipient":{"{{#member}}":"{{ CareTeam.participant.member }}","{{*}}":{"reference":"{{ %member.reference }}"},"status":"sent"}}"""
      val t = renderFailure(template, careTeam)
      t must haveClass[FhirExpressionException]
      t.getMessage must contain("Invalid FHIR template section")
    }
  }
}

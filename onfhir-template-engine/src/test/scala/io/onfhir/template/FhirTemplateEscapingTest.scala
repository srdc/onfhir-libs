package io.onfhir.template

import org.json4s.JValue
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Resolved values are substituted into the surrounding string literally; regression tests for values
 * that carry characters with a special meaning for regular expression replacements ('$' and '\').
 */
@RunWith(classOf[JUnitRunner])
class FhirTemplateEscapingTest extends Specification with TemplateRenderSupport {
  sequential

  /** Template with a placeholder embedded in a larger string */
  val embeddingTemplate = """{"contentString":"Note: {{ Patient.name.text }} end"}"""

  /**
   * Patient input with a single name; built as JSON values so that the tested characters do not
   * additionally travel through JSON string escaping
   */
  def patientNamed(nameText: String, family: Option[String] = None): JValue =
    JObject(
      "resourceType" -> JString("Patient"),
      "id" -> JString("p1"),
      "name" -> JArray(List(
        JObject(
          List("text" -> JString(nameText)) ++ family.map(f => "family" -> JString(f)).toList
        )
      ))
    )

  "A resolved value embedded in a string" should {

    "keep a group-reference-like dollar sequence" in {
      render(embeddingTemplate, patientNamed("Cost $1 dollars")) \ "contentString" mustEqual
        JString("Note: Cost $1 dollars end")
    }

    "keep a dollar sequence with no matching group" in {
      render(embeddingTemplate, patientNamed("pay $9 now")) \ "contentString" mustEqual
        JString("Note: pay $9 now end")
    }

    "keep a trailing dollar sign" in {
      render(embeddingTemplate, patientNamed("100$")) \ "contentString" mustEqual
        JString("Note: 100$ end")
    }

    "keep a dollar-brace sequence" in {
      render(embeddingTemplate, patientNamed("${HOME}/reports")) \ "contentString" mustEqual
        JString("Note: ${HOME}/reports end")
    }

    "keep backslashes" in {
      render(embeddingTemplate, patientNamed("""C:\reports\a.pdf""")) \ "contentString" mustEqual
        JString("""Note: C:\reports\a.pdf end""")
    }

    "keep a backslash followed by a dollar sign" in {
      render(embeddingTemplate, patientNamed("""\$1""")) \ "contentString" mustEqual
        JString("""Note: \$1 end""")
    }

    "keep special characters in every placeholder of a multi-placeholder string" in {
      val template = """{"contentString":"{{ Patient.name.text }} / {{ Patient.name.family }}"}"""
      render(template, patientNamed("$1 first", Some("$2 second"))) \ "contentString" mustEqual
        JString("$1 first / $2 second")
    }
  }

  "A resolved value that is the complete JSON value" should {

    "keep special characters" in {
      render("""{"text":"{{ Patient.name.text }}"}""", patientNamed("""$1 and \ and 100$""")) \ "text" mustEqual
        JString("""$1 and \ and 100$""")
    }
  }

  "A resolved value within a section" should {

    "keep special characters" in {
      val template =
        """{"payload":{"{{#n}}":"{{ Patient.name }}","{{*}}":{"contentString":"Name: {{ %n.text }}"}}}"""
      val input = JObject(
        "resourceType" -> JString("Patient"),
        "id" -> JString("p1"),
        "name" -> JArray(List(
          JObject("text" -> JString("A $1 B")),
          JObject("text" -> JString("100$"))
        ))
      )
      render(template, input) \ "payload" \ "contentString" mustEqual
        JArray(List(JString("Name: A $1 B"), JString("Name: 100$")))
    }
  }
}

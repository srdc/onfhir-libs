package io.onfhir.template

import io.onfhir.expression.FhirExpression
import io.onfhir.path.FhirPathEvaluator
import org.json4s.JsonAST.JString
import org.json4s.jackson.JsonMethods.parse
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * Executable versions of the examples in the module README
 */
@RunWith(classOf[JUnitRunner])
class FhirTemplateReadmeExampleTest extends Specification {
  sequential

  "the README quick-start example" should {
    "render a FHIRPath placeholder" in {
      implicit val executionContext: ExecutionContext = ExecutionContext.global
      val handler = new FhirTemplateExpressionHandler(isSourceContentFhir = true)
      val patient = parse("""{"resourceType":"Patient","id":"p1"}""")
      val template = FhirExpression(
        "patient-summary",
        handler.languageSupported,
        value = Some(parse("""{"id":"{{ Patient.id }}"}""")))

      val rendered = Await.result(handler.evaluateExpression(template, Map.empty, patient), 5.seconds)
      (rendered \ "id") mustEqual JString("p1")
    }
  }

  "the README worked example" should {

    val observationEvent = parse(
      """{
        |  "resourceType": "Observation",
        |  "id": "obs-00001",
        |  "status": "final",
        |  "subject": { "reference": "Patient/f001", "display": "P. van de Heuvel" },
        |  "effectiveDateTime": "2013-04-02T09:30:10+01:00",
        |  "valueQuantity": { "value": 6.3, "unit": "mmol/L" }
        |}""".stripMargin)

    val careTeam = parse(
      """{
        |  "resourceType": "CareTeam",
        |  "id": "ct-0001",
        |  "participant": [
        |    { "member": { "reference": "Practitioner/pr1", "display": "Dorothy Dietition" } },
        |    { "member": { "reference": "Patient/example" } }
        |  ]
        |}""".stripMargin)

    val communicationTemplate =
      """{
        |  "resourceType": "Communication",
        |  "status": "completed",
        |  "subject": "{{ Observation.subject }}",
        |  "sent": "{{ now() }}",
        |  "recipient": {
        |    "{{#member}}": "{{ %careTeam.participant.member }}",
        |    "{{*}}": {
        |      "reference": "{{ %member.reference }}",
        |      "display": "{{? %member.display }}"
        |    }
        |  },
        |  "payload": [
        |    {
        |      "contentString": "Patient {{ Observation.subject.display }} has a potassium value of {{ Observation.valueQuantity.value }} mmol/L."
        |    },
        |    {
        |      "contentReference": { "reference": "Observation/{{ Observation.id }}" }
        |    }
        |  ]
        |}""".stripMargin

    "render the documented Communication" in {
      implicit val executionContext: ExecutionContext = ExecutionContext.global
      val handler = new FhirTemplateExpressionHandler(isSourceContentFhir = true)
      val template = FhirExpression("notify-care-team", handler.languageSupported, value = Some(parse(communicationTemplate)))

      val communication =
        Await.result(handler.evaluateExpression(template, Map("careTeam" -> careTeam), observationEvent), 5.seconds)

      val evaluator = FhirPathEvaluator()
      //The whole subject element is copied from the event
      communication \ "subject" mustEqual observationEvent \ "subject"
      //One recipient per care team member, with the optional display of the second member omitted
      evaluator.evaluateString("recipient.reference", communication) mustEqual
        Seq("Practitioner/pr1", "Patient/example")
      evaluator.evaluateString("recipient.display", communication) mustEqual Seq("Dorothy Dietition")
      //Placeholders inside strings are rendered as text
      evaluator.evaluateString("payload.contentString", communication) mustEqual
        Seq("Patient P. van de Heuvel has a potassium value of 6.3 mmol/L.")
      evaluator.evaluateString("payload.contentReference.reference", communication) mustEqual
        Seq("Observation/obs-00001")
      //The evaluation time is rendered as a FHIR instant
      evaluator.evaluateDateTime("sent", communication) must haveSize(1)
    }
  }
}

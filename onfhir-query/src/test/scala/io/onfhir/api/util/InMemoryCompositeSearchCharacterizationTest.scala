package io.onfhir.api.util

import io.onfhir.api.{FHIR_DATA_TYPES, FHIR_PARAMETER_CATEGORIES, FHIR_PARAMETER_TYPES}
import io.onfhir.api.model.Parameter
import io.onfhir.config.{FhirEndpointSettings, SearchParameterConf}
import io.onfhir.util.JsonFormatter._
import org.json4s.JValue
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Characterization of composite search parameter evaluation in
 * `ImMemorySearchUtil.handleCompositeParameter`.
 *
 * Two conf shapes appear here on purpose:
 *
 *  - The *real* shape produced by `SearchParameterConfigurator` for a composite:
 *    `paths` holds the base context of the composite's own expression (for
 *    `Observation` that is a single empty path meaning "the resource itself")
 *    with target type `Resource`, and `targets` holds the component parameter
 *    names. The component element paths live only in the component confs.
 *    Verified against the R4 standard package: `code-value-quantity` resolves to
 *    `paths=[""] targetTypes=[Resource] targets=[code, value-quantity]`.
 *
 *  - A *component-path* shape, where the composite's own `paths`/`targetTypes`
 *    are the component element paths. The configurator never emits this, but it
 *    is the shape hand-built fixtures have used.
 *
 * Outcomes are captured as strings so that a thrown exception is pinned just as
 * precisely as a boolean result.
 */
@RunWith(classOf[JUnitRunner])
class InMemoryCompositeSearchCharacterizationTest extends Specification {
  private val endpointSettings = FhirEndpointSettings("http://localhost:8080/fhir")

  /** Target type the configurator emits for a composite's base context. */
  private val RESOURCE_TARGET_TYPE = "Resource"

  private def conf(
      name: String,
      parameterType: String,
      paths: Seq[String],
      targetTypes: Seq[String],
      targets: Seq[String] = Nil): SearchParameterConf =
    SearchParameterConf(
      url = s"http://example.org/SearchParameter/$name",
      pname = name,
      ptype = parameterType,
      paths = paths,
      targets = targets,
      targetTypes = targetTypes,
      restrictions = paths.map(_ => Nil)
    )

  //Component confs, shaped as the R4 configurator produces them
  private val codeConf = conf("code", FHIR_PARAMETER_TYPES.TOKEN, Seq("code"), Seq(FHIR_DATA_TYPES.CODEABLE_CONCEPT))
  private val valueConceptConf =
    conf("value-concept", FHIR_PARAMETER_TYPES.TOKEN, Seq("valueCodeableConcept"), Seq(FHIR_DATA_TYPES.CODEABLE_CONCEPT))
  private val valueQuantityConf =
    conf(
      "value-quantity",
      FHIR_PARAMETER_TYPES.QUANTITY,
      Seq("valueQuantity", "valueSampledData"),
      Seq(FHIR_DATA_TYPES.QUANTITY, FHIR_DATA_TYPES.SAMPLED_DATA))

  //Real shapes of the CodeSystem context-type-value family, whose base context is
  //a nested element while the component paths stay absolute from the resource root
  private val contextTypeConf =
    conf("context-type", FHIR_PARAMETER_TYPES.TOKEN, Seq("useContext[i].code"), Seq(FHIR_DATA_TYPES.CODING))
  private val contextConf =
    conf("context", FHIR_PARAMETER_TYPES.TOKEN, Seq("useContext[i].valueCodeableConcept"), Seq(FHIR_DATA_TYPES.CODEABLE_CONCEPT))

  private val componentConfs =
    Seq(codeConf, valueConceptConf, valueQuantityConf, contextTypeConf, contextConf)
      .map(c => c.pname -> c).toMap

  //Composites in the real shape: base context only, components named in targets
  private val codeValueConcept =
    conf("code-value-concept", FHIR_PARAMETER_TYPES.COMPOSITE, Seq(""), Seq(RESOURCE_TARGET_TYPE), Seq("code", "value-concept"))
  private val codeValueQuantity =
    conf("code-value-quantity", FHIR_PARAMETER_TYPES.COMPOSITE, Seq(""), Seq(RESOURCE_TARGET_TYPE), Seq("code", "value-quantity"))

  //The same composites in the component-path shape used by hand-built fixtures
  private val codeValueConceptComponentPaths =
    conf(
      "code-value-concept",
      FHIR_PARAMETER_TYPES.COMPOSITE,
      Seq("code", "valueCodeableConcept"),
      Seq(FHIR_DATA_TYPES.CODEABLE_CONCEPT, FHIR_DATA_TYPES.CODEABLE_CONCEPT),
      Seq("code", "value-concept"))
  private val codeValueQuantityComponentPaths =
    conf(
      "code-value-quantity",
      FHIR_PARAMETER_TYPES.COMPOSITE,
      Seq("code", "valueQuantity"),
      Seq(FHIR_DATA_TYPES.CODEABLE_CONCEPT, FHIR_DATA_TYPES.QUANTITY),
      Seq("code", "value-quantity"))

  private val conceptObservation: JValue =
    """{
      |  "resourceType": "Observation",
      |  "code": {"coding": [{"system": "http://loinc.org", "code": "85354-9"}]},
      |  "valueCodeableConcept": {"coding": [{"system": "http://snomed.info/sct", "code": "260385009"}]}
      |}""".stripMargin.parseJson

  private val quantityObservation: JValue =
    """{
      |  "resourceType": "Observation",
      |  "code": {"coding": [{"system": "http://loinc.org", "code": "85354-9"}]},
      |  "valueQuantity": {"value": 5.5, "system": "http://unitsofmeasure.org", "code": "mg"}
      |}""".stripMargin.parseJson

  private val LOINC_CODE = "http://loinc.org|85354-9"
  private val SNOMED_CODE = "http://snomed.info/sct|260385009"
  private val MG_QUANTITY = "5.5|http://unitsofmeasure.org|mg"

  /** Evaluate a composite statement, pinning either the result or the failure. */
  private def outcome(resource: JValue, composite: SearchParameterConf, value: String): String =
    try {
      val parameter =
        Parameter(FHIR_PARAMETER_CATEGORIES.NORMAL, composite.ptype, composite.pname, Seq("" -> value))
      val values = ImMemorySearchUtil.extractValuesAndTargetTypes(composite, resource)
      s"returned ${ImMemorySearchUtil.handleCompositeParameter(parameter, composite, values, componentConfs, endpointSettings)}"
    } catch {
      case t: Throwable => s"${t.getClass.getSimpleName}: ${t.getMessage}"
    }

  "handleCompositeParameter on configurator-shaped composites" should {
    "evaluate a token+token composite whose components both match" in {
      outcome(conceptObservation, codeValueConcept, s"$LOINC_CODE$$$SNOMED_CODE") mustEqual "returned true"
    }

    //Each component is evaluated through its own configuration, so the code
    //component cannot be satisfied by the value element or vice versa
    "reject a token+token composite whose components are swapped" in {
      outcome(conceptObservation, codeValueConcept, s"$SNOMED_CODE$$$LOINC_CODE") mustEqual "returned false"
    }

    "reject a token+token composite whose value component does not match" in {
      outcome(conceptObservation, codeValueConcept, s"$LOINC_CODE$$http://snomed.info/sct|999") mustEqual
        "returned false"
    }

    "evaluate a token+quantity composite whose components both match" in {
      outcome(quantityObservation, codeValueQuantity, s"$LOINC_CODE$$$MG_QUANTITY") mustEqual "returned true"
    }

    "reject a token+quantity composite whose quantity component does not match" in {
      outcome(quantityObservation, codeValueQuantity, s"$LOINC_CODE$$9.9|http://unitsofmeasure.org|mg") mustEqual
        "returned false"
    }

    "reject a token+quantity composite whose code component does not match" in {
      outcome(quantityObservation, codeValueQuantity, s"http://loinc.org|999$$$MG_QUANTITY") mustEqual
        "returned false"
    }
  }

  //The component-path shape makes each component element a base context of its
  //own, and a component's path does not resolve inside a sibling's element
  "handleCompositeParameter on component-path shaped composites" should {
    "no longer match a token+token composite in the defined component order" in {
      outcome(conceptObservation, codeValueConceptComponentPaths, s"$LOINC_CODE$$$SNOMED_CODE") mustEqual
        "returned false"
    }

    "reject a token+token composite whose components are swapped" in {
      outcome(conceptObservation, codeValueConceptComponentPaths, s"$SNOMED_CODE$$$LOINC_CODE") mustEqual
        "returned false"
    }

    "reject a token+token composite whose value component matches no element" in {
      outcome(conceptObservation, codeValueConceptComponentPaths, s"$LOINC_CODE$$http://snomed.info/sct|999") mustEqual
        "returned false"
    }

    "no longer fail a token+quantity composite when the code matches" in {
      outcome(quantityObservation, codeValueQuantityComponentPaths, s"$LOINC_CODE$$$MG_QUANTITY") mustEqual
        "returned false"
    }

    "no longer fail a token+quantity composite when the code does not match" in {
      outcome(quantityObservation, codeValueQuantityComponentPaths, s"http://loinc.org|999$$$MG_QUANTITY") mustEqual
        "returned false"
    }
  }

  //A component's paths are absolute from the resource root, so they are made
  //relative to the base context they are evaluated against.
  "handleCompositeParameter on a composite whose base context is a nested element" should {
    val codeSystem =
      """{
        |  "resourceType": "CodeSystem",
        |  "useContext": [{
        |    "code": {"system": "http://terminology.hl7.org/CodeSystem/usage-context-type", "code": "focus"},
        |    "valueCodeableConcept": {"coding": [{"system": "http://snomed.info/sct", "code": "260385009"}]}
        |  }]
        |}""".stripMargin.parseJson

    //Two usage contexts, neither of which satisfies both components on its own
    val splitCodeSystem =
      """{
        |  "resourceType": "CodeSystem",
        |  "useContext": [
        |    {
        |      "code": {"system": "http://terminology.hl7.org/CodeSystem/usage-context-type", "code": "focus"},
        |      "valueCodeableConcept": {"coding": [{"system": "http://snomed.info/sct", "code": "999"}]}
        |    },
        |    {
        |      "code": {"system": "http://terminology.hl7.org/CodeSystem/usage-context-type", "code": "other"},
        |      "valueCodeableConcept": {"coding": [{"system": "http://snomed.info/sct", "code": "260385009"}]}
        |    }
        |  ]
        |}""".stripMargin.parseJson

    val contextTypeValue =
      conf(
        "context-type-value",
        FHIR_PARAMETER_TYPES.COMPOSITE,
        Seq("useContext[i]"),
        Seq("UsageContext"),
        Seq("context-type", "context"))

    val FOCUS = "http://terminology.hl7.org/CodeSystem/usage-context-type|focus"

    "match when both components are satisfied by the same element" in {
      outcome(codeSystem, contextTypeValue, s"$FOCUS$$$SNOMED_CODE") mustEqual "returned true"
    }

    "reject a statement whose value component does not match" in {
      outcome(codeSystem, contextTypeValue, s"$FOCUS$$http://snomed.info/sct|999") mustEqual "returned false"
    }

    "reject a statement whose components are swapped" in {
      outcome(codeSystem, contextTypeValue, s"$SNOMED_CODE$$$FOCUS") mustEqual "returned false"
    }

    //The correlation requirement: each component matches, but in different elements
    "reject a statement satisfied only across two different elements" in {
      outcome(splitCodeSystem, contextTypeValue, s"$FOCUS$$$SNOMED_CODE") mustEqual "returned false"
    }
  }

  //A composite declaring both the resource root and a repeating element as base
  //contexts, as Observation combo-code-value-quantity does
  "handleCompositeParameter on a composite with several base contexts" should {
    val comboCodeConf =
      conf("combo-code", FHIR_PARAMETER_TYPES.TOKEN, Seq("code", "component.code"), Seq(FHIR_DATA_TYPES.CODEABLE_CONCEPT, FHIR_DATA_TYPES.CODEABLE_CONCEPT))
    val comboValueQuantityConf =
      conf("combo-value-quantity", FHIR_PARAMETER_TYPES.QUANTITY, Seq("valueQuantity", "component.valueQuantity"), Seq(FHIR_DATA_TYPES.QUANTITY, FHIR_DATA_TYPES.QUANTITY))

    val comboConfs = Seq(comboCodeConf, comboValueQuantityConf).map(c => c.pname -> c).toMap

    val comboComposite =
      conf(
        "combo-code-value-quantity",
        FHIR_PARAMETER_TYPES.COMPOSITE,
        Seq("", "component"),
        Seq(RESOURCE_TARGET_TYPE, "BackboneElement"),
        Seq("combo-code", "combo-value-quantity"))

    //One component carries the code, a different one carries the quantity
    val componentObservation =
      """{
        |  "resourceType": "Observation",
        |  "component": [
        |    {
        |      "code": {"coding": [{"system": "http://loinc.org", "code": "85354-9"}]},
        |      "valueQuantity": {"value": 9.9, "system": "http://unitsofmeasure.org", "code": "mg"}
        |    },
        |    {
        |      "code": {"coding": [{"system": "http://loinc.org", "code": "8480-6"}]},
        |      "valueQuantity": {"value": 5.5, "system": "http://unitsofmeasure.org", "code": "mg"}
        |    }
        |  ]
        |}""".stripMargin.parseJson

    def comboOutcome(resource: JValue, value: String): String =
      try {
        val parameter =
          Parameter(FHIR_PARAMETER_CATEGORIES.NORMAL, comboComposite.ptype, comboComposite.pname, Seq("" -> value))
        val values = ImMemorySearchUtil.extractValuesAndTargetTypes(comboComposite, resource)
        s"returned ${ImMemorySearchUtil.handleCompositeParameter(parameter, comboComposite, values, comboConfs, endpointSettings)}"
      } catch {
        case t: Throwable => s"${t.getClass.getSimpleName}: ${t.getMessage}"
      }

    "match through the resource root base context" in {
      comboOutcome(quantityObservation, s"$LOINC_CODE$$$MG_QUANTITY") mustEqual "returned true"
    }

    "match through the repeating element base context" in {
      comboOutcome(componentObservation, s"http://loinc.org|8480-6$$$MG_QUANTITY") mustEqual "returned true"
    }

    //The root base context must not pair a code from one component with a
    //quantity from another
    "reject a statement satisfied only across two different components" in {
      comboOutcome(componentObservation, s"$LOINC_CODE$$$MG_QUANTITY") mustEqual "returned false"
    }
  }

  "handleCompositeParameter" should {
    "ignore components that have no corresponding value part" in {
      outcome(conceptObservation, codeValueConcept, LOINC_CODE) mustEqual "returned true"
    }

    "reject a statement whose only value part matches no component element" in {
      outcome(conceptObservation, codeValueConcept, "http://loinc.org|999") mustEqual "returned false"
    }

    "reject a statement when the composite's base context resolves to nothing" in {
      val noBaseContext =
        conf("code-value-concept", FHIR_PARAMETER_TYPES.COMPOSITE, Seq("absent"), Seq(RESOURCE_TARGET_TYPE), Seq("code", "value-concept"))

      outcome(conceptObservation, noBaseContext, s"$LOINC_CODE$$$SNOMED_CODE") mustEqual "returned false"
    }
  }
}

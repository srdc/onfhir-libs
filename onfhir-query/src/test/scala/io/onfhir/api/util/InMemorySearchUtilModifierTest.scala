package io.onfhir.api.util

import io.onfhir.api.{FHIR_DATA_TYPES, FHIR_PARAMETER_CATEGORIES, FHIR_PARAMETER_TYPES, FHIR_PREFIXES_MODIFIERS}
import io.onfhir.api.model.Parameter
import io.onfhir.config.{FhirEndpointSettings, SearchParameterConf}
import io.onfhir.exception.{InvalidParameterException, UnsupportedParameterException}
import org.json4s.JValue
import org.json4s.jackson.JsonMethods.parse
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class InMemorySearchUtilModifierTest extends Specification {
  sequential

  private val endpointSettings = FhirEndpointSettings("https://example.org/fhir")

  private val patient = parse(
    """{
      |  "resourceType": "Patient",
      |  "maritalStatus": {
      |    "coding": [{
      |      "system": "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus",
      |      "code": "M",
      |      "display": "Married"
      |    }],
      |    "text": "Married"
      |  },
      |  "identifier": [{
      |    "type": {
      |      "coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0203", "code": "MR"}],
      |      "text": "Medical record number"
      |    },
      |    "system": "urn:mrn",
      |    "value": "12345"
      |  }],
      |  "managingOrganization": {
      |    "reference": "Organization/42",
      |    "identifier": {"system": "urn:org", "value": "ABC"}
      |  },
      |  "website": "https://example.org/fhir/ValueSet/child",
      |  "profileCanonical": "http://example.org/StructureDefinition/base|1.2.3",
      |  "weight": {
      |    "value": 72.5,
      |    "system": "http://unitsofmeasure.org",
      |    "code": "kg",
      |    "unit": "kg"
      |  },
      |  "activePeriod": {"start": "2020-01-01", "end": "2020-12-31"}
      |}""".stripMargin
  )

  private def searchParameter(
      name: String,
      parameterType: String,
      path: String,
      targetType: String,
      targets: Seq[String] = Nil): SearchParameterConf =
    SearchParameterConf(
      url = s"http://example.org/SearchParameter/$name",
      pname = name,
      ptype = parameterType,
      paths = Seq(path),
      targets = targets,
      targetTypes = Seq(targetType),
      restrictions = Seq(Nil)
    )

  private def parameter(
      config: SearchParameterConf,
      value: String,
      prefix: String = "",
      suffix: String = ""): Parameter =
    Parameter(
      paramCategory = FHIR_PARAMETER_CATEGORIES.NORMAL,
      paramType = config.ptype,
      name = config.pname,
      valuePrefixList = Seq(prefix -> value),
      suffix = suffix
    )

  private def matches(
      resource: JValue,
      config: SearchParameterConf,
      value: String,
      prefix: String = "",
      suffix: String = ""): Boolean = {
    val values = ImMemorySearchUtil.extractValuesAndTargetTypes(config, resource)
    ImMemorySearchUtil.handleSimpleParameter(
      parameter(config, value, prefix, suffix),
      config,
      values,
      endpointSettings
    )
  }

  "ImMemorySearchUtil modifier handling" should {
    "match token text and invert token matches with not" in {
      val config = searchParameter(
        "marital-status",
        FHIR_PARAMETER_TYPES.TOKEN,
        "maritalStatus",
        FHIR_DATA_TYPES.CODEABLE_CONCEPT
      )

      matches(patient, config, "mar", suffix = FHIR_PREFIXES_MODIFIERS.TEXT) must beTrue
      matches(
        patient,
        config,
        "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus|M",
        suffix = FHIR_PREFIXES_MODIFIERS.NOT
      ) must beFalse
      matches(
        patient,
        config,
        "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus|S",
        suffix = FHIR_PREFIXES_MODIFIERS.NOT
      ) must beTrue
    }

    "match an Identifier with the of-type modifier" in {
      val config = searchParameter(
        "identifier",
        FHIR_PARAMETER_TYPES.TOKEN,
        "identifier",
        FHIR_DATA_TYPES.IDENTIFIER
      )

      matches(
        patient,
        config,
        "http://terminology.hl7.org/CodeSystem/v2-0203|MR|12345",
        suffix = FHIR_PREFIXES_MODIFIERS.OF_TYPE
      ) must beTrue
      matches(
        patient,
        config,
        "http://terminology.hl7.org/CodeSystem/v2-0203|SS|12345",
        suffix = FHIR_PREFIXES_MODIFIERS.OF_TYPE
      ) must beFalse
    }

    "reject terminology expansion modifiers that need an external service" in {
      val config = searchParameter(
        "marital-status",
        FHIR_PARAMETER_TYPES.TOKEN,
        "maritalStatus",
        FHIR_DATA_TYPES.CODEABLE_CONCEPT
      )

      matches(
        patient,
        config,
        "http://example.org/ValueSet/marital-status",
        suffix = FHIR_PREFIXES_MODIFIERS.IN
      ) must throwA[UnsupportedParameterException]
    }

    "match reference type, identifier, and not modifiers" in {
      val config = searchParameter(
        "organization",
        FHIR_PARAMETER_TYPES.REFERENCE,
        "managingOrganization",
        FHIR_DATA_TYPES.REFERENCE,
        targets = Seq("Organization")
      )

      matches(
        patient,
        config,
        "Organization",
        suffix = FHIR_PREFIXES_MODIFIERS.TYPE
      ) must beTrue
      matches(
        patient,
        config,
        "urn:org|ABC",
        suffix = FHIR_PREFIXES_MODIFIERS.IDENTIFIER
      ) must beTrue
      matches(
        patient,
        config,
        "Organization/42",
        suffix = FHIR_PREFIXES_MODIFIERS.NOT
      ) must beFalse
      matches(
        patient,
        config,
        "Organization/43",
        suffix = FHIR_PREFIXES_MODIFIERS.NOT
      ) must beTrue
    }

    "match URI hierarchy and not modifiers" in {
      val config = searchParameter(
        "website",
        FHIR_PARAMETER_TYPES.URI,
        "website",
        FHIR_DATA_TYPES.URI
      )

      matches(patient, config, "https://example.org/fhir/ValueSet/child") must beTrue
      matches(
        patient,
        config,
        "https://example.org/fhir/ValueSet",
        suffix = FHIR_PREFIXES_MODIFIERS.BELOW
      ) must beTrue
      matches(
        patient,
        config,
        "https://example.org/fhir/ValueSet/other",
        suffix = FHIR_PREFIXES_MODIFIERS.NOT
      ) must beTrue
    }

    "match canonical versions with the below modifier" in {
      val config = searchParameter(
        "profile",
        FHIR_PARAMETER_TYPES.REFERENCE,
        "profileCanonical",
        FHIR_DATA_TYPES.CANONICAL
      )

      matches(
        patient,
        config,
        "http://example.org/StructureDefinition/base|1.2",
        suffix = FHIR_PREFIXES_MODIFIERS.BELOW
      ) must beTrue
      matches(
        patient,
        config,
        "http://example.org/StructureDefinition/base|2.0",
        suffix = FHIR_PREFIXES_MODIFIERS.BELOW
      ) must beFalse
    }

    "match quantity values together with system and code" in {
      val config = searchParameter(
        "weight",
        FHIR_PARAMETER_TYPES.QUANTITY,
        "weight",
        FHIR_DATA_TYPES.QUANTITY
      )

      matches(
        patient,
        config,
        "72.5|http://unitsofmeasure.org|kg"
      ) must beTrue
      matches(
        patient,
        config,
        "72.5|http://unitsofmeasure.org|g"
      ) must beFalse
      matches(
        patient,
        config,
        "70|http://unitsofmeasure.org|kg",
        prefix = FHIR_PREFIXES_MODIFIERS.GREATER_THAN
      ) must beTrue
    }

    "match Period targets against implicit date ranges" in {
      val config = searchParameter(
        "active-period",
        FHIR_PARAMETER_TYPES.DATE,
        "activePeriod",
        FHIR_DATA_TYPES.PERIOD
      )

      matches(
        patient,
        config,
        "2020",
        prefix = FHIR_PREFIXES_MODIFIERS.EQUAL
      ) must beTrue
      matches(
        patient,
        config,
        "2021",
        prefix = FHIR_PREFIXES_MODIFIERS.EQUAL
      ) must beFalse
    }

    "reject invalid missing modifier values" in {
      val config = searchParameter(
        "website",
        FHIR_PARAMETER_TYPES.URI,
        "website",
        FHIR_DATA_TYPES.URI
      )

      matches(
        patient,
        config,
        "yes",
        suffix = FHIR_PREFIXES_MODIFIERS.MISSING
      ) must throwA[InvalidParameterException]
    }
  }
}

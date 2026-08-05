package io.onfhir.validation

import org.json4s.JsonAST.{JBool, JDecimal, JInt, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class PrimitiveValidationTest extends Specification {
  "FhirContentValidator.validatePrimitive" should {
    "accept representative FHIR primitive values" in {
      FhirContentValidator.validatePrimitive(JBool(true), "boolean") must beTrue
      FhirContentValidator.validatePrimitive(JInt(12), "integer") must beTrue
      FhirContentValidator.validatePrimitive(JDecimal(BigDecimal("12.50")), "decimal") must beTrue
      FhirContentValidator.validatePrimitive(JString("active"), "code") must beTrue
      FhirContentValidator.validatePrimitive(JString("2026-08-04"), "date") must beTrue
      FhirContentValidator.validatePrimitive(JString("2026-08-04T12:30:00Z"), "dateTime") must beTrue
      FhirContentValidator.validatePrimitive(JString("12:30:00"), "time") must beTrue
      FhirContentValidator.validatePrimitive(JString("https://example.org/value"), "uri") must beTrue
      FhirContentValidator.validatePrimitive(JString("patient-1"), "id") must beTrue
    }

    "reject values with an incompatible JSON representation or lexical form" in {
      FhirContentValidator.validatePrimitive(JString("true"), "boolean") must beFalse
      FhirContentValidator.validatePrimitive(JString("12.5"), "integer") must beFalse
      FhirContentValidator.validatePrimitive(JString("not-a-decimal"), "decimal") must beFalse
      FhirContentValidator.validatePrimitive(JString(""), "code") must beFalse
      FhirContentValidator.validatePrimitive(JString("2026-13-99"), "date") must beFalse
      FhirContentValidator.validatePrimitive(JString("not-a-time"), "time") must beFalse
      FhirContentValidator.validatePrimitive(JString("contains space"), "id") must beFalse
    }
  }
}

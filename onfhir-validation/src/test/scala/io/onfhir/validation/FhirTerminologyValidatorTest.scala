package io.onfhir.validation

import io.onfhir.api.validation.{ValueSetDef, ValueSetRestrictions}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FhirTerminologyValidatorTest extends Specification {
  import ValidationTestFixtures._

  private val systemUrl = "http://example.org/CodeSystem/test"
  private val importedValueSetUrl = "http://example.org/ValueSet/imported"
  private val valueSetUrl = "http://example.org/ValueSet/test"
  private val valueSets = Map(
    importedValueSetUrl -> Map("1" -> ValueSetRestrictions(ValueSetDef(Map(systemUrl -> Set("imported"))))),
    valueSetUrl -> Map("1" -> ValueSetRestrictions(
      includes = ValueSetDef(Map(systemUrl -> Set("allowed")), Set(importedValueSetUrl)),
      excludes = Some(ValueSetDef(Map(systemUrl -> Set("excluded"))))
    ))
  )
  private val validator = FhirTerminologyValidator(config(Seq(profile()), valueSets = valueSets), Nil)

  "FhirTerminologyValidator" should {
    "resolve local codes, imported value sets, and exclusions" in {
      validator.isValueSetSupported(valueSetUrl) must beTrue
      validator.getAllCodes(valueSetUrl)(systemUrl) mustEqual Set("allowed", "imported")
      validator.validateCodeAgainstValueSet(valueSetUrl, Some("1"), Some(systemUrl), "allowed") must beTrue
      validator.validateCodeAgainstValueSet(valueSetUrl, Some("1"), Some(systemUrl), "imported") must beTrue
      validator.validateCodeAgainstValueSet(valueSetUrl, Some("1"), Some(systemUrl), "excluded") must beFalse
      validator.validateCodeAgainstValueSet(valueSetUrl, Some("1"), Some(systemUrl), "unknown") must beFalse
    }

    "leave unknown value sets to an external terminology policy" in {
      validator.isValueSetSupported("http://example.org/ValueSet/unknown") must beFalse
      validator.validateCodeAgainstValueSet("http://example.org/ValueSet/unknown", None, Some(systemUrl), "any-code") must beTrue
    }
  }
}

package io.onfhir.r4

import io.onfhir.config.{FHIRSearchParameter, FhirServerConfig, SearchParameterConf, SearchParameterConfigurator}
import io.onfhir.r4.parsers.R4Parser
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Integration suite: parse the R4 SearchParameter bundle with `R4Parser` and
 * turn the definitions into `SearchParameterConf` through
 * `SearchParameterConfigurator`, using the profiles parsed from the real
 * standard package.
 *
 * This is the only coverage of `R4Parser.parseSearchParameter` and of the
 * configurator's path/type resolution against genuine R4 profiles, where
 * choice elements have to be expanded and reference targets resolved.
 *
 * `SearchParameterConfigurator` requires a `FhirServerConfig`. That type lives
 * in onfhir-common and is a plain holder, so the suite populates one from the
 * parsed `BaseFhirConfig`; no server runtime is involved.
 */
@RunWith(classOf[JUnitRunner])
class R4SearchParameterConfiguratorTest extends Specification {
  import R4IntegrationFixtures._

  private lazy val parser =
    new R4Parser(fhirConfig.FHIR_COMPLEX_TYPES, fhirConfig.FHIR_PRIMITIVE_TYPES)

  /** All SearchParameter definitions of the standard package. */
  private lazy val allSearchParameters: Seq[FHIRSearchParameter] =
    configReader
      .readStandardBundleFile("search-parameters.json", Set("SearchParameter"))
      .map(parser.parseSearchParameter)

  private lazy val observationSearchParameters: Seq[FHIRSearchParameter] =
    allSearchParameters.filter(_.base.contains("Observation"))

  /** A FhirServerConfig carrying the profiles parsed from the standard package. */
  private lazy val serverConfig: FhirServerConfig = {
    val config = new FhirServerConfig("R4")
    config.fhirVersion = "4.0.1"
    config.profileRestrictions = fhirConfig.profileRestrictions
    config.valueSetRestrictions = fhirConfig.valueSetRestrictions
    config.FHIR_RESOURCE_TYPES = fhirConfig.FHIR_RESOURCE_TYPES
    config.FHIR_COMPLEX_TYPES = fhirConfig.FHIR_COMPLEX_TYPES
    config.FHIR_PRIMITIVE_TYPES = fhirConfig.FHIR_PRIMITIVE_TYPES
    config
  }

  private lazy val observationConfigurator =
    new SearchParameterConfigurator(
      rtype = "Observation",
      rtypeBaseProfile = None,
      fhirConfig = serverConfig,
      allSearchParameters = observationSearchParameters.map(sp => sp.url -> sp.name).toMap)

  private def confFor(name: String): Option[SearchParameterConf] =
    observationSearchParameters.find(_.name == name).flatMap(observationConfigurator.createSearchParameterConf)

  "R4Parser" should {

    "parse every SearchParameter of the standard package" in {
      // FHIR 4.0.1 ships 1375 SearchParameter definitions in search-parameters.json.
      allSearchParameters must haveSize(1375)
      allSearchParameters.map(_.name).distinct must not(beEmpty)
      allSearchParameters.forall(_.url.nonEmpty) must beTrue
      allSearchParameters.forall(_.base.nonEmpty) must beTrue

      allSearchParameters.map(_.ptype).toSet mustEqual Set(
        "composite", "date", "number", "quantity", "reference", "special", "string", "token", "uri")
    }

    "parse an individual SearchParameter definition with its multi-resource base" in {
      val birthdate = allSearchParameters.find(_.url == "http://hl7.org/fhir/SearchParameter/individual-birthdate")
      birthdate must beSome

      birthdate.get.name mustEqual "birthdate"
      birthdate.get.ptype mustEqual "date"
      birthdate.get.base mustEqual Set("Patient", "Person", "RelatedPerson")
      birthdate.get.expression must beSome("Patient.birthDate | Person.birthDate | RelatedPerson.birthDate")
    }

    "classify _text as a special parameter even though R4 does not" in {
      // R4Parser deliberately overrides the declared type of _text, because the
      // R4 specification does not mark it as 'special'.
      val text = allSearchParameters.find(_.url == "http://hl7.org/fhir/SearchParameter/DomainResource-text")
      text must beSome

      text.get.name mustEqual "_text"
      text.get.ptype mustEqual "special"
    }
  }

  "SearchParameterConfigurator over the parsed R4 profiles" should {

    "configure every search parameter defined for Observation" in {
      observationSearchParameters must haveSize(38)

      val configured = observationSearchParameters.flatMap(observationConfigurator.createSearchParameterConf)
      configured must haveSize(38)
      // Composite parameters carry their component names instead of paths.
      configured.forall(conf => conf.paths.nonEmpty || conf.ptype == "composite") must beTrue
    }

    "resolve a token parameter to its element path and data type" in {
      val code = confFor("code")
      code must beSome

      code.get.ptype mustEqual "token"
      code.get.paths mustEqual Seq("code")
      code.get.targetTypes mustEqual Seq("CodeableConcept")
    }

    "expand a choice element into one path per alternative type" in {
      // Observation.effective[x] has four alternatives, and the configurator
      // must produce a path and a matching target type for each.
      val date = confFor("date")
      date must beSome

      date.get.ptype mustEqual "date"
      date.get.paths mustEqual Seq("effectiveDateTime", "effectivePeriod", "effectiveTiming", "effectiveInstant")
      date.get.targetTypes mustEqual Seq("dateTime", "Period", "Timing", "instant")
    }

    "expand a quantity parameter over the choice alternatives it applies to" in {
      val valueQuantity = confFor("value-quantity")
      valueQuantity must beSome

      valueQuantity.get.ptype mustEqual "quantity"
      valueQuantity.get.paths mustEqual Seq("valueQuantity", "valueSampledData")
      valueQuantity.get.targetTypes mustEqual Seq("Quantity", "SampledData")
    }

    "resolve reference targets from the profile's target profiles" in {
      val subject = confFor("subject")
      subject must beSome

      subject.get.ptype mustEqual "reference"
      subject.get.paths mustEqual Seq("subject")
      subject.get.targetTypes mustEqual Seq("Reference")
      subject.get.targets.toSet mustEqual Set("Patient", "Group", "Device", "Location")

      // 'patient' points at the same element but is restricted to the target
      // types its own definition declares.
      val patient = confFor("patient")
      patient must beSome
      patient.get.paths mustEqual Seq("subject")
      patient.get.targets.toSet mustEqual Set("Patient", "Group")
    }

    "resolve a composite parameter to the names of its component parameters" in {
      val codeValueQuantity = confFor("code-value-quantity")
      codeValueQuantity must beSome

      codeValueQuantity.get.ptype mustEqual "composite"
      codeValueQuantity.get.targets.toSet mustEqual Set("code", "value-quantity")
    }
  }
}

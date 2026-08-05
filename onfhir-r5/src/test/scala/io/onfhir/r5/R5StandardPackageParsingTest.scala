package io.onfhir.r5

import io.onfhir.api.validation.{ConstraintKeys, ElementRestrictions, ProfileRestrictions}
import io.onfhir.config.{FHIRSearchParameter, FhirServerConfig, SearchParameterConfigurator}
import io.onfhir.r5.parsers.R5Parser
import io.onfhir.validation.{CardinalityMinRestriction, CodeBindingRestriction, ConstraintsRestriction, ReferenceRestrictions, TypeRestriction}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Integration suite: parse the real FHIR R5 5.0.0 standard definitions package
 * with [[R5Parser]], which currently inherits the compatible implementation
 * from R4 while owning R5 defaults and future R5-specific behavior.
 *
 * Counts are pinned to FHIR 5.0.0 as packaged by `onfhir-definitions-r5`,
 * for the same reason the R4 suite pins its counts: the artifact is
 * byte-pinned, so a count change means the package or a parser changed.
 */
@RunWith(classOf[JUnitRunner])
class R5StandardPackageParsingTest extends Specification {
  import R5IntegrationFixtures._

  private val observationUrl = "http://hl7.org/fhir/StructureDefinition/Observation"
  private val observationStatusValueSet = "http://hl7.org/fhir/ValueSet/observation-status"
  private val observationCategoryValueSet = "http://hl7.org/fhir/ValueSet/observation-category"
  private val administrativeGenderValueSet = "http://hl7.org/fhir/ValueSet/administrative-gender"

  private def element(profile: ProfileRestrictions, path: String): Option[ElementRestrictions] =
    profile.elementRestrictions.find(_._1 == path).map(_._2)

  "The parsed R5 standard package" should {

    "match the R5 parser defaults to the official definition package" in {
      R5Parser.DefaultPrimitiveTypes mustEqual fhirConfig.FHIR_PRIMITIVE_TYPES
      R5Parser.DefaultComplexTypes mustEqual fhirConfig.FHIR_COMPLEX_TYPES
    }

    "derive the R5 type universes, including the types new in R5" in {
      fhirConfig.FHIR_RESOURCE_TYPES must contain(allOf("Patient", "Observation", "Bundle",
        // resource types that did not exist in R4
        "NutritionProduct", "InventoryItem", "TestPlan"))
      fhirConfig.FHIR_COMPLEX_TYPES must contain(allOf("CodeableConcept", "Quantity", "Reference",
        // complex types that did not exist in R4
        "CodeableReference", "RatioRange", "Availability", "ExtendedContactDetail"))
      fhirConfig.FHIR_PRIMITIVE_TYPES must contain(allOf("code", "dateTime", "boolean",
        // primitive type new in R5
        "integer64"))

      fhirConfig.FHIR_COMPLEX_TYPES.forall(_.head.isUpper) must beTrue
      fhirConfig.FHIR_PRIMITIVE_TYPES.forall(_.head.isLower) must beTrue
      fhirConfig.FHIR_COMPLEX_TYPES.intersect(fhirConfig.FHIR_PRIMITIVE_TYPES) must beEmpty

      // FHIR 5.0.0 counts (abstract definitions are excluded by the configurator).
      fhirConfig.FHIR_PRIMITIVE_TYPES must haveSize(21)
      fhirConfig.FHIR_COMPLEX_TYPES must haveSize(42)
      fhirConfig.FHIR_RESOURCE_TYPES must haveSize(158)
    }

    "populate profile and value set restrictions from the R5 bundles" in {
      // The fixture narrows VALUESET_AND_CODESYSTEM_BUNDLE_FILES to
      // valuesets.json, mirroring Repofyr's FhirR5Configurator, because the R5
      // core package no longer ships v3-codesystems.json or v2-tables.json.
      // The value set universe is therefore roughly half of R4's 1199.
      fhirConfig.profileRestrictions must haveSize(665)
      fhirConfig.valueSetRestrictions must haveSize(546)

      val resourceTypesWithoutBaseProfile =
        fhirConfig.FHIR_RESOURCE_TYPES.filter(rtype => Option(fhirConfig.getBaseProfile(rtype)).isEmpty)
      resourceTypesWithoutBaseProfile must beEmpty

      fhirConfig.getBaseProfileChain("Patient").map(_.url) must contain(allOf(
        "http://hl7.org/fhir/StructureDefinition/Patient",
        "http://hl7.org/fhir/StructureDefinition/DomainResource",
        "http://hl7.org/fhir/StructureDefinition/Resource"))
    }

    "parse the R5 Observation profile with its widened choice and reference targets" in {
      val observation = fhirConfig.getBaseProfile("Observation")
      observation.url mustEqual observationUrl
      observation.version must beSome("5.0.0")

      val status = element(observation, "status")
      status.flatMap(_.restrictions.get(ConstraintKeys.MIN)) must beSome(CardinalityMinRestriction(1))
      status.flatMap(_.restrictions.get(ConstraintKeys.BINDING)) must beSome(
        CodeBindingRestriction(observationStatusValueSet, Some("5.0.0"), "required"))

      // R5 adds Attachment and Reference(MolecularSequence) to value[x].
      val valueChoiceTypes = element(observation, "value[x]")
        .flatMap(_.restrictions.get(ConstraintKeys.DATATYPE))
        .map(_.asInstanceOf[TypeRestriction].dataTypesAndProfiles.map(_._1))
      valueChoiceTypes must beSome
      valueChoiceTypes.get must haveSize(13)
      valueChoiceTypes.get must contain(allOf("Attachment", "Reference"))
      val valueReferenceTargets = element(observation, "value[x]")
        .flatMap(_.restrictions.get(ConstraintKeys.REFERENCE_TARGET))
        .map(_.asInstanceOf[ReferenceRestrictions].targetProfiles.map(_.split('/').last))
      valueReferenceTargets must beSome
      valueReferenceTargets.get mustEqual Set("MolecularSequence")

      // R5 widens subject from R4's four target types to eleven.
      val subjectTargets = element(observation, "subject")
        .flatMap(_.restrictions.get(ConstraintKeys.REFERENCE_TARGET))
        .map(_.asInstanceOf[ReferenceRestrictions].targetProfiles.map(_.split('/').last))
      subjectTargets must beSome
      subjectTargets.get mustEqual Set(
        "Patient", "Group", "Device", "Location", "Organization", "Procedure",
        "Practitioner", "Medication", "Substance", "BiologicallyDerivedProduct", "NutritionProduct")

      // obs-8 is new in R5.
      val rootConstraintKeys = observation.constraints.collect {
        case constraints: ConstraintsRestriction => constraints.fhirConstraints.map(_.key)
      }
      rootConstraintKeys must beSome
      rootConstraintKeys.get must contain(allOf("obs-6", "obs-7", "obs-8"))
    }

    "parse R5 Patient element restrictions and invariants" in {
      val patient = fhirConfig.getBaseProfile("Patient")

      val contactConstraintKeys = element(patient, "contact")
        .flatMap(_.restrictions.get(ConstraintKeys.CONSTRAINT))
        .map(_.asInstanceOf[ConstraintsRestriction].fhirConstraints.map(_.key))
      contactConstraintKeys must beSome
      contactConstraintKeys.get must contain("pat-1")

      element(patient, "gender")
        .flatMap(_.restrictions.get(ConstraintKeys.BINDING)) must beSome(
        CodeBindingRestriction(administrativeGenderValueSet, Some("5.0.0"), "required"))

      element(patient, "deceased[x]")
        .flatMap(_.restrictions.get(ConstraintKeys.DATATYPE))
        .map(_.asInstanceOf[TypeRestriction].dataTypesAndProfiles.map(_._1)) must beSome(Seq("boolean", "dateTime"))
    }

    "expand extensional R5 ValueSets to their exact code sets" in {
      val gender = fhirConfig.valueSetRestrictions(administrativeGenderValueSet)("5.0.0")
      gender.includes.codes mustEqual Map(
        "http://hl7.org/fhir/administrative-gender" -> Set("male", "female", "other", "unknown"))

      val observationStatus = fhirConfig.valueSetRestrictions(observationStatusValueSet)("5.0.0")
      observationStatus.includes.codes("http://hl7.org/fhir/observation-status") mustEqual Set(
        "registered", "preliminary", "final", "amended",
        "corrected", "cancelled", "entered-in-error", "unknown")
    }

    "not know ValueSets whose content moved to the separate terminology package" in {
      // NOTE: documents current behavior, see plan Findings. In R5 the
      // observation-category ValueSet content comes from the THO terminology
      // package, which the core definitions ZIP does not include, so the
      // parsed configuration cannot know it. Consumers needing it must supply
      // THO content separately (e.g. FSConfigReader codeSystemsPath /
      // valueSetsPath).
      fhirConfig.valueSetRestrictions.get(observationCategoryValueSet) must beNone
    }
  }

  "The base R5 CapabilityStatement parsed with the R5 parser" should {

    "yield a plausible FHIRCapabilityStatement" in {
      capabilityStatement.fhirVersion mustEqual "5.0.0"
      capabilityStatement.restResourceConf must haveSize(157)
      capabilityStatement.formats mustEqual Set("xml", "json")
      // Unlike the R4 base statement, the R5 base statement declares no
      // patchFormat and no search-system interaction.
      capabilityStatement.patchFormats must beEmpty
      capabilityStatement.systemLevelInteractions mustEqual Set("batch", "transaction")
      capabilityStatement.searchParamDefUrls must haveSize(18)
      capabilityStatement.operationDefUrls must haveSize(8)

      val patient = capabilityStatement.restResourceConf.find(_.resource == "Patient")
      patient must beSome
      patient.get.profile must beSome("http://hl7.org/fhir/StructureDefinition/Patient")
      patient.get.searchParams must haveSize(23)
      patient.get.versioning mustEqual "versioned"
    }

    "stay consistent with the R5 definitions package it ships beside" in {
      capabilityStatement.restResourceConf.map(_.resource).toSet
        .diff(fhirConfig.FHIR_RESOURCE_TYPES) must beEmpty
      capabilityStatement.restResourceConf.flatMap(_.profile)
        .filter(url => fhirConfig.findProfile(url).isEmpty) must beEmpty
    }
  }

  "The R5 SearchParameter bundle parsed with the R5 parser" should {

    lazy val parser = new R5Parser(fhirConfig.FHIR_COMPLEX_TYPES, fhirConfig.FHIR_PRIMITIVE_TYPES)

    lazy val allSearchParameters: Seq[FHIRSearchParameter] =
      configReader
        .readStandardBundleFile("search-parameters.json", Set("SearchParameter"))
        .map(parser.parseSearchParameter)

    lazy val observationSearchParameters: Seq[FHIRSearchParameter] =
      allSearchParameters.filter(_.base.contains("Observation"))

    lazy val observationConfigurator: SearchParameterConfigurator = {
      val serverConfig = new FhirServerConfig("R5")
      serverConfig.fhirVersion = "5.0.0"
      serverConfig.profileRestrictions = fhirConfig.profileRestrictions
      serverConfig.valueSetRestrictions = fhirConfig.valueSetRestrictions
      serverConfig.FHIR_RESOURCE_TYPES = fhirConfig.FHIR_RESOURCE_TYPES
      serverConfig.FHIR_COMPLEX_TYPES = fhirConfig.FHIR_COMPLEX_TYPES
      serverConfig.FHIR_PRIMITIVE_TYPES = fhirConfig.FHIR_PRIMITIVE_TYPES
      new SearchParameterConfigurator(
        rtype = "Observation",
        rtypeBaseProfile = None,
        fhirConfig = serverConfig,
        allSearchParameters = observationSearchParameters.map(sp => sp.url -> sp.name).toMap)
    }

    "parse every R5 SearchParameter even though R5 dropped the xpath element" in {
      allSearchParameters must haveSize(1239)
      allSearchParameters.map(_.ptype).toSet mustEqual Set(
        "composite", "date", "number", "quantity", "reference", "special", "string", "token", "uri")
      // R5 removed SearchParameter.xpath; the shared implementation reads it as optional,
      // so every parsed definition simply carries None.
      allSearchParameters.forall(_.xpath.isEmpty) must beTrue
    }

    "configure the R5 Observation search parameters against the parsed profiles" in {
      observationSearchParameters must haveSize(42)

      val date = observationSearchParameters.find(_.name == "date")
        .flatMap(observationConfigurator.createSearchParameterConf)
      date must beSome
      date.get.paths mustEqual Seq("effectiveDateTime", "effectivePeriod", "effectiveTiming", "effectiveInstant")

      val subject = observationSearchParameters.find(_.name == "subject")
        .flatMap(observationConfigurator.createSearchParameterConf)
      subject must beSome
      subject.get.targets.toSet mustEqual Set(
        "Patient", "Group", "Device", "Location", "Organization", "Procedure",
        "Practitioner", "Medication", "Substance", "BiologicallyDerivedProduct", "NutritionProduct")
    }

    "refuse the three R5 Observation parameters that cannot be configured" in {
      // NOTE: documents current behavior, see plan Findings.
      // - value-canonical and component-value-canonical select
      //   value.ofType(canonical), but R5 Observation.value[x] has no canonical
      //   alternative, so there is no element path to index.
      // - code-value-string is an upstream R5 package inconsistency: its
      //   component references SearchParameter/Observation-value-string, which
      //   does not exist in the 5.0.0 package (the parameter became
      //   value-markdown), so composite resolution correctly refuses it.
      val configured = observationSearchParameters.flatMap(observationConfigurator.createSearchParameterConf)
      configured must haveSize(39)

      val refusedNames = observationSearchParameters
        .filter(sp => observationConfigurator.createSearchParameterConf(sp).isEmpty)
        .map(_.name)
      refusedNames.toSet mustEqual Set("value-canonical", "component-value-canonical", "code-value-string")
    }
  }
}

package io.onfhir.r4

import io.onfhir.api.validation.{ConstraintKeys, ElementRestrictions, ProfileRestrictions}
import io.onfhir.validation.{CardinalityMinRestriction, CodeBindingRestriction, ConstraintsRestriction, ReferenceRestrictions, TypeRestriction}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Integration suite: parse the real FHIR R4 4.0.1 standard definitions package
 * through the release-neutral configuration pipeline and the R4 parsers.
 *
 * Reaching any assertion here already proves the whole chain works:
 * `FSConfigReader` resolving the definitions zip and base CapabilityStatement
 * from their default CLASSPATH locations, `BaseFhirConfigurator` reading and
 * deriving the type universes, and `R4Parser` / `StructureDefinitionParser`
 * translating StructureDefinitions, ValueSets, CodeSystems and the
 * CapabilityStatement into the onFHIR configuration models.
 *
 * Counts are pinned to FHIR 4.0.1 as packaged by `onfhir-definitions-r4`. That
 * artifact carries a byte-pinned copy of the HL7 package, so a count change
 * means either the package or the parser changed, and both deserve review.
 */
@RunWith(classOf[JUnitRunner])
class R4StandardPackageParsingTest extends Specification {
  import R4IntegrationFixtures._

  private val observationUrl = "http://hl7.org/fhir/StructureDefinition/Observation"
  private val observationStatusValueSet = "http://hl7.org/fhir/ValueSet/observation-status"
  private val administrativeGenderValueSet = "http://hl7.org/fhir/ValueSet/administrative-gender"
  private val administrativeGenderCodeSystem = "http://hl7.org/fhir/administrative-gender"
  private val actEncounterValueSet = "http://terminology.hl7.org/ValueSet/v3-ActEncounterCode"
  private val actCodeCodeSystem = "http://terminology.hl7.org/CodeSystem/v3-ActCode"

  private def element(profile: ProfileRestrictions, path: String): Option[ElementRestrictions] =
    profile.elementRestrictions.find(_._1 == path).map(_._2)

  "The parsed R4 standard package" should {

    "derive the type universes from the standard StructureDefinitions" in {
      fhirConfig.FHIR_RESOURCE_TYPES must contain(allOf("Patient", "Observation", "Bundle", "CapabilityStatement", "StructureDefinition"))
      fhirConfig.FHIR_COMPLEX_TYPES must contain(allOf("CodeableConcept", "Quantity", "HumanName", "Reference", "Period"))
      fhirConfig.FHIR_PRIMITIVE_TYPES must contain(allOf("code", "dateTime", "boolean", "uri", "decimal"))

      // The configurator splits types by leading-character case, so the two
      // type sets must stay disjoint and correctly cased.
      fhirConfig.FHIR_COMPLEX_TYPES.forall(_.head.isUpper) must beTrue
      fhirConfig.FHIR_PRIMITIVE_TYPES.forall(_.head.isLower) must beTrue
      fhirConfig.FHIR_COMPLEX_TYPES.intersect(fhirConfig.FHIR_PRIMITIVE_TYPES) must beEmpty

      // FHIR 4.0.1 counts (abstract definitions are excluded by the configurator).
      fhirConfig.FHIR_PRIMITIVE_TYPES must haveSize(20)
      fhirConfig.FHIR_COMPLEX_TYPES must haveSize(39)
      fhirConfig.FHIR_RESOURCE_TYPES must haveSize(147)
    }

    "populate profile and value set restrictions for the whole package" in {
      fhirConfig.profileRestrictions must not(beEmpty)
      fhirConfig.valueSetRestrictions must not(beEmpty)
      fhirConfig.profileRestrictions must haveSize(629)
      fhirConfig.valueSetRestrictions must haveSize(1199)

      // Every resource type must have a resolvable base profile whose chain
      // terminates, which exercises baseDefinition linking across the package.
      val resourceTypesWithoutBaseProfile =
        fhirConfig.FHIR_RESOURCE_TYPES.filter(rtype => Option(fhirConfig.getBaseProfile(rtype)).isEmpty)
      resourceTypesWithoutBaseProfile must beEmpty

      val patientChain = fhirConfig.getBaseProfileChain("Patient").map(_.url)
      patientChain must contain(allOf(
        "http://hl7.org/fhir/StructureDefinition/Patient",
        "http://hl7.org/fhir/StructureDefinition/DomainResource",
        "http://hl7.org/fhir/StructureDefinition/Resource"))
    }

    "leave the numeric fhirVersion unset" in {
      // NOTE: documents current behavior, see plan Findings.
      // BaseFhirConfig.fhirVersion is documented as the numeric FHIR version
      // (e.g. 4.0.1), but initializePlatform never assigns it, so it stays
      // null for any config the pipeline builds. One visible consequence is
      // that FhirValidator's meta.profile version normalization can never
      // match. The release-family version passed to the constructor is what
      // actually carries "R4".
      fhirConfig.fhirVersion must beNull
    }

    "parse the Observation profile down to element level" in {
      val observation = fhirConfig.getBaseProfile("Observation")

      observation.url mustEqual observationUrl
      observation.version must beSome("4.0.1")
      observation.resourceType mustEqual "Observation"
      observation.isAbstract must beFalse

      // status: required element with a required binding to the R4 value set
      val status = element(observation, "status")
      status must beSome
      status.get.restrictions.get(ConstraintKeys.MIN) must beSome(CardinalityMinRestriction(1))
      status.get.restrictions.get(ConstraintKeys.DATATYPE) must beSome(TypeRestriction(Seq("code" -> Nil)))
      status.get.restrictions.get(ConstraintKeys.BINDING) must beSome(
        CodeBindingRestriction(observationStatusValueSet, Some("4.0.1"), "required"))
      status.get.profileDefinedIn mustEqual observationUrl

      // value[x]: a choice element carrying every R4 alternative type
      val valueChoiceTypes = element(observation, "value[x]")
        .flatMap(_.restrictions.get(ConstraintKeys.DATATYPE))
        .map(_.asInstanceOf[TypeRestriction].dataTypesAndProfiles.map(_._1))
      valueChoiceTypes must beSome
      valueChoiceTypes.get must contain(allOf(
        "Quantity", "CodeableConcept", "string", "boolean", "integer",
        "Range", "Ratio", "SampledData", "time", "dateTime", "Period"))
      valueChoiceTypes.get must haveSize(11)

      // subject: a reference constrained to specific target profiles
      val subjectTargets = element(observation, "subject")
        .flatMap(_.restrictions.get(ConstraintKeys.REFERENCE_TARGET))
        .map(_.asInstanceOf[ReferenceRestrictions])
      subjectTargets must beSome
      subjectTargets.get.referenceDataTypes mustEqual Set("Reference")
      subjectTargets.get.targetProfiles mustEqual Set(
        "http://hl7.org/fhir/StructureDefinition/Patient",
        "http://hl7.org/fhir/StructureDefinition/Group",
        "http://hl7.org/fhir/StructureDefinition/Device",
        "http://hl7.org/fhir/StructureDefinition/Location")

      // root-level invariants are parsed as FHIRPath constraints
      val rootConstraintKeys = observation.constraints.collect {
        case constraints: ConstraintsRestriction => constraints.fhirConstraints.map(_.key)
      }
      rootConstraintKeys must beSome
      rootConstraintKeys.get must contain(allOf("obs-6", "obs-7"))
    }

    "parse element-level invariants of the Patient profile" in {
      val patient = fhirConfig.getBaseProfile("Patient")

      val contactConstraints = element(patient, "contact")
        .flatMap(_.restrictions.get(ConstraintKeys.CONSTRAINT))
        .map(_.asInstanceOf[ConstraintsRestriction].fhirConstraints)
      contactConstraints must beSome
      contactConstraints.get.map(_.key) must contain("pat-1")
      contactConstraints.get.find(_.key == "pat-1").map(_.isWarning) must beSome(false)

      element(patient, "gender")
        .flatMap(_.restrictions.get(ConstraintKeys.BINDING)) must beSome(
        CodeBindingRestriction(administrativeGenderValueSet, Some("4.0.1"), "required"))

      element(patient, "deceased[x]")
        .flatMap(_.restrictions.get(ConstraintKeys.DATATYPE))
        .map(_.asInstanceOf[TypeRestriction].dataTypesAndProfiles.map(_._1)) must beSome(Seq("boolean", "dateTime"))
    }

    "expand an extensional ValueSet to its exact code set" in {
      val gender = fhirConfig.valueSetRestrictions.get(administrativeGenderValueSet)
      gender must beSome
      gender.get.keys must contain("4.0.1")

      val genderRestrictions = gender.get("4.0.1")
      genderRestrictions.includes.codes mustEqual Map(
        administrativeGenderCodeSystem -> Set("male", "female", "other", "unknown"))
      genderRestrictions.includes.valueSets must beEmpty
      genderRestrictions.excludes must beNone

      // The value set bound by Observation.status must also be fully expanded.
      val observationStatus = fhirConfig.valueSetRestrictions(observationStatusValueSet)("4.0.1")
      observationStatus.includes.codes("http://hl7.org/fhir/observation-status") mustEqual Set(
        "registered", "preliminary", "final", "amended",
        "corrected", "cancelled", "entered-in-error", "unknown")
    }

    "expand a filter-based ValueSet against a CodeSystem shipped in the package" in {
      // v3-ActEncounterCode includes 'is-a _ActEncounterCode' from the v3
      // ActCode CodeSystem, so the hierarchy filter has to be applied against
      // the packaged CodeSystem rather than skipped.
      val actEncounter = fhirConfig.valueSetRestrictions.get(actEncounterValueSet)
      actEncounter must beSome

      val byVersion = actEncounter.get
      byVersion.keys must contain("2014-03-26")

      val codes = byVersion("2014-03-26").includes.codes
      codes.keys must contain(actCodeCodeSystem)
      codes(actCodeCodeSystem) must contain(allOf("AMB", "EMER", "IMP", "VR"))
      codes(actCodeCodeSystem) must haveSize(11)
    }
  }

  "The base R4 CapabilityStatement" should {

    "parse into a plausible FHIRCapabilityStatement" in {
      capabilityStatement.fhirVersion mustEqual "4.0.1"
      capabilityStatement.restResourceConf must not(beEmpty)
      capabilityStatement.restResourceConf must haveSize(145)
      capabilityStatement.formats mustEqual Set("xml", "json")
      capabilityStatement.patchFormats must contain("application/json-patch+json")
      capabilityStatement.systemLevelInteractions mustEqual Set("batch", "transaction", "search-system")
      capabilityStatement.searchParamDefUrls must not(beEmpty)
      capabilityStatement.operationDefUrls must not(beEmpty)
    }

    "describe individual resources with parsed interactions and search parameters" in {
      val patient = capabilityStatement.restResourceConf.find(_.resource == "Patient")
      patient must beSome

      patient.get.profile must beSome("http://hl7.org/fhir/StructureDefinition/Patient")
      patient.get.interactions must contain(allOf("read", "vread", "create", "update", "delete", "search-type"))
      patient.get.searchParams must haveSize(23)
      patient.get.versioning mustEqual "versioned"
    }

    "stay consistent with the definitions package it ships beside" in {
      // Both files come from the same artifact, so every resource the
      // CapabilityStatement declares must be a resource type and every profile
      // it names must resolve in the parsed package.
      val unknownResources =
        capabilityStatement.restResourceConf.map(_.resource).toSet.diff(fhirConfig.FHIR_RESOURCE_TYPES)
      unknownResources must beEmpty

      val unresolvableProfiles =
        capabilityStatement.restResourceConf.flatMap(_.profile).filter(url => fhirConfig.findProfile(url).isEmpty)
      unresolvableProfiles must beEmpty
    }
  }
}

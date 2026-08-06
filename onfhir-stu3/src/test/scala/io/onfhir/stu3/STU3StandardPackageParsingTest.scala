package io.onfhir.stu3

import io.onfhir.api.validation.{ConstraintKeys, ElementRestrictions, ProfileRestrictions}
import io.onfhir.config.{FHIRSearchParameter, FhirServerConfig, SearchParameterConfigurator}
import io.onfhir.stu3.parsers.STU3Parser
import io.onfhir.validation.{CardinalityMinRestriction, CodeBindingRestriction, ConstraintsRestriction, ReferenceRestrictions, TypeRestriction}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Integration suite: parse the real FHIR STU3 3.0.2 standard definitions
 * package with [[STU3Parser]] and [[io.onfhir.stu3.parsers.STU3StructureDefinitionParser]].
 *
 * Counts are pinned to STU3 3.0.2 as packaged by `onfhir-definitions-stu3`,
 * for the same reason the R4 and R5 suites pin theirs: the artifact is
 * byte-pinned, so a count change means the package or a parser changed.
 *
 * Three STU3-specific parsing limitations are pinned here with
 * `// NOTE: documents current behavior` comments rather than fixed, per the
 * plan's hard constraint 4. They are pre-existing and are inherited from the
 * Repofyr implementation this module was copied from.
 */
@RunWith(classOf[JUnitRunner])
class STU3StandardPackageParsingTest extends Specification {
  import STU3IntegrationFixtures._

  private def element(profile: ProfileRestrictions, path: String): Option[ElementRestrictions] =
    profile.elementRestrictions.find(_._1 == path).map(_._2)

  "The parsed STU3 standard package" should {

    "derive the STU3 type universes" in {
      fhirConfig.FHIR_RESOURCE_TYPES must contain(allOf("Patient", "Observation", "Bundle",
        // resource types that exist in STU3 and were renamed or removed in R4
        "ProcedureRequest", "ReferralRequest", "DeviceComponent", "ImagingManifest"))
      // ... and the R4/R5 replacements must NOT be present
      fhirConfig.FHIR_RESOURCE_TYPES must not(contain("ServiceRequest"))
      fhirConfig.FHIR_RESOURCE_TYPES must not(contain("MedicationKnowledge"))

      fhirConfig.FHIR_COMPLEX_TYPES must contain(allOf("CodeableConcept", "Quantity", "Reference", "Period"))
      fhirConfig.FHIR_PRIMITIVE_TYPES must contain(allOf("code", "dateTime", "boolean", "uri"))
      // canonical, url and integer64 are later additions and must be absent.
      fhirConfig.FHIR_PRIMITIVE_TYPES must not(contain("canonical"))
      fhirConfig.FHIR_PRIMITIVE_TYPES must not(contain("url"))
      fhirConfig.FHIR_PRIMITIVE_TYPES must not(contain("integer64"))

      fhirConfig.FHIR_COMPLEX_TYPES.forall(_.head.isUpper) must beTrue
      fhirConfig.FHIR_PRIMITIVE_TYPES.forall(_.head.isLower) must beTrue

      // FHIR 3.0.2 counts (abstract definitions are excluded by the configurator).
      fhirConfig.FHIR_PRIMITIVE_TYPES must haveSize(18)
      fhirConfig.FHIR_COMPLEX_TYPES must haveSize(33)
      fhirConfig.FHIR_RESOURCE_TYPES must haveSize(118)
    }

    "populate profile and value set restrictions from all three terminology bundles" in {
      fhirConfig.profileRestrictions must haveSize(563)
      fhirConfig.valueSetRestrictions must haveSize(1091)

      // Unlike R5, the STU3 package still ships v2-tables.json and
      // v3-codesystems.json, so no VALUESET_AND_CODESYSTEM_BUNDLE_FILES
      // narrowing is needed and both families are present.
      fhirConfig.valueSetRestrictions.keys.count(_.contains("/v3-")) must be_>(100)
      fhirConfig.valueSetRestrictions.keys.count(_.contains("/v2-")) must be_>(100)

      val resourceTypesWithoutBaseProfile =
        fhirConfig.FHIR_RESOURCE_TYPES.filter(rtype => Option(fhirConfig.getBaseProfile(rtype)).isEmpty)
      resourceTypesWithoutBaseProfile must beEmpty

      fhirConfig.getBaseProfileChain("Patient").map(_.url) must contain(allOf(
        "http://hl7.org/fhir/StructureDefinition/Patient",
        "http://hl7.org/fhir/StructureDefinition/DomainResource",
        "http://hl7.org/fhir/StructureDefinition/Resource"))
    }

    "parse the STU3 Observation profile down to element level" in {
      val observation = fhirConfig.getBaseProfile("Observation")
      observation.url mustEqual "http://hl7.org/fhir/StructureDefinition/Observation"
      observation.resourceType mustEqual "Observation"

      element(observation, "status").flatMap(_.restrictions.get(ConstraintKeys.MIN)) must
        beSome(CardinalityMinRestriction(1))
      element(observation, "code").flatMap(_.restrictions.get(ConstraintKeys.MIN)) must
        beSome(CardinalityMinRestriction(1))

      // STU3 value[x] has 11 alternatives; Attachment is present but the R4/R5
      // additions (integer, Reference) are not.
      val valueChoiceTypes = element(observation, "value[x]")
        .flatMap(_.restrictions.get(ConstraintKeys.DATATYPE))
        .map(_.asInstanceOf[TypeRestriction].dataTypesAndProfiles.map(_._1))
      valueChoiceTypes must beSome
      valueChoiceTypes.get must haveSize(11)
      valueChoiceTypes.get must contain(allOf("Quantity", "CodeableConcept", "Attachment"))
      valueChoiceTypes.get must not(contain("integer"))

      // Observation.context is STU3-only; R4 renamed it to encounter.
      element(observation, "context") must beSome

      val rootConstraintKeys = observation.constraints.collect {
        case constraints: ConstraintsRestriction => constraints.fhirConstraints.map(_.key)
      }
      rootConstraintKeys must beSome
      rootConstraintKeys.get must contain(allOf("obs-6", "obs-7"))
    }

    "parse STU3-only Patient elements and element-level invariants" in {
      val patient = fhirConfig.getBaseProfile("Patient")

      // Patient.animal existed in STU3 and was removed in R4.
      element(patient, "animal") must beSome

      val contactConstraintKeys = element(patient, "contact")
        .flatMap(_.restrictions.get(ConstraintKeys.CONSTRAINT))
        .map(_.asInstanceOf[ConstraintsRestriction].fhirConstraints.map(_.key))
      contactConstraintKeys must beSome
      contactConstraintKeys.get must contain("pat-1")

      element(patient, "deceased[x]")
        .flatMap(_.restrictions.get(ConstraintKeys.DATATYPE))
        .map(_.asInstanceOf[TypeRestriction].dataTypesAndProfiles.map(_._1)) must beSome(Seq("boolean", "dateTime"))
    }

    "expand extensional STU3 ValueSets to their exact code sets at version 3.0.2" in {
      val gender = fhirConfig.valueSetRestrictions("http://hl7.org/fhir/ValueSet/administrative-gender")("3.0.2")
      gender.includes.codes mustEqual Map(
        "http://hl7.org/fhir/administrative-gender" -> Set("male", "female", "other", "unknown"))

      val observationStatus = fhirConfig.valueSetRestrictions("http://hl7.org/fhir/ValueSet/observation-status")("3.0.2")
      observationStatus.includes.codes("http://hl7.org/fhir/observation-status") mustEqual Set(
        "registered", "preliminary", "final", "amended",
        "corrected", "cancelled", "entered-in-error", "unknown")
    }

    "resolve every code binding to the $parent sentinel" in {
      // NOTE: documents current behavior, see plan Findings.
      // STU3 expresses ElementDefinition.binding.valueSet as the choice
      // valueSetUri | valueSetReference, but
      // AbstractStructureDefinitionParser.createBindingRestriction reads only
      // the R4 canonical field "valueSet" and falls back to the "$parent"
      // sentinel when it is absent. Every STU3 binding therefore parses as
      // "$parent", and because the whole chain is STU3 the validator never
      // finds a concrete ValueSet to inherit, so terminology bindings are
      // silently not enforced for STU3. STU3StructureDefinitionParser does not
      // override binding parsing. See STU3StandardValidationTest for the
      // observable consequence.
      val observationStatusBinding = element(fhirConfig.getBaseProfile("Observation"), "status")
        .flatMap(_.restrictions.get(ConstraintKeys.BINDING))
        .map(_.asInstanceOf[CodeBindingRestriction])
      observationStatusBinding must beSome
      observationStatusBinding.get.valueSetUrl mustEqual "$parent"
      observationStatusBinding.get.strength mustEqual "required"

      val patientGenderBinding = element(fhirConfig.getBaseProfile("Patient"), "gender")
        .flatMap(_.restrictions.get(ConstraintKeys.BINDING))
        .map(_.asInstanceOf[CodeBindingRestriction])
      patientGenderBinding must beSome
      patientGenderBinding.get.valueSetUrl mustEqual "$parent"
    }

    "keep only the first reference target of a multi-entry STU3 type array" in {
      // NOTE: documents current behavior, see plan Findings.
      // STU3 repeats ElementDefinition.type once per target profile, whereas R4
      // uses a single Reference entry with an array of targetProfiles.
      // StructureDefinitionParser builds REFERENCE_TARGET from
      // dataTypeAndProfile.find(...), i.e. the FIRST matching entry, so for
      // STU3 only the first target profile survives. Observation.subject
      // therefore parses as Patient-only even though STU3 allows Patient,
      // Group, Device and Location.
      val observation = fhirConfig.getBaseProfile("Observation")

      val subjectTypes = element(observation, "subject")
        .flatMap(_.restrictions.get(ConstraintKeys.DATATYPE))
        .map(_.asInstanceOf[TypeRestriction].dataTypesAndProfiles.map(_._1))
      // The four separate type entries survive as four duplicate data types.
      subjectTypes must beSome(Seq("Reference", "Reference", "Reference", "Reference"))

      val subjectTargets = element(observation, "subject")
        .flatMap(_.restrictions.get(ConstraintKeys.REFERENCE_TARGET))
        .map(_.asInstanceOf[ReferenceRestrictions].targetProfiles.map(_.split('/').last))
      subjectTargets must beSome
      subjectTargets.get mustEqual Set("Patient")
    }
  }

  "The base STU3 CapabilityStatement" should {

    "parse through the STU3-specific Reference-shaped profile field" in {
      // STU3 CapabilityStatement.rest.resource.profile is a Reference, so
      // STU3Parser reads profile.reference where R4Parser reads a canonical.
      capabilityStatement.restResourceConf must haveSize(116)

      val patient = capabilityStatement.restResourceConf.find(_.resource == "Patient")
      patient must beSome
      patient.get.profile must beSome("http://hl7.org/fhir/StructureDefinition/Patient")
      patient.get.interactions must contain(allOf("read", "vread", "create", "update", "delete", "search-type"))
      patient.get.searchParams must haveSize(25)
      patient.get.versioning mustEqual "versioned"

      capabilityStatement.formats mustEqual Set("xml", "json")
      capabilityStatement.systemLevelInteractions mustEqual Set("batch", "transaction")
      capabilityStatement.searchParamDefUrls must haveSize(7)
      // The STU3 base statement declares no patchFormat and no operations.
      capabilityStatement.patchFormats must beEmpty
      capabilityStatement.operationDefUrls must beEmpty
    }

    "declare fhirVersion 3.0.1 although the definitions bundle is 3.0.2" in {
      // NOTE: documents current behavior, see plan Findings. This is an
      // upstream inconsistency in HL7's STU3 3.0.2 package, not a parser bug:
      // the bundled base CapabilityStatement still says 3.0.1 while every
      // ValueSet in definitions-stu3.json.zip carries version 3.0.2.
      capabilityStatement.fhirVersion mustEqual "3.0.1"
      fhirConfig.valueSetRestrictions("http://hl7.org/fhir/ValueSet/administrative-gender").keys must contain("3.0.2")
    }

    "stay consistent with the definitions package it ships beside" in {
      capabilityStatement.restResourceConf.map(_.resource).toSet
        .diff(fhirConfig.FHIR_RESOURCE_TYPES) must beEmpty
      capabilityStatement.restResourceConf.flatMap(_.profile)
        .filter(url => fhirConfig.findProfile(url).isEmpty) must beEmpty
    }
  }

  "The STU3 SearchParameter and OperationDefinition bundles" should {

    lazy val parser = new STU3Parser(fhirConfig.FHIR_COMPLEX_TYPES, fhirConfig.FHIR_PRIMITIVE_TYPES)

    lazy val allSearchParameters: Seq[FHIRSearchParameter] =
      configReader
        .readStandardBundleFile("search-parameters.json", Set("SearchParameter"))
        .map(parser.parseSearchParameter)

    lazy val observationSearchParameters: Seq[FHIRSearchParameter] =
      allSearchParameters.filter(_.base.contains("Observation"))

    lazy val observationConfigurator: SearchParameterConfigurator = {
      val serverConfig = new FhirServerConfig("STU3")
      serverConfig.fhirVersion = "3.0.2"
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

    "parse every STU3 SearchParameter, retaining xpath and omitting multipleOr/And" in {
      allSearchParameters must haveSize(1217)
      // STU3 has no 'special' parameter type; R4 introduced it.
      allSearchParameters.map(_.ptype).toSet mustEqual Set(
        "composite", "date", "number", "quantity", "reference", "string", "token", "uri")
      // Unlike R5, STU3 still carries xpath on virtually every definition.
      allSearchParameters.count(_.xpath.isDefined) mustEqual 1202
      // STU3Parser deliberately does not read multipleOr/multipleAnd.
      allSearchParameters.forall(sp => sp.multipleOr.isEmpty && sp.multipleAnd.isEmpty) must beTrue
    }

    "configure the non-composite STU3 Observation search parameters" in {
      observationSearchParameters must haveSize(38)

      val code = observationSearchParameters.find(_.name == "code")
        .flatMap(observationConfigurator.createSearchParameterConf)
      code must beSome
      code.get.ptype mustEqual "token"
      code.get.paths mustEqual Seq("code")
      code.get.targetTypes mustEqual Seq("CodeableConcept")

      // STU3 effective[x] offers only dateTime and Period, where R4/R5 add
      // Timing and instant.
      val date = observationSearchParameters.find(_.name == "date")
        .flatMap(observationConfigurator.createSearchParameterConf)
      date must beSome
      date.get.paths mustEqual Seq("effectiveDateTime", "effectivePeriod")
      date.get.targetTypes mustEqual Seq("dateTime", "Period")
    }

    "refuse every STU3 composite search parameter" in {
      // NOTE: documents current behavior, see plan Findings.
      // STU3 SearchParameter.component.definition is a Reference, not the
      // canonical string R4 uses, so STU3Parser's
      // (searchParameter \ "component" \ "definition").extractOrElse[Seq[String]](Nil)
      // yields Nil. SearchParameterConfigurator then refuses the parameter
      // because a composite needs a non-empty component set. All nine STU3
      // Observation composites are therefore unsupported.
      val configured = observationSearchParameters.flatMap(observationConfigurator.createSearchParameterConf)
      configured must haveSize(29)

      val refused = observationSearchParameters
        .filter(sp => observationConfigurator.createSearchParameterConf(sp).isEmpty)
      refused must haveSize(9)
      refused.forall(_.ptype == "composite") must beTrue
      refused.forall(_.components.isEmpty) must beTrue
      refused.map(_.name).toSet must contain(allOf("code-value-quantity", "combo-code-value-quantity", "related"))
    }

    "parse STU3 OperationDefinitions including the Reference-shaped parameter binding" in {
      val operationDefinitions = configReader
        .readStandardBundleFile("profiles-resources.json", Set("OperationDefinition"))
        .map(parser.parseOperationDefinition)

      operationDefinitions must haveSize(36)

      val expand = operationDefinitions.find(_.name == "expand")
      expand must beSome
      expand.get.url mustEqual "http://hl7.org/fhir/OperationDefinition/ValueSet-expand"
      expand.get.kind mustEqual "operation"
      expand.get.levels mustEqual Set("instance", "type")
      expand.get.inputParams must haveSize(16)
      expand.get.outputParams must haveSize(1)

      // STU3Parser reads binding.valueSetUri or binding.valueSetReference.reference
      // where R4Parser reads the canonical binding.valueSet.
      operationDefinitions
        .flatMap(op => op.inputParams ++ op.outputParams)
        .count(_.binding.isDefined) mustEqual 1
    }
  }
}

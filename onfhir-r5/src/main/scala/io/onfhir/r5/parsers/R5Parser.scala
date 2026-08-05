package io.onfhir.r5.parsers

import io.onfhir.config.FhirCapabilityDefaults
import io.onfhir.r4.parsers.R4Parser

/**
 * FHIR R5 foundation-resource parser.
 *
 * The foundation-resource fields currently consumed by onFHIR have compatible
 * R4 and R5 shapes, so this parser reuses the proven R4 implementation while
 * owning the R5 defaults and the public extension point for future R5-specific
 * behavior.
 *
 * @param fhirComplexTypes   complex datatypes supported by the definition package
 * @param fhirPrimitiveTypes primitive datatypes supported by the definition package
 * @param capabilityDefaults defaults for optional CapabilityStatement fields
 */
class R5Parser(
                fhirComplexTypes: Set[String] = R5Parser.DefaultComplexTypes,
                fhirPrimitiveTypes: Set[String] = R5Parser.DefaultPrimitiveTypes,
                capabilityDefaults: FhirCapabilityDefaults = FhirCapabilityDefaults.Standard)
  extends R4Parser(fhirComplexTypes, fhirPrimitiveTypes, capabilityDefaults)

object R5Parser {
  /**
   * Non-abstract FHIR R5 5.0.0 primitive datatype universe.
   *
   * The ordinary primitive types come from the HL7 R5 datatype table; `xhtml`
   * is the special-purpose primitive shown in the same page's datatype summary.
   */
  val DefaultPrimitiveTypes: Set[String] = Set(
    "base64Binary", "boolean", "canonical", "code", "date", "dateTime", "decimal", "id", "instant",
    "integer", "integer64", "markdown", "oid", "positiveInt", "string", "time", "unsignedInt", "uri",
    "url", "uuid", "xhtml")

  /**
   * Distinct non-abstract complex type names in the FHIR R5 5.0.0
   * `profiles-types.json` bundle.
   *
   * This follows the general-purpose, metadata, and special-purpose type
   * families on the HL7 R5 datatype page. `SimpleQuantity` and
   * `MoneyQuantity` are represented by their StructureDefinition type
   * `Quantity`; reusable `MarketingStatus` and `ProductShelfLife` structures
   * are included from the same official type bundle.
   */
  val DefaultComplexTypes: Set[String] = Set(
    "Address", "Age", "Annotation", "Attachment", "Availability", "CodeableConcept", "CodeableReference",
    "Coding", "ContactDetail", "ContactPoint", "Contributor", "Count", "DataRequirement", "Distance",
    "Dosage", "Duration", "ElementDefinition", "Expression", "ExtendedContactDetail", "Extension",
    "HumanName", "Identifier", "MarketingStatus", "Meta", "MonetaryComponent", "Money", "Narrative",
    "ParameterDefinition", "Period", "ProductShelfLife", "Quantity", "Range", "Ratio", "RatioRange",
    "Reference", "RelatedArtifact", "SampledData", "Signature", "Timing", "TriggerDefinition", "UsageContext",
    "VirtualServiceDetail")
}

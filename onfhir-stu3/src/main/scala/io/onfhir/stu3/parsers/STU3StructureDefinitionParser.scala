package io.onfhir.stu3.parsers

import io.onfhir.api.util.FHIRUtil
import io.onfhir.r4.parsers.StructureDefinitionParser
import org.json4s.JsonAST.JObject

/**
 * STU3-specific parsing of `ElementDefinition.type` entries.
 *
 * STU3 differs from R4 in that `type.profile` and `type.targetProfile` are
 * single values rather than arrays, so they are read with
 * `extractValueOption` and lifted into a sequence.
 *
 * @param fhirComplexTypes   List of FHIR complex types defined in the standard
 * @param fhirPrimitiveTypes List of FHIR primitive types defined in the standard
 */
class STU3StructureDefinitionParser(fhirComplexTypes: Set[String], fhirPrimitiveTypes: Set[String])
  extends StructureDefinitionParser(fhirComplexTypes, fhirPrimitiveTypes) {

  override def parseTypeInElemDefinition(typeDef: JObject): (String, Seq[String], Seq[String], Option[String], Seq[String]) = {
    (
      FHIRUtil.extractValue[String](typeDef, "code") match {
        case "http://hl7.org/fhirpath/System.String" => "string" // Some base definitions have these
        case oth => oth
      },
      FHIRUtil.extractValueOption[String](typeDef, "profile").toSeq,
      FHIRUtil.extractValueOption[String](typeDef, "targetProfile").toSeq,
      FHIRUtil.extractValueOption[String](typeDef, "versioning"),
      FHIRUtil.extractValue[Seq[String]](typeDef, "aggregation")
    )
  }
}

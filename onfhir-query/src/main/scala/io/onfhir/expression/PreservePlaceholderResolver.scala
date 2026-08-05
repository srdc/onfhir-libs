package io.onfhir.expression

import io.onfhir.api.{FHIR_PARAMETER_TYPES, FHIR_PREFIXES_MODIFIERS}
import io.onfhir.api.parsers.ISearchParamPlaceholderResolver
import io.onfhir.path.{FhirPathEvaluator, FhirPathException}

/**
 * Just keep the FHIR Path expression as it is (do not resolve yet), used for parsing
 */
class PreservePlaceholderResolver extends ISearchParamPlaceholderResolver {
  override private[onfhir] val preservesExpression: Boolean = true

  override def resolveExpression(spValueExpr: String, searchParamType: String,  modifier:String, prefix:String): String = {
    if (searchParamType == FHIR_PARAMETER_TYPES.COMPOSITE)
      throw FhirExpressionException(
        "Invalid x-fhir-query shape: placeholders are not supported for composite search parameters.",
        expression = Some(spValueExpr)
      )

    val allowedPrefixes = searchParamType match {
      case FHIR_PARAMETER_TYPES.NUMBER |
           FHIR_PARAMETER_TYPES.DATE |
           FHIR_PARAMETER_TYPES.QUANTITY => Set(
        FHIR_PREFIXES_MODIFIERS.BLANK_EQUAL,
        FHIR_PREFIXES_MODIFIERS.EQUAL,
        FHIR_PREFIXES_MODIFIERS.NOT_EQUAL,
        FHIR_PREFIXES_MODIFIERS.GREATER_THAN,
        FHIR_PREFIXES_MODIFIERS.GREATER_THAN_M,
        FHIR_PREFIXES_MODIFIERS.LESS_THAN,
        FHIR_PREFIXES_MODIFIERS.LESS_THAN_M,
        FHIR_PREFIXES_MODIFIERS.GREATER_THAN_EQUAL,
        FHIR_PREFIXES_MODIFIERS.LESS_THAN_EQUAL,
        FHIR_PREFIXES_MODIFIERS.STARTS_AFTER,
        FHIR_PREFIXES_MODIFIERS.ENDS_BEFORE,
        FHIR_PREFIXES_MODIFIERS.APPROXIMATE
      )
      case _ => Set(FHIR_PREFIXES_MODIFIERS.BLANK_EQUAL)
    }

    if (!allowedPrefixes.contains(prefix))
      throw FhirExpressionException(
        s"Invalid x-fhir-query shape: prefix '$prefix' is not valid for $searchParamType search parameters.",
        expression = Some(spValueExpr)
      )

    try {
      FhirPathEvaluator.parseStrict(spValueExpr)
      s"{{$spValueExpr}}"
    } catch {
      case e: FhirPathException =>
        throw FhirExpressionException(
          s"Invalid FHIRPath placeholder expression: $spValueExpr",
          expression = Some(spValueExpr),
          t = Some(e)
        )
    }
  }
}

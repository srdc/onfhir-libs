package io.onfhir.query

import io.onfhir.api.FHIR_PARAMETER_CATEGORIES
import io.onfhir.api.model.Parameter
import io.onfhir.expression.XFhirQueryUtil

/**
 * A FHIR query statement that has been validated against a FHIR configuration.
 *
 * The parameters are kept in the order they appeared in the query statement,
 * including repetitions of the same parameter name.
 *
 * @param resourceType FHIR resource type the query is executed on e.g. Patient
 * @param parameters   Validated search, result and special category parameters of the query
 */
final case class ParsedFhirQuery(resourceType: String, parameters: List[Parameter]) {

  /**
   * Parameters that restrict which resources are members of the result set.
   * Composite parameters are included; they are normal category parameters
   * with a composite parameter type.
   *
   * @return Normal category parameters in query order
   */
  def searchParameters: List[Parameter] =
    parameters.filter(_.paramCategory == FHIR_PARAMETER_CATEGORIES.NORMAL)

  /**
   * Parameters that control how the result set is returned e.g. _sort, _count.
   *
   * @return Result category parameters in query order
   */
  def resultParameters: List[Parameter] =
    parameters.filter(_.paramCategory == FHIR_PARAMETER_CATEGORIES.RESULT)

  /**
   * Encode the query back into a FHIR query statement e.g. Patient?name=Smith.
   * Unresolved FHIRPath placeholders are preserved, so a query returned by
   * [[FhirQueryEvaluator.validateXFhirQuery]] can be encoded without losing them.
   *
   * @return The resource type alone when there is no parameter, otherwise the
   *         resource type and the encoded parameters joined with '&'
   */
  def encode: String =
    if (parameters.isEmpty) resourceType
    else s"$resourceType?${parameters.map(XFhirQueryUtil.encodeParameterPreservingPlaceholders).mkString("&")}"
}

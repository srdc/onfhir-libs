package io.onfhir.api.parsers

import io.onfhir.api.model.{OrderedQuery, Parameter}
import io.onfhir.config.{FhirSearchHandling, FhirServerConfig}

/**
 * A utility class to parse/validate a FHIR query statement against the fhir configuration
 * @param fhirServerConfig      The FHIR server configuration
 * @param defaultSearchHandling Handling used when the query has no request-level override
 */
class FhirQueryParser(fhirServerConfig: FhirServerConfig, defaultSearchHandling: FhirSearchHandling) {
  private val searchParamParser = new FHIRSearchParameterValueParser(fhirServerConfig, defaultSearchHandling)

  /**
   * Parse the given x-fhir-query statement without any FHIR Path referencing
   * e.g. Patient?gender=male
   *
   * Note: The statement is split manually instead of via java.net.URI because FHIR search
   * statements legitimately contain characters a strict URI parser rejects (e.g. '|' in
   * token values such as code=http://loinc.org|15074-8).
   *
   * @param query FHIR Query statement
   * @return
   */
  def parseQuery(query: String): (String, List[Parameter]) = {
    val (path, rawQuery) = splitQueryStatement(query)
    val queryParams = OrderedQuery.parse(rawQuery).toMultiMap
    val pathSegments = path.split("/").filter(_.nonEmpty)
    val rtype = pathSegments match {
      case Array(resourceType) => resourceType
      case _ => throw new IllegalArgumentException("Invalid FHIR query, FHIR resource type is missing")
    }

    rtype -> searchParamParser.parseSearchParameters(rtype, queryParams)
  }

  /**
   * Parse the given FHIR query for the specified FHIR Resource type
   *
   * @param rtype FHIR resource type
   * @param query FHIR Query statement
   *              e.g. ?code=...&value=...
   * @return
   */
  private def parseQuery(rtype: String, query: String): List[Parameter] = {
    val queryParams = OrderedQuery.parse(splitQueryStatement(query)._2).toMultiMap
    searchParamParser.parseSearchParameters(rtype, queryParams)
  }

  /**
   * Split an x-fhir-query statement into its path part and raw query part
   * @param query FHIR Query statement e.g. Patient?gender=male
   * @return
   */
  private def splitQueryStatement(query: String): (String, String) = {
    query.indexOf('?') match {
      case -1 => (query, "")
      case i => (query.substring(0, i), query.substring(i + 1))
    }
  }
}

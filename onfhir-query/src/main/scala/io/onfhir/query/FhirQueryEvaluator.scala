package io.onfhir.query

import io.onfhir.api.model.Parameter
import io.onfhir.api.parsers.{FHIRResultParameterResolver, FhirQueryParser}
import io.onfhir.api.{FHIR_PARAMETER_CATEGORIES, FHIR_PARAMETER_TYPES, FHIR_SEARCH_RESULT_PARAMETERS, FHIR_SEARCH_SPECIAL_PARAMETERS}
import io.onfhir.config.{FhirEndpointSettings, FhirResultDefaults, FhirSearchHandling, FhirServerConfig}
import io.onfhir.exception.{InvalidParameterException, UnsupportedParameterException}
import io.onfhir.expression.{XFhirQueryParser, XFhirQueryUtil}
import io.onfhir.path.FhirPathEvaluator
import org.json4s.JsonAST.{JNothing, JValue}

/**
 * SDK facade combining the FHIR query workflows of this module behind one
 * entry point: parsing plain FHIR query and x-fhir-query statements, resolving
 * result parameters into typed instructions, and compiling a query into a
 * reusable single-resource predicate.
 *
 * It composes [[io.onfhir.api.parsers.FhirQueryParser]],
 * [[io.onfhir.expression.XFhirQueryParser]],
 * [[io.onfhir.api.parsers.FHIRResultParameterResolver]] and
 * [[io.onfhir.api.util.ImMemorySearchUtil]]; those remain public for advanced
 * use. All methods are synchronous and the facade holds no mutable state.
 *
 * [[parse]] and [[compile]] serve different intents: a parsed query may
 * legitimately contain parameters that only a repository can evaluate (e.g.
 * chained parameters or _include), while [[compile]] validates that every
 * search parameter is locally evaluable and fails fast otherwise, so that a
 * criterion is rejected when it is registered rather than when the first
 * resource is evaluated.
 *
 * @param fhirConfig            FHIR configuration declaring the resource types and
 *                              search parameters usable in queries
 * @param endpointSettings      Settings of the FHIR endpoint the queries and
 *                              resources belong to; reference matching treats a
 *                              relative reference and an absolute reference under
 *                              this root URL as equivalent
 * @param defaultSearchHandling Handling for unsupported parameters when the query
 *                              has no request-level override; strict rejects them,
 *                              lenient ignores them
 * @param resultDefaults        Server defaults required only by [[resolveResultControls]]
 * @param fhirPathEvaluator     FHIRPath evaluator used only by the x-fhir-query methods
 */
final class FhirQueryEvaluator(
    val fhirConfig: FhirServerConfig,
    endpointSettings: FhirEndpointSettings,
    defaultSearchHandling: FhirSearchHandling = FhirSearchHandling.Strict,
    resultDefaults: Option[FhirResultDefaults] = None,
    fhirPathEvaluator: FhirPathEvaluator = FhirPathEvaluator()) {
  import CompiledFhirQuery._
  import FhirQueryEvaluator._

  require(fhirConfig != null, "fhirConfig must not be null")
  require(fhirConfig.resourceQueryParameters != null, "fhirConfig.resourceQueryParameters must be populated")
  require(fhirConfig.commonQueryParameters != null, "fhirConfig.commonQueryParameters must be populated")
  require(fhirConfig.FHIR_RESULT_PARAMETERS != null, "fhirConfig.FHIR_RESULT_PARAMETERS must be populated")
  require(fhirConfig.FHIR_SPECIAL_PARAMETERS != null, "fhirConfig.FHIR_SPECIAL_PARAMETERS must be populated")

  private val queryParser = new FhirQueryParser(fhirConfig, defaultSearchHandling)
  private val xFhirQueryParser = new XFhirQueryParser(fhirConfig, defaultSearchHandling, fhirPathEvaluator)
  private val resultParameterResolver = resultDefaults.map(new FHIRResultParameterResolver(fhirConfig, _))

  /**
   * Parse and validate a plain FHIR query statement against the configuration.
   * e.g. Patient?name=Smith&_sort=-birthdate
   *
   * @param query FHIR query statement whose path is the FHIR resource type
   * @return The typed parse result keeping parameters in query order
   */
  def parse(query: String): ParsedFhirQuery = {
    val (resourceType, parameters) = queryParser.parseQuery(query)
    ParsedFhirQuery(resourceType, parameters)
  }

  /**
   * Parse and validate an x-fhir-query statement by resolving the FHIRPath
   * expressions in its placeholders.
   * e.g. Observation?subject={{%patientRef}}&date=ge{{today()}}
   *
   * @param query   The x-fhir-query statement
   * @param context Named context parameters for FHIRPath expression resolution
   * @param input   Input content for FHIRPath expression resolution
   * @return The typed parse result with placeholder values resolved
   */
  def parseXFhirQuery(query: String, context: Map[String, JValue] = Map.empty, input: JValue = JNothing): ParsedFhirQuery = {
    val (resourceType, queryPart) = XFhirQueryUtil.splitResourceTypeAndQuery(query)
    ParsedFhirQuery(resourceType, xFhirQueryParser.parseXFhirQuery(resourceType, queryPart.getOrElse(""), context, input))
  }

  /**
   * Validate the shape of an x-fhir-query statement, including the FHIRPath
   * syntax of its placeholders, while keeping the placeholders unresolved.
   * The result can be encoded back without losing the placeholders; it cannot
   * be compiled for local evaluation before its placeholders are resolved.
   *
   * @param query The x-fhir-query statement
   * @return The typed parse result with placeholder values preserved
   */
  def validateXFhirQuery(query: String): ParsedFhirQuery = {
    val (resourceType, queryPart) = XFhirQueryUtil.splitResourceTypeAndQuery(query)
    ParsedFhirQuery(resourceType, xFhirQueryParser.parseXFhirQueryShape(resourceType, queryPart.getOrElse("")))
  }

  /**
   * Resolve the result category parameters of a parsed query into typed
   * instructions usable by a repository implementation. Search parameters in
   * the query are not consulted. Result parameters this module does not
   * resolve e.g. _include, _revinclude are carried in
   * [[FhirResultControls.unresolvedResultParameters]].
   *
   * @param parsed A parsed query
   * @return Typed sorting, projection, pagination and total instructions
   */
  def resolveResultControls(parsed: ParsedFhirQuery): FhirResultControls = {
    val resolver = resultParameterResolver.getOrElse(
      throw new IllegalStateException("resolveResultControls requires 'resultDefaults' to be provided to the FhirQueryEvaluator constructor."))
    val resultParameters = parsed.resultParameters

    val sorting =
      resolver
        .resolveSortingParameters(parsed.resourceType, resultParameters)
        .map { case (paramName, direction, pathsAndTargetTypes) => FhirSortInstruction(paramName, direction == -1, pathsAndTargetTypes) }
        .toList
    val summary =
      resolver
        .resolveSummaryParameter(parsed.resourceType, resultParameters)
        .map { case (include, elements) => FhirElementProjection(include, elements) }
    val (pageSize, pageOrCursor) = resolver.resolveCountPageParameters(resultParameters)
    val pagination = pageOrCursor match {
      case Left(page) => FhirPaginationInstruction.ByPage(page)
      case Right((values, forward)) => FhirPaginationInstruction.ByCursor(values, forward)
    }

    FhirResultControls(
      sorting = sorting,
      summary = summary,
      elements = resolver.resolveElementsParameter(resultParameters),
      pageSize = pageSize,
      pagination = pagination,
      includeTotal = resolver.resolveTotalParameter(resultParameters),
      unresolvedResultParameters = resultParameters.filterNot(p => ResolvedResultParameterNames.contains(p.name))
    )
  }

  /**
   * Compile a parsed query into a reusable single-resource predicate.
   *
   * All local-evaluability checks happen here rather than at match time:
   * chained, reverse chained (_has), compartment and special parameters other
   * than _id are rejected with [[io.onfhir.exception.UnsupportedParameterException]],
   * and unresolved placeholders are rejected with
   * [[io.onfhir.exception.InvalidParameterException]]. Result category
   * parameters do not affect membership of a single resource; they are ignored
   * and reported through [[CompiledFhirQuery.ignoredParameters]].
   *
   * @param parsed A parsed query with resolved parameter values
   * @return The compiled query predicate
   */
  def compile(parsed: ParsedFhirQuery): CompiledFhirQuery = {
    parsed.parameters
      .find(_.valuePrefixList.exists(_._2.contains("{{")))
      .foreach(parameter =>
        throw new InvalidParameterException(
          s"Parameter '${parameter.name}' contains an unresolved FHIRPath placeholder! Resolve placeholders with parseXFhirQuery before compiling the query for local evaluation."))

    val searchParameterConfigurations = fhirConfig.getSupportedParameters(parsed.resourceType)
    val (ignoredParameters, localParameters) = parsed.parameters.partition(_.paramCategory == FHIR_PARAMETER_CATEGORIES.RESULT)

    val predicates =
      localParameters.map(parameter =>
        parameter.paramCategory match {
          case FHIR_PARAMETER_CATEGORIES.NORMAL =>
            val conf = fhirConfig
              .findSupportedSearchParameter(parsed.resourceType, parameter.name)
              .getOrElse(throw new UnsupportedParameterException(
                s"Search parameter '${parameter.name}' is not supported for resource type '${parsed.resourceType}'! Check the configuration this evaluator is constructed with."))
            if (conf.ptype == FHIR_PARAMETER_TYPES.COMPOSITE) {
              val missingComponents = conf.targets.filterNot(searchParameterConfigurations.contains)
              if (missingComponents.nonEmpty)
                throw new UnsupportedParameterException(
                  s"Composite search parameter '${parameter.name}' references component parameters (${missingComponents.mkString(", ")}) that are not configured for resource type '${parsed.resourceType}'!")
              CompositePredicate(parameter, conf)
            } else
              SimplePredicate(parameter, conf)

          case FHIR_PARAMETER_CATEGORIES.SPECIAL if parameter.name == FHIR_SEARCH_SPECIAL_PARAMETERS.ID =>
            if (parameter.suffix.nonEmpty || parameter.valuePrefixList.exists(_._1.nonEmpty))
              throw new UnsupportedParameterException("Parameter '_id' cannot be evaluated locally with a prefix or modifier!")
            IdPredicate(parameter.valuePrefixList.map(_._2))

          case FHIR_PARAMETER_CATEGORIES.SPECIAL =>
            throw new UnsupportedParameterException(
              s"Parameter '${parameter.name}' requires repository or index semantics and cannot be evaluated against a single resource!")

          case FHIR_PARAMETER_CATEGORIES.CHAINED =>
            throw new UnsupportedParameterException(
              s"Chained parameter '${parameter.name}' requires access to a repository and cannot be evaluated against a single resource!")

          case FHIR_PARAMETER_CATEGORIES.REVCHAINED =>
            throw new UnsupportedParameterException(
              s"Reverse chained (_has) parameter '${parameter.name}' requires access to a repository and cannot be evaluated against a single resource!")

          case other =>
            throw new UnsupportedParameterException(
              s"Parameter '${parameter.name}' with category '$other' cannot be evaluated against a single resource!")
        }
      )

    new CompiledFhirQuery(parsed, ignoredParameters, predicates, endpointSettings, searchParameterConfigurations)
  }

  /**
   * Parse the given plain FHIR query statement and compile it into a reusable
   * single-resource predicate. Equivalent to compile(parse(query)).
   *
   * @param query FHIR query statement whose path is the FHIR resource type
   * @return The compiled query predicate
   */
  def compile(query: String): CompiledFhirQuery =
    compile(parse(query))
}

object FhirQueryEvaluator {
  /** Result parameter names resolved into typed fields of [[FhirResultControls]]. */
  private val ResolvedResultParameterNames: Set[String] =
    Set(
      FHIR_SEARCH_RESULT_PARAMETERS.SORT,
      FHIR_SEARCH_RESULT_PARAMETERS.SUMMARY,
      FHIR_SEARCH_RESULT_PARAMETERS.ELEMENTS,
      FHIR_SEARCH_RESULT_PARAMETERS.COUNT,
      FHIR_SEARCH_RESULT_PARAMETERS.PAGE,
      FHIR_SEARCH_RESULT_PARAMETERS.SEARCH_AFTER,
      FHIR_SEARCH_RESULT_PARAMETERS.SEARCH_BEFORE,
      FHIR_SEARCH_RESULT_PARAMETERS.TOTAL
    )

  def apply(fhirConfig: FhirServerConfig,
            endpointSettings: FhirEndpointSettings,
            defaultSearchHandling: FhirSearchHandling = FhirSearchHandling.Strict,
            resultDefaults: Option[FhirResultDefaults] = None,
            fhirPathEvaluator: FhirPathEvaluator = FhirPathEvaluator()): FhirQueryEvaluator =
    new FhirQueryEvaluator(fhirConfig, endpointSettings, defaultSearchHandling, resultDefaults, fhirPathEvaluator)
}

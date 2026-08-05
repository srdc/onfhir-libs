package io.onfhir.query

import io.onfhir.api.model.Parameter
import io.onfhir.api.util.ImMemorySearchUtil
import io.onfhir.config.{FhirEndpointSettings, SearchParameterConf}
import org.json4s.JsonAST.{JString, JValue}

import scala.collection.mutable

/**
 * A FHIR query compiled into a reusable single-resource membership predicate.
 *
 * Instances are created by [[FhirQueryEvaluator.compile]], which resolves every
 * search parameter to its configuration and rejects parameters that cannot be
 * evaluated against a single resource (chained, reverse chained, compartment
 * and special parameters other than _id). Because those checks already
 * happened, [[matches]] is a pure function over the given resource.
 *
 * A compiled query holds no mutable state and can be shared freely between
 * threads; one instance is intended to be reused across many resources, for
 * example when evaluating a subscription criterion or a conditional operation.
 *
 * @param query             The parsed query this predicate was compiled from
 * @param ignoredParameters Result category parameters of the query e.g. _sort,
 *                          _count. They shape a server search result, not the
 *                          membership of a single resource, so they are
 *                          reported here and ignored by [[matches]]
 */
final class CompiledFhirQuery private[query] (
    val query: ParsedFhirQuery,
    val ignoredParameters: List[Parameter],
    predicates: List[CompiledFhirQuery.CompiledPredicate],
    endpointSettings: FhirEndpointSettings,
    searchParameterConfigurations: Map[String, SearchParameterConf]) {
  import CompiledFhirQuery._

  /**
   * Check whether the given resource satisfies this query.
   *
   * The resource must declare the query's resource type in its resourceType
   * element; any other resource type never matches. Repeated parameters are
   * combined with FHIR AND semantics while comma separated values within one
   * parameter follow FHIR OR semantics. A query without search parameters
   * matches every resource of the queried type.
   *
   * @param resource FHIR resource content in json4s representation
   * @return True when the resource is a member of the query's result set
   */
  def matches(resource: JValue): Boolean =
    (resource \ "resourceType") == JString(query.resourceType) && {
      //Element values extracted per search parameter configuration, shared by
      //repeated parameters on the same configuration within this call only
      val extractedValues = mutable.HashMap.empty[String, Seq[(Seq[JValue], String)]]
      def valuesOf(conf: SearchParameterConf): Seq[(Seq[JValue], String)] =
        extractedValues.getOrElseUpdate(conf.pname, ImMemorySearchUtil.extractValuesAndTargetTypes(conf, resource))

      predicates.forall {
        case SimplePredicate(parameter, conf) =>
          ImMemorySearchUtil.handleSimpleParameter(parameter, conf, valuesOf(conf), endpointSettings)
        case CompositePredicate(parameter, conf) =>
          ImMemorySearchUtil.handleCompositeParameter(parameter, conf, valuesOf(conf), searchParameterConfigurations, endpointSettings)
        case IdPredicate(expectedIds) =>
          (resource \ "id") match {
            case JString(id) => expectedIds.contains(id)
            case _ => false
          }
      }
    }
}

object CompiledFhirQuery {
  /**
   * A single locally evaluable search parameter of a compiled query, resolved
   * to everything needed at match time.
   */
  private[query] sealed trait CompiledPredicate

  /** A normal parameter evaluated with its search parameter configuration. */
  private[query] final case class SimplePredicate(parameter: Parameter, conf: SearchParameterConf) extends CompiledPredicate

  /** A composite parameter evaluated with its component configurations. */
  private[query] final case class CompositePredicate(parameter: Parameter, conf: SearchParameterConf) extends CompiledPredicate

  /** The _id parameter evaluated as equality on the resource id element. */
  private[query] final case class IdPredicate(expectedIds: Seq[String]) extends CompiledPredicate
}

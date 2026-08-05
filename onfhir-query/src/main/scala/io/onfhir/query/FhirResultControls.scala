package io.onfhir.query

import io.onfhir.api.model.Parameter

/**
 * Typed representation of the FHIR search result parameters of a query.
 *
 * @param sorting                    Sorting instructions in the order given by _sort
 * @param summary                    Element projection requested by _summary, if any
 * @param elements                   Element names requested by _elements
 * @param pageSize                   Requested _count, or the configured default page size
 * @param pagination                 Requested page or cursor position
 * @param includeTotal               Whether the total number of matches was requested
 * @param unresolvedResultParameters Result parameters this module does not resolve
 *                                   e.g. _include, _revinclude; carried for the caller
 */
final case class FhirResultControls(
    sorting: List[FhirSortInstruction],
    summary: Option[FhirElementProjection],
    elements: Set[String],
    pageSize: Int,
    pagination: FhirPaginationInstruction,
    includeTotal: Boolean,
    unresolvedResultParameters: List[Parameter])

/**
 * One resolved _sort entry.
 *
 * @param paramName           Name of the search parameter to sort on
 * @param descending          Whether the sort direction is descending
 * @param pathsAndTargetTypes Alternative element paths and their target FHIR data types
 *                            e.g. Seq(effectiveDateTime -> dateTime, effectivePeriod -> Period)
 */
final case class FhirSortInstruction(
    paramName: String,
    descending: Boolean,
    pathsAndTargetTypes: Seq[(String, String)])

/**
 * A projection over the elements of the returned resources.
 *
 * Note that _summary=count is represented by an inclusive projection over an
 * empty element set; no resource content is returned for it.
 *
 * @param include  Whether the given elements are the ones to include or to exclude
 * @param elements Element names the projection is about
 */
final case class FhirElementProjection(include: Boolean, elements: Set[String])

/**
 * Requested position within the result set; either a page number or a cursor.
 */
sealed trait FhirPaginationInstruction

object FhirPaginationInstruction {

  /**
   * Numbered page pagination.
   *
   * @param page Requested 1-based page number
   */
  final case class ByPage(page: Int) extends FhirPaginationInstruction

  /**
   * Cursor (offset) based pagination.
   *
   * @param values  Cursor values given by _searchafter or _searchbefore
   * @param forward True for _searchafter, false for _searchbefore
   */
  final case class ByCursor(values: Seq[String], forward: Boolean) extends FhirPaginationInstruction
}

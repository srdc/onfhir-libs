package io.onfhir.api.validation

import io.onfhir.api.Resource
import io.onfhir.api.model.{FhirCanonicalReference, FhirLiteralReference, FhirLogicalReference}

import scala.concurrent.Future

/**
 * Resolves FHIR references that cannot be resolved from the current resource
 * or its enclosing Bundle.
 *
 * Implementations are shared by validation runs and must therefore be safe
 * for concurrent use. A successful [[Future]] containing [[None]] means that
 * the referenced resource was not found. Transport, authorization, timeout,
 * and other infrastructure failures must be represented by a failed future.
 */
trait IExternalFhirReferenceResolver {
  /** Resolve a literal (relative or absolute) resource reference. */
  def resolveLiteral(reference: FhirLiteralReference): Future[Option[Resource]]

  /**
   * Resolve the canonical resource itself. The context-aware resolver handles
   * an optional canonical fragment after this call completes.
   */
  def resolveCanonical(reference: FhirCanonicalReference): Future[Option[Resource]]

  /** Resolve a logical reference identified by resource type and/or identifier. */
  def resolveLogical(reference: FhirLogicalReference): Future[Option[Resource]]
}

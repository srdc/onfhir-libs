package io.onfhir.api.validation

import io.onfhir.api.Resource
import io.onfhir.api.model.{FhirCanonicalReference, FhirLiteralReference, FhirLogicalReference}
import io.onfhir.config.BaseFhirConfig

import scala.concurrent.{ExecutionContext, Future}

/**
 * Context-aware reference resolver used by standalone consumers.
 *
 * Contained and Bundle references are resolved by [[AbstractReferenceResolver]].
 * This class delegates only unresolved literal, canonical, and logical
 * references to an optional context-free external resolver.
 */
final class DefaultReferenceResolver(
  resource: Resource,
  bundle: Option[(Option[String], Resource)] = None,
  fhirConfig: Option[BaseFhirConfig] = None,
  externalResolver: Option[IExternalFhirReferenceResolver] = None,
  fhirRootUrl: String = "http://onfhir.io/fhir"
)(implicit ec: ExecutionContext)
  extends AbstractReferenceResolver(resource, bundle, fhirConfig, fhirRootUrl) {

  override protected def getResource(
    serverUrl: Option[String],
    rtype: String,
    rid: String,
    version: Option[String]
  ): Future[Option[Resource]] =
    externalResolver
      .map(_.resolveLiteral(FhirLiteralReference(serverUrl, rtype, rid, version)))
      .getOrElse(Future.successful(None))

  override protected def getResourceByCanonicalUrl(
    url: String,
    rtype: String,
    rid: String,
    version: Option[String]
  ): Future[Option[Resource]] =
    externalResolver
      .map(_.resolveCanonical(FhirCanonicalReference(url, rtype, rid, version, None)))
      .getOrElse(Future.successful(None))

  override protected def getResourceByIdentifier(
    rtype: String,
    system: Option[String],
    value: String
  ): Future[Option[Resource]] =
    externalResolver
      .map(_.resolveLogical(FhirLogicalReference(Some(rtype), system, value)))
      .getOrElse(Future.successful(None))

  override protected def getResourceByLogicalReference(
    reference: FhirLogicalReference
  ): Future[Option[Resource]] =
    externalResolver
      .map(_.resolveLogical(reference))
      .getOrElse(Future.successful(None))

  override protected def isResourceExist(
    serverUrl: Option[String],
    rtype: String,
    rid: String
  ): Future[Boolean] =
    getResource(serverUrl, rtype, rid, None).map(_.isDefined)

  override protected def isResourceExistByCanonicalUrl(
    url: String,
    rtype: String,
    rid: String,
    version: Option[String]
  ): Future[Boolean] =
    getResourceByCanonicalUrl(url, rtype, rid, version).map(_.isDefined)
}

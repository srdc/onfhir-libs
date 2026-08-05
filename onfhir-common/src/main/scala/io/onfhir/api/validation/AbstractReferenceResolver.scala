package io.onfhir.api.validation

import io.onfhir.api.Resource
import io.onfhir.api.model.{FhirCanonicalReference, FhirInternalReference, FhirLiteralReference, FhirLogicalReference, FhirReference, FhirUUIDReference}
import io.onfhir.api.util.FHIRUtil
import io.onfhir.config.BaseFhirConfig
import org.json4s.JsonAST.{JArray, JObject}

import scala.concurrent.{ExecutionContext, Future}
import io.onfhir.util.JsonFormatter._

/**
 * Abstract class to resolve a FHIR reference within OnFhir platform
 * e.g.
 *    - a reference to a local resource in repository,
 *    - a reference to a contained resource,
 *    - a reference to a resource in the bundle that contain the resource,
 *    - a reference to a remote resource located in another repository
 *
 * @param resource    Resource itself that FHIR reference is in
 * @param bundle      If resource is a part of a FHIR Bundle, the entry fullUrl of the resource and bundle content itself
 * @param fhirConfig  Base FHIR configuration for the platform
 * @param fhirRootUrl Root url for the FHIR server (Used reference resolving within bundles)
 */
abstract class AbstractReferenceResolver(val resource: Resource,
                                         val bundle:Option[(Option[String], Resource)] = None,
                                         fhirConfig:Option[BaseFhirConfig] = None,
                                         fhirRootUrl:String = "http://onfhir.io/fhir")(implicit ec:ExecutionContext)
  extends IReferenceResolver {

  // Resource's root url given in fullUrl in the Bundle.entry
  private val resourceRootUrl:Option[String] =
    bundle
      .flatMap(_._1) //If there is a fullUrl given for the resource
      .flatMap(
        FHIRUtil
          .parseRestfullFhirResourceUrl(_)  //Try to parse it to get root url
          .flatMap(_._1)
      )
  /**
   * Extract bundle entries (their fullUrls and resource themselves)
   */
  private val bundleItems:Option[Seq[(Option[String], JObject)]] =
    bundle
      .flatMap(b =>
        b._2 \ "entry" match {
          case JArray(arr) =>
            Some(arr
              .map(e =>
                (e \ "fullUrl")
                  .extractOpt[String]
                  .orElse(
                    (e \ "resource" \ "id")
                      .extractOpt[String]
                      .map(rid => s"${fhirRootUrl}/${(e \ "resource" \ "resourceType").extract[String]}/$rid")
                  ) -> //Extract fullUrl from Bundle
                    (e \ "resource").asInstanceOf[JObject])
            )
          case _ =>
            None
       })

  /**
   * Get the resource given with rtype, rid and optionall version id
   * @param serverUrl   FHIR server url to retrieve (if none it means local onFhir repository)
   * @param rtype       Resource type
   * @param rid         Resource identifier
   * @param version     Version of the resource
   * @return
   */
  protected def getResource(serverUrl:Option[String], rtype:String, rid:String,  version:Option[String] = None):Future[Option[Resource]]

  /**
   * Get the resource by given canonical url parts
   * @param url       Root part of canonical url
   * @param rtype     Resource type
   * @param rid       Resource identifier
   * @param version   Business version of the resource
   * @return
   */
  protected def getResourceByCanonicalUrl(url:String, rtype:String, rid:String, version:Option[String]):Future[Option[Resource]]

  /**
   * Get the resource by given FHIR Identifier
   * @param rtype     Resource type
   * @param system    System of identifier
   * @param value     Value of identifier
   * @return
   */
  protected def getResourceByIdentifier(rtype:String, system:Option[String], value:String):Future[Option[Resource]]

  /**
   * Check if the resource given with rtype, rid and optionall version id exists
   * @param serverUrl   FHIR server url to retrieve (if none it means local onFhir repository)
   * @param rtype       Resource type
   * @param rid         Resource identifier
   * @return
   */
  protected def isResourceExist(serverUrl:Option[String], rtype:String, rid:String):Future[Boolean]

  /**
   * Check if the resource given with canonical url exists
   * @param url       Root part of canonical url
   * @param rtype     Resource type
   * @param rid       Resource id
   * @param version   Business version
   * @return
   */
  protected def isResourceExistByCanonicalUrl(url:String, rtype:String, rid:String, version:Option[String]):Future[Boolean]

  /**
   * Resolve a logical reference. The default implementation retains the
   * historic identifier lookup for typed references. Context-free external
   * resolvers may override this hook to support type-less logical references.
   */
  protected def getResourceByLogicalReference(reference:FhirLogicalReference):Future[Option[Resource]] =
    reference.rtype match {
      case Some(rtype) if fhirConfig.forall(_.FHIR_RESOURCE_TYPES.contains(rtype)) =>
        getResourceByIdentifier(rtype, reference.system, reference.identifier)
      case _ =>
        Future.successful(None)
    }

  /**
   * Resolve a FHIR reference within onFhir
   *
   * @param reference FHIR  reference
   * @return
   */
  override def resolveReference(reference: FhirReference): Future[Option[Resource]] = {
    reference match {
      //Normal literal reference
      case FhirLiteralReference(url, rtype, rid, version) =>
        checkWithinBundle(reference)  //First check if resource exists within the bundle (if resource is within a bundle)
          .flatMap {
            case None => getResource(url, rtype, rid, version)
            case Some(found) => Future.apply(Some(found))
          }
      //Canonical reference
      case FhirCanonicalReference(url, rtype, rid, version, fragment)  =>
       getResourceByCanonicalUrl(url, rtype,rid, version)
          .map(_.flatMap(resource =>
            fragment
              .map(f => getContainedResource(resource, f))  //If there is a fragment part, get from the contained resource
              .getOrElse(Some(resource))
          ))

      //Internal reference to contained resource
      case FhirInternalReference(ref) =>
        Future.apply(getContainedResource(resource, ref))

      //Logical references may be delegated to a specialised implementation.
      case logicalReference:FhirLogicalReference =>
        getResourceByLogicalReference(logicalReference)

      //If it is a uuid reference within bundle
      case FhirUUIDReference(_) =>
        checkWithinBundle(reference)

      //Otherwise none
      case _ => Future.apply(None)
    }
  }

  /**
   * Check a reference within the bundle if this resource is given in a bundle
   * @param reference FHIR Reference
   * @return
   */
  private def checkWithinBundle(reference:FhirReference):Future[Option[Resource]] = {
    Future.apply(
      bundleItems.flatMap(bitems =>
        reference match {
          //If literal reference, check if there is a entry with a fullUrl
          case FhirLiteralReference(url, rtype, rid, version) =>
            bitems
              .find(bitem =>
                bitem._1
                  .flatMap(FHIRUtil.parseRestfullFhirResourceUrl).exists {
                    case (iurl, irtype, irid, iversion) =>
                      irtype == rtype &&
                        irid == rid &&
                        iurl.getOrElse(fhirRootUrl) == url.orElse(resourceRootUrl).getOrElse(fhirRootUrl) &&
                          (version == iversion || version.forall(v => FHIRUtil.extractVersionFromResource(bitem._2).toString == v))
                }
              )
              .map(_._2)

          case FhirUUIDReference(uuid) =>
            bitems
              .find(_._1.contains(uuid))
              .map(_._2)
          case _ => None
        }
      )
    )
  }

  /**
   * Find contained resource
   * @param resource  Resource content
   * @param id        Identifier of the contained resource to search
   * @return
   */
  private def getContainedResource(resource:Resource, id:String):Option[Resource] = {
    resource \ "contained" match {
      case JArray(arr) => arr.map(_.asInstanceOf[JObject]).find(r => FHIRUtil.extractIdFromResource(r) == id)
      case _ => None
    }
  }

  /**
   * Check if a referenced resource exist
   *
   * @param reference FHIR  reference
   * @param profiles  Profiles that resource is expected to conform
   */
  override def isReferencedResourceExist(reference: FhirReference, profiles: Set[String]): Future[Boolean] = {
    reference match {
      //Normal literal reference without version/profile checks can use a cheap existence lookup.
      case literalReference @ FhirLiteralReference(url, rtype, rid, version) if version.isEmpty && profiles.isEmpty =>
        checkWithinBundle(literalReference)
          .flatMap {
            case None =>
              isResourceExist(url, rtype, rid)
            case Some(_) => Future.apply(true)
          }
      //Canonical references without a fragment/profile check can also use a cheap existence lookup.
      case FhirCanonicalReference(url, rtype, rid, version, None) if profiles.isEmpty =>
        isResourceExistByCanonicalUrl(url, rtype, rid, version)
      //Every other reference kind, and every profile-constrained reference, needs its resolved content.
      case _ =>
        resolveReference(reference).map {
          case None => false
          case Some(referencedResource) =>
            profiles.isEmpty ||
              FHIRUtil.extractProfilesFromBson(referencedResource).intersect(profiles).nonEmpty
              //TODO Or referenced resource is valid against one of the given profile
        }
    }
  }
}

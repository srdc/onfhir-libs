package io.onfhir.validation

import io.onfhir.api.Resource
import io.onfhir.api.model.FhirReference
import io.onfhir.api.validation.{ElementRestrictions, FhirRestriction, FhirSlicing, IReferenceResolver, ProfileRestrictions, ValueSetRestrictions}
import io.onfhir.config.BaseFhirConfig
import org.json4s.JsonAST.JObject
import org.json4s.jackson.JsonMethods.parse

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

/**
 * Compact, release-neutral fixtures for validation tests.
 *
 * The validation module consumes parsed profile restrictions. Keeping those
 * restrictions in code makes its unit tests independent of a FHIR release
 * package, release-specific parser, and server runtime.
 */
object ValidationTestFixtures {
  val TestProfileUrl = "http://example.org/fhir/StructureDefinition/TestResource"
  val LatestVersion = "latest"

  def config(
      profiles: Seq[ProfileRestrictions],
      valueSets: Map[String, Map[String, ValueSetRestrictions]] = Map.empty,
      resourceTypes: Set[String] = Set("TestResource"),
      complexTypes: Set[String] = Set.empty,
      primitiveTypes: Set[String] = Set("boolean", "code", "date", "dateTime", "decimal", "id", "integer", "string", "time", "uri")
  ): BaseFhirConfig = {
    val fhirConfig = new BaseFhirConfig("test")
    fhirConfig.fhirVersion = "test"
    fhirConfig.profileRestrictions = profiles
      .groupBy(_.url)
      .view
      .mapValues(_.map(profile => profile.version.getOrElse(LatestVersion) -> profile).toMap)
      .toMap
    fhirConfig.valueSetRestrictions = valueSets
    fhirConfig.FHIR_RESOURCE_TYPES = resourceTypes
    fhirConfig.FHIR_COMPLEX_TYPES = complexTypes
    fhirConfig.FHIR_PRIMITIVE_TYPES = primitiveTypes
    fhirConfig
  }

  def profile(
      url: String = TestProfileUrl,
      resourceType: String = "TestResource",
      version: Option[String] = None,
      baseUrl: Option[(String, Option[String])] = None,
      elementRestrictions: Seq[(String, ElementRestrictions)] = Nil,
      constraints: Option[FhirRestriction] = None,
      isAbstract: Boolean = false
  ): ProfileRestrictions =
    ProfileRestrictions(
      url = url,
      version = version,
      id = None,
      baseUrl = baseUrl,
      resourceType = resourceType,
      resourceName = None,
      resourceDescription = None,
      elementRestrictions = elementRestrictions,
      summaryElements = Set.empty,
      constraints = constraints,
      isAbstract = isAbstract
    )

  def element(
      path: String,
      restrictions: Map[Int, FhirRestriction] = Map.empty,
      slicing: Option[FhirSlicing] = None,
      sliceName: Option[String] = None,
      contentReference: Option[String] = None,
      profileDefinedIn: String = TestProfileUrl
  ): (String, ElementRestrictions) =
    path -> ElementRestrictions(
      path = path,
      restrictions = restrictions,
      slicing = slicing,
      sliceName = sliceName,
      contentReference = contentReference,
      profileDefinedIn = profileDefinedIn
    )

  def resource(json: String): JObject = parse(json).asInstanceOf[JObject]

  def awaitResult[T](future: Future[T]): T = Await.result(future, 3.seconds)

  final class TestReferenceResolver(
      exists: (FhirReference, Set[String]) => Boolean = (_, _) => true,
      resolved: FhirReference => Option[Resource] = _ => None
  ) extends IReferenceResolver {
    override val resource: Resource = JObject()
    override val bundle: Option[(Option[String], Resource)] = None

    override def resolveReference(reference: FhirReference): Future[Option[Resource]] = Future.successful(resolved(reference))

    override def isReferencedResourceExist(reference: FhirReference, profiles: Set[String]): Future[Boolean] =
      Future.successful(exists(reference, profiles))
  }
}

package io.onfhir.validation

import io.onfhir.api.{FHIR_ROOT_URL_FOR_DEFINITIONS, Resource}
import io.onfhir.api.model.FHIRResponse.{OUTCOME_CODES, SEVERITY_CODES}
import io.onfhir.api.model.OutcomeIssue
import io.onfhir.api.service.IFhirTerminologyService
import io.onfhir.api.util.FHIRUtil
import io.onfhir.api.validation.{DefaultReferenceResolver, IExternalFhirReferenceResolver, IFhirResourceValidator, IFhirTerminologyValidator, ProfileRestrictions}
import io.onfhir.config.{BaseFhirConfig, TerminologyServiceConf}
import org.json4s.JsonAST.{JArray, JObject, JString}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Release-neutral SDK facade for validating FHIR resources against a populated
  * [[BaseFhirConfig]]. It combines configured local ValueSets with optional
  * external terminology services and creates a fresh content validator for
  * each validation run.
  *
  * @param fhirConfig populated release-specific FHIR configuration
  * @param externalTerminologyServices terminology services consulted before local ValueSets
  * @param externalReferenceResolver optional resolver for references outside the current resource and Bundle
  */
final class FhirValidator(
                            val fhirConfig: BaseFhirConfig,
                            externalTerminologyServices: Seq[(TerminologyServiceConf, IFhirTerminologyService)] = Nil,
                            externalReferenceResolver: Option[IExternalFhirReferenceResolver] = None
                          )(implicit executionContext: ExecutionContext) {
  import FhirValidator._

  require(fhirConfig != null, "fhirConfig must not be null")
  require(fhirConfig.profileRestrictions != null, "fhirConfig.profileRestrictions must be populated")
  require(fhirConfig.FHIR_RESOURCE_TYPES != null, "fhirConfig.FHIR_RESOURCE_TYPES must be populated")
  require(fhirConfig.FHIR_COMPLEX_TYPES != null, "fhirConfig.FHIR_COMPLEX_TYPES must be populated")
  require(fhirConfig.FHIR_PRIMITIVE_TYPES != null, "fhirConfig.FHIR_PRIMITIVE_TYPES must be populated")

  private val configuredTerminologyServices = externalTerminologyServices.toVector

  /** Terminology validator combining external services and local ValueSets. */
  val terminologyValidator: IFhirTerminologyValidator =
    FhirTerminologyValidator(fhirConfig, configuredTerminologyServices)

  /**
    * Validate against the resource's base profile and every known profile it
    * claims in meta.profile. Unknown claimed profiles are returned as warnings.
    */
  def validateResource(resource: Resource): Future[Seq[OutcomeIssue]] =
    extractResourceType(resource) match {
      case Some(resourceType) => validateResourceInternal(resource, resourceType, None, None)
      case None => Future.successful(Seq(invalidIssue("FHIR resourceType is missing or is not a non-empty string.", "resourceType")))
    }

  /** Validate against one explicitly selected canonical profile. */
  def validateResourceAgainstProfile(resource: Resource, profile: String): Future[Seq[OutcomeIssue]] =
    validateResourceAgainstProfiles(resource, Seq(profile))

  /**
    * Validate against all independent explicitly selected canonical profiles.
    * A base profile is not evaluated again when a selected derived profile
    * already includes it in its profile chain.
    */
  def validateResourceAgainstProfiles(resource: Resource, profiles: Seq[String]): Future[Seq[OutcomeIssue]] =
    extractResourceType(resource) match {
      case Some(resourceType) => validateProfilesInternal(resource, resourceType, profiles, None, None)
      case None => Future.successful(Seq(invalidIssue("FHIR resourceType is missing or is not a non-empty string.", "resourceType")))
    }

  /**
    * Private compatibility bridge used by FhirContentValidator for recursive
    * contained resources and Bundle entries. It deliberately always returns
    * OutcomeIssues rather than exposing server-oriented exception behaviour.
    */
  private val recursiveResourceValidator: IFhirResourceValidator = new IFhirResourceValidator {
    override def validateResource(resource: Resource,
                                  rtype: String,
                                  parentPath: Option[String],
                                  bundle: Option[(Option[String], Resource)],
                                  silent: Boolean): Future[Seq[OutcomeIssue]] =
      validateResourceInternal(resource, rtype, parentPath, bundle)

    override def validateResourceAgainstProfile(resource: Resource,
                                                rtype: String,
                                                profile: Option[String],
                                                parentPath: Option[String],
                                                bundle: Option[(Option[String], Resource)],
                                                silent: Boolean): Future[Seq[OutcomeIssue]] =
      profile
        .map(p => validateProfilesInternal(resource, rtype, Seq(p), parentPath, bundle))
        .getOrElse(validateResourceInternal(resource, rtype, parentPath, bundle))

    override def getTerminologyValidator(): Option[IFhirTerminologyValidator] =
      Some(FhirValidator.this.terminologyValidator)
  }

  private def validateResourceInternal(resource: Resource,
                                       resourceType: String,
                                       parentPath: Option[String],
                                       bundle: Option[(Option[String], Resource)]): Future[Seq[OutcomeIssue]] = {
    val baseProfile = baseProfileCanonical(resourceType)
    val claimedProfiles = extractClaimedProfiles(resource).map(normalizeBaseProfileVersion(_, resourceType))
    val (knownProfiles, unknownProfileIssues) = claimedProfiles.foldLeft((Vector.empty[String], Vector.empty[OutcomeIssue])) {
      case ((known, issues), profile) if fhirConfig.findProfileChainByCanonical(profile).nonEmpty =>
        (known :+ profile) -> issues
      case ((known, issues), profile) =>
        known -> (issues :+ warningIssue(
          s"Profile with url '$profile' is not known to this validator. Validation is skipped for this profile.",
          expressionAt(parentPath, "meta.profile")
        ))
    }

    validateProfilesInternal(resource, resourceType, baseProfile +: knownProfiles, parentPath, bundle)
      .map(unknownProfileIssues ++ _)
  }

  private def validateProfilesInternal(resource: Resource,
                                       resourceType: String,
                                       profiles: Seq[String],
                                       parentPath: Option[String],
                                       bundle: Option[(Option[String], Resource)]): Future[Seq[OutcomeIssue]] = {
    if (profiles.isEmpty)
      Future.successful(Seq(invalidIssue("At least one profile must be supplied for profile-based validation.", expressionAt(parentPath, "resourceType"))))
    else if (!fhirConfig.FHIR_RESOURCE_TYPES.contains(resourceType))
      Future.successful(Seq(notSupportedIssue(s"Resource type '$resourceType' is not configured for validation.", Seq(expressionAt(parentPath, "resourceType")))))
    else {
      val (resolvedProfiles, profileIssues) = profiles.distinct.foldLeft((Vector.empty[ResolvedProfile], Vector.empty[OutcomeIssue])) {
        case ((resolved, issues), profileCanonical) =>
          val chain = fhirConfig.findProfileChainByCanonical(profileCanonical)
          if (chain.isEmpty)
            resolved -> (issues :+ notSupportedIssue(
              s"Profile with url '$profileCanonical' is not known to this validator.",
              parentPath.toSeq
            ))
          else if (profileResourceType(chain).exists(_ != resourceType))
            resolved -> (issues :+ invalidIssue(
              s"Profile '$profileCanonical' targets resource type '${profileResourceType(chain).get}', not '$resourceType'.",
              expressionAt(parentPath, "resourceType")
            ))
          else
            (resolved :+ new ResolvedProfile(profileCanonical, chain)) -> issues
      }

      Future.sequence(independentProfiles(resolvedProfiles).map(validateProfile(resource, _, parentPath, bundle)))
        .map(profileIssues ++ _.flatten)
    }
  }

  private def validateProfile(resource: Resource,
                              resolvedProfile: ResolvedProfile,
                              parentPath: Option[String],
                              bundle: Option[(Option[String], Resource)]): Future[Seq[OutcomeIssue]] = {
    val referenceResolver = new DefaultReferenceResolver(
      resource = resource,
      bundle = bundle,
      fhirConfig = Some(fhirConfig),
      externalResolver = externalReferenceResolver
    )
    val contentValidator = new FhirContentValidator(
      fhirConfig,
      resolvedProfile.canonical,
      Some(referenceResolver),
      Some(recursiveResourceValidator),
      None
    )
    contentValidator
      .validateComplexContent(resource, parentPath)
      .map(_.map(issue => issue.copy(
        diagnostics = Some(s"[Validating against '${resolvedProfile.canonical}'] => ${issue.diagnostics.getOrElse("")}")
      )))
  }

  private def independentProfiles(profiles: Seq[ResolvedProfile]): Seq[ResolvedProfile] =
    profiles.filterNot(profile =>
      profiles.exists(other =>
        (other ne profile) && other.chain.tail.exists(parent => profile.identity == (parent.url -> parent.version))
      )
    )

  private def extractResourceType(resource: Resource): Option[String] =
    (resource \ "resourceType") match {
      case JString(resourceType) if resourceType.nonEmpty => Some(resourceType)
      case _ => None
    }

  private def extractClaimedProfiles(resource: Resource): Seq[String] =
    (resource \ "meta" \ "profile") match {
      case JArray(profiles) => profiles.collect { case JString(profile) if profile.nonEmpty => profile }.distinct
      case _ => Nil
    }

  private def normalizeBaseProfileVersion(profile: String, resourceType: String): String = {
    val (url, version) = FHIRUtil.parseCanonicalValue(profile)
    if (url == baseProfileCanonical(resourceType) && version.contains(fhirConfig.fhirVersion)) url else profile
  }

  private def baseProfileCanonical(resourceType: String): String =
    s"$FHIR_ROOT_URL_FOR_DEFINITIONS/StructureDefinition/$resourceType"

  private def profileResourceType(chain: Seq[ProfileRestrictions]): Option[String] =
    chain.findLast(!_.isAbstract).map(_.url.split('/').last)

  private def expressionAt(parentPath: Option[String], field: String): String =
    parentPath.map(path => s"$path.$field").getOrElse(field)

  private def invalidIssue(diagnostics: String, expression: String): OutcomeIssue =
    OutcomeIssue(SEVERITY_CODES.ERROR, OUTCOME_CODES.INVALID, None, Some(diagnostics), Seq(expression))

  private def notSupportedIssue(diagnostics: String, expression: Seq[String]): OutcomeIssue =
    OutcomeIssue(SEVERITY_CODES.ERROR, OUTCOME_CODES.NOT_SUPPORTED, None, Some(diagnostics), expression)

  private def warningIssue(diagnostics: String, expression: String): OutcomeIssue =
    OutcomeIssue(SEVERITY_CODES.WARNING, OUTCOME_CODES.NOT_SUPPORTED, None, Some(diagnostics), Seq(expression))

  private final class ResolvedProfile(val canonical: String, val chain: Seq[ProfileRestrictions]) {
    val identity: (String, Option[String]) = chain.head.url -> chain.head.version
  }
}

object FhirValidator {
  def apply(fhirConfig: BaseFhirConfig,
            externalTerminologyServices: Seq[(TerminologyServiceConf, IFhirTerminologyService)] = Nil,
            externalReferenceResolver: Option[IExternalFhirReferenceResolver] = None)(implicit executionContext: ExecutionContext): FhirValidator =
    new FhirValidator(fhirConfig, externalTerminologyServices, externalReferenceResolver)
}

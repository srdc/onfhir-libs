package io.onfhir.config

import com.typesafe.config.Config
import io.onfhir.exception.InitializationException

import scala.jdk.CollectionConverters._

/** Library-safe endpoint settings. The URL is intentionally kept as a String until Phase 2. */
final case class FhirEndpointSettings(rootUrl: String) {
  if (rootUrl.trim.isEmpty)
    throw new InitializationException("FHIR root URL cannot be empty")
}

final case class FhirRequestDefaults(
    searchHandling: FhirSearchHandling,
    returnPreference: FhirReturnPreference)

object FhirRequestDefaults {
  val Standard: FhirRequestDefaults = FhirRequestDefaults(
    FhirSearchHandling.Strict,
    FhirReturnPreference.Representation)

  /**
   * Build the request defaults from the `fhir.default` subtree.
   *
   * Reads the relative keys `search-handling` and `return-preference`. Both are optional and
   * fall back to [[Standard]]. Values may be written either as the bare token (`strict`,
   * `representation`, canonical in configuration) or as the full header code
   * (`handling=strict`, `return=representation`).
   *
   * @param config the already-scoped `fhir.default` subtree
   */
  def fromConfig(config: Config): FhirRequestDefaults = FhirRequestDefaults(
    searchHandling =
      if (config.hasPath("search-handling")) FhirSearchHandling.fromConfigValue(config.getString("search-handling"))
      else Standard.searchHandling,
    returnPreference =
      if (config.hasPath("return-preference")) FhirReturnPreference.fromConfigValue(config.getString("return-preference"))
      else Standard.returnPreference)
}

final case class FhirResultDefaults(
    defaultPageSize: Int,
    paginationMode: FhirPaginationMode,
    totalHandling: FhirSearchTotalHandling) {
  if (defaultPageSize < 0)
    throw new InitializationException("FHIR default page size cannot be negative")
}

object FhirResultDefaults {
  val Standard: FhirResultDefaults = FhirResultDefaults(
    defaultPageSize = 50,
    FhirPaginationMode.Page,
    FhirSearchTotalHandling.Accurate)

  /**
   * Build the result defaults from the `fhir.default` subtree.
   *
   * Reads the relative keys `page-count`, `pagination` and `search-total`. All are optional and
   * fall back to [[Standard]].
   *
   * @param config the already-scoped `fhir.default` subtree
   */
  def fromConfig(config: Config): FhirResultDefaults = FhirResultDefaults(
    defaultPageSize =
      if (config.hasPath("page-count")) config.getInt("page-count")
      else Standard.defaultPageSize,
    paginationMode =
      if (config.hasPath("pagination")) FhirPaginationMode.fromCode(config.getString("pagination"))
      else Standard.paginationMode,
    totalHandling =
      if (config.hasPath("search-total")) FhirSearchTotalHandling.fromCode(config.getString("search-total"))
      else Standard.totalHandling)
}

final case class FhirSubscriptionSettings(
    active: Boolean,
    allowedResources: Option[Set[String]])

object FhirSubscriptionSettings {
  val Standard: FhirSubscriptionSettings = FhirSubscriptionSettings(
    active = false,
    allowedResources = None)

  /**
   * Build the subscription settings from the `fhir.subscription` subtree.
   *
   * Reads the relative keys `active` and `allowed-resources`. Both are optional and fall back to
   * [[Standard]]. An absent `allowed-resources` is `None`, which means "no restriction" and is
   * not the same as a configured empty list.
   *
   * @param config the already-scoped `fhir.subscription` subtree
   */
  def fromConfig(config: Config): FhirSubscriptionSettings = FhirSubscriptionSettings(
    active =
      if (config.hasPath("active")) config.getBoolean("active")
      else Standard.active,
    allowedResources =
      if (config.hasPath("allowed-resources")) Some(config.getStringList("allowed-resources").asScala.toSet)
      else Standard.allowedResources)
}

final case class FhirCapabilityDefaults(
    versioning: FhirVersioningPolicy,
    readHistory: Boolean,
    updateCreate: Boolean,
    conditionalCreate: Boolean,
    conditionalRead: FhirConditionalReadSupport,
    conditionalUpdate: Boolean,
    conditionalDelete: FhirConditionalDeleteSupport)

object FhirCapabilityDefaults {
  val Standard: FhirCapabilityDefaults = FhirCapabilityDefaults(
    FhirVersioningPolicy.Versioned,
    readHistory = false,
    updateCreate = false,
    conditionalCreate = false,
    FhirConditionalReadSupport.FullSupport,
    conditionalUpdate = false,
    FhirConditionalDeleteSupport.NotSupported)

  /**
   * Build the capability defaults from the `fhir.default` subtree.
   *
   * Reads the relative keys `versioning`, `read-history`, `update-create`, `conditional-create`,
   * `conditional-read`, `conditional-update` and `conditional-delete`. All are optional and fall
   * back to [[Standard]].
   *
   * @param config the already-scoped `fhir.default` subtree
   */
  def fromConfig(config: Config): FhirCapabilityDefaults = FhirCapabilityDefaults(
    versioning =
      if (config.hasPath("versioning")) FhirVersioningPolicy.fromCode(config.getString("versioning"))
      else Standard.versioning,
    readHistory =
      if (config.hasPath("read-history")) config.getBoolean("read-history")
      else Standard.readHistory,
    updateCreate =
      if (config.hasPath("update-create")) config.getBoolean("update-create")
      else Standard.updateCreate,
    conditionalCreate =
      if (config.hasPath("conditional-create")) config.getBoolean("conditional-create")
      else Standard.conditionalCreate,
    conditionalRead =
      if (config.hasPath("conditional-read")) FhirConditionalReadSupport.fromCode(config.getString("conditional-read"))
      else Standard.conditionalRead,
    conditionalUpdate =
      if (config.hasPath("conditional-update")) config.getBoolean("conditional-update")
      else Standard.conditionalUpdate,
    conditionalDelete =
      if (config.hasPath("conditional-delete")) FhirConditionalDeleteSupport.fromCode(config.getString("conditional-delete"))
      else Standard.conditionalDelete)
}

sealed trait FhirSearchHandling { def code: String }
object FhirSearchHandling {
  case object Strict extends FhirSearchHandling { val code = "handling=strict" }
  case object Lenient extends FhirSearchHandling { val code = "handling=lenient" }

  def fromCode(value: String): FhirSearchHandling = value match {
    case Strict.code => Strict
    case Lenient.code => Lenient
    case other => FhirRuntimeSettingsValidation.invalid("FHIR search handling", other, Seq(Strict.code, Lenient.code))
  }

  /**
   * Accepts the bare token ("strict") or the full header code ("handling=strict").
   * The bare token is canonical in configuration; [[fromCode]] stays strict for the header form.
   */
  def fromConfigValue(value: String): FhirSearchHandling =
    fromCode(if (value.startsWith("handling=")) value else s"handling=$value")
}

sealed trait FhirReturnPreference { def code: String }
object FhirReturnPreference {
  case object Minimal extends FhirReturnPreference { val code = "return=minimal" }
  case object Representation extends FhirReturnPreference { val code = "return=representation" }
  case object OperationOutcome extends FhirReturnPreference { val code = "return=OperationOutcome" }

  def fromCode(value: String): FhirReturnPreference = value match {
    case Minimal.code => Minimal
    case Representation.code => Representation
    case OperationOutcome.code => OperationOutcome
    case other => FhirRuntimeSettingsValidation.invalid("FHIR return preference", other, Seq(Minimal.code, Representation.code, OperationOutcome.code))
  }

  /**
   * Accepts the bare token ("representation") or the full header code ("return=representation").
   * The bare token is canonical in configuration; [[fromCode]] stays strict for the header form.
   */
  def fromConfigValue(value: String): FhirReturnPreference =
    fromCode(if (value.startsWith("return=")) value else s"return=$value")
}

sealed trait FhirPaginationMode { def code: String }
object FhirPaginationMode {
  case object Page extends FhirPaginationMode { val code = "page" }
  case object Offset extends FhirPaginationMode { val code = "offset" }

  def fromCode(value: String): FhirPaginationMode = value match {
    case Page.code => Page
    case Offset.code => Offset
    case other => FhirRuntimeSettingsValidation.invalid("FHIR pagination mode", other, Seq(Page.code, Offset.code))
  }
}

sealed trait FhirSearchTotalHandling { def code: String }
object FhirSearchTotalHandling {
  case object None extends FhirSearchTotalHandling { val code = "none" }
  case object Estimate extends FhirSearchTotalHandling { val code = "estimate" }
  case object Accurate extends FhirSearchTotalHandling { val code = "accurate" }

  def fromCode(value: String): FhirSearchTotalHandling = value match {
    case None.code => None
    case Estimate.code => Estimate
    case Accurate.code => Accurate
    case other => FhirRuntimeSettingsValidation.invalid("FHIR search total handling", other, Seq(None.code, Estimate.code, Accurate.code))
  }
}

sealed trait FhirVersioningPolicy { def code: String }
object FhirVersioningPolicy {
  case object NoVersion extends FhirVersioningPolicy { val code = "no-version" }
  case object Versioned extends FhirVersioningPolicy { val code = "versioned" }
  case object VersionedUpdate extends FhirVersioningPolicy { val code = "versioned-update" }

  def fromCode(value: String): FhirVersioningPolicy = value match {
    case NoVersion.code => NoVersion
    case Versioned.code => Versioned
    case VersionedUpdate.code => VersionedUpdate
    case other => FhirRuntimeSettingsValidation.invalid("FHIR versioning policy", other, Seq(NoVersion.code, Versioned.code, VersionedUpdate.code))
  }
}

sealed trait FhirConditionalReadSupport { def code: String }
object FhirConditionalReadSupport {
  case object NotSupported extends FhirConditionalReadSupport { val code = "not-supported" }
  case object ModifiedSince extends FhirConditionalReadSupport { val code = "modified-since" }
  case object NotMatch extends FhirConditionalReadSupport { val code = "not-match" }
  case object FullSupport extends FhirConditionalReadSupport { val code = "full-support" }

  def fromCode(value: String): FhirConditionalReadSupport = value match {
    case NotSupported.code => NotSupported
    case ModifiedSince.code => ModifiedSince
    case NotMatch.code => NotMatch
    case FullSupport.code => FullSupport
    case other => FhirRuntimeSettingsValidation.invalid("FHIR conditional read support", other, Seq(NotSupported.code, ModifiedSince.code, NotMatch.code, FullSupport.code))
  }
}

sealed trait FhirConditionalDeleteSupport { def code: String }
object FhirConditionalDeleteSupport {
  case object NotSupported extends FhirConditionalDeleteSupport { val code = "not-supported" }
  case object Single extends FhirConditionalDeleteSupport { val code = "single" }
  case object Multiple extends FhirConditionalDeleteSupport { val code = "multiple" }

  def fromCode(value: String): FhirConditionalDeleteSupport = value match {
    case NotSupported.code => NotSupported
    case Single.code => Single
    case Multiple.code => Multiple
    case other => FhirRuntimeSettingsValidation.invalid("FHIR conditional delete support", other, Seq(NotSupported.code, Single.code, Multiple.code))
  }
}

private[config] object FhirRuntimeSettingsValidation {
  def invalid[A](setting: String, value: String, allowed: Seq[String]): A =
    throw new InitializationException(s"Invalid $setting '$value'. Allowed values: ${allowed.mkString(", ")}")
}

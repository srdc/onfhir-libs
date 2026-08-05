package io.onfhir.r4

import io.onfhir.api.Resource
import io.onfhir.api.parsers.IFhirFoundationResourceParser
import io.onfhir.config.{BaseFhirConfig, BaseFhirConfigurator, FHIRCapabilityStatement, FSConfigReader, FhirCapabilityDefaults}
import io.onfhir.r4.parsers.R4Parser
import io.onfhir.validation.FhirValidator
import org.json4s.JsonAST.JObject
import org.json4s.jackson.JsonMethods

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * Shared fixture for the onfhir-r4 integration suites.
 *
 * It builds ONE [[BaseFhirConfig]] from the real FHIR R4 4.0.1 standard package
 * supplied by the test-scope `onfhir-definitions-r4` artifact, and reuses it
 * across every suite in this module. Parsing the full package is expensive
 * (tens of seconds and a large heap), so everything here is a `lazy val` and
 * surefire is configured with a single reused fork.
 *
 * Two contracts are deliberately exercised by construction rather than by an
 * explicit assertion:
 *
 *  - `new FSConfigReader(fhirVersion = "R4")` is created with NO explicit
 *    paths, so the standard zip and base CapabilityStatement must resolve from
 *    their default CLASSPATH locations, which only the definitions artifact
 *    provides.
 *  - `BaseFhirConfigurator.initializePlatform` is the complete release-neutral
 *    pipeline; the R4-specific part supplied here is only the parser.
 */
object R4IntegrationFixtures {
  implicit val executionContext: ExecutionContext = ExecutionContext.global

  /** Generous timeout: a validation run may parse and evaluate large profiles. */
  val awaitTimeout: Duration = 120.seconds

  /**
   * Minimal concrete R4 configurator. There is no concrete configurator in
   * onfhir-libs (BaseFhirConfigurator is abstract and the release-specific
   * subclasses live in the server repository), so the test supplies one. It
   * adds nothing beyond the FHIR version and the parser choice.
   */
  private final class R4TestConfigurator extends BaseFhirConfigurator {
    override val fhirVersion: String = "R4"

    override def getFoundationResourceParser(complexTypes: Set[String],
                                             primitiveTypes: Set[String],
                                             capabilityDefaults: FhirCapabilityDefaults): IFhirFoundationResourceParser =
      new R4Parser(complexTypes, primitiveTypes, capabilityDefaults)
  }

  private val configurator = new R4TestConfigurator

  /** Reader with no explicit paths: everything resolves from the classpath. */
  lazy val configReader: FSConfigReader = new FSConfigReader(fhirVersion = "R4")

  /** The parsed R4 standard package. Built once per JVM. */
  lazy val fhirConfig: BaseFhirConfig = configurator.initializePlatform(configReader)

  /** Validator over the parsed standard package. */
  lazy val validator: FhirValidator = FhirValidator(fhirConfig)

  /** The base R4 CapabilityStatement parsed with the same R4 parser. */
  lazy val capabilityStatement: FHIRCapabilityStatement =
    configurator
      .getFoundationResourceParser(
        fhirConfig.FHIR_COMPLEX_TYPES,
        fhirConfig.FHIR_PRIMITIVE_TYPES,
        FhirCapabilityDefaults.Standard)
      .parseCapabilityStatement(configReader.readCapabilityStatement())

  /** Parse a JSON string into a FHIR resource. */
  def resource(json: String): Resource = JsonMethods.parse(json).asInstanceOf[JObject]

  /** Named `awaitResult`, not `await`, to avoid clashing with specs2's FutureMatchers.await. */
  def awaitResult[T](future: Future[T]): T = Await.result(future, awaitTimeout)
}

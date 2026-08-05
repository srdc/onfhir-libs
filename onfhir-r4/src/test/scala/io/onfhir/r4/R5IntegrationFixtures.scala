package io.onfhir.r4

import io.onfhir.api.FOUNDATION_RESOURCES_FILE_SUFFIX
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
 * Shared fixture for the R5 integration suites, which live in this module
 * because the onFHIR R5 server configurator reuses [[R4Parser]] for foundation
 * resources. These suites pin that reuse contract against the real FHIR R5
 * 5.0.0 package supplied by the test-scope `onfhir-definitions-r5` artifact.
 *
 * The private configurator mirrors Repofyr's `FhirR5Configurator`
 * (onfhir-server-r5) in the two respects that matter to the library layer:
 *
 *  - `fhirVersion = "R5"`, so `FSConfigReader` resolves
 *    `definitions-r5.json.zip` and `conformance-statement-r5.json` from their
 *    default classpath locations;
 *  - `VALUESET_AND_CODESYSTEM_BUNDLE_FILES` narrowed to `valuesets.json`,
 *    because the R5 core package no longer ships `v3-codesystems.json` or
 *    `v2-tables.json` (they moved to the separate HL7 terminology package) and
 *    `initializePlatform` throws on a missing bundle.
 *
 * Both definitions artifacts sit on this module's test classpath; the
 * per-release file names are what keep them from colliding.
 */
object R5IntegrationFixtures {
  implicit val executionContext: ExecutionContext = ExecutionContext.global

  /** Generous timeout: a validation run may parse and evaluate large profiles. */
  val awaitTimeout: Duration = 120.seconds

  /** Library-layer mirror of Repofyr's FhirR5Configurator. */
  private final class R5TestConfigurator extends BaseFhirConfigurator {
    override val fhirVersion: String = "R5"

    // The R5 core package ships only valuesets.json; v2/v3 code systems live in
    // the separate HL7 terminology (THO) package.
    override protected val VALUESET_AND_CODESYSTEM_BUNDLE_FILES: Seq[String] =
      Seq(s"valuesets$FOUNDATION_RESOURCES_FILE_SUFFIX")

    override def getFoundationResourceParser(complexTypes: Set[String],
                                             primitiveTypes: Set[String],
                                             capabilityDefaults: FhirCapabilityDefaults): IFhirFoundationResourceParser =
      new R4Parser(complexTypes, primitiveTypes, capabilityDefaults)
  }

  private val configurator = new R5TestConfigurator

  /** Reader with no explicit paths: everything resolves from the classpath. */
  lazy val configReader: FSConfigReader = new FSConfigReader(fhirVersion = "R5")

  /** The parsed R5 standard package. Built once per JVM. */
  lazy val fhirConfig: BaseFhirConfig = configurator.initializePlatform(configReader)

  /** Validator over the parsed standard package. */
  lazy val validator: FhirValidator = FhirValidator(fhirConfig)

  /** The base R5 CapabilityStatement parsed with the same (R4) parser. */
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

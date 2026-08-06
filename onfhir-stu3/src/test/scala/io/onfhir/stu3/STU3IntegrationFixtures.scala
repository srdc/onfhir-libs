package io.onfhir.stu3

import io.onfhir.api.Resource
import io.onfhir.api.parsers.IFhirFoundationResourceParser
import io.onfhir.config.{BaseFhirConfig, BaseFhirConfigurator, FHIRCapabilityStatement, FSConfigReader, FhirCapabilityDefaults}
import io.onfhir.stu3.parsers.STU3Parser
import io.onfhir.validation.FhirValidator
import org.json4s.JsonAST.JObject
import org.json4s.jackson.JsonMethods

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * Shared fixture for the STU3 integration suites. These suites pin the
 * [[STU3Parser]] contract against the real FHIR STU3 3.0.1 package supplied by
 * the test-scope `onfhir-definitions-stu3` artifact.
 *
 * The private configurator mirrors Repofyr's `FhirSTU3Configurator` in the one
 * respect that matters to the library layer: `fhirVersion = "STU3"`, so
 * `FSConfigReader` resolves `definitions-stu3.json.zip` and
 * `conformance-statement-stu3.json` from their default classpath locations.
 *
 * Unlike R5, no `VALUESET_AND_CODESYSTEM_BUNDLE_FILES` narrowing is needed: the
 * STU3 package still ships `v3-codesystems.json` and `v2-tables.json` alongside
 * `valuesets.json`, so the inherited default list resolves.
 */
object STU3IntegrationFixtures {
  implicit val executionContext: ExecutionContext = ExecutionContext.global

  /** Generous timeout: a validation run may parse and evaluate large profiles. */
  val awaitTimeout: Duration = 120.seconds

  /** Library-layer mirror of Repofyr's FhirSTU3Configurator. */
  private final class STU3TestConfigurator extends BaseFhirConfigurator {
    override val fhirVersion: String = "STU3"

    override def getFoundationResourceParser(complexTypes: Set[String],
                                             primitiveTypes: Set[String],
                                             capabilityDefaults: FhirCapabilityDefaults): IFhirFoundationResourceParser =
      new STU3Parser(complexTypes, primitiveTypes, capabilityDefaults)
  }

  private val configurator = new STU3TestConfigurator

  /** Reader with no explicit paths: everything resolves from the classpath. */
  lazy val configReader: FSConfigReader = new FSConfigReader(fhirVersion = "STU3")

  /** The parsed STU3 standard package. Built once per JVM. */
  lazy val fhirConfig: BaseFhirConfig = configurator.initializePlatform(configReader)

  /** Validator over the parsed standard package. */
  lazy val validator: FhirValidator = FhirValidator(fhirConfig)

  /** The base STU3 CapabilityStatement parsed with the STU3 parser. */
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

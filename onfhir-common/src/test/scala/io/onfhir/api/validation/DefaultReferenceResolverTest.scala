package io.onfhir.api.validation

import io.onfhir.api.Resource
import io.onfhir.api.model.{FhirCanonicalReference, FhirInternalReference, FhirLiteralReference, FhirLogicalReference, FhirUUIDReference}
import org.json4s.JsonAST.JObject
import org.json4s.jackson.JsonMethods.parse
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

@RunWith(classOf[JUnitRunner])
class DefaultReferenceResolverTest extends Specification {
  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  "DefaultReferenceResolver" should {
    "resolve contained, Bundle, and UUID references without invoking the external resolver" in {
      val contained = resource("""{"resourceType":"Patient","id":"contained-patient"}""")
      val bundledPatient = resource("""{"resourceType":"Patient","id":"bundle-patient"}""")
      val uuidPatient = resource("""{"resourceType":"Patient","id":"uuid-patient"}""")
      val current = resource("""{
        "resourceType":"Observation",
        "contained":[{"resourceType":"Patient","id":"contained-patient"}]
      }""")
      val bundle = resource("""{
        "resourceType":"Bundle",
        "entry":[{
          "fullUrl":"http://example.org/fhir/Patient/bundle-patient",
          "resource":{"resourceType":"Patient","id":"bundle-patient"}
        },{
          "fullUrl":"urn:uuid:bundle-patient",
          "resource":{"resourceType":"Patient","id":"uuid-patient"}
        }]
      }""")
      val external = new RecordingExternalResolver
      val resolver = new DefaultReferenceResolver(
        current,
        Some(Some("http://example.org/fhir/Observation/o1") -> bundle),
        externalResolver = Some(external)
      )

      await(resolver.resolveReference(FhirInternalReference("contained-patient"))) must beSome(contained)
      await(resolver.resolveReference(FhirUUIDReference("urn:uuid:bundle-patient"))) must beSome(uuidPatient)
      await(resolver.resolveReference(FhirLiteralReference(None, "Patient", "bundle-patient", None))) must beSome(bundledPatient)
      external.callCount mustEqual 0
    }

    "delegate unresolved literal, canonical, and type-less logical references" in {
      val external = new RecordingExternalResolver
      val canonicalTarget = resource("""{
        "resourceType":"PlanDefinition",
        "contained":[{"resourceType":"ActivityDefinition","id":"action"}]
      }""")
      external.literalResult = Some(resource("""{"resourceType":"Patient","id":"p1"}"""))
      external.canonicalResult = Some(canonicalTarget)
      external.logicalResult = Some(resource("""{"resourceType":"Patient","id":"p2"}"""))
      val resolver = new DefaultReferenceResolver(resource("""{"resourceType":"Observation"}"""), externalResolver = Some(external))

      val literal = FhirLiteralReference(Some("http://remote.example/fhir"), "Patient", "p1", Some("2"))
      val canonical = FhirCanonicalReference("http://example.org/fhir", "PlanDefinition", "plan", Some("1"), Some("action"))
      val logical = FhirLogicalReference(None, Some("http://example.org/mrn"), "123")

      await(resolver.resolveReference(literal)) must beSome(external.literalResult.get)
      await(resolver.resolveReference(canonical)) must beSome(resource("""{"resourceType":"ActivityDefinition","id":"action"}"""))
      await(resolver.resolveReference(logical)) must beSome(external.logicalResult.get)
      external.literalReferences mustEqual Seq(literal)
      external.canonicalReferences mustEqual Seq(canonical.copy(fragment = None))
      external.logicalReferences mustEqual Seq(logical)
    }

    "return no result for an unresolved canonical fragment" in {
      val external = new RecordingExternalResolver
      external.canonicalResult = Some(resource("""{"resourceType":"PlanDefinition","id":"plan"}"""))
      val resolver = new DefaultReferenceResolver(resource("""{"resourceType":"Observation"}"""), externalResolver = Some(external))

      await(resolver.resolveReference(FhirCanonicalReference("http://example.org/fhir", "PlanDefinition", "plan", None, Some("missing")))) must beNone
    }

    "check profile requirements for locally resolved references and support every reference kind" in {
      val current = resource("""{
        "resourceType":"Observation",
        "contained":[{
          "resourceType":"Patient",
          "id":"contained-patient",
          "meta":{"profile":["http://example.org/fhir/StructureDefinition/PatientProfile"]}
        }]
      }""")
      val bundle = resource("""{
        "resourceType":"Bundle",
        "entry":[{
          "fullUrl":"urn:uuid:bundle-patient",
          "resource":{"resourceType":"Patient","id":"bundle-patient"}
        }]
      }""")
      val external = new RecordingExternalResolver
      external.logicalResult = Some(resource("""{"resourceType":"Patient","id":"logical-patient"}"""))
      val resolver = new DefaultReferenceResolver(current, Some(None -> bundle), externalResolver = Some(external))
      val profile = "http://example.org/fhir/StructureDefinition/PatientProfile"

      await(resolver.isReferencedResourceExist(FhirInternalReference("contained-patient"), Set(profile))) must beTrue
      await(resolver.isReferencedResourceExist(FhirUUIDReference("urn:uuid:bundle-patient"), Set.empty)) must beTrue
      await(resolver.isReferencedResourceExist(FhirLogicalReference(Some("Patient"), None, "logical"), Set.empty)) must beTrue
    }
  }

  private def resource(json: String): JObject = parse(json).asInstanceOf[JObject]

  private def await[T](future: Future[T]): T = Await.result(future, 3.seconds)

  private final class RecordingExternalResolver extends IExternalFhirReferenceResolver {
    val literalReferences: ArrayBuffer[FhirLiteralReference] = ArrayBuffer.empty
    val canonicalReferences: ArrayBuffer[FhirCanonicalReference] = ArrayBuffer.empty
    val logicalReferences: ArrayBuffer[FhirLogicalReference] = ArrayBuffer.empty
    var literalResult: Option[Resource] = None
    var canonicalResult: Option[Resource] = None
    var logicalResult: Option[Resource] = None

    def callCount: Int = literalReferences.size + canonicalReferences.size + logicalReferences.size

    override def resolveLiteral(reference: FhirLiteralReference): Future[Option[Resource]] = {
      literalReferences += reference
      Future.successful(literalResult)
    }

    override def resolveCanonical(reference: FhirCanonicalReference): Future[Option[Resource]] = {
      canonicalReferences += reference
      Future.successful(canonicalResult)
    }

    override def resolveLogical(reference: FhirLogicalReference): Future[Option[Resource]] = {
      logicalReferences += reference
      Future.successful(logicalResult)
    }
  }
}

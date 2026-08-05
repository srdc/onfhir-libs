package io.onfhir.api.util

import io.onfhir.api.model.{FhirCanonicalReference, FhirInternalReference, FhirLiteralReference, FhirLogicalReference, FhirUUIDReference}
import io.onfhir.config.FhirEndpointSettings
import io.onfhir.exception.InvalidParameterException
import io.onfhir.util.JsonFormatter.{formats, parseFromJson}
import org.json4s.JsonAST.{JArray, JNothing, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Characterization of the FHIRUtil helpers that other modules and consuming applications build on;
 * search value parsing, element paths, resource metadata access, and FHIR Parameters navigation
 */
@RunWith(classOf[JUnitRunner])
class FHIRUtilCharacterizationTest extends Specification {

  val endpoint: FhirEndpointSettings = FhirEndpointSettings("https://example.org/fhir")

  val observation = """{
      |  "resourceType": "Observation",
      |  "id": "obs1",
      |  "meta": {
      |    "versionId": "3",
      |    "lastUpdated": "2013-04-03T15:30:10.000Z",
      |    "profile": ["http://example.org/fhir/StructureDefinition/MyObs", "http://hl7.org/fhir/StructureDefinition/vitalsigns"]
      |  },
      |  "status": "final",
      |  "subject": {"reference": "Patient/f001"},
      |  "component": [
      |    {"valueQuantity": {"value": 120}},
      |    {"valueQuantity": {"value": 80}}
      |  ]
      |}""".stripMargin.parseJson

  "Resource locations" should {

    "build an absolute location from the endpoint settings" in {
      FHIRUtil.resourceLocation(endpoint, "Patient", "p1") mustEqual "https://example.org/fhir/Patient/p1"
    }

    "build an absolute versioned location" in {
      FHIRUtil.resourceLocationWithVersion(endpoint, "Patient", "p1", 4L) mustEqual
        "https://example.org/fhir/Patient/p1/_history/4"
    }

    "generate a non-empty resource id" in {
      FHIRUtil.generateResourceId() must not(beEmpty)
    }
  }

  "Resource metadata access" should {

    "extract the resource type, id, and version" in {
      FHIRUtil.extractResourceType(observation) mustEqual "Observation"
      FHIRUtil.extractIdFromResource(observation) mustEqual "obs1"
      FHIRUtil.extractVersionFromResource(observation) mustEqual 3L
      FHIRUtil.extractResourceTypeAndId(observation) mustEqual ("Observation" -> "obs1")
    }

    "report the version as optional when meta is absent" in {
      FHIRUtil.extractVersionOptionFromResource("""{"resourceType":"Patient","id":"p1"}""".parseJson) must beNone
      FHIRUtil.extractVersionOptionFromResource(observation) must beSome(3L)
    }

    "build relative references to the resource" in {
      FHIRUtil.getReference(observation) mustEqual "Observation/obs1"
      FHIRUtil.getReferenceWithVersion(observation) mustEqual "Observation/obs1/_history/3"
    }

    "extract the declared profiles" in {
      FHIRUtil.extractProfilesFromBson(observation) mustEqual Set(
        "http://example.org/fhir/StructureDefinition/MyObs",
        "http://hl7.org/fhir/StructureDefinition/vitalsigns")
    }

    "extract the base meta fields together" in {
      val (id, version, lastUpdated) = FHIRUtil.extractBaseMetaFields(observation)
      id mustEqual "obs1"
      version mustEqual 3L
      lastUpdated.toString mustEqual "2013-04-03T15:30:10Z"
    }

    "extract an element value by name" in {
      FHIRUtil.extractValue[String](observation, "status") mustEqual "final"
      FHIRUtil.extractValueOption[String](observation, "status") must beSome("final")
      FHIRUtil.extractValueOption[String](observation, "language") must beNone
    }

    "report an absent repeating element as an empty collection rather than None" in {
      //json4s extracts an absent element into an empty Seq, so Option emptiness cannot be used to test presence here
      FHIRUtil.extractValueOption[Seq[String]](observation, "category") must beSome(Seq.empty[String])
      (observation \ "category") mustEqual JNothing
    }

    "extract a value by dotted path, including array indexes" in {
      FHIRUtil.extractValueOptionByPath[String](observation, "subject.reference") must beSome("Patient/f001")
      FHIRUtil.extractValueOptionByPath[Int](observation, "component[1].valueQuantity.value") must beSome(80)
      FHIRUtil.extractValueOptionByPath[String](observation, "subject.display") must beNone
    }

    "set the id and the profile of a resource" in {
      val withId = FHIRUtil.setId("""{"resourceType":"Patient"}""".parseJson, "p9")
      FHIRUtil.extractIdFromResource(withId) mustEqual "p9"

      val withProfile = FHIRUtil.setProfile("""{"resourceType":"Patient","id":"p9"}""".parseJson, "http://example.org/P")
      FHIRUtil.extractProfilesFromBson(withProfile) mustEqual Set("http://example.org/P")
    }
  }

  "Reference parsing" should {

    "parse a relative reference" in {
      FHIRUtil.parseReferenceValue("Patient/575644") mustEqual ((None, "Patient", "575644", None))
    }

    "parse an absolute reference" in {
      FHIRUtil.parseReferenceValue("http://example.org/fhir/Observation/1x2") mustEqual
        ((Some("http://example.org/fhir"), "Observation", "1x2", None))
    }

    "parse an absolute reference carrying a version" in {
      FHIRUtil.parseReferenceValue("http://example.org/fhir/Observation/1x2/_history/2") mustEqual
        ((Some("http://example.org/fhir"), "Observation", "1x2", Some("2")))
    }

    "parse a relative reference carrying a version without a URL part" in {
      FHIRUtil.parseReferenceValue("Observation/1x2/_history/2") mustEqual ((None, "Observation", "1x2", Some("2")))
    }

    "reject a value that is not a reference" in {
      FHIRUtil.parseReferenceValue("Patient") must throwAn[Exception]
    }

    "classify a Reference element by its kind" in {
      FHIRUtil.parseReference(JObject("reference" -> JString("#p1"))) mustEqual FhirInternalReference("p1")
      FHIRUtil.parseReference(JObject("reference" -> JString("urn:uuid:1-2-3"))) mustEqual
        FhirUUIDReference("urn:uuid:1-2-3")
      FHIRUtil.parseReference(JObject("reference" -> JString("Patient/p1"))) mustEqual
        FhirLiteralReference(None, "Patient", "p1", None)
    }

    "classify an identifier-only Reference element as a logical reference" in {
      val logical = JObject(
        "type" -> JString("Patient"),
        "identifier" -> JObject("system" -> JString("urn:oid:1.2.3"), "value" -> JString("12345")))

      FHIRUtil.parseReference(logical) mustEqual
        FhirLogicalReference(Some("Patient"), Some("urn:oid:1.2.3"), "12345")
    }

    "parse a canonical reference with version and fragment" in {
      FHIRUtil.parseCanonicalReference("http://example.org/fhir/ValueSet/vs1|1.2.0#frag") mustEqual
        FhirCanonicalReference("http://example.org/fhir", "ValueSet", "vs1", Some("1.2.0"), Some("frag"))
    }

    "parse a canonical reference without version or fragment" in {
      FHIRUtil.parseCanonicalReference("http://example.org/fhir/ValueSet/vs1") mustEqual
        FhirCanonicalReference("http://example.org/fhir", "ValueSet", "vs1", None, None)
    }
  }

  "Search value parsing" should {

    "split a token value into system and code" in {
      FHIRUtil.parseTokenValue("http://loinc.org|500-5") mustEqual ((Some("http://loinc.org"), Some("500-5")))
      FHIRUtil.parseTokenValue("500-5") mustEqual ((None, Some("500-5")))
      FHIRUtil.parseTokenValue("|500-5") mustEqual ((Some(""), Some("500-5")))
      FHIRUtil.parseTokenValue("http://loinc.org|") mustEqual ((Some("http://loinc.org"), None))
    }

    "require all three parts for the of-type modifier" in {
      FHIRUtil.parseTokenOfTypeValue("http://terminology.hl7.org/CodeSystem/v2-0203|MR|446053") mustEqual
        (("http://terminology.hl7.org/CodeSystem/v2-0203", "MR", "446053"))
      FHIRUtil.parseTokenOfTypeValue("http://sys|MR") must throwAn[InvalidParameterException]
      FHIRUtil.parseTokenOfTypeValue("http://sys||446053") must throwAn[InvalidParameterException]
    }

    "split a quantity value into value, system, and code" in {
      FHIRUtil.parseQuantityValue("5.4|http://unitsofmeasure.org|mg") mustEqual
        (("5.4", Some("http://unitsofmeasure.org"), Some("mg")))
      FHIRUtil.parseQuantityValue("5.4||mg") mustEqual (("5.4", None, Some("mg")))
      FHIRUtil.parseQuantityValue("5.4") mustEqual (("5.4", None, None))
    }

    "split a canonical value into url and version" in {
      FHIRUtil.parseCanonicalValue("http://example.org/vs|2.0") mustEqual (("http://example.org/vs", Some("2.0")))
      FHIRUtil.parseCanonicalValue("http://example.org/vs") mustEqual (("http://example.org/vs", None))
    }

    "resolve a reference search value using the modifier" in {
      //No modifier and a bare id resolves against the single declared target type
      FHIRUtil.resolveReferenceValue("p1", "", Seq("Patient")) mustEqual ((None, "Patient", "p1", None))
      //A typed reference value is parsed as given
      FHIRUtil.resolveReferenceValue("Patient/p1", "", Seq("Patient", "Group")) mustEqual
        ((None, "Patient", "p1", None))
      //A :[type] modifier supplies the type
      FHIRUtil.resolveReferenceValue("p1", ":Patient", Nil) mustEqual ((None, "Patient", "p1", None))
    }

    "reject a bare id when the parameter declares several target types" in {
      FHIRUtil.resolveReferenceValue("p1", "", Seq("Patient", "Group")) must throwAn[InvalidParameterException]
    }

    "calculate the search precision delta from the value's decimal places" in {
      FHIRUtil.calculatePrecisionDelta("5.4") mustEqual 0.05
      FHIRUtil.calculatePrecisionDelta("-5.4") mustEqual 0.05
      FHIRUtil.calculatePrecisionDelta("5.40") mustEqual 0.005
    }
  }

  "Element path helpers" should {

    "remove array indicators" in {
      FHIRUtil.normalizeElementPath("component[i].code.coding[i].code") mustEqual "component.code.coding.code"
      FHIRUtil.normalizeElementPath("status") mustEqual "status"
    }

    "split a path at its last array element" in {
      FHIRUtil.splitElementPathIntoElemMatchAndQueryPaths("target[i].dueDate") mustEqual
        ((Some("target"), Some("dueDate")))
      FHIRUtil.splitElementPathIntoElemMatchAndQueryPaths("identifier[i]") mustEqual ((Some("identifier"), None))
      FHIRUtil.splitElementPathIntoElemMatchAndQueryPaths("code.coding[i]") mustEqual ((Some("code.coding"), None))
      FHIRUtil.splitElementPathIntoElemMatchAndQueryPaths("code.text") mustEqual ((None, Some("code.text")))
      FHIRUtil.splitElementPathIntoElemMatchAndQueryPaths("") mustEqual ((None, None))
    }

    "merge element paths" in {
      FHIRUtil.mergeElementPath(Some("code"), "coding.code") mustEqual "code.coding.code"
      FHIRUtil.mergeElementPath(None, "coding.code") mustEqual "coding.code"
      FHIRUtil.mergeElementPath("code", "coding") mustEqual "code.coding"
      FHIRUtil.mergeElementPath("", "coding") mustEqual "coding"
      FHIRUtil.mergeElementPath("code", "") mustEqual "code"
    }

    "merge file paths with a slash" in {
      FHIRUtil.mergeFilePath(Some("profiles"), "patient.json") mustEqual "profiles/patient.json"
      FHIRUtil.mergeFilePath(None, "patient.json") mustEqual "patient.json"
    }

    "decapitalize a name" in {
      FHIRUtil.decapitilize("Observation") mustEqual "observation"
      FHIRUtil.decapitilize("observation") mustEqual "observation"
    }

    "normalize the composite search parameter name anomaly" in {
      FHIRUtil.transformSearchParameterName("value-x") mustEqual "value-[x]"
      FHIRUtil.transformSearchParameterName("code-value-quantity") mustEqual "code-value-quantity"
    }

    "apply a search parameter path to a resource" in {
      FHIRUtil.applySearchParameterPath("subject.reference", observation) mustEqual Seq(JString("Patient/f001"))
      FHIRUtil.applySearchParameterPath("code.text", observation) must beEmpty
    }

    "extract reference values found at a path" in {
      FHIRUtil.extractReferences("subject", observation) mustEqual Seq("Patient/f001")
    }
  }

  "FHIR Parameters navigation" should {

    val parameters = """{
        |  "resourceType": "Parameters",
        |  "parameter": [
        |    {"name": "result", "valueBoolean": true},
        |    {"name": "message", "valueString": "ok"},
        |    {"name": "subject", "resource": {"resourceType": "Patient", "id": "p1"}},
        |    {"name": "repeated", "valueString": "a"},
        |    {"name": "repeated", "valueString": "b"}
        |  ]
        |}""".stripMargin.parseJson

    "read a simple parameter value by name" in {
      FHIRUtil.getParameterValueByName(parameters, "message") must beSome(JString("ok"))
    }

    "read a resource parameter value by name" in {
      FHIRUtil.getParameterValueByName(parameters, "subject").map(r => (r \ "id")) must beSome(JString("p1"))
    }

    "collect repeated parameters of the same name into an array" in {
      FHIRUtil.getParameterValueByName(parameters, "repeated") must
        beSome(JArray(List(JString("a"), JString("b"))))
    }

    "report an absent parameter as empty" in {
      FHIRUtil.getParameterValueByName(parameters, "missing") must beNone
    }

    "parse one Parameters.parameter into a name and value pair" in {
      FHIRUtil.parseParameter(JObject("name" -> JString("x"), "valueCode" -> JString("y"))) mustEqual
        ("x" -> JString("y"))
    }

    "prefer a value field, then a resource, then parts" in {
      FHIRUtil.getValueFromParameter(JObject("name" -> JString("x"), "valueString" -> JString("v"))) must
        beSome(JString("v"))
      FHIRUtil.getValueFromParameter(
        JObject("name" -> JString("x"), "resource" -> JObject("resourceType" -> JString("Patient")))) must
        beSome(JObject("resourceType" -> JString("Patient")))
      FHIRUtil.getValueFromParameter(JObject("name" -> JString("x"))) must beNone
    }
  }

  "Bundle construction" should {

    //The entries are already-formed Bundle entries, not bare resources; the caller wraps the resource
    val searchSetEntry = """{
        |  "fullUrl": "https://example.org/fhir/Patient/p1",
        |  "resource": {"resourceType": "Patient", "id": "p1"},
        |  "search": {"mode": "match"}
        |}""".stripMargin.parseJson

    "create a searchset bundle carrying the total, links, and entries" in {
      val bundle = FHIRUtil.createBundle(
        "searchset",
        List("self" -> "https://example.org/fhir/Patient"),
        Seq(searchSetEntry),
        total = 1L,
        summary = None)

      FHIRUtil.extractValueOption[String](bundle, "type") must beSome("searchset")
      FHIRUtil.extractValueOptionByPath[Int](bundle, "total") must beSome(1)
      FHIRUtil.extractValueOptionByPath[String](bundle, "link[0].relation") must beSome("self")
      FHIRUtil.extractValueOptionByPath[String](bundle, "entry[0].resource.id") must beSome("p1")
    }

    "omit links and entries for a count-only summary" in {
      val bundle = FHIRUtil.createBundle(
        "searchset",
        List("self" -> "https://example.org/fhir/Patient"),
        Seq(searchSetEntry),
        total = 1L,
        summary = Some("count"))

      FHIRUtil.extractValueOptionByPath[Int](bundle, "total") must beSome(1)
      (bundle \ "entry") mustEqual JNothing
      (bundle \ "link") mustEqual JNothing
    }

    "omit the total for a bundle type that must not carry one" in {
      val bundle = FHIRUtil.createBundle("collection", Nil, Seq(searchSetEntry), total = 1L, summary = None)

      FHIRUtil.extractValueOption[Int](bundle, "total") must beNone
    }

    "create a transaction bundle from its entries" in {
      val bundle = FHIRUtil.createTransactionBatchBundle("transaction", Seq(searchSetEntry))

      FHIRUtil.extractValueOption[String](bundle, "type") must beSome("transaction")
      FHIRUtil.extractValueOptionByPath[String](bundle, "entry[0].resource.id") must beSome("p1")
    }
  }
}

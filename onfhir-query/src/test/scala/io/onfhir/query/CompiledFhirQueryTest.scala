package io.onfhir.query

import io.onfhir.api.{FHIR_DATA_TYPES, FHIR_PARAMETER_TYPES, FHIR_SEARCH_RESULT_PARAMETERS, FHIR_SEARCH_SPECIAL_PARAMETERS}
import io.onfhir.config._
import org.json4s.JValue
import org.json4s.jackson.JsonMethods.parse
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

@RunWith(classOf[JUnitRunner])
class CompiledFhirQueryTest extends Specification {
  sequential

  /** Target type SearchParameterConfigurator emits for a composite's base context. */
  private val COMPOSITE_BASE_TARGET_TYPE = "Resource"

  private val config = new FhirServerConfig("R4")
  config.FHIR_RESULT_PARAMETERS = Seq(
    FHIR_SEARCH_RESULT_PARAMETERS.SORT,
    FHIR_SEARCH_RESULT_PARAMETERS.COUNT
  )
  config.FHIR_SPECIAL_PARAMETERS = Seq(FHIR_SEARCH_SPECIAL_PARAMETERS.ID)
  config.commonQueryParameters = Map(
    "_lastUpdated" -> searchParameter("_lastUpdated", FHIR_PARAMETER_TYPES.DATE, Seq("meta.lastUpdated"), Seq(FHIR_DATA_TYPES.INSTANT))
  )
  config.resourceQueryParameters = Map(
    "Patient" -> Seq(
      searchParameter("name", FHIR_PARAMETER_TYPES.STRING, Seq("name.family"), Seq(FHIR_DATA_TYPES.STRING)),
      searchParameter("gender", FHIR_PARAMETER_TYPES.TOKEN, Seq("gender"), Seq(FHIR_DATA_TYPES.CODE)),
      searchParameter("birthdate", FHIR_PARAMETER_TYPES.DATE, Seq("birthDate"), Seq(FHIR_DATA_TYPES.DATE)),
      searchParameter("identifier", FHIR_PARAMETER_TYPES.TOKEN, Seq("identifier"), Seq(FHIR_DATA_TYPES.IDENTIFIER)),
      searchParameter("organization", FHIR_PARAMETER_TYPES.REFERENCE, Seq("managingOrganization"), Seq(FHIR_DATA_TYPES.REFERENCE), targets = Seq("Organization"))
    ).map(parameter => parameter.pname -> parameter).toMap,
    "Observation" -> Seq(
      searchParameter("code", FHIR_PARAMETER_TYPES.TOKEN, Seq("code"), Seq(FHIR_DATA_TYPES.CODEABLE_CONCEPT)),
      searchParameter("combo-value-concept", FHIR_PARAMETER_TYPES.TOKEN, Seq("valueCodeableConcept"), Seq(FHIR_DATA_TYPES.CODEABLE_CONCEPT)),
      searchParameter(
        "value-quantity",
        FHIR_PARAMETER_TYPES.QUANTITY,
        Seq("valueQuantity", "valueSampledData"),
        Seq(FHIR_DATA_TYPES.QUANTITY, FHIR_DATA_TYPES.SAMPLED_DATA)
      ),
      //A composite's own paths are the base context of its expression, and its
      //components are named in targets; the component element paths come from the
      //component configurations. This mirrors what SearchParameterConfigurator
      //produces for the R4 Observation composites.
      searchParameter(
        "code-value-concept",
        FHIR_PARAMETER_TYPES.COMPOSITE,
        Seq(""),
        Seq(COMPOSITE_BASE_TARGET_TYPE),
        targets = Seq("code", "combo-value-concept")
      ),
      searchParameter(
        "code-value-quantity",
        FHIR_PARAMETER_TYPES.COMPOSITE,
        Seq(""),
        Seq(COMPOSITE_BASE_TARGET_TYPE),
        targets = Seq("code", "value-quantity")
      )
    ).map(parameter => parameter.pname -> parameter).toMap
  )
  config.resourceConfigurations = Map(
    "Patient" -> ResourceConf("Patient"),
    "Observation" -> ResourceConf("Observation")
  )

  private val evaluator = FhirQueryEvaluator(config, FhirEndpointSettings("https://example.org/fhir"))

  private def searchParameter(
      name: String,
      parameterType: String,
      paths: Seq[String],
      targetTypes: Seq[String],
      targets: Seq[String] = Nil): SearchParameterConf =
    SearchParameterConf(
      url = s"http://example.org/SearchParameter/$name",
      pname = name,
      ptype = parameterType,
      paths = paths,
      targets = targets,
      targetTypes = targetTypes,
      restrictions = paths.map(_ => Nil)
    )

  private val patient: JValue = parse(
    """{
      |  "resourceType": "Patient",
      |  "id": "p1",
      |  "meta": {"lastUpdated": "2024-05-01T10:00:00Z"},
      |  "name": [{"family": "Smith"}, {"family": "Jones"}],
      |  "gender": "male",
      |  "birthDate": "2000-06-15",
      |  "identifier": [{"system": "urn:mrn", "value": "12345"}],
      |  "managingOrganization": {"reference": "https://example.org/fhir/Organization/42"}
      |}""".stripMargin
  )

  private val patientOnlySmith: JValue = parse(
    """{
      |  "resourceType": "Patient",
      |  "id": "p2",
      |  "name": [{"family": "Smith"}]
      |}""".stripMargin
  )

  private val observation: JValue = parse(
    """{
      |  "resourceType": "Observation",
      |  "id": "obs1",
      |  "code": {"coding": [{"system": "http://loinc.org", "code": "85354-9"}]},
      |  "valueCodeableConcept": {"coding": [{"system": "http://snomed.info/sct", "code": "260385009"}]}
      |}""".stripMargin
  )

  private val quantityObservation: JValue = parse(
    """{
      |  "resourceType": "Observation",
      |  "id": "obs2",
      |  "code": {"coding": [{"system": "http://loinc.org", "code": "85354-9"}]},
      |  "valueQuantity": {"value": 5.5, "system": "http://unitsofmeasure.org", "code": "mg"}
      |}""".stripMargin
  )

  "CompiledFhirQuery.matches" should {
    "never match a resource of another type" in {
      val compiled = evaluator.compile("Patient?name=Smith")

      compiled.matches(observation) must beFalse
      compiled.matches(parse("{}")) must beFalse
    }

    "match every resource of the type for a query without search parameters" in {
      evaluator.compile("Patient").matches(patient) must beTrue
      evaluator.compile("Patient").matches(patientOnlySmith) must beTrue
    }

    "combine repeated parameters with AND semantics" in {
      val compiled = evaluator.compile("Patient?name=Smith&name=Jones")

      compiled.matches(patient) must beTrue
      compiled.matches(patientOnlySmith) must beFalse
    }

    "combine comma separated values with OR semantics" in {
      evaluator.compile("Patient?name=Adams,Jones").matches(patient) must beTrue
      evaluator.compile("Patient?name=Adams,Brown").matches(patient) must beFalse
    }

    "evaluate token parameters including system and value" in {
      evaluator.compile("Patient?gender=male").matches(patient) must beTrue
      evaluator.compile("Patient?gender=female").matches(patient) must beFalse
      evaluator.compile("Patient?identifier=urn:mrn%7C12345").matches(patient) must beTrue
      evaluator.compile("Patient?identifier=urn:mrn%7C99999").matches(patient) must beFalse
    }

    "evaluate the missing modifier" in {
      evaluator.compile("Patient?gender:missing=false").matches(patient) must beTrue
      evaluator.compile("Patient?gender:missing=true").matches(patient) must beFalse
      evaluator.compile("Patient?gender:missing=true").matches(patientOnlySmith) must beTrue
    }

    "treat relative and endpoint-rooted absolute references as equivalent" in {
      evaluator.compile("Patient?organization=Organization/42").matches(patient) must beTrue
      evaluator.compile("Patient?organization=Organization/43").matches(patient) must beFalse
    }

    "evaluate _id as equality on the resource id with OR across values" in {
      evaluator.compile("Patient?_id=p1").matches(patient) must beTrue
      evaluator.compile("Patient?_id=zz,p1").matches(patient) must beTrue
      evaluator.compile("Patient?_id=zz").matches(patient) must beFalse
      evaluator.compile("Patient?_id=p1").matches(patientOnlySmith) must beFalse
    }

    "evaluate common underscore parameters as normal search parameters" in {
      evaluator.compile("Patient?_lastUpdated=ge2024-01-01").matches(patient) must beTrue
      evaluator.compile("Patient?_lastUpdated=le2023-12-31").matches(patient) must beFalse
    }

    "evaluate repeated date parameters sharing one configuration" in {
      evaluator.compile("Patient?birthdate=ge2000-01-01&birthdate=le2000-12-31").matches(patient) must beTrue
      evaluator.compile("Patient?birthdate=ge2001-01-01").matches(patient) must beFalse
    }

    "evaluate composite parameters through their component configurations" in {
      val matching = "Observation?code-value-concept=http://loinc.org%7C85354-9$http://snomed.info/sct%7C260385009"
      val nonMatching = "Observation?code-value-concept=http://loinc.org%7C85354-9$http://snomed.info/sct%7C999"

      evaluator.compile(matching).matches(observation) must beTrue
      evaluator.compile(nonMatching).matches(observation) must beFalse
    }

    "bind each composite component to its own element" in {
      //Same two codes as the matching case, given in the wrong component order
      val swapped = "Observation?code-value-concept=http://snomed.info/sct%7C260385009$http://loinc.org%7C85354-9"

      evaluator.compile(swapped).matches(observation) must beFalse
    }

    "evaluate a composite whose components have different search types" in {
      val matching = "Observation?code-value-quantity=http://loinc.org%7C85354-9$5.5%7Chttp://unitsofmeasure.org%7Cmg"
      val wrongQuantity = "Observation?code-value-quantity=http://loinc.org%7C85354-9$9.9%7Chttp://unitsofmeasure.org%7Cmg"
      val wrongCode = "Observation?code-value-quantity=http://loinc.org%7C999$5.5%7Chttp://unitsofmeasure.org%7Cmg"

      evaluator.compile(matching).matches(quantityObservation) must beTrue
      evaluator.compile(wrongQuantity).matches(quantityObservation) must beFalse
      evaluator.compile(wrongCode).matches(quantityObservation) must beFalse
    }

    "ignore result parameters without affecting the predicate" in {
      val compiled = evaluator.compile("Patient?gender=female&_count=1")

      compiled.ignoredParameters.map(_.name) mustEqual List(FHIR_SEARCH_RESULT_PARAMETERS.COUNT)
      compiled.matches(patient) must beFalse
      evaluator.compile("Patient?_count=1").matches(patient) must beTrue
    }

    "return stable results when one compiled query is shared between threads" in {
      val compiled = evaluator.compile("Patient?name=Smith&gender=male&birthdate=ge2000-01-01")

      val results = Await.result(
        Future.sequence((1 to 50).map(_ => Future(compiled.matches(patient)))),
        10.seconds
      )

      results must contain(beTrue).forall
    }
  }
}

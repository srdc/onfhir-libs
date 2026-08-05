package io.onfhir.query

import io.onfhir.api.model.Parameter
import io.onfhir.api.validation.ProfileRestrictions
import io.onfhir.api.{FHIR_DATA_TYPES, FHIR_PARAMETER_CATEGORIES, FHIR_PARAMETER_TYPES, FHIR_SEARCH_RESULT_PARAMETERS, FHIR_SEARCH_SPECIAL_PARAMETERS}
import io.onfhir.config._
import io.onfhir.exception.{InvalidParameterException, UnsupportedParameterException}
import io.onfhir.expression.FhirExpressionException
import org.json4s.JsonAST.JString
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FhirQueryEvaluatorTest extends Specification {
  sequential

  private val patientProfileUrl = "http://hl7.org/fhir/StructureDefinition/Patient"
  private val summaryElements = Set("id", "meta", "name")

  private val config = new FhirServerConfig("R4")
  config.FHIR_RESULT_PARAMETERS = Seq(
    FHIR_SEARCH_RESULT_PARAMETERS.SORT,
    FHIR_SEARCH_RESULT_PARAMETERS.COUNT,
    FHIR_SEARCH_RESULT_PARAMETERS.PAGE,
    FHIR_SEARCH_RESULT_PARAMETERS.TOTAL,
    FHIR_SEARCH_RESULT_PARAMETERS.SUMMARY,
    FHIR_SEARCH_RESULT_PARAMETERS.ELEMENTS,
    FHIR_SEARCH_RESULT_PARAMETERS.INCLUDE,
    FHIR_SEARCH_RESULT_PARAMETERS.REVINCLUDE,
    FHIR_SEARCH_RESULT_PARAMETERS.SEARCH_AFTER,
    FHIR_SEARCH_RESULT_PARAMETERS.SEARCH_BEFORE
  )
  config.FHIR_SPECIAL_PARAMETERS = Seq(
    FHIR_SEARCH_SPECIAL_PARAMETERS.ID,
    FHIR_SEARCH_SPECIAL_PARAMETERS.LIST
  )
  config.commonQueryParameters = Map(
    "_lastUpdated" -> searchParameter("_lastUpdated", FHIR_PARAMETER_TYPES.DATE, Seq("meta.lastUpdated"), Seq(FHIR_DATA_TYPES.INSTANT))
  )
  config.resourceQueryParameters = Map(
    "Patient" -> Seq(
      searchParameter("name", FHIR_PARAMETER_TYPES.STRING, Seq("name.family"), Seq(FHIR_DATA_TYPES.STRING)),
      searchParameter("gender", FHIR_PARAMETER_TYPES.TOKEN, Seq("gender"), Seq(FHIR_DATA_TYPES.CODE)),
      searchParameter("birthdate", FHIR_PARAMETER_TYPES.DATE, Seq("birthDate"), Seq(FHIR_DATA_TYPES.DATE)),
      searchParameter("organization", FHIR_PARAMETER_TYPES.REFERENCE, Seq("managingOrganization"), Seq(FHIR_DATA_TYPES.REFERENCE), targets = Seq("Organization")),
      searchParameter("general-practitioner", FHIR_PARAMETER_TYPES.REFERENCE, Seq("generalPractitioner"), Seq(FHIR_DATA_TYPES.REFERENCE), targets = Seq("Practitioner"))
    ).map(parameter => parameter.pname -> parameter).toMap,
    "Practitioner" -> Seq(
      searchParameter("name", FHIR_PARAMETER_TYPES.STRING, Seq("name.family"), Seq(FHIR_DATA_TYPES.STRING))
    ).map(parameter => parameter.pname -> parameter).toMap,
    "Observation" -> Seq(
      searchParameter("subject", FHIR_PARAMETER_TYPES.REFERENCE, Seq("subject"), Seq(FHIR_DATA_TYPES.REFERENCE), targets = Seq("Patient")),
      searchParameter("patient", FHIR_PARAMETER_TYPES.REFERENCE, Seq("subject"), Seq(FHIR_DATA_TYPES.REFERENCE), targets = Seq("Patient")),
      searchParameter("code", FHIR_PARAMETER_TYPES.TOKEN, Seq("code"), Seq(FHIR_DATA_TYPES.CODEABLE_CONCEPT)),
      searchParameter("date", FHIR_PARAMETER_TYPES.DATE, Seq("effectiveDateTime"), Seq(FHIR_DATA_TYPES.DATETIME))
    ).map(parameter => parameter.pname -> parameter).toMap
  )
  config.resourceConfigurations = Map(
    "Patient" -> ResourceConf("Patient", profile = Some(patientProfileUrl), searchInclude = Set("Patient.general-practitioner")),
    "Practitioner" -> ResourceConf("Practitioner"),
    "Observation" -> ResourceConf("Observation")
  )
  config.profileRestrictions = Map(
    patientProfileUrl -> Map(
      "4.0.1" -> ProfileRestrictions(
        url = patientProfileUrl,
        version = Some("4.0.1"),
        id = Some("Patient"),
        baseUrl = None,
        resourceType = "Patient",
        resourceName = Some("Patient"),
        resourceDescription = None,
        elementRestrictions = Nil,
        summaryElements = summaryElements
      )
    )
  )

  private val endpointSettings = FhirEndpointSettings("https://example.org/fhir")
  private val resultDefaults = FhirResultDefaults(
    defaultPageSize = 20,
    paginationMode = FhirPaginationMode.Page,
    totalHandling = FhirSearchTotalHandling.Accurate
  )

  private val evaluator = FhirQueryEvaluator(config, endpointSettings, resultDefaults = Some(resultDefaults))
  private val lenientEvaluator = FhirQueryEvaluator(config, endpointSettings, FhirSearchHandling.Lenient)
  private val evaluatorWithoutDefaults = FhirQueryEvaluator(config, endpointSettings)

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

  "FhirQueryEvaluator.parse" should {
    "parse a plain query into a typed result keeping parameter order" in {
      val parsed = evaluator.parse("Patient?name=Smith&name=Jones&gender=male&_sort=-birthdate")

      parsed.resourceType mustEqual "Patient"
      parsed.parameters must haveSize(4)
      parsed.searchParameters.map(_.name) mustEqual List("name", "name", "gender")
      parsed.searchParameters.flatMap(_.valuePrefixList.map(_._2)).take(2) mustEqual List("Smith", "Jones")
      parsed.resultParameters.map(_.name) mustEqual List("_sort")
    }

    "encode a parsed query back into the query statement" in {
      val query = "Patient?name=Smith&name=Jones&gender=male&_sort=-birthdate"

      evaluator.parse(query).encode mustEqual query
    }

    "parse a query without parameters and encode it as the resource type" in {
      val parsed = evaluator.parse("Patient")

      parsed.parameters must beEmpty
      parsed.encode mustEqual "Patient"
    }

    "reject an unsupported parameter under strict handling and drop it under lenient handling" in {
      evaluator.parse("Patient?unknown=x") must throwA[UnsupportedParameterException]
      lenientEvaluator.parse("Patient?unknown=x").parameters must beEmpty
    }
  }

  "FhirQueryEvaluator x-fhir-query methods" should {
    "resolve placeholders from context parameters" in {
      val parsed = evaluator.parseXFhirQuery(
        "Observation?subject={{%patientRef}}&code=http://loinc.org|85354-9",
        context = Map("patientRef" -> JString("Patient/123"))
      )

      parsed.resourceType mustEqual "Observation"
      parsed.parameters.find(_.name == "subject").map(_.valuePrefixList) must beSome(Seq("" -> "Patient/123"))
      parsed.parameters.find(_.name == "code").map(_.valuePrefixList) must beSome(Seq("" -> "http://loinc.org|85354-9"))
    }

    "parse an x-fhir-query without a query part" in {
      evaluator.parseXFhirQuery("Observation").parameters must beEmpty
    }

    "wrap invalid parameter values in FhirExpressionException" in {
      evaluator.parseXFhirQuery("Observation?date=notadate") must throwA[FhirExpressionException]
    }

    "validate the query shape while preserving placeholders and encode them back" in {
      val query = "Observation?date=ge{{today()}}"
      val parsed = evaluator.validateXFhirQuery(query)

      parsed.parameters.find(_.name == "date").map(_.valuePrefixList) must beSome(Seq("ge" -> "{{today()}}"))
      parsed.encode mustEqual query
    }
  }

  "FhirQueryEvaluator.resolveResultControls" should {
    "resolve result parameters into typed instructions and carry unresolved ones" in {
      val parsed = evaluator.parse(
        "Patient?_sort=-birthdate,name&_count=5&_page=3&_total=none&_elements=name&_summary=count&_include=Patient:general-practitioner"
      )
      val controls = evaluator.resolveResultControls(parsed)

      controls.sorting mustEqual List(
        FhirSortInstruction("birthdate", descending = true, Seq("birthDate" -> FHIR_DATA_TYPES.DATE)),
        FhirSortInstruction("name", descending = false, Seq("name.family" -> FHIR_DATA_TYPES.STRING))
      )
      controls.summary must beSome(FhirElementProjection(include = true, Set.empty[String]))
      controls.elements mustEqual Set("name")
      controls.pageSize mustEqual 5
      controls.pagination mustEqual FhirPaginationInstruction.ByPage(3)
      controls.includeTotal must beFalse
      controls.unresolvedResultParameters.map(_.name) mustEqual List(FHIR_SEARCH_RESULT_PARAMETERS.INCLUDE)
    }

    "resolve _summary=true to the configured summary elements" in {
      val controls = evaluator.resolveResultControls(evaluator.parse("Patient?_summary=true"))

      controls.summary must beSome(FhirElementProjection(include = true, summaryElements))
    }

    "apply the configured defaults when no result parameter is given" in {
      val controls = evaluator.resolveResultControls(evaluator.parse("Patient"))

      controls.sorting must beEmpty
      controls.summary must beNone
      controls.elements must beEmpty
      controls.pageSize mustEqual 20
      controls.pagination mustEqual FhirPaginationInstruction.ByPage(1)
      controls.includeTotal must beTrue
      controls.unresolvedResultParameters must beEmpty
    }

    "resolve cursor pagination for _searchafter" in {
      val controls = evaluator.resolveResultControls(evaluator.parse("Patient?_searchafter=a,b"))

      controls.pagination mustEqual FhirPaginationInstruction.ByCursor(Seq("a", "b"), forward = true)
    }

    "fail meaningfully when the evaluator has no result defaults" in {
      evaluatorWithoutDefaults.resolveResultControls(evaluatorWithoutDefaults.parse("Patient")) must
        throwA[IllegalStateException](message = "resultDefaults")
    }
  }

  "FhirQueryEvaluator.compile" should {
    "reject chained parameters at compile time" in {
      evaluator.compile("Patient?general-practitioner.name=Joe") must
        throwA[UnsupportedParameterException](message = "Chained parameter")
    }

    "reject reverse chained parameters at compile time" in {
      evaluator.compile("Patient?_has:Observation:patient:code=85354-9") must
        throwA[UnsupportedParameterException](message = "Reverse chained")
    }

    "reject special parameters other than _id at compile time" in {
      evaluator.compile("Patient?_list=42") must
        throwA[UnsupportedParameterException](message = "repository or index semantics")

      val handBuiltFilter = ParsedFhirQuery(
        "Patient",
        List(Parameter(FHIR_PARAMETER_CATEGORIES.SPECIAL, "", FHIR_SEARCH_SPECIAL_PARAMETERS.FILTER, Seq("" -> "name eq 'Smith'")))
      )
      evaluator.compile(handBuiltFilter) must throwA[UnsupportedParameterException]
    }

    "reject a parameter that is not supported by the configuration" in {
      val handBuilt = ParsedFhirQuery(
        "Patient",
        List(Parameter(FHIR_PARAMETER_CATEGORIES.NORMAL, FHIR_PARAMETER_TYPES.STRING, "unknown", Seq("" -> "x")))
      )

      evaluator.compile(handBuilt) must throwA[UnsupportedParameterException]
    }

    "reject _id with a prefix or modifier" in {
      val handBuilt = ParsedFhirQuery(
        "Patient",
        List(Parameter(FHIR_PARAMETER_CATEGORIES.SPECIAL, FHIR_PARAMETER_TYPES.TOKEN, FHIR_SEARCH_SPECIAL_PARAMETERS.ID, Seq("" -> "p1"), suffix = ":missing"))
      )

      evaluator.compile(handBuilt) must throwA[UnsupportedParameterException]
    }

    "reject unresolved placeholders at compile time" in {
      val validated = evaluator.validateXFhirQuery("Observation?date=ge{{today()}}")

      evaluator.compile(validated) must throwA[InvalidParameterException](message = "unresolved FHIRPath placeholder")
    }

    "report result parameters as ignored instead of failing" in {
      val compiled = evaluator.compile("Patient?gender=male&_count=1&_sort=name")

      compiled.ignoredParameters.map(_.name).toSet mustEqual Set(
        FHIR_SEARCH_RESULT_PARAMETERS.COUNT,
        FHIR_SEARCH_RESULT_PARAMETERS.SORT
      )
      compiled.query.searchParameters.map(_.name) mustEqual List("gender")
    }
  }
}

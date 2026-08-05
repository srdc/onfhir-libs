package io.onfhir.api.parsers

import io.onfhir.api._
import io.onfhir.api.model.Parameter
import io.onfhir.api.validation.ProfileRestrictions
import io.onfhir.config._
import io.onfhir.exception.{InvalidParameterException, UnsupportedParameterException}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FHIRResultParameterResolverTest extends Specification {
  sequential

  private val patientProfileUrl = "http://hl7.org/fhir/StructureDefinition/Patient"
  private val summaryElements = Set("id", "meta", "name")

  private val config = new FhirServerConfig("R4")
  config.resourceConfigurations = Map(
    "Patient" -> ResourceConf("Patient", profile = Some(patientProfileUrl))
  )
  config.resourceQueryParameters = Map(
    "Patient" -> Map(
      "name" -> searchParameter(
        "name",
        FHIR_PARAMETER_TYPES.STRING,
        Seq("name.family"),
        Seq(FHIR_DATA_TYPES.STRING)
      ),
      "birthdate" -> searchParameter(
        "birthdate",
        FHIR_PARAMETER_TYPES.DATE,
        Seq("birthDate"),
        Seq(FHIR_DATA_TYPES.DATE)
      ),
      "name-birthdate" -> searchParameter(
        "name-birthdate",
        FHIR_PARAMETER_TYPES.COMPOSITE,
        Seq("name", "birthDate"),
        Seq(FHIR_DATA_TYPES.STRING, FHIR_DATA_TYPES.DATE)
      )
    )
  )
  config.commonQueryParameters = Map.empty
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

  private val pageDefaults = FhirResultDefaults(
    defaultPageSize = 20,
    paginationMode = FhirPaginationMode.Page,
    totalHandling = FhirSearchTotalHandling.Accurate
  )
  private val resolver = new FHIRResultParameterResolver(config, pageDefaults)

  private def searchParameter(
      name: String,
      parameterType: String,
      paths: Seq[String],
      targetTypes: Seq[String]): SearchParameterConf =
    SearchParameterConf(
      url = s"http://example.org/SearchParameter/$name",
      pname = name,
      ptype = parameterType,
      paths = paths,
      targetTypes = targetTypes
    )

  private def resultParameter(
      name: String,
      values: Seq[(String, String)]): Parameter =
    Parameter(
      paramCategory = FHIR_PARAMETER_CATEGORIES.RESULT,
      paramType = "",
      name = name,
      valuePrefixList = values
    )

  "FHIRResultParameterResolver" should {
    "resolve ascending and descending sort fields" in {
      val sort = resultParameter(
        FHIR_SEARCH_RESULT_PARAMETERS.SORT,
        Seq(
          "" -> "name",
          FHIR_PREFIXES_MODIFIERS.DESCENDING -> "birthdate"
        )
      )

      resolver.resolveSortingParameters("Patient", List(sort)) mustEqual Seq(
        ("name", 1, Seq("name.family" -> FHIR_DATA_TYPES.STRING)),
        ("birthdate", -1, Seq("birthDate" -> FHIR_DATA_TYPES.DATE))
      )
    }

    "reject unsupported and composite sort fields" in {
      val unsupported = resultParameter(
        FHIR_SEARCH_RESULT_PARAMETERS.SORT,
        Seq("" -> "unknown")
      )
      val composite = resultParameter(
        FHIR_SEARCH_RESULT_PARAMETERS.SORT,
        Seq("" -> "name-birthdate")
      )

      resolver.resolveSortingParameters("Patient", List(unsupported)) must
        throwA[UnsupportedParameterException]
      resolver.resolveSortingParameters("Patient", List(composite)) must
        throwA[UnsupportedParameterException]
    }

    "resolve every supported summary mode" in {
      resolver.resolveSummary("Patient", "false") must beNone
      resolver.resolveSummary("Patient", "true") must beSome(true -> summaryElements)
      resolver.resolveSummary("Patient", "data") must beSome(false -> Set(FHIR_COMMON_FIELDS.TEXT))
      resolver.resolveSummary("Patient", "text") must beSome.which {
        case (include, fields) =>
          include && fields.contains(FHIR_COMMON_FIELDS.TEXT) &&
            FHIR_MANDATORY_SUMMARY_FIELDS.forall(fields.contains)
      }
      resolver.resolveSummary("Patient", "count") must beSome(true -> Set.empty[String])
      resolver.resolveSummary("Patient", "invalid") must throwA[InvalidParameterException]
    }

    "resolve summary and element parameters from parsed results" in {
      val summary = resultParameter(
        FHIR_SEARCH_RESULT_PARAMETERS.SUMMARY,
        Seq("" -> "true")
      )
      val firstElements = resultParameter(
        FHIR_SEARCH_RESULT_PARAMETERS.ELEMENTS,
        Seq("" -> "name", "" -> "birthDate")
      )
      val repeatedElements = resultParameter(
        FHIR_SEARCH_RESULT_PARAMETERS.ELEMENTS,
        Seq("" -> "name")
      )

      resolver.resolveSummaryParameter("Patient", List(summary)) must
        beSome(true -> summaryElements)
      resolver.resolveElementsParameter(List(firstElements, repeatedElements)) mustEqual
        Set("name", "birthDate")
    }

    "apply page defaults and explicit page parameters" in {
      resolver.resolveCountPageParameters(Nil) mustEqual (20 -> Left(1))

      val count = resultParameter(FHIR_SEARCH_RESULT_PARAMETERS.COUNT, Seq("" -> "5"))
      val page = resultParameter(FHIR_SEARCH_RESULT_PARAMETERS.PAGE, Seq("" -> "3"))

      resolver.resolveCountPageParameters(List(count, page)) mustEqual (5 -> Left(3))
    }

    "prefer search-after and search-before cursors over page numbers" in {
      val count = resultParameter(FHIR_SEARCH_RESULT_PARAMETERS.COUNT, Seq("" -> "5"))
      val page = resultParameter(FHIR_SEARCH_RESULT_PARAMETERS.PAGE, Seq("" -> "3"))
      val after = resultParameter(
        FHIR_SEARCH_RESULT_PARAMETERS.SEARCH_AFTER,
        Seq("" -> "a", "" -> "b")
      )
      val before = resultParameter(
        FHIR_SEARCH_RESULT_PARAMETERS.SEARCH_BEFORE,
        Seq("" -> "c")
      )

      resolver.resolveCountPageParameters(List(count, page, after)) mustEqual
        (5 -> Right(Seq("a", "b") -> true))
      resolver.resolveCountPageParameters(List(count, page, before)) mustEqual
        (5 -> Right(Seq("c") -> false))
    }

    "use the offset sentinel when offset pagination has no cursor" in {
      val offsetResolver = new FHIRResultParameterResolver(
        config,
        pageDefaults.copy(paginationMode = FhirPaginationMode.Offset)
      )

      offsetResolver.resolveCountPageParameters(Nil) mustEqual
        (20 -> Right(Seq("") -> true))
    }

    "resolve total calculation from the request or configured default" in {
      resolver.resolveTotalParameter(Nil) must beTrue

      val none = resultParameter(FHIR_SEARCH_RESULT_PARAMETERS.TOTAL, Seq("" -> "none"))
      val estimate = resultParameter(FHIR_SEARCH_RESULT_PARAMETERS.TOTAL, Seq("" -> "estimate"))

      resolver.resolveTotalParameter(List(none)) must beFalse
      resolver.resolveTotalParameter(List(estimate)) must beTrue
    }
  }
}

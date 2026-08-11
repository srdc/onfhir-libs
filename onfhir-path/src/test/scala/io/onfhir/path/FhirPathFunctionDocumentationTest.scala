package io.onfhir.path

import io.onfhir.api.{FHIR_DATA_TYPES, FHIR_PARAMETER_TYPES}
import io.onfhir.path.annotation.{FhirPathFunction, FhirPathFunctionDocumentation, FhirPathFunctionReturn}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Constants that the annotation reader cannot resolve, used to check that unresolvable references are dropped.
 */
object UnknownFhirTypes {
  val SOMETHING = "something"
}

/**
 * Function library whose annotations declare their return/input types with FHIR_PARAMETER_TYPES and FHIR_DATA_TYPES
 * references rather than string literals, as onFhir's own libraries (e.g. FhirPathAggFunctions) do.
 */
class ConstantReferencingFunctionLibrary extends AbstractFhirPathFunctionLibrary {
  @FhirPathFunction(
    documentation = FhirPathFunctionDocumentation(
      detail = "A function whose types are given as constant references.",
      usageWarnings = None,
      parameters = None,
      returnValue = FhirPathFunctionReturn(detail = None, examples = Seq("5")),
      examples = Seq("tst:references()")
    ),
    insertText = "tst:references()",
    detail = "tst",
    label = "tst:references",
    kind = "Method",
    returnType = Seq(FHIR_PARAMETER_TYPES.NUMBER),
    inputType = Seq(FHIR_DATA_TYPES.INTEGER, FHIR_DATA_TYPES.DECIMAL)
  )
  def references(): Seq[FhirPathResult] = Nil

  @FhirPathFunction(
    documentation = FhirPathFunctionDocumentation(
      detail = "A function mixing string literals and constant references.",
      usageWarnings = None,
      parameters = None,
      returnValue = FhirPathFunctionReturn(detail = None, examples = Seq("'abc'")),
      examples = Seq("tst:mixed()")
    ),
    insertText = "tst:mixed()",
    detail = "tst",
    label = "tst:mixed",
    kind = "Method",
    returnType = Seq("string", FHIR_DATA_TYPES.CODE),
    inputType = Seq(UnknownFhirTypes.SOMETHING, FHIR_DATA_TYPES.STRING)
  )
  def mixed(): Seq[FhirPathResult] = Nil

  @FhirPathFunction(
    documentation = FhirPathFunctionDocumentation(
      detail = "A function accepting and returning any data type.",
      usageWarnings = None,
      parameters = None,
      returnValue = FhirPathFunctionReturn(detail = None, examples = Seq("true")),
      examples = Seq("tst:any()")
    ),
    insertText = "tst:any()",
    detail = "tst",
    label = "tst:any",
    kind = "Function",
    returnType = Seq(),
    inputType = Seq()
  )
  def any(): Seq[FhirPathResult] = Nil
}

@RunWith(classOf[JUnitRunner])
class FhirPathFunctionDocumentationTest extends Specification {

  private val documentations = new ConstantReferencingFunctionLibrary().getFunctionDocumentation()

  private def documentationOf(label: String) =
    documentations.find(_.label == label).getOrElse(throw new NoSuchElementException(s"No documentation for $label!"))

  "FhirPathFunction documentation reader" should {

    "resolve FHIR_PARAMETER_TYPES/FHIR_DATA_TYPES references to their plain values" in {
      val documentation = documentationOf("tst:references")
      documentation.returnType mustEqual Seq("number")
      documentation.inputType mustEqual Seq("integer", "decimal")
    }

    "read string literals and constant references within the same sequence" in {
      val documentation = documentationOf("tst:mixed")
      documentation.returnType mustEqual Seq("string", "code")
      // the unresolvable reference is dropped, the resolvable one is kept
      documentation.inputType mustEqual Seq("string")
    }

    "keep empty type sequences empty" in {
      val documentation = documentationOf("tst:any")
      documentation.returnType must beEmpty
      documentation.inputType must beEmpty
    }

    "read the remaining annotation fields of each function" in {
      val documentation = documentationOf("tst:references")
      documentation.insertText mustEqual "tst:references()"
      documentation.detail mustEqual "tst"
      documentation.kind mustEqual "Method"
      documentation.documentation.detail mustEqual "A function whose types are given as constant references."
      documentation.documentation.examples mustEqual Seq("tst:references()")
      // each function keeps its own documentation
      documentationOf("tst:any").documentation.detail mustEqual "A function accepting and returning any data type."
    }
  }

  "Default onFhir function libraries" should {

    "expose their annotated types as plain strings" in {
      val environment = FhirPathEnvironment(Nil, None)
      val aggregationDocumentations = FhirPathAggFunctionsFactory.getLibrary(environment, Nil).getFunctionDocumentation()

      val sum = aggregationDocumentations.find(_.label == "agg:sum").getOrElse(throw new NoSuchElementException("No documentation for agg:sum!"))
      sum.returnType mustEqual Seq("number")
      sum.inputType mustEqual Seq("number")

      // no type name of any default function is an Option rendering such as 'Some(number)'
      aggregationDocumentations.flatMap(d => d.returnType ++ d.inputType) must not(contain(beMatching("(Some|None).*")))
    }
  }
}

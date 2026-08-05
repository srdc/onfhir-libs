# onfhir-path

`onfhir-path` is a standalone Scala FHIRPath parser and evaluator over json4s
`JValue` content. It implements most of the FHIRPath N1 language surface and
adds FHIR-aware JSON navigation, typed Scala result access, path discovery,
custom function libraries, and optional reference, terminology, and identity
services. It has no server-runtime dependency.

## Specification compatibility

The implementation target is the
[FHIRPath Normative Release N1](https://hl7.org/fhirpath/N1/), specification
version 2.0.0 (formally, *HL7 Cross-Paradigm Specification: FHIRPath,
Release 1*).

The support tables below are an implementation inventory based on the parser,
evaluator, and automated tests. They are not a formal HL7 conformance
certification. A whole N1 section is marked **Supported** only when its
principal language surface is implemented; known omissions or behavioral
differences are marked **Partial**.

The `agg:`, `utl:`, and `nav:` libraries are onFHIR extensions and are not
part of N1. Behavior introduced by later FHIRPath releases should not be
assumed unless it is explicitly documented and tested by this module.

## Dependency

The artifact carries the Scala 2.13 binary-version suffix:

```xml
<dependency>
  <groupId>io.onfhir</groupId>
  <artifactId>onfhir-path_2.13</artifactId>
  <version>4.0.0</version>
</dependency>
```

## Quick start

Create an evaluator and pass it a FHIRPath expression plus parsed JSON:

```scala
import io.onfhir.path.FhirPathEvaluator
import org.json4s.jackson.JsonMethods.parse

val observation = parse("""
  {
    "resourceType": "Observation",
    "id": "f001",
    "status": "final",
    "code": {
      "coding": [
        {"system": "http://loinc.org", "code": "15074-8"},
        {"system": "http://snomed.info/sct", "code": "4544556"}
      ]
    },
    "valueQuantity": {"value": 6.3, "unit": "mmol/L"}
  }
""")

val evaluator = FhirPathEvaluator()

evaluator.evaluateString(
  "Observation.code.coding.code",
  observation
)
// Seq("15074-8", "4544556")

evaluator.satisfies(
  "Observation.code.coding.exists(code = '15074-8')",
  observation
)
// true
```

An expression may start with the resource type (`Observation.code`) or with
the first element (`code`). A path that does not match returns an empty
collection.

## Choosing an evaluation method

FHIRPath evaluates every expression to a collection. `FhirPathEvaluator`
provides both the raw result model and typed convenience methods:

| Method | Result |
|---|---|
| `evaluate` | `Seq[FhirPathResult]`, preserving FHIRPath result types |
| `evaluateString` | zero or more Scala `String` values |
| `evaluateNumerical` | zero or more Scala `BigDecimal` values |
| `evaluateBoolean` | zero or more Scala `Boolean` values |
| `evaluateDateTime` | zero or more `java.time.temporal.Temporal` values |
| `evaluateOptionalString`, `evaluateOptionalNumerical`, `evaluateOptionalBoolean`, `evaluateOptionalDateTime` | zero or one typed value; multiple results fail |
| `evaluateOptionalTime` | one `(LocalTime, Option[ZoneId])` value; empty, multiple, or non-time results currently fail |
| `evaluateAndReturnJson` | `None`, one `JValue`, or a `JArray` for multiple results |
| `satisfies` | one Boolean constraint result |

The raw result types include `FhirPathString`, `FhirPathNumber`,
`FhirPathBoolean`, `FhirPathDateTime`, `FhirPathTime`, `FhirPathQuantity`, and
`FhirPathComplex`. Every `FhirPathResult` can be converted back to json4s with
`toJson`.

`satisfies` is an onFHIR API for constraint checking, rather than an N1
language function. It returns the single Boolean result, throws when the
expression produces another non-empty shape, and returns `true` for an empty
result because the constraint is treated as not applicable.

## N1 support matrix

Status meanings:

| Status | Meaning |
|---|---|
| Supported | Principal N1 behavior is implemented and exercised by tests |
| Partial | Useful support exists, with known missing or differing behavior |
| Runtime | Supported through runtime validation rather than static type checking |
| Reference | Specification material used as guidance, not an executable feature |

### Specification sections

| N1 section | Status | Implementation notes |
|---|---|---|
| 1. Background | Reference | Defines the language goals and conventions; no runtime feature to implement. |
| 2. Navigation model | Supported | Traverses FHIR or ordinary JSON represented as json4s values. FHIR primitive extensions and choice names receive FHIR-aware handling. |
| 3. Path selection | Partial | Member navigation, optional root type, repeating elements, indexes, backtick identifiers, and polymorphic paths are implemented and tested. Because evaluation uses JSON rather than N1 model metadata, an unknown member normally returns empty instead of reporting an unresolved identifier. |
| 4. Expressions | Supported | Empty, Boolean, string, number, date/date-time, time, and quantity literals; invocation, indexing, polarity, precedence, and collection/singleton evaluation are implemented. |
| 5. Functions | Partial | Most categories are implemented. See the detailed function table below for known omissions and differences. |
| 6. Operations | Partial | Equality/equivalence, comparison, type, collection, Boolean, math, and date/time operator families are implemented and tested. Quantity comparison requires matching units; general UCUM unit conversion is not implemented. |
| 7. Aggregates | Supported | Both `aggregate(expression)` and `aggregate(expression, init)` are implemented with `$this`, `$index`, and `$total`. |
| 8. Lexical elements | Supported | The ANTLR grammar handles whitespace, block/line comments, escapes, identifiers, backtick identifiers, symbols, and N1 literal forms. `parseStrict` checks syntax and trailing input. |
| 9. Environment variables | Partial | The N1 `%context` and `%ucum` values are supported. `%loinc`, `%sct`, `%resource`, caller-supplied variables, and process-environment lookup are extensions; `%resource` depends on a supplied resolver. An undefined variable returns empty instead of the N1-required error. The additional FHIR `%vs-*` and `%ext-*` forms are explicitly not implemented. |
| 10. Types and reflection | Partial | `is`, `as`, and `ofType` support FHIR JSON type/choice navigation. The N1 `type()` reflection model and its `TypeInfo` structures are not implemented. |
| 11. Type safety and strict evaluation | Runtime | Syntax can be checked with `parseStrict`; type and cardinality rules are enforced during evaluation with `FhirPathException`. There is no compile-time static type checker. |
| 12. Formal specifications | Reference | The N1 grammar and model information are implementation references; this module uses its bundled ANTLR grammar and json4s/FHIR model adapter. |

### Standard function categories

| N1 section | Status | Supported surface and known gaps                                                                                                                                                                                                                                                                                                       |
|---|---|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 5.1 Existence | Supported | `empty`, `exists`, `all`, `allTrue`, `anyTrue`, `allFalse`, `anyFalse`, `subsetOf`, `supersetOf`, `count`, `distinct`, and `isDistinct`.                                                                                                                                                                                               |
| 5.2 Filtering and projection | Supported | `where`, `select`, `repeat`, and `ofType`.                                                                                                                                                                                                                                                                                             |
| 5.3 Subsetting | Supported | `single`, `first`, `last`, `tail`, `skip`, `take`, `intersect`, and `exclude`.                                                                                                                                                                                                                                                         |
| 5.4 Combining | Supported | `combine`, `union`, and the synonymous `\|` union operator are implemented. `union` and `\|` eliminate duplicate values. |
| 5.5 Conversion | Partial | `iif`; the Boolean, Integer, Decimal, Date, DateTime, Quantity, String, and Time conversion functions; and their `convertsTo...` predicates are implemented. `convertsToQuantity(unit)` supports same-unit checks but not general UCUM conversion. A known difference is that `toDateTime` fills absent time components with midnight. |
| 5.6 String manipulation | Supported | `indexOf`, `substring`, `startsWith`, `endsWith`, `contains`, `upper`, `lower`, `replace`, `matches`, `replaceMatches`, `length`, and `toChars` are implemented. `toChars` iterates Unicode code points, so supplementary characters remain single result items.                                                                       |
| 5.7 Math | Supported | `abs`, `ceiling`, `exp`, `floor`, `ln`, `log`, `power`, `round`, `sqrt`, and `truncate`.                                                                                                                                                                                                                                               |
| 5.8 Tree navigation | Supported | `children` and `descendants`.                                                                                                                                                                                                                                                                                                          |
| 5.9 Utility | Partial | `today`, `now`, and `timeOfDay` share one timestamp per expression evaluation. Both `trace` signatures return the input as required, but diagnostic logging is not implemented. |

Most standard behavior is exercised in `FhirPathEvaluatorTest`; the N1
conversion functions have focused coverage in
`FhirPathN1ConversionFunctionsTest`. These are project regression suites rather
than the official FHIRPath N1 test corpus.
When strict interoperability depends on a listed partial area, validate the
specific expressions used by your application.

## FHIR model behavior

When `isContentFhir` is `true` (the default), the evaluator understands FHIR
JSON choice-element names such as `valueQuantity`:

```scala
evaluator.evaluateOptionalNumerical(
  "Observation.value.ofType(Quantity).value",
  observation
)
// Some(BigDecimal("6.3"))
```

FHIR-specific `resolve()`, `extension(url)`, and `memberOf(url)` functions are
implemented separately from the standard N1 function inventory:

- `resolve()` requires an `IReferenceResolver`.
- `extension(url)` navigates ordinary and primitive extensions.
- `memberOf(url)` requires an `IFhirTerminologyValidator`.

## Evaluator configuration

### Environment variables

Additional variables are immutable configuration on an evaluator instance:

```scala
import org.json4s.JsonAST.JString

val configured = FhirPathEvaluator()
  .withEnvironmentVariable("targetCode", JString("15074-8"))

configured.evaluateString(
  "Observation.code.coding.where(code = %targetCode).code",
  observation
)
// Seq("15074-8")
```

### Reference resolution

Construct the evaluator with an application-provided `IReferenceResolver` to
enable `resolve()`. The library itself does not fetch resources:

```scala
val configured = FhirPathEvaluator(referenceResolver)

configured.evaluateString(
  "Observation.subject.resolve().gender",
  observation
)
```

Literal, canonical, and in-bundle reference behavior is controlled by the
resolver implementation. See `FhirPathEvaluatorTest` for a complete in-memory
resolver example.

### Terminology and identity services

Service-backed function libraries are optional:

```scala
val configured = FhirPathEvaluator()
  .withTerminologyService(terminologyService)
  .withIdentityService(identityService)
```

The application owns these service implementations. The default unit suite
does not call external terminology or identity endpoints; integration tests
for those functions require configured services.

## onFHIR function-library extensions

`withDefaultFunctionLibraries()` enables the bundled aggregation, utility,
and navigation libraries. Their explicit prefixes are `agg:`, `utl:`, and
`nav:`; supported functions can also be resolved without a prefix when the
name is unambiguous.

```scala
val extended = FhirPathEvaluator().withDefaultFunctionLibraries()

extended.evaluateAndReturnJson(
  "utl:createFhirReference('Observation', id)",
  observation
)
// Some({"reference":"Observation/f001"})

extended.evaluateString("'1+1+2'.utl:split('+')", observation)
// Seq("1", "1", "2")
```

The bundled libraries include helpers for grouping and aggregation, periods
and durations, FHIR Reference, CodeableConcept, and Quantity construction,
string splitting, date parsing, expression evaluation, and path navigation.

Register an application-specific function library with a prefix:

```scala
val configured = FhirPathEvaluator()
  .withFunctionLibrary("acme", customFunctionLibraryFactory)
```

The factory implements `IFhirPathFunctionLibraryFactory` and creates an
`AbstractFhirPathFunctionLibrary` for the current environment and input
collection.

## Evaluating ordinary JSON

Set `isContentFhir = false` when the input is ordinary JSON rather than FHIR
JSON. This disables FHIR-specific element-name transformation while retaining
the expression engine:

```scala
import org.json4s.JsonAST.{JInt, JObject, JString}

val row = JObject(
  "code" -> JString("C505"),
  "version" -> JInt(10)
)

val jsonEvaluator = new FhirPathEvaluator(isContentFhir = false)

jsonEvaluator.evaluateString(
  "code.substring(0, 3) & '.' & code.substring(3)",
  row
)
// Seq("C50.5")
```

## Finding concrete JSON paths

`evaluateToFindPaths` evaluates a path expression and reports every matching
JSON location as element names plus optional array indexes:

```scala
evaluator.evaluateToFindPaths(
  "Observation.code.coding.system",
  observation
)
// Seq(
//   Seq("code" -> None, "coding" -> Some(0), "system" -> None),
//   Seq("code" -> None, "coding" -> Some(1), "system" -> None)
// )
```

`getPathItemsWithRestrictions` is an onFHIR structural extractor intended for
path-shaped expressions such as FHIR SearchParameter expressions:

```scala
evaluator.getPathItemsWithRestrictions(
  "ActivityDefinition.relatedArtifact.where(type='composed-of').resource"
)
// Seq(
//   "ActivityDefinition" -> Nil,
//   "relatedArtifact" -> Seq("type" -> "composed-of"),
//   "resource" -> Nil
// )
```

The structural extractor accepts navigation, supported `where` restrictions,
array indexes, `extension(url)`, and choice-type casts. It is not a general
evaluator: branching or arbitrary predicate expressions can raise
`FhirPathException`.

## Parsing and errors

Use `parseStrict` when validating expressions before storing or executing
them:

```scala
FhirPathEvaluator.parseStrict("subject.reference") // parsed expression
FhirPathEvaluator.parseStrict("subject.reference + ") // throws
```

`parseStrict` rejects syntax errors and trailing input. `parse` retains the
historical parser behavior and is used by the evaluation convenience methods.
Type mismatches, invalid cardinality, unsupported structural extraction, and
evaluation failures are reported as `FhirPathException` or a parser error.

## Tests and examples

The principal API examples in this README are exercised by
[`FhirPathReadmeExampleTest`](src/test/scala/io/onfhir/path/FhirPathReadmeExampleTest.scala).
The comprehensive
[`FhirPathEvaluatorTest`](src/test/scala/io/onfhir/path/FhirPathEvaluatorTest.scala)
covers operators, standard functions, bundled extensions, FHIR edge cases,
path discovery, and regression scenarios using the JSON resources under
`src/test/resources`.

Run the module and its prerequisites with:

```shell
mvn -pl onfhir-path -am test
```

# onfhir-query

`onfhir-query` provides configuration-aware parsing and single-resource
evaluation for FHIR search expressions represented by the transport-neutral
models in `onfhir-common`. It also supports x-fhir-query statements whose
values contain FHIRPath placeholders.

The module does not expose HTTP routes, generate database queries, maintain a
search index, or execute a search across a repository. Server and storage
implementations can consume the parsed `Parameter` values and the reusable
in-memory matching facade provided here.

## Dependency

The artifact carries the Scala 2.13 binary-version suffix:

```xml
<dependency>
  <groupId>io.onfhir</groupId>
  <artifactId>onfhir-query_2.13</artifactId>
  <version>4.0.0</version>
</dependency>
```

Query depends on `onfhir-common`, `onfhir-expression`, and `onfhir-path`.
Applications do not need to declare those artifacts separately unless they
use their APIs directly.

## Responsibilities and entry points

| Responsibility | Public entry point | Result |
|---|---|---|
| Parse, resolve result controls, and match through one facade | `FhirQueryEvaluator` | Typed `ParsedFhirQuery`, `FhirResultControls`, and `CompiledFhirQuery` |
| Parse a resource-type search URI | `FhirQueryParser` | Resource type and validated `Parameter` values |
| Parse and resolve x-fhir-query placeholders | `XFhirQueryParser` | Validated `Parameter` values |
| Split or re-encode x-fhir-query content | `XFhirQueryUtil` | Resource/query parts or encoded parameter text |
| Resolve FHIR search result controls | `FHIRResultParameterResolver` | Sorting, projection, pagination, and total instructions |
| Match parsed parameters against one JSON resource | `ImMemorySearchUtil` | Boolean match result |

`FHIRSearchParameterValueParser` and the `Parameter` model are owned by
`onfhir-common`; Query composes them into the higher-level workflows above.
`InMemoryPrefixModifierHandler` is a lower-level implementation helper. New
consumers should call `ImMemorySearchUtil` rather than depending on the helper
directly.

The spelling of `ImMemorySearchUtil` is historical and retained for binary and
source compatibility.

## Combined usage

`FhirQueryEvaluator` in the `io.onfhir.query` package fronts the workflows
below behind one synchronous entry point. Its `parse`, `parseXFhirQuery`, and
`validateXFhirQuery` methods take full query statements such as
`Patient?name=Smith` and return a typed `ParsedFhirQuery`. `compile` turns a
parsed query into a `CompiledFhirQuery`: a reusable, thread-safe
single-resource predicate intended for workloads that parse one query and
evaluate many resources, such as subscription criteria or conditional
operations.

```scala
import io.onfhir.config.FhirEndpointSettings
import io.onfhir.query.FhirQueryEvaluator

// configuredServer is a FhirServerConfig with the search-parameter definitions
val evaluator = FhirQueryEvaluator(
  configuredServer,
  FhirEndpointSettings("https://example.org/fhir")
)

val compiled = evaluator.compile("Patient?gender=male&birthdate=ge2000-01-01")
compiled.matches(patientJson) // Boolean; compiled once, evaluated per resource
```

`parse` and `compile` serve different intents. A parsed query may contain
parameters that only a repository can execute; `compile` checks local
evaluability up front so that an unsupported criterion fails when it is
registered rather than when the first resource is evaluated. Chained,
reverse chained (`_has`), compartment, and special parameters other than
`_id` are rejected with `UnsupportedParameterException`; values still holding
`{{...}}` placeholders are rejected with `InvalidParameterException`. Result
category parameters never affect a single resource's membership, so they are
ignored by `matches` and reported through `CompiledFhirQuery.ignoredParameters`.

`resolveResultControls` maps the result parameters of a parsed query onto one
typed `FhirResultControls` value (sorting, `_summary`/`_elements` projection,
page size, page or cursor pagination, and total calculation). It requires
`FhirResultDefaults` to be supplied to the evaluator constructor. Parameters
the resolver does not cover, such as `_include` and `_revinclude`, are carried
unchanged in `FhirResultControls.unresolvedResultParameters`.

## Parsing an ordinary FHIR query

Parsing is driven by `FhirServerConfig`. The configuration must declare the
resource type and every search parameter used by the query.

```scala
import io.onfhir.api.FHIR_PARAMETER_TYPES
import io.onfhir.api.parsers.FhirQueryParser
import io.onfhir.config.{FhirSearchHandling, FhirServerConfig, SearchParameterConf}

val config = new FhirServerConfig("R4")
config.FHIR_RESULT_PARAMETERS = Nil
config.FHIR_SPECIAL_PARAMETERS = Nil
config.commonQueryParameters = Map.empty
config.resourceQueryParameters = Map(
  "Patient" -> Map(
    "name" -> SearchParameterConf(
      url = "http://hl7.org/fhir/SearchParameter/Patient-name",
      pname = "name",
      ptype = FHIR_PARAMETER_TYPES.STRING,
      paths = Seq("name.family")
    )
  )
)

val parser = new FhirQueryParser(config, FhirSearchHandling.Strict)
val (resourceType, parameters) =
  parser.parseQuery("Patient?name=Smith&name=Jones")

// resourceType == "Patient"
// parameters contains two name parameters in input order
```

The URI path must contain exactly one segment: the FHIR resource type.
`Patient?...` is valid; a server-relative path such as
`fhir/Patient?...` is not accepted by this API.

The configured `FhirSearchHandling` is used when no request-level handling
override is available. Strict handling rejects unsupported parameters;
lenient handling ignores them. Malformed values for supported parameters are
still rejected.

Parsing uses `OrderedQuery` from Common before converting the query to the
search parser's multimap representation. Repeated keys and their value order
are retained and percent-encoded values are decoded. At the multimap boundary,
a key without `=` and a key with an empty value are both represented by the
empty string.

## Parsing x-fhir-query

x-fhir-query embeds FHIRPath expressions inside `{{...}}` placeholders. The
caller supplies a configured `FhirPathEvaluator`, optional named context
values, and an optional JSON input against which expressions are evaluated.
In the example below, `configuredServer` is a `FhirServerConfig` containing
the Observation search-parameter definitions.

```scala
import io.onfhir.config.FhirSearchHandling
import io.onfhir.expression.XFhirQueryParser
import io.onfhir.path.FhirPathEvaluator
import org.json4s.JsonAST.JString

val parser = new XFhirQueryParser(
  configuredServer,
  FhirSearchHandling.Strict,
  FhirPathEvaluator()
)

val parameters = parser.parseXFhirQuery(
  rtype = "Observation",
  queryStmt = "subject={{%patientRef}}&date=ge{{today()}}&status=final",
  contextParams = Map("patientRef" -> JString("Patient/123"))
)
```

`parseXFhirQuery` resolves placeholders and then validates the resulting
search values. `parseXFhirQueryShape` validates the search and FHIRPath syntax
while preserving the placeholders for later resolution.

### Placeholder result normalization

| Search parameter type | Accepted FHIRPath result |
|---|---|
| `reference` | Strings or FHIR `Reference`-like objects containing `reference` |
| `number` | Numbers |
| `quantity` | FHIRPath quantities or JSON Quantity-like objects |
| `date` | FHIRPath date, dateTime, or instant values |
| `token` | Strings or Coding, CodeableConcept, and Identifier-like JSON objects |
| `string`, `uri` | Strings |
| `composite` | Placeholders are not supported |

Collections become comma-separated FHIR search alternatives. Numeric values
are rendered without unnecessary trailing zeroes. FHIRPath quantity literals
with a unit are normalized to `value|http://unitsofmeasure.org|unit`.

Prefixes outside a placeholder are retained, for example
`date=gt{{today()}}` and `value-quantity=le{{4.5 'mg'}}`.

### x-fhir-query syntax constraints

The raw splitter preserves `&` and `=` characters inside a placeholder and
supports repeated parameter names. Its current grammar assumes:

- at most one non-nested `{{...}}` placeholder in each parameter value;
- braces occur only as part of placeholder syntax;
- the value is the query portion after `?` when passed to
  `parseXFhirQuery`.

Empty queries, missing resource types, invalid FHIRPath syntax, incompatible
FHIRPath result types, and unresolved placeholders fail with
`FhirExpressionException` where the x-fhir-query layer can provide expression
context.

## Resolving search result parameters

`FHIRResultParameterResolver` converts parsed result-category parameters into
instructions usable by a repository implementation. It requires explicit
`FhirResultDefaults`; it does not read server singleton configuration.

| Method | Behavior |
|---|---|
| `resolveSortingParameters` | Resolves `_sort` to parameter name, direction, paths, and target types |
| `resolveSummaryParameter` | Resolves `_summary` to an include/exclude projection |
| `resolveElementsParameter` | Collects requested `_elements` into a set |
| `resolveCountPageParameters` | Resolves `_count` and page/cursor pagination using configured defaults |
| `resolveTotalParameter` | Reports whether total calculation was requested |

Pagination returns `(count, pageOrCursor)`. `Left(page)` represents numbered
page pagination. `Right((values, forward))` represents cursor/offset
pagination, where `forward` distinguishes `_searchafter` from
`_searchbefore`.

The supported `_summary` values are `false`, `true`, `data`, `text`, and
`count`. Summary and sorting behavior depend on the resource definitions in
`FhirServerConfig`.

## Matching one resource in memory

`ImMemorySearchUtil` evaluates already-parsed search parameters against a
single json4s `JValue`. Extraction and matching are separate so callers can
reuse extracted values when evaluating more than one condition.

```scala
import io.onfhir.api.{FHIR_DATA_TYPES, FHIR_PARAMETER_CATEGORIES, FHIR_PARAMETER_TYPES}
import io.onfhir.api.model.Parameter
import io.onfhir.api.util.ImMemorySearchUtil
import io.onfhir.config.{FhirEndpointSettings, SearchParameterConf}
import org.json4s.jackson.JsonMethods.parse

val searchConfig = SearchParameterConf(
  url = "http://hl7.org/fhir/SearchParameter/Patient-name",
  pname = "name",
  ptype = FHIR_PARAMETER_TYPES.STRING,
  paths = Seq("name.family"),
  targetTypes = Seq(FHIR_DATA_TYPES.STRING),
  restrictions = Seq(Nil)
)

val resource = parse(
  """{"resourceType":"Patient","name":[{"family":"Smith"}]}"""
)
val parameter = Parameter(
  paramCategory = FHIR_PARAMETER_CATEGORIES.NORMAL,
  paramType = FHIR_PARAMETER_TYPES.STRING,
  name = "name",
  valuePrefixList = Seq("" -> "Smith")
)
val values = ImMemorySearchUtil.extractValuesAndTargetTypes(searchConfig, resource)
val matches = ImMemorySearchUtil.handleSimpleParameter(
  parameter,
  searchConfig,
  values,
  FhirEndpointSettings("https://example.org/fhir")
)
```

`FhirEndpointSettings` is required because reference comparison treats a
relative local reference and an absolute reference under the configured FHIR
root as equivalent.

### In-memory support inventory

This table describes the implementation surface, not a claim of complete FHIR
Search conformance.

| Search type | Implemented behavior |
|---|---|
| `string` | Case-insensitive starts-with matching; `:exact` and `:contains` |
| `number` | Integer, decimal, and Range targets with comparison prefixes and implicit decimal precision |
| `date` | Date/dateTime/instant, Period, and Timing targets with range-aware comparison prefixes |
| `token` | Primitive tokens and common Coding, CodeableConcept, Identifier, and contact-point shapes; selected `:text`, `:not`, and `:of-type` behavior |
| `quantity` | Quantity-family and SampledData targets, including system/code or unit matching |
| `reference` | Relative/absolute references, `:identifier`, `:type`, `:not`, and canonical matching |
| `uri` | Exact, `:above`, `:below`, and `:not` matching |
| `composite` | Each component evaluated through its own component search-parameter configuration, all components required to match within the same base context element |

The common `:missing` modifier is handled through the public facade. Token
terminology modifiers such as `:in`, `:not-in`, `:above`, and `:below` are not
implemented for general terminology expansion and may fail explicitly.

Search definitions must align `paths`, `targetTypes`, and `restrictions` by
index. For a composite parameter those paths describe the base context of the
composite's own expression, not the component elements: the component element
paths are taken from the configurations named in `targets`, and the `$`
separated parts of the search statement bind to those components in order.

A component configuration's paths are absolute from the resource root, so they
are made relative to the base context they are evaluated against. A composite
therefore also resolves where its base context is a nested element, such as
Observation `component`, the `context-type-value` family's `useContext`, or the
MolecularSequence coordinate composites' `referenceSeq`. Every component must be
satisfied within the same base context element, so a statement is not satisfied
by components matching in different repeating elements. Where a composite
declares both the resource root and a more specific element as base contexts,
component paths under the more specific one are excluded from the root context,
which keeps that correlation in place for parameters such as Observation
`combo-code-value-quantity`.

Path restrictions are applied while extracting values; a restriction is
positioned relative to the end of its path, so it survives being made relative
to a base context unless it addresses an element outside it, in which case that
path is not evaluated. The evaluator does not resolve remote references, call
terminology services, convert UCUM units, or inspect other resources in a
repository.

## Error behavior

- Unsupported configured search parameters normally raise
  `UnsupportedParameterException` under strict handling.
- Malformed values and invalid modifier/type combinations raise
  `InvalidParameterException` or a more specific configuration/runtime error.
- x-fhir-query resolution wraps validation failures with
  `FhirExpressionException` and includes the original expression where
  available.
- Callers should treat undocumented type/modifier combinations as unsupported
  rather than relying on incidental matching behavior.

## Tests and examples

Focused ordinary-query and in-memory behavior is covered by:

- [`FhirQueryEvaluatorTest`](src/test/scala/io/onfhir/query/FhirQueryEvaluatorTest.scala)
- [`CompiledFhirQueryTest`](src/test/scala/io/onfhir/query/CompiledFhirQueryTest.scala)
- [`FHIRResultParameterResolverTest`](src/test/scala/io/onfhir/api/parsers/FHIRResultParameterResolverTest.scala)
- [`FhirQueryParserEncodingTest`](src/test/scala/io/onfhir/api/parsers/FhirQueryParserEncodingTest.scala)
- [`InMemorySearchUtilCharacterizationTest`](src/test/scala/io/onfhir/api/util/InMemorySearchUtilCharacterizationTest.scala)
- [`InMemorySearchUtilModifierTest`](src/test/scala/io/onfhir/api/util/InMemorySearchUtilModifierTest.scala)
- [`XFhirQueryParserRegexTest`](src/test/scala/io/onfhir/expression/XFhirQueryParserRegexTest.scala)
- [`XFhirQueryParserTest`](src/test/scala/io/onfhir/expression/XFhirQueryParserTest.scala)

Run Query and its prerequisites from the reusable-library repository root:

```shell
mvn -pl onfhir-query -am test
```

When changing parsing or in-memory semantics, add focused characterization
coverage before updating the support inventory above.

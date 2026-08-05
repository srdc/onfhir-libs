# onfhir-query SDK facade: `FhirQueryEvaluator` (design)

Status: IMPLEMENTED 2026-08-05 (authored and approved the same day). Sources in
`onfhir-query/src/main/scala/io/onfhir/query/`, suites `FhirQueryEvaluatorTest` (20)
and `CompiledFhirQueryTest` (13) green; uncommitted per module-by-module flow.
Scope: `onfhir-query` module only. Purely additive; no existing signature changes.

## Motivation

The module exposes parsing (`FhirQueryParser`, `XFhirQueryParser`), result-parameter
resolution (`FHIRResultParameterResolver`), and per-parameter in-memory matching
primitives (`ImMemorySearchUtil`), but no combined front door. In particular there is
**no whole-query "does this resource satisfy this query" entry point**: callers must
resolve `SearchParameterConf`s, extract element values, dispatch simple vs composite,
and apply FHIR AND semantics themselves. The facade fills that gap following the
`FhirValidator` precedent (workflow-named facade, `require`-checked config, companion
`apply`) and the `FhirPathEvaluator` naming precedent (parse + evaluate against a
resource). The dominant matching use cases (subscription criteria, conditional
create/update, CDS filtering) are *parse once, match many resources*, so the
centerpiece is a compiled query, in the spirit of `java.util.regex.Pattern.compile`.

## Public API

New package `io.onfhir.query` in `onfhir-query` (internals stay in `io.onfhir.api.*`
and `io.onfhir.expression`).

```scala
package io.onfhir.query

final class FhirQueryEvaluator(
    val fhirConfig: FhirServerConfig,                    // search-param metadata required (not BaseFhirConfig)
    endpointSettings: FhirEndpointSettings,              // per-server identity: constructor, not per call
    defaultSearchHandling: FhirSearchHandling = FhirSearchHandling.Strict,
    resultDefaults: Option[FhirResultDefaults] = None,   // required only by resolveResultControls
    fhirPathEvaluator: FhirPathEvaluator = FhirPathEvaluator()) {  // used only by x-fhir-query methods

  /** Parse a plain FHIR query statement, e.g. "Patient?name=Smith&_sort=-birthdate". */
  def parse(query: String): ParsedFhirQuery

  /** Parse an x-fhir-query statement, resolving {{...}} FHIRPath placeholders. */
  def parseXFhirQuery(query: String,
                      context: Map[String, JValue] = Map.empty,
                      input: JValue = JNothing): ParsedFhirQuery

  /** Validate x-fhir-query shape; placeholders preserved unresolved. */
  def validateXFhirQuery(query: String): ParsedFhirQuery

  /** Resolve result-category parameters into typed instructions. Requires resultDefaults. */
  def resolveResultControls(parsed: ParsedFhirQuery): FhirResultControls

  /** Compile into a reusable single-resource predicate. Rejects non-locally-evaluable
    * parameters here (fail at registration time, not at first match). */
  def compile(parsed: ParsedFhirQuery): CompiledFhirQuery
  def compile(query: String): CompiledFhirQuery          // = compile(parse(query))
}

object FhirQueryEvaluator {
  def apply(/* same parameters and defaults */): FhirQueryEvaluator
}

final case class ParsedFhirQuery(resourceType: String, parameters: List[Parameter]) {
  def searchParameters: List[Parameter]   // paramCategory NORMAL (includes composite ptype)
  def resultParameters: List[Parameter]   // paramCategory RESULT
  def encode: String                      // "Patient?name=Smith"; placeholder-preserving
}

final class CompiledFhirQuery private[query] (...) {
  val query: ParsedFhirQuery
  val ignoredParameters: List[Parameter]  // RESULT params: do not affect membership
  def matches(resource: JValue): Boolean  // pure, thread-safe
}

final case class FhirResultControls(
    sorting: List[FhirSortInstruction],
    summary: Option[FhirElementProjection],
    elements: Set[String],
    pageSize: Int,
    pagination: FhirPaginationInstruction,
    includeTotal: Boolean,
    unresolvedResultParameters: List[Parameter])  // e.g. _include/_revinclude: carried, not resolved

final case class FhirSortInstruction(paramName: String, descending: Boolean,
                                     pathsAndTargetTypes: Seq[(String, String)])

final case class FhirElementProjection(include: Boolean, elements: Set[String])

sealed trait FhirPaginationInstruction
object FhirPaginationInstruction {
  final case class ByPage(page: Int) extends FhirPaginationInstruction
  final case class ByCursor(values: Seq[String], forward: Boolean) extends FhirPaginationInstruction
}
```

Use `List[Parameter]` throughout (module idiom) — not `Vector`. All new types live in
`io.onfhir.query`; suggested files: `FhirQueryEvaluator.scala`, `ParsedFhirQuery.scala`,
`CompiledFhirQuery.scala`, `FhirResultControls.scala` (implementer may reorganize).

## Semantics

### Construction

`require` non-null: `fhirConfig`, `fhirConfig.resourceQueryParameters`,
`fhirConfig.commonQueryParameters`, `fhirConfig.FHIR_RESULT_PARAMETERS`,
`fhirConfig.FHIR_SPECIAL_PARAMETERS` (the value parser dereferences these).
Mirror `FhirValidator`'s message style. Everything is synchronous — no `Future`,
no `ExecutionContext` (matching and parsing are pure; contrast with validation).

### parse / parseXFhirQuery / validateXFhirQuery

Thin composition over existing classes — do not duplicate their logic:

- `parse` delegates to `FhirQueryParser.parseQuery` (URI-based; resource type is the
  single path segment) and wraps the `(String, List[Parameter])` tuple.
- `parseXFhirQuery` splits with `XFhirQueryUtil.splitResourceTypeAndQuery`, then
  delegates to `XFhirQueryParser.parseXFhirQuery(rtype, queryPart.getOrElse(""), context, input)`.
- `validateXFhirQuery` same split, delegates to `parseXFhirQueryShape`.
- `ParsedFhirQuery.encode` joins `XFhirQueryUtil.encodeParameterPreservingPlaceholders`
  per parameter with `&`, prefixed `resourceType?`; bare `resourceType` when no parameters.

Existing exception behavior passes through unchanged (`InvalidParameterException`,
`UnsupportedParameterException`, `FhirExpressionException`).

### resolveResultControls

Throws `IllegalStateException` with a message naming the constructor parameter when
`resultDefaults` is `None`. Otherwise a mechanical mapping over
`FHIRResultParameterResolver` (instantiate once per evaluator):

| Field | Source | Mapping |
|---|---|---|
| `sorting` | `resolveSortingParameters` | `(name, dir, paths)` → `FhirSortInstruction(name, dir == -1, paths)` |
| `summary` | `resolveSummaryParameter` | `Option[(Boolean, Set[String])]` → `FhirElementProjection(include, elements)`; note `_summary=count` → `Some(FhirElementProjection(true, Set.empty))` (count-only) |
| `elements` | `resolveElementsParameter` | as-is |
| `pageSize`, `pagination` | `resolveCountPageParameters` | `(count, Left(page))` → `ByPage`; `(count, Right((values, fwd)))` → `ByCursor` |
| `includeTotal` | `resolveTotalParameter` | as-is |
| `unresolvedResultParameters` | — | RESULT-category params whose name is none of `_sort`, `_summary`, `_elements`, `_count`, `_page`, `_searchafter`, `_searchbefore`, `_total` (use `FHIR_SEARCH_RESULT_PARAMETERS` constants) |

Only result-category parameters are consulted; search parameters in the same
`ParsedFhirQuery` are ignored by this method.

### compile — category dispatch (all checks happen here, not in `matches`)

| Parameter | Disposition |
|---|---|
| category NORMAL, ptype != composite | evaluate via `ImMemorySearchUtil.handleSimpleParameter`; resolve `SearchParameterConf` now via `fhirConfig.findSupportedSearchParameter(rtype, name)`; missing conf → `UnsupportedParameterException` |
| category NORMAL, ptype composite | evaluate via `handleCompositeParameter` with `fhirConfig.getSupportedParameters(rtype)` as the valid-conf map |
| category SPECIAL, name `_id` | evaluate as id equality (below); non-empty prefix or suffix → `UnsupportedParameterException` |
| category SPECIAL (other: `_list`, `_filter`, `_text`, `_content`, `_query`) | `UnsupportedParameterException` — repository/index semantics |
| category CHAINED / REVCHAINED / COMPARTMENT | `UnsupportedParameterException` — requires a repository |
| category RESULT | collected into `ignoredParameters`; never affects `matches` |

Additional compile-time guard: any parameter value containing `{{` (unresolved
placeholder, e.g. output of `validateXFhirQuery`) → `InvalidParameterException`.
Exception messages must name the offending parameter and say *why* it cannot be
evaluated locally (e.g. "chained parameters require a repository and cannot be
evaluated against a single resource").

### CompiledFhirQuery.matches

1. Resource-type gate: `resource \ "resourceType"` must be a `JString` equal to the
   compiled `resourceType`; otherwise `false` (subscription semantics — a criterion
   `Observation?...` never matches a Patient).
2. Empty predicate list (e.g. `"Patient"` or only result params) → `true`.
3. FHIR AND across parameters (`forall`); OR across each parameter's
   `valuePrefixList` is already implemented inside the `ImMemorySearchUtil` handlers.
4. Element extraction via `ImMemorySearchUtil.extractValuesAndTargetTypes(conf, resource)`,
   memoized per `matches` call across parameters sharing the same conf (keyed by
   `conf.pname`) — e.g. `date=ge2020&date=le2021` extracts once. The memo map is
   call-local; the object holds no mutable state (thread-safe, document in scaladoc).
5. `_id`: matches when the resource's `id` element is a `JString` equal to any of the
   parameter's values (OR), honoring the values as parsed (no prefix/modifier logic).
6. `:missing` and modifiers flow through `handleSimpleParameter` unchanged.

## Non-goals (v1)

No repository execution, no `_include`/`_revinclude` resolution, no terminology
expansion for token `:in`/`:not-in`/`:above`/`:below` (existing explicit failures
propagate from the primitives at match time), no `Future`-based API, no
`onfhir-sdk` aggregate artifact, no deprecation of any existing entry point.

## Tests (specs2, module conventions: `@RunWith(classOf[JUnitRunner])`, `sequential`)

New suites under `onfhir-query/src/test/scala/io/onfhir/query/`. Crib
`FhirServerConfig` setup from the existing suites (`FHIRResultParameterResolverTest`,
`XFhirQueryParserTest`, `InMemorySearchUtilModifierTest`). Required coverage:

1. `parse` round-trip: typed result, category views, `encode`; repeated params kept in order.
2. `parseXFhirQuery` with context placeholder + function placeholder; `validateXFhirQuery` preserves placeholders; both wrap errors in `FhirExpressionException`.
3. `resolveResultControls`: sort direction mapping, `_summary=count` count-only shape, page vs cursor pagination (both pagination modes), `includeTotal` default vs explicit, `_include` lands in `unresolvedResultParameters`; missing `resultDefaults` → `IllegalStateException`.
4. `compile` rejections: chained, `_has`, `_filter`, unknown parameter, unresolved placeholder, `_id` with modifier — each with informative message.
5. `matches`: resource-type gate; empty query matches; AND across repeated params (both directions: pass and fail); OR within comma values; composite parameter; reference param with relative vs absolute URL under `endpointSettings` root; `_id` equality; `:missing`; result params ignored (`ignoredParameters` populated, `matches` unaffected).
6. Thread-safety smoke: one `CompiledFhirQuery` shared across a `par` collection of resources yields stable results.

## README and verification

- README: add the facade as the first row of the entry-points table and a short
  "Combined usage" section with one compile-and-match example; keep existing
  sections (the facade composes them). Match the README's existing voice.
- Gates (all must pass): `mvn -pl onfhir-query -am test`,
  `scripts/check-forbidden-imports.ps1`, `scripts/check-binary-compatibility.ps1`
  (additive-only ⇒ MiMa vs 3.3 must stay clean).
- Do not commit; leave changes in the working tree (module-by-module commit flow is
  handled by the repo owner).

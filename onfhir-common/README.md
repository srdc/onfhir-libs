# onfhir-common

`onfhir-common` is the foundation the other reusable onFHIR libraries are built
on. It holds the FHIR JSON aliases and constants, the neutral request/response
and reference models, the runtime configuration and parsed-definition models,
the service and validation interfaces other modules implement, and the shared
JSON, date, path, and I/O utilities.

It is transport-neutral and FHIR-release neutral: no HTTP framework, no Akka or
Pekko, no persistence, no FHIR release package, and no runnable server. FHIR
content is plain json4s JSON (`io.onfhir.api.Resource`, an alias for `JObject`),
so the same models carry R4, R5, or STU3 content.

Maven coordinate: `io.onfhir:onfhir-common_2.13`. Most other library modules
depend on it, so applications normally get it transitively and depend on it
directly only when they use these APIs themselves.

## What is in it

| Area | Principal APIs |
| --- | --- |
| FHIR JSON aliases and constants | `io.onfhir.api.Resource`, the `FHIR_*` constant objects (common fields, data types, parameter types and categories, interactions, bundle fields) |
| Runtime configuration | `FhirEndpointSettings`, `FhirRequestDefaults`, `FhirResultDefaults`, `FhirCapabilityDefaults`, `FhirSubscriptionSettings`, and the typed enumerations `FhirSearchHandling`, `FhirReturnPreference`, `FhirPaginationMode`, `FhirSearchTotalHandling`, `FhirVersioningPolicy` |
| Parsed FHIR definitions | `BaseFhirConfig`, `FhirServerConfig`, `ProfileRestrictions`, `SearchParameterConf`, `FHIRSearchParameter`, `FHIRCapabilityStatement`, `OperationConf`, `FHIRCompartmentDefinition`, `TerminologyServiceConf` |
| Neutral request/response models | `FHIRRequest`, `FHIRResponse`, `FHIROperationRequest`, `FHIROperationResponse`, `FHIRSearchResult`, `OutcomeIssue`, `Parameter` |
| Neutral HTTP value types | `HttpStatus`, `HttpMethod`, `HttpHeaders`, `FhirMediaType`, `FhirContentType`, `EntityTagCondition`, `AuthenticateChallenge`, `OrderedQuery` |
| References | `FhirReference` and its cases: literal, logical, canonical, contained (`#id`), and Bundle UUID |
| Extension interfaces | `IFhirTerminologyService`, `IFhirIdentityService`, `IFhirConfigReader`, `IFhirFoundationResourceParser`, `IFhirResourceValidator`, `IFhirTerminologyValidator`, `IReferenceResolver`, `IExternalFhirReferenceResolver` |
| Reference resolution | `AbstractReferenceResolver`, `DefaultReferenceResolver`, `SimpleReferenceResolver` |
| Parsers | `FHIRSearchParameterValueParser`, `BundleRequestParser` |
| Utilities | `FHIRUtil`, `FhirPatchUtil`, `JsonFormatter`, `DateTimeUtil`, `IOUtil`, `OnFhirZipInputStream` |

The `FHIR_*` constants and the neutral HTTP types exist so that the library
family, the server modules, and consuming applications name the same FHIR and
HTTP concepts identically without agreeing on a web framework.

## Typed runtime settings

Runtime settings are small typed values rather than a global configuration
singleton, so a library caller passes exactly what an API needs and an invalid
value fails at construction:

```scala
import io.onfhir.config.{FhirEndpointSettings, FhirResultDefaults, FhirPaginationMode,
  FhirSearchHandling, FhirSearchTotalHandling}

val endpoint = FhirEndpointSettings("https://example.org/fhir")
val handling = FhirSearchHandling.Strict          // Prefer: handling=strict
val results = FhirResultDefaults(
  defaultPageSize = 20,
  paginationMode = FhirPaginationMode.Offset,
  totalHandling = FhirSearchTotalHandling.Accurate)
```

The enumerations parse and render their FHIR wire codes through `fromCode` and
`code`, and reject unknown values, so header and parameter handling does not
rely on loose strings.

## Working with FHIR JSON

`JsonFormatter` supplies the json4s formats and parsing/serialization helpers
used across the family:

```scala
import io.onfhir.api.Resource
import io.onfhir.util.JsonFormatter._

val patient: Resource = """{"resourceType":"Patient","id":"p1"}""".parseJson
val serialized: String = patient.toJson
```

`FHIRUtil` is the shared helper for FHIR content and search values. Its main
groups are resource metadata access (`extractResourceType`,
`extractIdFromResource`, `extractVersionFromResource`, `getReference`,
`extractProfilesFromBson`, `extractValueOptionByPath`), search value parsing
(`parseReferenceValue`, `parseTokenValue`, `parseQuantityValue`,
`parseCanonicalValue`, `resolveReferenceValue`, `calculatePrecisionDelta`),
element path handling (`normalizeElementPath`, `mergeElementPath`,
`splitElementPathIntoElemMatchAndQueryPaths`), FHIR `Parameters` navigation
(`getParameterValueByName`, `getParameterValueByPath`), and Bundle construction
(`createBundle`, `createTransactionBatchBundle`).

```scala
import io.onfhir.api.util.FHIRUtil

FHIRUtil.parseReferenceValue("http://example.org/fhir/Observation/1x2/_history/2")
// (Some("http://example.org/fhir"), "Observation", "1x2", Some("2"))

FHIRUtil.parseTokenValue("http://loinc.org|500-5")
// (Some("http://loinc.org"), Some("500-5"))

FHIRUtil.resourceLocation(endpoint, "Patient", "p1")
// "https://example.org/fhir/Patient/p1"
```

Two behaviors are worth knowing when reading FHIR JSON through these helpers:

- `extractValueOption[Seq[T]]` returns `Some(Nil)` for an absent repeating
  element, because json4s extracts nothing into an empty collection. Test
  presence with `(resource \ "element") == JNothing` instead.
- `createBundle` and `createTransactionBatchBundle` take already-formed
  `Bundle.entry` objects, not bare resources; the caller wraps each resource
  and supplies `fullUrl`, `search`, or `request` as the bundle type requires.

## Scope boundary

This module is deliberately **not** a destination for unrelated convenience
code. HTTP routing, response marshalling, persistence, event buses, concrete
server configuration, and release-specific server behavior belong to the server
modules. Query parsing and evaluation belong to `onfhir-query`, FHIRPath to
`onfhir-path`, profile validation to `onfhir-validation`, and definition
loading to `onfhir-config`. What lives here is what several of those modules
must agree on.

Add only the more specific artifacts an application actually needs; Common by
itself provides no server and no network client.

## Tests

| Suite | What it covers |
| --- | --- |
| `CommonReadmeExampleTest` | the examples in this README |
| `FHIRUtilCharacterizationTest` | resource metadata access, reference/token/quantity/canonical value parsing, element path helpers, `Parameters` navigation, and Bundle construction |
| `FHIRUtilTest` | REST URL parsing, `Parameters` path access, and FHIR version comparison |
| `NeutralHttpModelsTest` | the neutral HTTP value types and their wire rendering |
| `FhirRuntimeSettingsTest` | typed settings validation and wire-code round trips |
| `FHIRSearchParameterValueParserTest` | search parameter value parsing |
| `BundleRequestParserTest` | Bundle request entry parsing |
| `DefaultReferenceResolverTest` | contained, Bundle, and external reference resolution |

```shell
mvn -pl onfhir-common test
```

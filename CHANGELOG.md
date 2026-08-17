# Changelog

User-visible changes to the onFHIR reusable libraries. The library family is
versioned independently of the Repofyr server.

All coordinates release together at one version. Patches are fixes only,
minors are additive and backward binary-compatible, and binary-incompatible
changes occur only in a major release - where they additionally get a row in
the [migration guide](docs/migration/3.x-to-4.0.0.md) and a reconciled MiMa
baseline under `docs/compatibility/`.

## 4.0.0 (unreleased)

First independent release of the reusable library family from
`srdc/onfhir-libs`, split out of the onFHIR monorepo and relicensed to
Apache-2.0. The server continues separately as Repofyr (`io.repofyr`
coordinates, GPL-3.0). This is an intentional major release; the complete
upgrade path from 3.x is in the
[migration guide](docs/migration/3.x-to-4.0.0.md).

### Added

- `onfhir-query_2.13`: query/x-fhir-query parsing and in-memory matching as
  a standalone artifact, with the new `FhirQueryEvaluator` /
  `CompiledFhirQuery` parse-once-match-many facade.
- `onfhir-validation`: `FhirValidator` SDK facade with profile selection,
  terminology composition, recursive validation, and contained/Bundle
  reference resolution.
- `onfhir-path`: FHIRPath Normative 1 functions `convertsToBoolean`,
  `convertsToInteger`, `convertsToDate`, `convertsToDateTime`,
  `convertsToQuantity`, `convertsToString`, `convertsToTime`, `toTime`,
  `toChars`, `union`, `timeOfDay`; time-offset literals; one shared clock
  sample per expression evaluation.
- `onfhir-r5_2.13` (`R5Parser` and R5 defaults) and `onfhir-stu3_2.13`
  (STU3 foundation parsers layered on R4).
- Resources-only artifacts `onfhir-definitions-r4`, `onfhir-definitions-r5`,
  and `onfhir-definitions-stu3` packaging the HL7 FHIR standard definitions
  (CC0 1.0) with release-qualified file names.
- `onfhir-libs-bom` for version-aligned multi-module consumption.
- Config-driven construction for the typed runtime settings:
  `FhirCapabilityDefaults`, `FhirResultDefaults`, `FhirRequestDefaults`, and
  `FhirSubscriptionSettings` gain `Standard` presets and `fromConfig`
  companions. Each takes an already-scoped `com.typesafe.config.Config`
  subtree (`fhir.default`, `fhir.subscription`) and reads relative keys; every
  key is optional and falls back to `Standard`. No `reference.conf` is
  shipped, so the artifacts reserve no key paths in a consumer's
  configuration. In configuration, `search-handling` and `return-preference`
  accept the bare token (`strict`, `representation`) as well as the full
  header code; `fromCode` remains strict for the header form.
- `onfhir-common_2.13` declares `com.typesafe:config` (Apache-2.0), the
  configuration model in the `fromConfig` signatures.

### Changed

- All Akka HTTP types in public signatures replaced by the transport-neutral
  model (`java.net.URI`, `java.time.Instant`, `HttpStatus`, `HttpMethod`,
  `FhirMediaType`/`FhirContentType`, `EntityTag`, `AuthenticateChallenge`,
  `OrderedQuery`); contract specified in ADR 0001.
- `onfhir-client` rewritten on the JDK HTTP client: implicit
  `ExecutionContext` instead of `ActorSystem`, neutral interceptor API,
  `onfhir.client.http` configuration, asynchronous bearer-token refresh,
  and `FhirClientException`/`BundleRequestParsingException` error model;
  `executeAndReturnOperationOutcome` fails the returned `Future` on error
  responses.
- Every `onfhir-client` configuration entry point takes an already-scoped
  subtree and reads relative keys, so the library hardcodes no key path.
  `BearerTokenInterceptorFromTokenEndpoint.getFromConfig` no longer reads the
  absolute path `onfhir.client.authz` off a root config; pass
  `config.getConfig("onfhir.client.authz")` instead. The signature is
  unchanged, and a root config now fails at construction with a
  `ConfigException.Missing`.
- Library classes take explicit configuration parameters
  (`FhirSearchHandling`, `FhirEndpointSettings`, `FhirResultDefaults`,
  `FhirCapabilityDefaults`, ...) instead of reading the server's global
  `OnfhirConfig`.
- `FHIRSearchParameter.components` is an ordered `Seq[String]` (composite
  values bind positionally).
- `FhirExpressionEvaluator.evaluateExpression`/`satisfies` report an unknown
  expression language through the returned `Future` instead of throwing.
- `BaseFhirProfileHandler` moved to `onfhir-config` and accepts any
  `BaseFhirConfig`.
- Modules declare only `slf4j-api`; consumers choose the logging provider
  (Logback is no longer exported).
- `io.onfhir:onfhir-template-engine` corrected to
  `io.onfhir:onfhir-template-engine_2.13`.
- Jackson (transitive, under json4s) managed up from 2.12.2 to 2.15.2 via
  the imported `jackson-bom` - the version Apache Spark 3.5.x bundles -
  closing CVE-2020-36518 (stack exhaustion on deeply nested untrusted
  JSON). The json4s line itself stays at the Spark-3.5-compatible
  3.7.0-M11 because json4s types are part of the public API.
- `onfhir-path` no longer packages its ANTLR grammar, generated `.tokens`
  files, or the ANTLR tool JAR (artifact size 2314 KB to 473 KB).

### Removed

- Akka and Pekko from all sources, resources, and resolved dependency
  graphs.
- `org.antlr:stringtemplate` from `onfhir-path`: a leftover of build-time
  grammar generation that no source referenced, which also transitively
  removes `org.antlr:antlr-runtime:3.3` from every consumer's dependency
  graph. Only `antlr4-runtime`, which the generated parser uses, remains.
- The packaged `application.conf` from `onfhir-client`. A library must not
  ship one: `ConfigFactory.load()` merges every `application.conf` on the
  classpath in classloader order, so the jar's values could silently override
  a consumer's own. Its only live keys restated `ClientHttpSettings`'
  hardcoded defaults, which `fromConfig` already falls back to on an empty
  config, and the rest was commented-out sample text kept in the module
  README. No `reference.conf` replaces it, consistent with the rest of the
  family reserving no key paths.
- Server runtime concerns (routing directives, persistence, auditing,
  authorization runtime, events/Kafka, HTTP response exceptions,
  `OnfhirConfig`): continued in the Repofyr server under `io.repofyr`
  coordinates, GPL-3.0.

### Fixed

- `FhirPathEnvironment` no longer resolves unsupplied `%name` variables from
  the OS process environment (see Security below).
- `FHIRUtil.extractValueOptionByPath` returns values for paths containing
  array indexes (previously silently `None`).
- Template engine: FHIRPath results containing `$` or `\` no longer corrupt
  regex replacement (`Regex.quoteReplacement`); `validateExpression` no
  longer rejects every valid template (inverted check); missing template
  content raises `FhirExpressionException` instead of
  `NoSuchElementException`; section objects are field-order independent.
- `AbstractFhirPathFunctionLibrary.getFunctionDocumentation()` resolves
  `returnType`/`inputType` entries written as `FHIR_DATA_TYPES` or
  `FHIR_PARAMETER_TYPES` references to their plain values. They previously
  leaked the wrapping `Option` into the `Seq[String]` (for example
  `Some(number)` for `agg:sum`), which surfaced in anything serializing the
  documentation. A reference that cannot be resolved is now logged and
  dropped instead of being reported as `None`.
- `TerminologyParser` ValueSet filter-operator handling; unversioned
  ValueSet references resolve to the latest version.
- Client defects found by the new contract suite, including shared
  `OperationOutcome` parsing.

### Security

- FHIRPath expressions (profile invariants, search parameter definitions,
  subscription criteria, mapping templates) could read process secrets via
  the `%name` fallthrough to `sys.env` and surface them in validation
  diagnostics or mapped resources. The fallthrough is removed; unsupplied
  variables resolve to empty and deployments must pass values explicitly
  with `withEnvironmentVariable`.

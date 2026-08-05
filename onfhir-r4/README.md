# onfhir-r4

`onfhir-r4` is the FHIR R4 foundation-resource parser for the reusable
onFHIR libraries. It translates parsed FHIR infrastructure resources into the
neutral configuration models used by `onfhir-config` and consuming
applications such as Repofyr and Spark-on-FHIR.

Maven coordinate: `io.onfhir:onfhir-r4_2.13`.

## What it parses

`R4Parser` implements `IFhirFoundationResourceParser` for the FHIR resources
that define an application's FHIR behavior:

| FHIR resource | Resulting onFHIR model |
| --- | --- |
| `CapabilityStatement` | `FHIRCapabilityStatement` |
| `StructureDefinition` | `ProfileRestrictions` |
| `SearchParameter` | `FHIRSearchParameter` |
| `ValueSet` and `CodeSystem` | `ValueSetRestrictions` |
| `OperationDefinition` | `OperationConf` |
| `CompartmentDefinition` | `FHIRCompartmentDefinition` |

`StructureDefinitionParser` provides the R4-specific parsing of profile and
element constraints used by `R4Parser`.

## Relationship to onfhir-config

`onfhir-config` is responsible for locating standard and
application-specific configuration resources from a FHIR definitions ZIP, the
file system, or a FHIR API. This module is the R4-specific adapter that
interprets those parsed resources.

```scala
import io.onfhir.r4.parsers.R4Parser

val parser = new R4Parser()
val compactCapability = parser.parseCapabilityStatement(capabilityStatement)
```

The input is a parsed json4s FHIR resource (`Resource`), not a generated R4
POJO. Constructor defaults cover R4 primitive and complex types and standard
capability defaults. Applications can instead supply explicit type sets and
`FhirCapabilityDefaults` when initializing configuration from a particular
definition package.

## R5 foundation-resource compatibility

The R5 server configurator currently reuses `R4Parser` for its foundation
resource configuration. The R4 parser covers the infrastructure-resource
fields that onFHIR currently uses for R5 configuration, where those resource
shapes and semantics remain compatible.

This does not make `onfhir-r4` a general-purpose R5 parser, nor does it imply
that every R5 resource or every future R5 foundation-resource change is
handled as R4. A dedicated parser should be introduced when an R5 difference
affects an onFHIR configuration model or its interpretation.

This reuse lets the configuration layer remain data-driven: an R5 deployment
can provide its own compatible FHIR definition package while continuing to use
the shared foundation-resource mapping.

## Integration test suite

The module's tests are the repository's end-to-end coverage of the FHIR R4
and R5 standard packages. They take test-scope dependencies on
[`onfhir-definitions-r4`](../onfhir-definitions-r4/README.md) and
[`onfhir-definitions-r5`](../onfhir-definitions-r5/README.md), which package
the real HL7 4.0.1 and 5.0.0 definitions ZIPs and base CapabilityStatements,
plus a test-scope dependency on `onfhir-config` for the release-neutral
configuration pipeline. None of these is a compile dependency of this module.

The R5 suites live here, not in a separate module, because this module's
`R4Parser` IS the parser the onFHIR R5 server configurator reuses (see the
compatibility section above); the suites pin that reuse contract against the
real 5.0.0 package. Their fixture mirrors Repofyr's `FhirR5Configurator`,
including narrowing the value set bundles to `valuesets.json` because the R5
core package no longer ships the v2/v3 terminology bundles.

`R4IntegrationFixtures` builds one `BaseFhirConfig` per JVM by handing a
`new FSConfigReader(fhirVersion = "R4")` - with no explicit file paths, so the
definitions resolve from the classpath - to a minimal concrete
`BaseFhirConfigurator` whose only R4-specific behavior is returning an
`R4Parser`. Parsing the full package takes a few seconds.

| Suite | What it covers |
| --- | --- |
| `R4StandardPackageParsingTest` | type universes, profile and value set restrictions, `Observation`/`Patient` element restrictions and invariants, extensional and filter-based ValueSet expansion, and the parsed base CapabilityStatement |
| `R4StandardValidationTest` | `FhirValidator` over the real definitions: conformant resources plus one negative case per validation category, Bundle entry paths, and `meta.profile` handling |
| `R4SearchParameterConfiguratorTest` | `R4Parser.parseSearchParameter` over all 1375 standard definitions and `SearchParameterConfigurator` path, choice-expansion, and reference-target resolution |
| `R5StandardPackageParsingTest` | the R5 package through the same parsers: R5 type universes (`integer64`, `CodeableReference`, ...), widened `Observation` choice/reference targets, the terminology gap left by the missing v2/v3 bundles, the base R5 CapabilityStatement, and R5 SearchParameter parsing/configuration |
| `R5StandardValidationTest` | `FhirValidator` over the R5-parsed definitions: conformant resources, one negative case per category, R5-widened reference targets, and the THO ValueSet warning |

Applications that ship their own definitions package do not need
`onfhir-definitions-r4` or `onfhir-definitions-r5`; they are a convenience for
tests and for consumers that want a standard package on the classpath.

## Scope

The module builds on Common and Validation. It does not contain an HTTP server,
subscription runtime, persistence implementation, or general FHIR resource
model. Those responsibilities remain with the consuming application and the
release-specific server modules such as `onfhir-server-r4` and
`onfhir-server-r5`.

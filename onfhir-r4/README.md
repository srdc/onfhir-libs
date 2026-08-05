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

FHIR R5 consumers should use the release-specific
[`onfhir-r5`](../onfhir-r5/README.md) module. Its `R5Parser` currently reuses
this implementation through inheritance while owning R5 defaults and the
extension point for future release-specific behavior.

## Integration test suite

The module's tests are the repository's end-to-end coverage of the FHIR R4
standard package. They take test-scope dependencies on
[`onfhir-definitions-r4`](../onfhir-definitions-r4/README.md), which packages
the real HL7 4.0.1 definitions ZIP and base CapabilityStatement, plus
`onfhir-config` for the release-neutral configuration pipeline. None of these
is a compile dependency of this module. R5 coverage lives with `R5Parser` in
`onfhir-r5`.

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

Applications that ship their own definitions package do not need
`onfhir-definitions-r4`; it is a convenience for tests and consumers that want
the standard package on the classpath.

## Scope

The module builds on Common and Validation. It does not contain an HTTP server,
subscription runtime, persistence implementation, or general FHIR resource
model. Those responsibilities remain with the consuming application and the
release-specific server modules such as `onfhir-server-r4` and
`onfhir-server-r5`.

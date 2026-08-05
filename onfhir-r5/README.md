# onfhir-r5

`onfhir-r5` is the FHIR R5 foundation-resource parser for the reusable onFHIR
libraries. It gives R5 consumers a release-specific API and home for R5
compatibility tests while reusing the R4-compatible parsing implementation.

Maven coordinate: `io.onfhir:onfhir-r5_2.13`.

## Parser

```scala
import io.onfhir.r5.parsers.R5Parser

val parser = new R5Parser()
val compactCapability = parser.parseCapabilityStatement(capabilityStatement)
```

`R5Parser` extends `R4Parser` because the infrastructure-resource fields
currently consumed by onFHIR have compatible R4 and R5 shapes. The R5 class is
the public extension point for future R5-specific overrides; consumers no
longer need to express R5 configuration through an R4-named parser.

Constructor defaults cover the 21 primitive and 42 distinct, non-abstract
complex datatype names in the official FHIR R5 5.0.0 type definitions. The
lists follow the [HL7 R5 datatype summary](https://hl7.org/fhir/R5/datatypes.html)
and are pinned against the packaged `profiles-types.json` by this module's
integration tests. A `BaseFhirConfigurator` normally derives these sets from
the selected definition package and passes them explicitly, so the constants
primarily support direct parser construction.

Applications may supply explicit type sets and `FhirCapabilityDefaults`:

```scala
new R5Parser(complexTypes, primitiveTypes, capabilityDefaults)
```

## Integration tests

The module tests the parser against the real HL7 5.0.0 package supplied by
[`onfhir-definitions-r5`](../onfhir-definitions-r5/README.md). These are
test-scope dependencies and do not place the definition package or
`onfhir-config` on the published runtime graph.

| Suite | What it covers |
| --- | --- |
| `R5StandardPackageParsingTest` | R5 type universes and parser defaults, profiles, terminology, CapabilityStatement, and SearchParameter configuration |
| `R5StandardValidationTest` | realistic R5 validation, widened reference targets, and the known THO terminology warning |

The test fixture narrows the standard terminology bundle list to
`valuesets.json`, because FHIR R5 moved the v2/v3 content into the separate HL7
terminology package.

## Scope

The module contains no HTTP server, persistence implementation, actor runtime,
or generated FHIR resource model. Server behavior remains in release-specific
applications such as `onfhir-server-r5`.

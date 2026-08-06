# onfhir-stu3

`onfhir-stu3` is the FHIR STU3 foundation-resource parser for the reusable
onFHIR libraries. It translates parsed FHIR infrastructure resources into the
neutral configuration models used by `onfhir-config` and consuming
applications.

Maven coordinate: `io.onfhir:onfhir-stu3_2.13`.

## Relationship to onfhir-r4

`STU3Parser` extends [`R4Parser`](../onfhir-r4/README.md) and
`STU3StructureDefinitionParser` extends the R4 `StructureDefinitionParser`, so
`onfhir-r4` is a compile dependency. The subclasses override only the
foundation-resource fields whose STU3 shape differs from R4:

| Override | STU3 difference |
| --- | --- |
| `parseCapabilityStatement` | `rest.resource.profile` is a `Reference`, so the URL comes from `profile.reference`; `compartment` sits under `rest` |
| `parseSearchParameter` | no `multipleOr`/`multipleAnd`; `component` order is significant |
| `parseOperationDefinition` | parameter binding uses `valueSetUri` or `valueSetReference.reference` instead of R4's canonical `valueSet` |
| `parseTypeInElemDefinition` | `type.profile` and `type.targetProfile` are single values, not arrays |

This mirrors the layering Repofyr's `onfhir-server-stu3` already used; the code
was moved here unchanged apart from its package (`io.onfhir.stu3.parsers`
rather than `io.repofyr.stu3.parsers`).

## Known STU3 limitations

Three STU3 behaviors are pinned by the test suites as CURRENT behavior rather
than fixed. They are inherited from the implementation this module was copied
from, and fixing them means changing shared `onfhir-validation` or `onfhir-r4`
code, which is a separate decision.

1. **Code bindings are not enforced.** STU3 expresses
   `ElementDefinition.binding.valueSet` as the choice `valueSetUri |
   valueSetReference`, but `AbstractStructureDefinitionParser` reads only R4's
   canonical `valueSet` field and falls back to the `$parent` sentinel. Every
   STU3 binding therefore parses as `$parent`, and since the whole profile
   chain is STU3 the validator never finds a concrete ValueSet to inherit, so
   terminology validation is silently skipped. A bogus `Observation.status` is
   accepted where R4 and R5 report a binding failure.
2. **Only the first reference target survives.** STU3 repeats
   `ElementDefinition.type` once per target profile; R4 uses one `Reference`
   entry with an array of `targetProfile`s. The shared parser builds the
   reference restriction from the first matching entry, so
   `Observation.subject` is understood as `Patient`-only even though STU3 also
   allows `Group`, `Device` and `Location`. Validation is over-restrictive for
   such elements.
3. **Composite search parameters are unsupported.** STU3
   `SearchParameter.component.definition` is a `Reference`, not the canonical
   string the parser reads, so component sets come out empty and
   `SearchParameterConfigurator` refuses every composite. Nine of the 38
   `Observation` search parameters are affected.

## Integration test suite

The tests take test-scope dependencies on
[`onfhir-definitions-stu3`](../onfhir-definitions-stu3/README.md), which
packages the real HL7 STU3 3.0.2 definitions ZIP and base CapabilityStatement,
and on `onfhir-config` for the release-neutral configuration pipeline. Neither
is a compile dependency.

`STU3IntegrationFixtures` builds one `BaseFhirConfig` per JVM by handing a
`new FSConfigReader(fhirVersion = "STU3")` - with no explicit file paths, so
the definitions resolve from the classpath - to a minimal concrete
`BaseFhirConfigurator` mirroring Repofyr's `FhirSTU3Configurator`. Unlike R5,
no `VALUESET_AND_CODESYSTEM_BUNDLE_FILES` narrowing is needed, because the STU3
package still ships `v2-tables.json` and `v3-codesystems.json`.

| Suite | What it covers |
| --- | --- |
| `STU3StandardPackageParsingTest` | STU3 type universes, profile and value set restrictions, STU3-only elements (`Observation.context`, `Patient.animal`), the base CapabilityStatement through the Reference-shaped profile field, SearchParameter and OperationDefinition parsing, and the three limitations above |
| `STU3StandardValidationTest` | `FhirValidator` over the STU3 definitions: conformant resources, one negative case per category, and the observable consequences of limitations 1 and 2 |

STU3 has no `dom-6` invariant, so conformant STU3 resources validate to an
empty issue list without needing a narrative, unlike R4 and R5.

## Scope

The module contains only foundation-resource parsers. It has no HTTP server,
subscription runtime, persistence implementation, or generated STU3 resource
model; those remain with the consuming application and the release-specific
server modules.

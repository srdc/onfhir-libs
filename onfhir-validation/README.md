# onfhir-validation

`onfhir-validation` validates JSON FHIR content against already parsed
`StructureDefinition` restrictions. It checks cardinality, arrays, primitive
and complex types, fixed/pattern values, terminology bindings, references,
FHIRPath invariants, profile inheritance, and slicing.

It is deliberately FHIR-release neutral. It does not contain a standard FHIR
definitions package, parse a particular release's `StructureDefinition`
format, download packages, persist content, or translate validation results
into HTTP responses. Use a release-specific companion such as `onfhir-r4` to
parse foundation resources, then provide the resulting configuration to this
module.

## Dependency

```xml
<dependency>
  <groupId>io.onfhir</groupId>
  <artifactId>onfhir-validation_2.13</artifactId>
  <version>${onfhir.libs.version}</version>
</dependency>
```

The module depends on `onfhir-common` and `onfhir-path`. Applications that
parse R4 definitions also need the matching `onfhir-r4` dependency; that is a
consumer concern, not a dependency of this module.

## Configuration prerequisites

Validation requires a populated `BaseFhirConfig`; a profile URL by itself is
not enough. Before creating a validator, populate:

- `profileRestrictions`: parsed base and custom profiles, keyed by canonical
  URL and version;
- `FHIR_RESOURCE_TYPES`, `FHIR_COMPLEX_TYPES`, and `FHIR_PRIMITIVE_TYPES` for
  the selected FHIR release;
- `valueSetRestrictions` when local terminology binding validation is wanted.
  It may be absent when external terminology services cover every required
  ValueSet; unmatched bindings are then reported as unsupported rather than
  causing a configuration failure.

The release-specific configurator is the normal place to load standard
definitions and parse `StructureDefinition`, `ValueSet`, and `CodeSystem`
resources. Keep the standard-package version pinned by the application so
validation is reproducible.

## Validating resources

```scala
import io.onfhir.config.BaseFhirConfig
import io.onfhir.validation.FhirValidator
import org.json4s.JsonAST.JObject

import scala.concurrent.ExecutionContext.Implicits.global

def validatePatient(config: BaseFhirConfig, patient: JObject) = {
  val validator = FhirValidator(config)

  validator.validateResource(patient)
}
```

`validateResource` infers the resource type from `resourceType`, validates the
base profile, and also validates every recognized canonical profile in
`meta.profile`. Unknown `meta.profile` values are returned as warnings and do
not prevent base-profile validation.

Use an explicit profile when the calling application, rather than the
resource, chooses the validation target:

```scala
val validator = FhirValidator(config)

validator.validateResourceAgainstProfile(
  patient,
  "http://example.org/fhir/StructureDefinition/MyPatient"
)

validator.validateResourceAgainstProfiles(patient, Seq(profileA, profileB))
```

All facade methods return `Future[Seq[OutcomeIssue]]`. An empty result
means the content conforms to the configured profile chain. Callers should
normally treat issues with severity `error` as validation failures; warnings
are returned as issues as well and should remain visible to users or logs.
Each issue includes an `expression` path that identifies the relevant JSON
element.

Profiles are resolved by canonical URL and optional version. Make sure the
base profiles of a custom profile are also present in `profileRestrictions`;
the validator applies the derived profile together with its complete base
profile chain.

When the supplied resource is a Bundle, the facade validates the Bundle and
recursively validates every present `Bundle.entry.resource` (and contained
resources). Nested issue paths retain their Bundle entry location. Bundle
entries without a `resource` are evaluated only through the Bundle's own
constraints.

## Optional reference and terminology support

Terminology bindings first use the configured external services that claim the
ValueSet, then fall back to `valueSetRestrictions` in `BaseFhirConfig` when no
external service matches. The first matching service wins, so service order is
significant and a service configured for `*` handles all ValueSets.

```scala
import io.onfhir.api.service.IFhirTerminologyService
import io.onfhir.config.TerminologyServiceConf

val terminologyServices: Seq[(TerminologyServiceConf, IFhirTerminologyService)] = Seq(
  terminologyServiceConf -> terminologyService
)

val validator = FhirValidator(fhirConfig, terminologyServices)
```

The composed terminology validator is available as
`validator.terminologyValidator`. External terminology calls use the timeout
from `TerminologyServiceConf` and the current implementation blocks while
waiting for the result; use an execution context suitable for blocking
validation work.

`FhirValidator` automatically resolves contained references and references to
other resources in the enclosing Bundle. To resolve references outside that
context, supply one reusable `IExternalFhirReferenceResolver`:

```scala
import io.onfhir.api.Resource
import io.onfhir.api.model.{FhirCanonicalReference, FhirLiteralReference, FhirLogicalReference}
import io.onfhir.api.validation.IExternalFhirReferenceResolver

import scala.concurrent.Future

val externalReferences = new IExternalFhirReferenceResolver {
  override def resolveLiteral(reference: FhirLiteralReference): Future[Option[Resource]] =
    lookupByResourceId(reference)

  override def resolveCanonical(reference: FhirCanonicalReference): Future[Option[Resource]] =
    lookupByCanonicalUrl(reference)

  override def resolveLogical(reference: FhirLogicalReference): Future[Option[Resource]] =
    lookupByIdentifier(reference)
}

val validator = FhirValidator(
  config,
  externalReferenceResolver = Some(externalReferences)
)
```

The external resolver receives only literal, canonical, and logical references.
It never receives contained (`#id`) or Bundle UUID references; those are
handled by the validator's context-aware resolver. Canonical fragments are
also handled locally after the external resolver returns the canonical
resource.

Return `Future.successful(None)` only when the target is not found. Propagate
authorization, transport, timeout, and other infrastructure failures as failed
futures so callers can distinguish validation failure from an unavailable
reference source. The validator uses the resolved resource to check required
target profiles.

`FhirContentValidator` remains available as the low-level API for callers that
already selected one profile and need direct validation of a resource or a
complex datatype value. An instance keeps per-run state, so it is not
thread-safe and must not be used for concurrent validations; that state is
reset at the start of every call, so sequential reuse of one instance is fine.
`FhirValidator` creates a fresh content validator for each validation run and
is therefore unaffected.

## Limitations

Validation covers what the parsed `StructureDefinition` restrictions and the
configured terminology can express. The following gaps are known:

- `base64Binary` and `xhtml` values are accepted without content validation,
  and `uri`, `url`, and `markdown` are only required to be non-empty strings,
  because FHIR's own definitions do not conform to strict URI syntax.
  `canonical` parses the URL part as a `java.net.URI`, which is permissive.
- The `htmlChecks()` invariant is dropped when element definitions are parsed,
  so narrative XHTML content itself is not constrained.
- Local terminology validation covers extensional `ValueSet` definitions, that
  is `compose.include` and `compose.exclude` entries listing explicit codes.
  Filter-based composition is expanded only when the referenced `CodeSystem`
  content is supplied alongside the `ValueSet`; hierarchy filters (`is-a`,
  `descendent-of`, `is-not-a`, `generalizes`) and property filters (`=`, `in`,
  `not-in`, `exists`, `regex`) are then applied to the supplied concepts. Only
  the first hierarchy filter of an `include` is applied, and filter operators
  outside the lists above, such as `child-of` or the invalid spelling
  `descendant-of`, are ignored; both cases are logged as a warning while
  parsing, and an ignored filter widens the resulting code set.
- Hierarchy filters read the concept tree only from nested
  `CodeSystem.concept.concept` elements, and their `filter.property` is not
  checked; the filter value is always matched against `concept.code`. In a
  flat `CodeSystem` that expresses hierarchy through a `parent` property,
  `is-a` and `generalizes` therefore resolve to the named concept alone and
  `descendent-of` yields no codes at all.
- A `ValueSet` that yields no local codes, such as an intensional definition
  over an external system like SNOMED CT, is left out of
  `valueSetRestrictions`; validating such bindings needs an external
  terminology service as described above.
- When neither a local `ValueSet` nor an external service claims a bound
  `ValueSet`, binding validation is skipped and reported as a warning rather
  than an error, so such warnings mark unvalidated bindings.

## Test guidance

The module's tests build compact `ProfileRestrictions` directly. This keeps
unit tests fast, deterministic, and independent of an R4/R5 package ZIP. Put
release-package compatibility tests beside the corresponding release parser or
in a dedicated integration-test module.

Run the module test suite from the library reactor:

```powershell
mvn -pl onfhir-validation test
```

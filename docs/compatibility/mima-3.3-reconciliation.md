# MiMa 3.3 Compatibility Reconciliation

> **Historical record.** This pair (`mima-3.3-accepted.txt` and this document)
> is the permanent record of what 4.0.0 changed relative to 3.3. It is no
> longer regenerated: after the 4.0.0 release the gate was retargeted at the
> published 4.0.0 API, so the live pair is
> [`mima-4.0.0-reconciliation.md`](mima-4.0.0-reconciliation.md) with
> `mima-4.0.0-accepted.txt`.

The accepted machine baseline (`mima-3.3-accepted.txt`) compares the current
reusable JARs with the public `3.3` artifacts. Version `4.0.0` is
intentionally a major release. This table groups every reported issue family
and connects it to the consumer-facing
[3.x to 4.0.0 migration guide](../migration/3.x-to-4.0.0.md) rather than
suppressing individual class findings.

| MiMa report group | Intended change / migration-guide section |
|---|---|
| Common FHIR media/content constants | Akka media/content types replaced by `FhirMediaType` / `FhirContentType` (guide 6.1) |
| `io.onfhir.api.client.*` missing from Common | Classes moved to `onfhir-client_2.13` with packages unchanged (guide 5.1) |
| neutral request/response/status/date/URI signatures | Akka HTTP types replaced by neutral/JDK models (guide 6.1) |
| bundle parsing methods | explicit endpoint settings, JDK URI, and library-safe exceptions (guide 6.2, 6.3) |
| query parsers/resolvers and in-memory query helpers missing from Common | moved to `onfhir-query_2.13` (guide 5.1, 6.6) |
| `FHIRUtil`, search parser, and foundation parser signatures | injected endpoint/capability/search defaults and neutral date/status types (guide 6.1, 6.3) |
| Common server auth, audit, DB, event, exception, validation-strategy, and configuration types | moved to the Repofyr server (guide 5.2) |
| `SubscriptionUtil` missing from Common | release-specific strategy obtained through the server configurator in Repofyr (guide 5.2) |
| client transport/interceptor/marshaller signatures and former case-class surface | JDK transport contract and explicit factories (guide 6.2) |
| Path terminology function constructor | terminology integration follows the injected service contract used by the transport-neutral library boundary (guide 6.4) |
| Config `BaseFhirServerConfigurator` missing | server configurator moved to Repofyr (guide 5.2) |
| Validation and R4 parser constructor/parse-element signatures | endpoint/capability defaults are explicit and structure parsers carry element metadata (guide 6.3) |
| `AuthzContext`, `AuthzResult`, `OperationConf`, `OperationParamDef`, and `ElementRestrictions` signatures | pre-split 3.3-to-4.0 model evolution retained on the approved release line; consumers must recompile and use JSON-valued auth context parameters, `AuthzConstraints`, level-aware operation parameters, and string profile provenance (guide 6.8) |
| `FHIRSearchParameter` constructor, `components`, `copy`, and `apply` signatures | composite component URLs became an ordered `Seq`, because a composite search statement binds its `$` separated value parts to the components positionally and an unordered set cannot express that; consumers must recompile and pass the components in declaration order (guide 6.8) |
| Query, Template, Definitions, R5, and STU3 artifacts | new artifacts with no public `3.3` baseline (guide 3.1) |
| Expression | binary compatible with `3.3`; one behavioral change (guide 6.7) |

The raw accepted findings are in `mima-3.3-accepted.txt`. CI regenerates the
report and fails on any difference. Updating the baseline requires reviewing
this reconciliation and the migration guide in the same change (see the
`mima-update` skill).

# AGENTS.md

Working contract for AI agents operating in the standalone onFHIR reusable
libraries repository.

## Repository boundary

This repository owns eleven reusable Scala libraries - common, client, path,
query, config, expression, validation, template engine, R4, R5, and STU3 - plus
three resources-only artifacts, `onfhir-definitions-r4`,
`onfhir-definitions-r5` and `onfhir-definitions-stu3`, that package the HL7
FHIR R4, R5 and STU3 standard definitions. Server runtime code belongs in the
Repofyr repository.

- Keep production source, resources, direct dependencies, and resolved
  dependency graphs free of Akka and Pekko.
- Keep existing `io.onfhir` group IDs, artifact IDs, and package roots stable,
  except for the approved pre-release correction to
  `io.onfhir:onfhir-template-engine_2.13` recorded in the split plan.
- Do not introduce HTTP routing, server lifecycle, persistence, or actor event
  bus concerns into these modules.
- Record public API changes and module relocations in
  `docs/plans/library-server-split-plan-v2.md`.
- Keep scripts ASCII-only for Windows PowerShell 5.1 compatibility.

## Verification

- `mvn test`
- `powershell -File scripts/check-forbidden-imports.ps1`
- `powershell -File scripts/check-library-dependency-licenses.ps1`
- `powershell -File scripts/check-binary-compatibility.ps1`

Release artifacts use Apache-2.0 and must package `META-INF/LICENSE` and
`META-INF/NOTICE`. Do not publish or push without explicit authorization.

The `onfhir-definitions-*` artifacts are the only ones whose packaged content is
not first-party: the HL7 FHIR specification files they carry are CC0 1.0,
recorded in `NOTICE` and in each module README. They also carry no Scala version
suffix, because they contain no compiled code. Their FHIR resources are marked
`-text` in `.gitattributes` so the published bytes are identical on every build
host, and every packaged file name carries its FHIR release so the artifacts can
share a classpath without colliding.

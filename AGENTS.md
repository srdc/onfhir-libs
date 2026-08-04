# AGENTS.md

Working contract for AI agents operating in the standalone onFHIR reusable
libraries repository.

## Repository boundary

This repository owns these nine libraries: common, client, path, query,
config, expression, validation, template engine, and R4. Server runtime code
belongs in the Repofyr repository.

- Keep production source, resources, direct dependencies, and resolved
  dependency graphs free of Akka and Pekko.
- Keep existing `io.onfhir` group IDs, artifact IDs, and package roots stable.
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

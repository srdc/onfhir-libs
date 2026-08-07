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
- Keep existing `io.onfhir` group IDs, artifact IDs, and package roots stable.
  (The pre-4.0.0 correction of `io.onfhir:onfhir-template-engine_2.13` is
  recorded in the migration guide.)
- Do not introduce HTTP routing, server lifecycle, persistence, or actor event
  bus concerns into these modules.
- Record user-visible changes in `CHANGELOG.md`. A binary-incompatible change
  or class relocation additionally needs, in the same change, a row in the
  migration guide under `docs/migration/` and a reconciled MiMa baseline under
  `docs/compatibility/` (see the `mima-update` skill). Binary breaks land only
  in a major release.
- The neutral HTTP model's semantic contract is
  `docs/adr/0001-neutral-http-contract.md`; do not weaken it (for example,
  collapsing ordered/repeated values into maps).
- Keep scripts ASCII-only for Windows PowerShell 5.1 compatibility.

## Verification

- `mvn test`
- `powershell -File scripts/check-forbidden-imports.ps1`
- `powershell -File scripts/check-library-dependency-licenses.ps1`
- `powershell -File scripts/check-binary-compatibility.ps1`

The `verify` skill runs the full suite in order. Release staging and
publishing follow `RELEASING.md`; do not publish or push without explicit
authorization.

## Local build notes

- This repository may be worked on by parallel sessions: check `git log`
  before assuming the state of the tree.
- Module-scoped builds need `-am` while the working tree is ahead of
  installed artifacts: `mvn -pl <module> -am test`.
- A killed Maven run can corrupt zinc incremental state under `target/`,
  producing bogus "not a member of package" errors; run
  `mvn -pl <module> clean` and rebuild.
- Run the gate scripts bare and filter their output afterwards; piping them
  (`| Select-String`, `2>&1`) under Windows PowerShell 5.1 turns any native
  stderr line into a terminating NativeCommandError.
- Quote any `-D` property whose NAME contains a dot when invoking Maven from
  PowerShell 5.1: it splits `-Dmaven.test.skip=true` at the first dot and
  Maven then reports `Unknown lifecycle phase ".test.skip=true"`. Write
  `"-Dmaven.test.skip=true"`, `"-Dgpg.skip=true"`. Dotless names such as
  `-DskipTests` and `-DaltDeploymentRepository=...` are unaffected, which is
  why the release commands work unquoted.
- `onfhir-client` tests need a short unix-domain-socket temp directory on
  Windows: set `JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\tmp` for
  `mvn test`, and remove the variable again before running gate scripts
  under PowerShell 5.1.

## Licensing

Release artifacts use Apache-2.0 and must package `META-INF/LICENSE` and
`META-INF/NOTICE`.

The `onfhir-definitions-*` artifacts are the only ones whose packaged content is
not first-party: the HL7 FHIR specification files they carry are CC0 1.0,
recorded in `NOTICE` and in each module README. They also carry no Scala version
suffix, because they contain no compiled code. Their FHIR resources are marked
`-text` in `.gitattributes` so the published bytes are identical on every build
host, and every packaged file name carries its FHIR release so the artifacts can
share a classpath without colliding.

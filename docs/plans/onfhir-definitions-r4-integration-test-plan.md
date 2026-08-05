# FHIR Definitions Module And R4 Integration Test Plan

Status: COMPLETE (design agreed with owner on 2026-08-05; delivered 2026-08-05)
Owner: Tuncay Namli. Executor: AI agent session in this repository.
Last updated: 2026-08-05

## Goal

Create a resources-only Maven module `io.onfhir:onfhir-definitions-r4` that
packages the official FHIR R4 (4.0.1) standard definitions package and base
CapabilityStatement, then use it (test scope) from `onfhir-r4` to build the
first end-to-end integration suite in this repository: parse the real
standard package with the R4 parsers and validate realistic resources with
`FhirValidator`.

Why this matters: today `onfhir-r4` and `onfhir-config` have zero tests, and
nothing in onfhir-libs exercises `AbstractStructureDefinitionParser`,
`StructureDefinitionParser`, `BaseFhirConfigurator`, or validation against
real R4 definitions. The deep suites stayed in Repofyr's `onfhir-server-r4`
when the repositories split. The server repos each embed their own copy of
the definitions zip; this module becomes the single shared source usable by
libs tests now, Repofyr later, and downstream consumers (spark-on-fhir,
toFHIR).

## Approved design decisions

1. One artifact per FHIR release. This plan delivers only
   `io.onfhir:onfhir-definitions-r4`. R5/R4B artifacts are deferred until a
   matching parser exists in this repository.
2. NO `_2.13` suffix. The artifact contains no Scala (or any) code; the
   Scala-suffix rule applies to Scala artifacts only (see the
   template-engine correction in the split plan). Plain `jar` packaging, no
   scala-maven-plugin execution.
3. Version is lockstep with the reactor (`${revision}`, currently 4.0.0).
   The FHIR package version (4.0.1) is recorded inside the JAR in a
   properties file, not in the Maven version.
4. Resources keep the exact default classpath names the onfhir-config
   readers already expect (see Verified facts): `definitions-r4.json.zip`
   and `conformance-statement-r4.json` at the same classpath locations the
   Repofyr server module uses today. This makes `new FSConfigReader("R4")`
   work with only this JAR on the classpath.
5. `db-index-conf-r4.json` is server persistence configuration and stays in
   Repofyr. It is NOT part of this module.
6. The integration tests live in `onfhir-r4/src/test`, NOT in
   `onfhir-validation`. Reason: `onfhir-r4` compile-depends on
   `onfhir-validation` (StructureDefinitionParser extends
   AbstractStructureDefinitionParser), so a test-scope edge from validation
   to r4 would create a Maven reactor cycle. `onfhir-validation` keeps its
   synthetic release-neutral unit tests by design.
7. Licensing: HL7 publishes the FHIR specification content under CC0 1.0.
   Record this in the module README and the repository `NOTICE`. The module
   is first-party, so the external-dependency license gate is unaffected,
   but the executor must confirm the gate still passes.
8. Repofyr switching to consume this artifact (deleting its embedded copies)
   is a separate post-split follow-up, out of scope here.

## Hard constraints

1. Do not commit or push anything; leave the working tree for owner review.
   The tree already contains unrelated in-progress work - do not revert or
   include it in your changes.
2. All new files ASCII-only (Windows PowerShell 5.1 compatibility rule in
   AGENTS.md).
3. Every new test MUST be a `class` (never an `object`) named `*Test`,
   annotated `@RunWith(classOf[JUnitRunner])`, extending
   `org.specs2.mutable.Specification`. Surefire detects tests through the
   JUnit provider; a specs2 class without `@RunWith` is silently ignored.
   After each test phase, confirm the new classes appear in
   `onfhir-r4/target/surefire-reports/`.
4. Do NOT modify `src/main/**` of any existing module. If a test reveals a
   suspected product bug, record it in the Findings section of this plan,
   write the test to assert CURRENT behavior with a
   `// NOTE: documents current behavior, see plan Findings` comment, and
   move on.
5. Follow the repository test-dependency convention: specs2 core + junit
   declared with `test` scope (the former `provided` convention was
   replaced repo-wide on 2026-08-05).
6. Copy the two resource files from the sibling Repofyr working copy
   byte-for-byte; do not download from the network.
7. Do not fix the known FhirTerminologyValidator bug here (it has its own
   pending task); see Interactions below.

## Read these files before writing code

- `docs/plans/library-server-split-plan-v2.md` sections 5 (gates), 7
  (migration tables), and the Phase 5A record - bookkeeping conventions.
- `onfhir-config/src/main/scala/io/onfhir/config/BaseFhirConfigurator.scala`
  (`initializePlatform`) and `IFhirVersionConfigurator` (bundle-file-name
  constants and abstract members).
- `onfhir-config/src/main/scala/io/onfhir/config/BaseConfigReader.scala` and
  `FSConfigReader.scala` - default zip/conformance classpath resolution.
- `io.onfhir.api` package constants in onfhir-common (`DEFAULT_ROOT_FOLDER`,
  `DEFAULT_RESOURCE_PATHS`, `FOUNDATION_RESOURCES_FILE_SUFFIX`,
  `FHIR_FOUNDATION_RESOURCES`) and `io.onfhir.api.util.IOUtil`
  (`readResource`, `readStandardBundleFile`, `readResourcesInFolderOrZip`) -
  these determine the exact classpath paths the module must provide.
- `onfhir-r4/src/main/scala/io/onfhir/r4/parsers/R4Parser.scala` and
  `StructureDefinitionParser.scala`.
- `onfhir-validation/src/main/scala/io/onfhir/validation/FhirValidator.scala`
  (SDK facade) and `onfhir-validation/README.md` (Limitations section).
- An existing module pom (e.g. `onfhir-validation/pom.xml`) plus the root
  `pom.xml` and `onfhir-libs-bom/pom.xml` for reactor/BOM wiring patterns.
- `onfhir-validation/src/test/scala/io/onfhir/validation/*Test.scala` for
  test style conventions.

## Verified facts (confirmed against source on 2026-08-05; rely on them)

- `BaseFhirConfigurator.initializePlatform(configReader): BaseFhirConfig`
  is the complete release-neutral pipeline: it reads the standard bundle
  files (profiles-resources, profiles-types, profiles-others,
  extension-definitions, valuesets and code system bundles) via
  `IFhirConfigReader`, derives `FHIR_RESOURCE_TYPES` / `FHIR_COMPLEX_TYPES`
  / `FHIR_PRIMITIVE_TYPES` from the parsed type/resource
  StructureDefinitions, obtains the release parser through
  `getFoundationResourceParser(complex, primitive,
  FhirCapabilityDefaults.Standard)`, and populates
  `profileRestrictions` and `valueSetRestrictions`.
- `BaseFhirConfigurator` is abstract; there is no concrete R4 configurator
  in this repository. The test suite must supply a small concrete subclass
  (test scope, inside onfhir-r4 tests) that sets the R4 version and returns
  `new R4Parser(complex, primitive, capabilityDefaults)`. Promoting such a
  configurator to onfhir-r4 `src/main` is a public API addition and is OUT
  of scope (record as an optional follow-up in Findings if it seems
  valuable).
- `BaseConfigReader` computes the default standard-zip path as
  `DEFAULT_ROOT_FOLDER/definitions-<version-lowercase><suffix>.zip` when no
  explicit path is given, and `FSConfigReader.readCapabilityStatement()`
  defaults to `DEFAULT_RESOURCE_PATHS.CONFORMANCE_PATH_R4` for version
  "R4"/"4.0.1". `IOUtil.readResource(explicit, default, rtype)` falls back
  to reading the default path from the CLASSPATH. Therefore: placing the
  files at those default classpath locations makes
  `new FSConfigReader(fhirVersion = "R4")` work with no explicit paths.
  The executor must resolve the exact constant values in Phase 0 and place
  resources accordingly.
- Source files to copy from the sibling Repofyr working copy:
  - `C:\srdc\codes\onfhir-io\onfhir\onfhir-server-r4\src\main\resources\definitions-r4.json.zip`
    (8,900,279 bytes, FHIR 4.0.1 definitions)
  - `C:\srdc\codes\onfhir-io\onfhir\onfhir-server-r4\src\main\resources\conformance-statement-r4.json`
    (834,879 bytes, base R4 CapabilityStatement)
- `onfhir-r4/pom.xml` currently depends only on scala-library,
  onfhir-common, onfhir-validation, and slf4j-api. It has NO test
  dependencies and NO test sources. `onfhir-config` does not depend on
  onfhir-r4, so adding onfhir-config to onfhir-r4 in test scope is acyclic.
- `FhirValidator(config)` (onfhir-validation) validates a resource against
  its base profile plus known `meta.profile` claims and returns
  `Future[Seq[OutcomeIssue]]`; empty result means conformant.
- `mvn -pl <module> test` WITHOUT `-am` may fail while installed 4.0.0
  artifacts are stale relative to the working tree; always use `-am`.

## Phases

### Phase 0 - Preflight

1. Resolve the exact constant values (`DEFAULT_ROOT_FOLDER`,
   `DEFAULT_RESOURCE_PATHS.CONFORMANCE_PATH_R4`,
   `FOUNDATION_RESOURCES_FILE_SUFFIX`, standard bundle file names in
   `IFhirVersionConfigurator`) and derive the exact classpath layout for the
   new module's resources. Record them in Findings.
2. List the zip entries (PowerShell:
   `[System.IO.Compression.ZipFile]::OpenRead(...)`) and confirm the bundle
   file names match the constants (expected: profiles-resources.json,
   profiles-types.json, profiles-others.json, extension-definitions.json,
   valuesets.json, plus v2/v3 bundles if referenced by the constants).
3. Confirm the baseline build is green before changing anything:
   `mvn -pl onfhir-r4 -am test` (expect BUILD SUCCESS, no tests in r4 yet).

### Phase 1 - Create the onfhir-definitions-r4 module

1. New directory `onfhir-definitions-r4/` with:
   - `pom.xml`: parent `onfhir-libs-parent`, artifactId
     `onfhir-definitions-r4` (no Scala suffix), packaging `jar`, no
     dependencies, no scala-maven-plugin/surefire configuration. Confirm
     parent-inherited packaging still attaches flattened POM, sources jar,
     and LICENSE/NOTICE files like other modules; if a scaladoc/source
     plugin execution fails on a no-code module, configure the minimal
     skip/empty-jar equivalent and record it in Findings.
   - `src/main/resources/...`: the two copied files at the Phase 0 layout.
   - `src/main/resources/onfhir-definitions.properties` (same folder as the
     zip) with: `fhir.version=4.0.1`,
     `package.source=https://hl7.org/fhir/R4/definitions.json.zip`,
     `content.license=CC0-1.0`, `packaged.files=definitions-r4.json.zip,conformance-statement-r4.json`.
   - `README.md`: what the module contains, that content is HL7 FHIR
     specification material under CC0 1.0, the Maven coordinate (no Scala
     suffix, and why), intended test-scope usage snippet, and a note that
     the artifact version tracks onfhir-libs while the FHIR package version
     is in the properties file.
2. Add the module to the root `pom.xml` reactor (before onfhir-r4) and to
   dependency management if the repo convention requires it; add it to
   `onfhir-libs-bom`.
3. Append a CC0 note for the packaged FHIR content to `NOTICE`.
4. Gate: `mvn -pl onfhir-definitions-r4 install` succeeds; inspect the
   produced JAR (`jar tf`) to confirm the resources, properties file, and
   META-INF/LICENSE + NOTICE are inside.

### Phase 2 - Wire onfhir-r4 test scope

1. Add to `onfhir-r4/pom.xml`: `onfhir-definitions-r4` (test scope),
   `onfhir-config_${scala.binary.version}` (test scope), specs2 core +
   junit (provided scope, matching repo convention), and the surefire +
   scala-maven-plugin test configuration mirroring `onfhir-validation`'s
   pom (testSourceDirectory etc.).
2. Gate: `mvn -pl onfhir-r4 -am test-compile` passes with an empty test
   tree; `mvn dependency:tree -pl onfhir-r4` shows the definitions and
   config dependencies in test scope only.

### Phase 3 - Integration test suites in onfhir-r4

Shared fixture first: `R4IntegrationFixtures` (test object) that
lazily builds ONE `BaseFhirConfig` for the whole module:

- a private concrete configurator extending `BaseFhirConfigurator` wired to
  `new R4Parser(complex, primitive, FhirCapabilityDefaults.Standard)` using
  the type sets the configurator derives (follow `initializePlatform`'s own
  flow - do not hand-maintain type lists in the fixture);
- `new FSConfigReader(fhirVersion = "R4")` relying on the default classpath
  resolution (this is itself part of the contract under test);
- expose the resulting `BaseFhirConfig` and a `FhirValidator` factory.
  Parsing the full package takes seconds to tens of seconds; it must run
  once (lazy val), and awaits should allow 60s+.

Suite A - `R4StandardPackageParsingTest`:

- the parsed config has non-empty `profileRestrictions`,
  `valueSetRestrictions`, and plausible type universes (spot-assert
  well-known members: resource types Patient/Observation/Bundle, complex
  types CodeableConcept/Quantity, primitive types code/dateTime);
- spot-check one parsed profile in depth (e.g. Observation: status element
  has a min-cardinality restriction and a required binding; value[x] is a
  choice with the expected alternative types);
- spot-check one extensional ValueSet (e.g. administrative-gender: exact
  code set) and one CodeSystem-backed ValueSet;
- the standard CapabilityStatement parses via
  `parseCapabilityStatement` and yields a plausible
  `FHIRCapabilityStatement` (fhirVersion 4.0.1, non-empty resource
  configurations).

Suite B - `R4StandardValidationTest` (all through `FhirValidator`):

- a valid Patient and a valid Observation produce zero issues;
- one negative case per validation category, each asserting the issue's
  severity and expression path:
  - missing required element (Observation without status/code);
  - primitive lexical error (Patient.birthDate = "2026-13-99");
  - choice-type violation (e.g. Patient.deceasedDateTime with a
    non-dateTime value, or an unrecognized valueX field);
  - required binding violation (Observation.status = "bogus");
  - invariant violation (e.g. Patient.contact with neither name, telecom,
    address, nor organization violates pat-1);
  - reference target-type violation (an Observation reference field
    pointing at a resource type its target profiles do not allow);
  - extension handling (an extension with url plus a value of a wrong
    primitive format is reported);
- one Bundle case: a Bundle whose entry resource is invalid reports issues
  at `entry[i].resource...` paths;
- one `meta.profile` case: unknown claimed profile yields a warning while
  base validation still runs.
- Negative-control rule: for each negative test, temporarily make the
  resource valid once to confirm the test fails without the defect, then
  restore (do not keep the sabotage in code; state in Findings that the
  pass was performed).

Suite C - optional if time allows: `R4SearchParameterConfiguratorTest`
smoke test if `SearchParameterConfigurator` can be driven with the parsed
config without server-only inputs; otherwise record as follow-up.

Gate: `mvn -pl onfhir-r4 -am test` green; new suites visible in
surefire-reports; record total test counts and wall-clock in Findings.

### Phase 4 - Bookkeeping

1. `docs/plans/library-server-split-plan-v2.md`: add migration-table rows
   recording the new artifact (`io.onfhir:onfhir-definitions-r4`, new in
   4.0.0, resources-only, no Scala suffix) and onfhir-r4's new test-scope
   dependencies; note it in the current phase record per invariant 4.
2. `docs/compatibility/mima-3.3-accepted.txt`: record the artifact as a new
   artifact following the existing convention used for query and
   template-engine.
3. Root `README.md` module catalog: add a row for onfhir-definitions-r4.
4. `AGENTS.md`: the repository-boundary sentence currently says "nine
   libraries" - update the count/list.
5. `onfhir-r4/README.md`: document the new integration suite and the
   test-scope definitions dependency; `onfhir-validation/README.md` already
   points release-package tests at the release parser module - verify the
   sentence still reads correctly, adjust only if wrong.

### Phase 5 - Full verification

Run and record results in Findings:

1. `mvn test` (full reactor);
2. `powershell -File scripts/check-forbidden-imports.ps1`;
3. `powershell -File scripts/check-library-dependency-licenses.ps1`;
4. `powershell -File scripts/check-binary-compatibility.ps1`;
5. `git status --short` - confirm only intended files were added/changed;
   `git diff --check` clean.

## Interactions with known pending work

- TerminologyParser's hierarchy-filter bug is FIXED (2026-08-05, separate
  task): selection and dispatch now agree on FHIR's `descendent-of`, the
  invalid spelling `descendant-of` is no longer selected as a hierarchy
  filter (it no longer throws either), `is-not-a` is applied instead of being
  unreachable, and filters the parser cannot apply are logged as a warning
  instead of being silently accepted. Consequence for
  Suite A/B: filter-based ValueSets whose CodeSystem ships in the package
  now expand to the defined code set, so exact code membership MAY be
  asserted for `is-a`, `descendent-of`, `is-not-a` and `generalizes`
  includes. Two limitations remain and must NOT be asserted as correct
  behavior: only the FIRST hierarchy filter of an `include` is applied, and
  operators outside `is-a`, `descendent-of`, `is-not-a`, `generalizes`, `=`,
  `in`, `not-in`, `exists`, `regex` (e.g. R5's `child-of`, or a misspelling)
  are ignored, which widens the code set. Both are recorded in the Limitations section of
  `onfhir-validation/README.md` and pinned by `TerminologyParserTest`; if a
  package ValueSet trips on one of them, assert current behavior with a
  `// NOTE: documents current behavior` comment referencing this section.
- FhirTerminologyValidator's unversioned ValueSet lookup is FIXED (2026-08-05,
  separate task): it no longer returns the lexicographically LOWEST version
  key but the latest one its scaladoc promises, preferring the key used for a
  ValueSet without a version (`*`) and otherwise comparing the version parts
  numerically, following the same convention as `FHIRUtil.getMentionedProfile`.
  One limitation remains and must NOT be asserted as correct behavior: a set
  whose keys are not all purely numeric, e.g. `1.0.0-draft`, is not comparable
  and falls back to the greatest key by plain string order; it is recorded in
  the Limitations section of `onfhir-validation/README.md` and the resolution
  order is pinned by `CodeBindingValidationTest`. Standard-package ValueSets
  are effectively single-version, so impact on Suite A/B should be nil.
- The working tree carries unrelated in-progress release-prep changes in
  several modules. Scope every edit to the files this plan names.

## Non-goals

- No R5/R4B definitions artifact yet.
- No promotion of an R4 configurator into onfhir-r4 src/main (optional
  follow-up decision for the owner).
- No Repofyr changes (switching the server to consume this artifact is a
  separate post-split task).
- No further work on ValueSet version resolution (the FhirTerminologyValidator
  version-resolution bug and the TerminologyParser filter bug are both already
  fixed by separate tasks).
- No publishing, signing, or pushing.

## Findings

(To be filled by the executor: constant values resolved in Phase 0, zip
entry inventory, plugin adjustments needed for the no-code module, test
counts and timings, negative-control confirmation, suspected product bugs
with file/line evidence, and any deviations from this plan with reasons.)

### Phase 0 - Preflight (executed 2026-08-05)

Constants resolved from
`onfhir-common/src/main/scala/io/onfhir/api/api.scala` and
`.../api/util/FHIRUtil.scala`:

| Constant | Definition | Value with defaults |
| --- | --- | --- |
| `DEFAULT_ROOT_FOLDER` | `api.scala:35`, `var ... : Option[String] = None` | `None` |
| `FOUNDATION_RESOURCES_FILE_SUFFIX` | `api.scala:79`, `var ... = ".json"` | `.json` |
| `FHIRUtil.mergeFilePath(None, sub)` | `FHIRUtil.scala:796` | `sub` (no prefix) |
| `DEFAULT_RESOURCE_PATHS.BASE_DEFINITONS_R4` | `api.scala:62` | `definitions-r4.json.zip` |
| `DEFAULT_RESOURCE_PATHS.CONFORMANCE_PATH_R4` | `api.scala:65` | `conformance-statement-r4.json` |

Because `DEFAULT_ROOT_FOLDER` defaults to `None`, both default paths are bare
file names, and both are loaded through
`getClass.getClassLoader.getResourceAsStream(...)`
(`IOUtil.readInnerResource`, `IOUtil.readResourceInZip`). The required
classpath layout is therefore the CLASSPATH ROOT, i.e.
`src/main/resources/definitions-r4.json.zip` and
`src/main/resources/conformance-statement-r4.json` with no intermediate
folder. Note that `DEFAULT_ROOT_FOLDER` and
`FOUNDATION_RESOURCES_FILE_SUFFIX` are mutable `var`s: an application that
reassigns them changes the default lookup, so the classpath-root contract
holds only for the unmodified defaults. This is pre-existing behavior, not
something this module introduces.

Standard bundle file names expected by `IFhirVersionConfigurator`
(`onfhir-common/src/main/scala/io/onfhir/config/IFhirVersionConfigurator.scala:11-21`):
`search-parameters.json`, `profiles-resources.json`, `profiles-types.json`,
`profiles-others.json`, `extension-definitions.json`, and the value
set / code system trio `valuesets.json`, `v3-codesystems.json`,
`v2-tables.json`.

Zip inventory of `definitions-r4.json.zip` (12 entries, all present and
matching the constants above):

| Entry | Uncompressed bytes |
| --- | --- |
| `profiles-resources.json` | 35,148,211 |
| `dataelements.json` | 20,861,067 |
| `valuesets.json` | 9,319,611 |
| `v3-codesystems.json` | 6,937,996 |
| `v2-tables.json` | 6,159,346 |
| `profiles-others.json` | 5,638,952 |
| `extension-definitions.json` | 4,524,933 |
| `profiles-types.json` | 2,529,955 |
| `search-parameters.json` | 2,208,733 |
| `fhir.schema.json.zip` | 287,789 |
| `conceptmaps.json` | 230,472 |
| `version.info` | 94 |

`version.info` confirms the package release:
`FhirVersion=4.0.1-9346c8cc45`, `version=4.0.1`, `buildId=9346c8cc45`,
`date=20191101092923`. This is the value recorded as `fhir.version=4.0.1` in
`onfhir-definitions.properties`.

Resource copy verification (byte-for-byte, no network download):

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `definitions-r4.json.zip` | 8,900,279 | `2A7ACD4FA755DD5768CA44199AA1248013FF93B74EA9431850B443CD56534EF5` |
| `conformance-statement-r4.json` | 834,879 | `43289458AE3A9ADCD66AECB8C6C427A43B0C3B9E5F08C163E653FDDA67F803BB` |

Both hashes are identical to the sources under
`C:\srdc\codes\onfhir-io\onfhir\onfhir-server-r4\src\main\resources`.
`db-index-conf-r4.json` (10,864 bytes) was left in the server module per
design decision 5.

### Phase 0 - Verification gate scripts

`scripts/check-forbidden-imports.ps1` and
`scripts/check-library-dependency-licenses.ps1` both carry a hard-coded
nine-module list. `scripts/check-binary-compatibility.ps1` carries a
hard-coded `$artifacts` map plus a `$newArtifacts` list, and its baseline
comparison fails if the generated report does not match
`docs/compatibility/mima-3.3-accepted.txt` exactly. Consequences:

- `check-binary-compatibility.ps1` MUST gain `onfhir-definitions-r4` in both
  `$artifacts` and `$newArtifacts` at the same time as the
  `mima-3.3-accepted.txt` entry required by Phase 4.2; changing only one of
  the two makes the gate fail. Deferred to Phase 4.
- `check-forbidden-imports.ps1` should NOT gain the new module. It runs
  `Select-String` over every file under `src/main/resources`, which would
  scan an 8.9 MB binary zip and an 834 KB JSON on every gate run for
  third-party content that contains no onFHIR source. Recorded here as a
  deliberate deviation rather than an omission.
- `check-library-dependency-licenses.ps1` needs no change: the module
  declares zero dependencies, so it contributes nothing to the aggregate
  third-party report (design decision 7).

Baseline build before any change: `mvn -pl onfhir-r4 -am test` BUILD SUCCESS
(exit 0), no tests in onfhir-r4 as expected.

### Phase 1 - Module creation (executed 2026-08-05)

Files added:

- `onfhir-definitions-r4/pom.xml` - parent `onfhir-libs-parent`, artifactId
  `onfhir-definitions-r4` (no Scala suffix), packaging `jar`, zero
  dependencies, no `sourceDirectory`, no scala-maven-plugin in the default
  build.
- `onfhir-definitions-r4/src/main/resources/definitions-r4.json.zip`
- `onfhir-definitions-r4/src/main/resources/conformance-statement-r4.json`
- `onfhir-definitions-r4/src/main/resources/onfhir-definitions.properties`
- `onfhir-definitions-r4/README.md`

Files changed: root `pom.xml` (reactor entry before `onfhir-r4`, plus an
`antrun.plugin.version` property), `onfhir-libs-bom/pom.xml` (managed
dependency without Scala suffix), `NOTICE` (CC0 1.0 attribution for the
packaged HL7 content).

No root `dependencyManagement` entry was added: that section manages
third-party dependencies only, while inter-module dependencies in this repo
are declared inline with `${onfhir.libs.version}`. This follows the existing
convention rather than the "if the repo convention requires it" branch of
Phase 1 step 2.

Plugin behavior on a no-code module - nothing failed, but the default
inherited `release` configuration produced two wrong outputs, so the module
now carries a minimal `release` profile override:

- `maven-source-plugin:jar-no-fork` bundles resources when no source root
  exists, so the sources JAR came out at 8,908,881 bytes - a near-exact
  duplicate of the 8,913,477-byte main JAR. Fixed with
  `excludeResources=true` plus `forceCreation=true`, giving a 1,860-byte
  marker sources JAR.
- `scala-maven-plugin:doc-jar` logs "No source files found" and attaches
  nothing, so no javadoc artifact existed at all. Maven Central expects one
  per artifact, so a `maven-jar-plugin` execution now attaches a 1,874-byte
  empty JAR with the `javadoc` classifier. maven-jar-plugin will not archive
  a nonexistent directory, so a `maven-antrun-plugin` execution
  (`antrun.plugin.version` 3.1.0, new property in the root pom) creates the
  empty input directory in `prepare-package`. Both overrides live in the
  module's `release` profile only; the default build still produces exactly
  one JAR.

Phase 1 gate results:

- `mvn -B -pl onfhir-definitions-r4 install` BUILD SUCCESS in 1.8 s.
- Main JAR (8,913,477 bytes) contents verified: `definitions-r4.json.zip`,
  `conformance-statement-r4.json` and `onfhir-definitions.properties` at the
  classpath ROOT (the layout Phase 0 requires), plus `META-INF/LICENSE`
  (11,357 bytes), `META-INF/NOTICE` (702 bytes, includes the new CC0 note),
  `META-INF/MANIFEST.MF`, and the flattened `META-INF/maven` POM.
- `mvn -B -Prelease -pl onfhir-definitions-r4 package` BUILD SUCCESS, and the
  enforcer `ban-actor-frameworks` rule passes (zero dependencies).
- `mvn -B install -DskipTests` for the whole reactor: BUILD SUCCESS, 12
  modules, the new module at position 10 of 12, immediately before
  `onfhir-r4`. `onfhir-libs-bom/.flattened-pom.xml` carries the new managed
  dependency in the same form as the existing entries.
- The default (non-release) install publishes only the main JAR and the
  flattened POM to the local repository, confirming the empty
  sources/javadoc JARs are release-profile only.

### Phase 1 - Line-ending hazard found and fixed

`conformance-statement-r4.json` is CRLF-terminated upstream: 19,203 CRLF
pairs, zero lone LF, zero lone CR, zero NUL bytes, 834,879 bytes total. Because
it has no NUL bytes, git classifies it as text, and this repository has
`core.autocrlf=true` with no `.gitattributes` rule covering it. The
consequence is that committing it as-is stores a normalized 815,676-byte blob,
which a Windows checkout restores to 834,879 bytes but a Linux or CI checkout
writes as 815,676 bytes. The published JAR would then be byte-different
depending on the build host, and hard constraint 6 (byte-for-byte copy) would
hold only on Windows.

Fix: `.gitattributes` now marks both packaged FHIR resources `-text`, so git
stores and restores the exact upstream bytes on every platform. Verified with
`git check-attr text`, which reports `text: unset` for both files while
`onfhir-definitions.properties` stays normal text (`text: unspecified`). The
zip would have been auto-detected as binary anyway; the rule is explicit for
both so the intent is self-documenting.

`git status --short --untracked-files=all -- onfhir-definitions-r4` lists
exactly the five intended files; `target/` and `.flattened-pom.xml` are
already ignored. All new and edited text files are ASCII-only (verified by
byte scan). `git diff --check` exits 0.

### Phase 2 - onfhir-r4 test wiring (executed 2026-08-05)

`onfhir-r4/pom.xml` changes only; no other module touched:

- `<testSourceDirectory>src/test/scala</testSourceDirectory>` and a
  `maven-surefire-plugin` entry, mirroring `onfhir-validation/pom.xml`. The
  scala-maven-plugin was already declared, and the parent's
  `scala-test-compile` execution binds `testCompile` automatically, so no
  additional plugin execution was needed.
- `io.onfhir:onfhir-definitions-r4` at `test` scope (no Scala suffix).
- `io.onfhir:onfhir-config_${scala.binary.version}` at `test` scope.
- `specs2-core` and `specs2-junit` at `provided` scope, per hard constraint 5.

Cycle check before wiring: `onfhir-config/pom.xml` depends on
`onfhir-common`, `onfhir-client` and `onfhir-validation` only, and not on
`onfhir-r4`, confirming the verified fact that this edge is acyclic. The
reactor for `-pl onfhir-r4 -am` grew from 5 to 8 modules accordingly.

Phase 2 gate results:

- `mvn -B -pl onfhir-r4 -am test-compile` BUILD SUCCESS (exit 0) with an empty
  test tree; scala-maven-plugin reports "No sources to compile" for the
  not-yet-existing `src/test/scala`.
- `mvn -B -pl onfhir-r4 dependency:tree` shows
  `io.onfhir:onfhir-definitions-r4:jar:4.0.0:test` and
  `io.onfhir:onfhir-config_2.13:jar:4.0.0:test`, with every config transitive
  (`onfhir-client`, typesafe `config`, nimbus JOSE/OIDC, json-smart, asm)
  also confined to `test`. Compile scope is unchanged: scala-library,
  onfhir-common, onfhir-validation, slf4j-api. specs2 and junit are
  `provided`. Because
  `check-library-dependency-licenses.ps1` runs with
  `-Dlicense.excludedScopes=test,provided`, the new nimbus and json-smart
  transitives add no license-review surface.
- `mvn -B -pl onfhir-r4 -am test` BUILD SUCCESS (exit 0): 361 existing tests
  green upstream (39 common, 72 path, 199 client, 51 validation) and "No tests
  to run" for config, definitions and r4.

Beyond the required gate, the Phase 0 classpath reasoning was validated
end-to-end against the real product code before writing any suite, using a
throwaway Java program on the generated onfhir-r4 test classpath (kept out of
the repository, in the session scratchpad):

- `ClassLoader.getResourceAsStream` resolves both `definitions-r4.json.zip`
  and `conformance-statement-r4.json` from the bare default names.
- `IOUtil.readInnerResource("conformance-statement-r4.json")` parses a
  `CapabilityStatement`.
- `IOUtil.readStandardBundleFile(None, "definitions-r4.json.zip",
  "profiles-types.json", Set("StructureDefinition"))` returns 63
  StructureDefinitions.
- `onfhir-definitions.properties` reads back `fhir.version=4.0.1` and
  `content.license=CC0-1.0`.

So the design decision 4 contract holds: this artifact on the classpath is
enough for the default resolution paths, with no explicit paths supplied.

Phase 3 note for the executor: parsing `profiles-resources.json` (35 MB) and
the value set bundles in one JVM is the memory-heaviest thing this repository
will do. Surefire currently inherits the default heap with
`forkCount=1`/`reuseForks=true` and no `argLine`. If Suite A or B hits
`OutOfMemoryError`, set an explicit surefire `argLine` heap for onfhir-r4
rather than reducing coverage, and record it here.

### Phase 3 - Integration suites (executed 2026-08-05)

Four new test files under `onfhir-r4/src/test/scala/io/onfhir/r4/`, no
`src/main` change anywhere:

| File | Tests |
| --- | --- |
| `R4IntegrationFixtures.scala` | shared fixture (object, not a test) |
| `R4StandardPackageParsingTest.scala` | 10 |
| `R4StandardValidationTest.scala` | 15 |
| `R4SearchParameterConfiguratorTest.scala` | 9 |

Fixture notes:

- The concrete configurator is a 5-line private subclass of
  `BaseFhirConfigurator` overriding only `fhirVersion` and
  `getFoundationResourceParser`. Everything else comes from
  `initializePlatform`, so the fixture hand-maintains no type lists.
- `new FSConfigReader(fhirVersion = "R4")` is constructed with no explicit
  paths, so the classpath-default contract is exercised by construction.
- The await helper is named `awaitResult`, NOT `await`: `await` collides with
  `org.specs2.matcher.FutureMatchers.await`, which is in scope inside a
  `Specification` and produced three "required: Matcher[?]" compile errors.
  `onfhir-validation`'s fixture already uses `awaitResult` for the same reason.
- Parsing the package proved far cheaper than the plan assumed: 4.3 s and no
  heap tuning. The Phase 2 `OutOfMemoryError` contingency did not materialise,
  so surefire keeps the inherited default heap and no `argLine` was added.

Parsed facts pinned by Suite A (FHIR 4.0.1 as packaged):

| Quantity | Value |
| --- | --- |
| `FHIR_RESOURCE_TYPES` | 147 |
| `FHIR_COMPLEX_TYPES` | 39 |
| `FHIR_PRIMITIVE_TYPES` | 20 |
| `profileRestrictions` | 629 |
| `valueSetRestrictions` | 1199 |
| CapabilityStatement `restResourceConf` | 145 |
| SearchParameter definitions | 1375 |
| Observation search parameters | 38 (all 38 configure) |

Counts are asserted exactly, on purpose: the definitions artifact is
byte-pinned, so a count change means the package or a parser changed and
should be reviewed rather than absorbed silently.

Suite A also cross-checks the two packaged files against each other: every
resource the CapabilityStatement declares is a parsed resource type, and every
profile URL it names resolves in `profileRestrictions`.

Suite B contract detail worth knowing: the base `dom-6` invariant ("A resource
should have narrative for robust management") is a WARNING on every
DomainResource, so a resource with no `text.div` can never yield an empty issue
list. The positive fixtures therefore carry a narrative, which makes "no
issues" mean literally zero. Suite B pins this too - the same Patient with the
narrative removed yields exactly one dom-6 warning and nothing else.

Suite C was delivered even though the plan marked it optional. It needs a
`FhirServerConfig`, but that type lives in onfhir-common and is a plain holder
populated from the parsed `BaseFhirConfig`, so no server runtime is involved.
It is the only coverage of `R4Parser.parseSearchParameter` and of the
configurator's choice-element expansion (`date` ->
`effectiveDateTime/effectivePeriod/effectiveTiming/effectiveInstant`) and
reference-target resolution (`subject` -> Patient/Group/Device/Location, while
`patient` narrows the same element to Patient/Group).

Negative-control pass (plan requirement) - PERFORMED. Every injected defect in
Suite B was removed at once (missing status/code restored, `birthDate`
corrected, `deceasedDateTime` made a real dateTime, `deceasedBogus` renamed to
`deceasedBoolean`, `status` set to `final`, `contact` given a name,
`Medication/med1` retargeted to `Patient/pat1`, the extension value made a real
dateTime) and the suite was re-run: 10 of 14 tests failed, exactly the ten
negative cases. The four that still passed are the two conformant-resource
tests, the vitalsigns test and the narrative test, none of which carry an
injected defect. The sabotage was reverted immediately and the file verified
byte-identical to its backup; nothing from that pass remains in the tree.

Gate: `mvn -B -pl onfhir-r4 -am test` BUILD SUCCESS (exit 0) in 49.3 s;
onfhir-r4 alone runs in 28.5 s of which 6.8 s is test execution. Reactor total
395 tests (39 common, 72 path, 199 client, 51 validation, 34 r4).
`onfhir-r4/target/surefire-reports/` contains exactly
`io.onfhir.r4.R4SearchParameterConfiguratorTest.txt`,
`io.onfhir.r4.R4StandardPackageParsingTest.txt` and
`io.onfhir.r4.R4StandardValidationTest.txt`, confirming the JUnit provider
picks up all three `@RunWith(classOf[JUnitRunner])` classes.

### Phase 3 - Suspected product defects (recorded, NOT fixed)

1. `BaseFhirConfig.fhirVersion` is never populated by the configuration
   pipeline. `BaseFhirConfig.scala:16` documents it as "Numeric FHIR version
   supported e.g. 4.0.1", but `BaseFhirConfigurator.initializePlatform`
   (`BaseFhirConfigurator.scala:47`) only passes the release family to the
   constructor and no code in onfhir-config ever assigns the field, so it stays
   `null` for every config the pipeline builds. Visible consequence:
   `FhirValidator.normalizeBaseProfileVersion`
   (`FhirValidator.scala:190-193`) compares a claimed profile version against
   `fhirConfig.fhirVersion`, so the normalization can never match and a
   `meta.profile` claim carrying the version suffix is not folded onto the base
   profile. It does not throw, because `Option.contains(null)` is simply false.
   Pinned by `R4StandardPackageParsingTest` "leave the numeric fhirVersion
   unset" with a `// NOTE: documents current behavior` comment.
2. `CodeBindingRestriction` reports a CodeableConcept that carries only `text`
   through its Coding branch. `CodeBindingRestriction.scala:35` selects the
   CodeableConcept branch only when a `coding` field is present, so a
   `{"text":"..."}` CodeableConcept falls through to `case obj: JObject` at
   line 48 and line 54 renders `printSystemCodes(Seq(None -> None))`. The
   resulting diagnostics read `system-code pairing '(' ',' ')'` instead of
   naming the missing coding. The severity is correct (warning for a
   non-required binding), so this is a message-quality defect, not a
   correctness one. Pinned by `R4StandardValidationTest` "enforce a known
   claimed meta.profile ..." with a `// NOTE: documents current behavior`
   comment.
3. Minor: `SearchParameterConf.onExtension` is commented out in
   `onfhir-common/src/main/scala/io/onfhir/config/SearchParameterConf.scala:34`
   while the scaladoc above the case class still describes it. Pre-existing,
   unrelated to this work, worth a cleanup decision.

### Phase 3 - Optional follow-up for the owner

Writing the fixture made the case for promoting a concrete R4 configurator into
`onfhir-r4/src/main` (design note: the plan lists this as out of scope). The
subclass needed is 5 lines, but every consumer that wants to build a
`BaseFhirConfig` from the standard package - Repofyr, spark-on-fhir, toFHIR,
and this test suite - has to write the same 5 lines and know that
`getFoundationResourceParser` is the only R4-specific hook. A public
`R4Configurator` in onfhir-r4, paired with `onfhir-definitions-r4`, would make
"parse the R4 standard package" a two-line operation for library users. It is a
public API addition, so it stays the owner's call.

### Phase 4 - Bookkeeping (executed 2026-08-05)

All five planned items done, plus two gate-script corrections the plan did not
anticipate.

1. `docs/plans/library-server-split-plan-v2.md`:
   - Section 2 library-family list gained `onfhir-definitions-r4` with a
     "resources only, no Scala suffix" note.
   - Section 7.3 (build and version contracts) gained two rows: the new
     artifact itself (coordinate, first release 4.0.0, packaged files, the
     no-suffix rationale against invariant 3, version-vs-FHIR-version split,
     CC0 content, and the `-text` attribute), and onfhir-r4's new test-scope
     dependencies with the explicit note that the published dependency graph is
     unchanged.
   - The Phase 5A implementation record gained two bullets per invariant 4:
     the reactor now builds ten reusable modules and the family publishes
     twelve coordinates instead of eleven, the staging rehearsal must be
     repeated for the new coordinate as well as for the template-engine suffix
     correction, and onfhir-r4 now has 34 tests taking the reactor from 98 to
     395.
   - Gate C and the Section 9 definition of done said "nine library modules";
     both are forward-looking statements, so both now say ten, and the Section
     9 coordinates bullet names the deliberately unsuffixed
     `io.onfhir:onfhir-definitions-r4`. Every other "nine" in the document sits
     inside a completed phase record and was left alone as an accurate
     historical statement.
2. `docs/compatibility/mima-3.3-accepted.txt`: added a
   `## onfhir-definitions-r4` section with
   `NEW-ARTIFACT: no 3.3 artifact was available`, following the convention
   already used for query and template-engine, positioned between
   template-engine and r4 to match the generator's iteration order.
3. Root `README.md`: module catalog row added.
4. `AGENTS.md`: the boundary sentence now reads "nine reusable Scala libraries
   ... plus the resources-only `onfhir-definitions-r4` artifact". A short
   paragraph was also added to the licensing note, because this is the only
   artifact whose packaged content is not first-party; it records the CC0
   content, the missing Scala suffix, and the `.gitattributes` byte-pinning, so
   a future agent does not "correct" any of the three.
5. `onfhir-r4/README.md`: new "Integration test suite" section describing the
   fixture, the test-scope definitions and config dependencies, and a table of
   the three suites. `onfhir-validation/README.md`'s test-guidance sentence was
   verified to still read correctly and was NOT rewritten; one factual sentence
   was appended pointing at where those release-package tests now live.

Gate-script corrections (required, see the Phase 0 gate-script analysis):

- `scripts/check-binary-compatibility.ps1` gained `onfhir-definitions-r4` in
  both `$artifacts` and `$newArtifacts`. Both had to change together with the
  baseline file or the report/baseline comparison would fail. Verified:
  `powershell -File scripts/check-binary-compatibility.ps1` prints
  `check-binary-compatibility: PASS`, exit 0.
- `scripts/check-staged-release.ps1` was NOT in the plan's Phase 4 list but had
  to change, and it carried a pre-existing defect. It enumerates the exact
  coordinates a staging repository must contain and requires a POM, binary,
  sources JAR and javadoc JAR for every `jar` entry:
  - It still listed `onfhir-template-engine` without the `_2.13` suffix, even
    though the module's actual artifactId is `onfhir-template-engine_2.13` per
    the correction approved on 2026-08-04. This was the last place the
    correction had not landed: the working tree's uncommitted changes had
    already updated the MiMa baseline section heading to
    `## onfhir-template-engine_2.13`, and both the root reactor and the BOM use
    the suffixed ID, but this script was still on the old name. With the stale
    entry the repeated staging rehearsal the Phase 5A record calls for would
    have failed on a missing artifact instead of verifying the corrected one.
    Corrected here for consistency; flagged for owner review since it is
    outside this plan's scope.
  - Added `onfhir-definitions-r4` as a `jar` coordinate. This is also the
    concrete justification for the Phase 1 release-profile work: without the
    empty javadoc JAR the gate would fail on the new artifact, because a
    resources-only module produces no Scaladoc.
  - Verified the new artifact satisfies the gate's POM checks: its flattened
    POM contains "Apache License, Version 2.0" and no GPL metadata.

`scripts/check-forbidden-imports.ps1` and
`scripts/check-library-dependency-licenses.ps1` were deliberately left
unchanged, for the reasons recorded in the Phase 0 gate-script analysis.
`check-forbidden-imports.ps1` was run anyway and still passes.

Note on hard constraint 2: `docs/compatibility/mima-3.3-accepted.txt` contains
three non-ASCII bytes, but they are a pre-existing UTF-8 BOM at offset 0 that
`check-binary-compatibility.ps1` itself writes through
`Set-Content -Encoding UTF8` under Windows PowerShell 5.1. Every byte added by
this phase is ASCII, and the gate reads the file with `Get-Content -Raw`, so the
BOM is immaterial to the comparison. All other edited files are pure ASCII.

### Phase 5 - Full verification (executed 2026-08-05)

| Gate | Result |
| --- | --- |
| 1. `mvn test` (full reactor) | PASS, exit 0, 115 s, 469 tests, 12 modules |
| 2. `scripts/check-forbidden-imports.ps1` | PASS, 0 findings across all nine Scala modules |
| 3. `scripts/check-library-dependency-licenses.ps1` | PASS, 31 external dependencies reviewed, one reviewed override (`org.antlr:antlr-runtime:3.3` -> BSD-3-Clause) |
| 4. `scripts/check-binary-compatibility.ps1` | PASS, exit 0, after the unrelated relocation below was reconciled; FAIL before that |
| 5. `git status --short` / `git diff --check` | reviewed; `git diff --check` exit 0 |

Gate 1 test distribution: 39 common, 72 path, 199 client, 67 query, 51
validation, 7 template-engine, 34 r4. `onfhir-expression`, `onfhir-config` and
`onfhir-definitions-r4` contribute no tests. Gate 1 was run twice, the second
time deliberately after the owner's concurrent commits landed (see below), and
passed both times.

Gate 3 reports 31 external dependencies where the Phase 5A record says 33. The
delta predates this work: this module declares zero dependencies, and the
test-scope additions to onfhir-r4 are excluded by the gate's
`-Dlicense.excludedScopes=test,provided`, so nothing here can have changed the
compile-scope dependency set.

#### Gate 4 - an unrelated in-flight relocation, since reconciled

`check-binary-compatibility.ps1` passed earlier in this session, immediately
after the Phase 4 baseline and script change, and fails now. The cause is not
this plan's work. During the session the owner worked in the same tree: HEAD
advanced from `5716258` to `61a5b8d` with two new commits (`ae10ec7`
TerminologyParser filter-operator fix, `61a5b8d` FhirTerminologyValidator
unversioned-ValueSet-lookup fix - both were listed as pending items in the
Interactions section above, so both are now closed), and a rename was staged:

```
R100 onfhir-validation/src/main/scala/io/onfhir/validation/BaseFhirProfileHandler.scala
  -> onfhir-config/src/main/scala/io/onfhir/validation/BaseFhirProfileHandler.scala
```

Moving `io.onfhir.validation.BaseFhirProfileHandler` from the validation
artifact to the config artifact produces exactly two new MiMa breaks:

```
## onfhir-config_2.13
io.onfhir.config.SearchParameterConfigurator: MissingTypes

## onfhir-validation_2.13
io.onfhir.validation.BaseFhirProfileHandler: MissingClass
```

`MissingClass` because the type left the validation artifact; `MissingTypes`
because `SearchParameterConfigurator`'s parent is no longer supplied from
another artifact.

Evidence that this is external to the definitions work: no file under any
`src/main` was edited by this plan (the only `src/main` entries in
`git status` are the staged rename plus the owner's
`onfhir-query/.../PreservePlaceholderResolver.scala` and
`onfhir-validation/.../ReferenceRestrictions.scala`, alongside the new
`onfhir-definitions-r4/src/main/resources` files). A regenerated report was
compared against the working baseline with `Compare-Object`: the ONLY
differences were those two lines. The `## onfhir-definitions-r4` section added
in Phase 4 matched the generated report exactly, in content and position, which
confirms the Phase 4 baseline and script change is correct and complete.

Initial action: none. The script's own instruction is "Run with -UpdateBaseline
only after reconciling changes with the migration table", invariant 4 requires a
relocation to be recorded by the phase that implements it, and hard constraint 1
forbids absorbing unrelated in-progress work. The baseline was restored to its
Phase 4 state after each diagnostic run and verified byte-identical to its
backup, so the break was never silently accepted. The reconciliation was
reported to the owner as their decision.

Resolution - the owner authorised the reconciliation, and the two edits were
already present in the tree when it was applied, having been made by the owner
concurrently. They were therefore verified rather than re-applied:

- Section 7.1 gained
  `| io.onfhir.validation.BaseFhirProfileHandler | onfhir-validation_2.13 | onfhir-config_2.13 | none | 5A | implemented 2026-08-05 |`,
  and the consumer note below the table now records that consumers must declare
  a direct `onfhir-config_2.13` dependency while the class keeps its
  `io.onfhir.validation` package. That package/artifact mismatch is the wrinkle
  worth remembering: the package name no longer indicates the owning artifact.
- The MiMa baseline gained the two lines in the exact positions the generator
  emits them: `SearchParameterConfigurator: MissingTypes` after
  `BaseFhirServerConfigurator: MissingClass` in the `onfhir-config_2.13`
  section, and `BaseFhirProfileHandler: MissingClass` between the two
  `AbstractStructureDefinitionParser` lines and the `FhirContentValidator` lines
  in the `onfhir-validation_2.13` section.

Verified: each of the three additions occurs exactly once, so nothing is
duplicated, and `powershell -File scripts/check-binary-compatibility.ps1` now
prints `check-binary-compatibility: PASS`, exit 0, in 36 s. All five Phase 5
gates are green.

Gate 5 detail: the working tree holds this plan's files - the new
`onfhir-definitions-r4/` module, four `onfhir-r4/src/test` files, this document,
and edits to `.gitattributes`, `AGENTS.md`, `NOTICE`, `README.md`, `pom.xml`,
`onfhir-libs-bom/pom.xml`, `onfhir-r4/pom.xml`, three module READMEs, the MiMa
baseline and two gate scripts - alongside the owner's concurrent work. Nothing
from this plan was staged, committed or pushed; the two commits on HEAD are the
owner's.

### Follow-on - onfhir-definitions-r5 (executed 2026-08-05, owner-requested)

Approved deviation: design decision 1 and the non-goals deferred an R5 artifact
"until a matching parser exists in this repository". No R5 parser exists yet.
The owner asked for the artifact anyway; this is recorded as a deliberate
override, not an oversight. The artifact is release-agnostic packaging, so it is
useful on its own, and `onfhir-r4`'s README already documents that the onFHIR R5
server configurator reuses `R4Parser` for foundation resources.

Sources copied byte-for-byte from
`C:\srdc\codes\onfhir-io\onfhir\onfhir-server-r5\src\main\resources`, SHA-256
verified:

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `definitions-r5.json.zip` | 7,712,577 | `7AA0F3FEEA13ECCCC5783EA00AF875D72CB75DCEF8548DD213039451779EFF28` |
| `conformance-statement-r5.json` | 900,075 | `1C451E55C72C6BFFE07784C50A13AE982D13461699BA46462891ED9C69DDC6C3` |

`version.info` in the ZIP confirms `version=5.0.0`, `buildId=2aecd53`,
`date=20230326152102`. `db-index-conf-r5.json` (10,838 bytes) stays in the
server module, matching design decision 5.

The same CRLF hazard applies: the R5 conformance statement has 20,406 CRLF pairs
and no NUL bytes, so both R5 resources are now marked `-text` in
`.gitattributes` alongside the R4 pair. Verified with `git check-attr text`,
which reports `text: unset` for both.

#### Classpath collision found and fixed

Both modules would have shipped `onfhir-definitions.properties` at the classpath
ROOT. With both artifacts present, `getResourceAsStream` would return whichever
JAR came first, so a consumer targeting R5 could silently read
`fhir.version=4.0.1`. The R4 file was therefore renamed to
`onfhir-definitions-r4.properties` and the new one created as
`onfhir-definitions-r5.properties`, matching how the ZIP and conformance file
names already carry the release. This deviates from the Phase 1 spec, which named
the file `onfhir-definitions.properties`, and is safe because neither artifact
has been released. The R4 README, the Section 7.3 migration row and this record
were updated accordingly.

#### The R5 package has no v2/v3 terminology bundles

The R5 ZIP has ten entries and, unlike R4's twelve, ships no
`v3-codesystems.json` and no `v2-tables.json`; in R5 those HL7 v2/v3 code
systems moved to the separate terminology package. This matters because
`IFhirVersionConfigurator.VALUESET_AND_CODESYSTEM_BUNDLE_FILES` names all three,
and `initializePlatform` reads every entry through `readStandardBundleFile`,
which throws `InitializationException` on a missing bundle. Verified empirically
rather than assumed, with a throwaway Java program on a classpath holding both
definitions artifacts:

- `definitions-r5.json.zip` and `conformance-statement-r5.json` both resolve
  from their bare default names; the conformance file parses as a
  `CapabilityStatement`.
- `profiles-types.json` yields 71 StructureDefinitions,
  `profiles-resources.json` 162, and `valuesets.json` 1236 ValueSet/CodeSystem
  resources.
- `v3-codesystems.json` and `v2-tables.json` both raise
  `InitializationException`.
- `onfhir-definitions-r4.properties` reads `4.0.1` and
  `onfhir-definitions-r5.properties` reads `5.0.0` from the same classpath, and
  the old shared name no longer resolves, confirming the collision fix.

A consumer driving `BaseFhirConfigurator` against this package must therefore
override the protected `VALUESET_AND_CODESYSTEM_BUNDLE_FILES` to
`Seq("valuesets.json")` and supply v2/v3 code systems separately if terminology
validation needs them. The module README carries this with a code sample,
because it is the one landmine in using the artifact.

#### Wiring and bookkeeping

Reactor entry after `onfhir-definitions-r4`; BOM managed dependency; `NOTICE`
restructured to list both artifacts and their upstream URLs; root `README.md`
catalog row; `AGENTS.md` boundary and licensing paragraphs pluralised;
split-plan Section 2 list, the Section 7.3 row rewritten to cover both releases
and the per-release properties naming, the Phase 5A record bullet updated to
eleven reusable modules and thirteen coordinates, and the Gate C and Section 9
counts moved from ten to eleven. Both gate scripts with hard-coded coordinate
lists gained the new artifact: `check-binary-compatibility.ps1` in `$artifacts`
and `$newArtifacts`, `check-staged-release.ps1` as a `jar` coordinate, and the
MiMa baseline gained a `## onfhir-definitions-r5` NEW-ARTIFACT section
positioned to match the generator's iteration order.

#### Verification

| Gate | Result |
| --- | --- |
| `mvn -pl onfhir-definitions-r5 install` | PASS; JAR carries both resources and the properties file at the classpath root plus `META-INF/LICENSE` and `META-INF/NOTICE` |
| `mvn -Prelease -pl onfhir-definitions-r4,onfhir-definitions-r5 package` | PASS; each module attaches a 1,860-byte marker sources JAR and a 1,874-byte empty javadoc JAR |
| `mvn test` (full reactor) | PASS, 207 s, 13 modules, 479 tests |
| `check-forbidden-imports.ps1` | PASS |
| `check-library-dependency-licenses.ps1` | PASS, 31 external dependencies |
| `check-binary-compatibility.ps1` | PASS |
| `git diff --check` | exit 0; all new and edited text files ASCII-only |

Reactor test count rose from 469 to 479 because `onfhir-config` gained 10 tests
of its own during the session, from the owner's concurrent work; the R5 module
itself ships no tests, for the reason given above.

### Follow-on - R5 integration suites in onfhir-r4 (executed 2026-08-05, owner-requested)

The owner asked whether R5 suites belong under `onfhir-r4` given that the R5
server reuses the same parsers, and confirmed that Repofyr's R5 configurator
already narrows `VALUESET_AND_CODESYSTEM_BUNDLE_FILES`. Answer: yes - the
`R4Parser` reuse is exactly the contract nothing in onfhir-libs pinned, so the
suites were added.

Fixture: `R5IntegrationFixtures` mirrors Repofyr's
`onfhir-server-r5 FhirR5Configurator` (read from the sibling working copy) in
the two library-relevant respects: `fhirVersion = "R5"` and
`override protected val VALUESET_AND_CODESYSTEM_BUNDLE_FILES =
Seq("valuesets.json")`, with `getFoundationResourceParser` returning
`new R4Parser(...)`. The R5 README's suggested override therefore matches the
production configurator exactly. `onfhir-r4/pom.xml` gained the test-scope
`onfhir-definitions-r5` dependency; both definitions artifacts now sit on one
test classpath, which makes the per-release resource naming load-bearing and
continuously verified.

New suites (package `io.onfhir.r4`, same conventions as the R4 suites):

| Suite | Tests |
| --- | --- |
| `R5StandardPackageParsingTest` | 11 |
| `R5StandardValidationTest` | 9 |

R5 facts pinned (FHIR 5.0.0 as packaged; parse takes ~3.2 s):

| Quantity | Value |
| --- | --- |
| `FHIR_RESOURCE_TYPES` | 158 (includes NutritionProduct, InventoryItem, TestPlan) |
| `FHIR_COMPLEX_TYPES` | 42 (includes CodeableReference, RatioRange, Availability, ExtendedContactDetail) |
| `FHIR_PRIMITIVE_TYPES` | 21 (includes integer64) |
| `profileRestrictions` | 665 |
| `valueSetRestrictions` | 546 (v2/v3 bundles absent, vs 1199 in R4) |
| CapabilityStatement `restResourceConf` | 157; fhirVersion 5.0.0; NO patchFormat and NO search-system interaction, unlike the R4 base statement |
| SearchParameter definitions | 1239, all with `xpath = None` (R5 removed the element; the R4 parser reads it as optional) |
| Observation search parameters | 42, of which 39 configure |

R5-specific behavior pinned with `// NOTE: documents current behavior`:

1. `Observation.value[x]` has 13 alternatives including the new `Attachment`
   and `Reference` (target `MolecularSequence`); `subject` widens from R4's 4
   target types to 11; root invariants include the new `obs-8`. The validation
   suite proves `Medication` is now an accepted subject target while
   `Encounter` is still rejected.
2. THO terminology gap: `http://hl7.org/fhir/ValueSet/observation-category` is
   NOT in the parsed value sets (its content lives in the separate THO
   package), so a category-carrying Observation gets a single warning "Unknown
   or not processable ValueSet ... skipping code binding". Consequence: the
   conformant-Observation fixture carries no `category`.
3. Three of the 42 Observation search parameters are refused by
   `SearchParameterConfigurator`: `value-canonical` and
   `component-value-canonical` select `value.ofType(canonical)` but R5
   `Observation.value[x]` has no canonical alternative; `code-value-string` is
   an UPSTREAM R5 5.0.0 package inconsistency - its component references
   `SearchParameter/Observation-value-string`, which does not exist in the
   package (the parameter became `value-markdown`; verified by inspecting
   `search-parameters.json` in the ZIP: exactly one occurrence of the
   `Observation-value-string` canonical, and it is that component reference).
   The refusals are correct behavior; pinned by name.

Negative-control pass - PERFORMED for the R5 validation suite: all seven
injected defects were removed at once (status/code restored, birthDate
corrected in both tests, status made `final`, Encounter retargeted to Patient,
contact given a name, and the category insertion neutralised); exactly the 7
negative tests failed while the 2 conformant tests passed; the file was
restored and verified byte-identical to its backup.

Result: `mvn -pl onfhir-r4 test` runs all five suites, 54 tests, BUILD SUCCESS
in ~32 s; both parsed configs (R4 and R5) coexist in the single reused
surefire fork on the default heap. All five suite classes appear in
`onfhir-r4/target/surefire-reports/`.

Docs updated: `onfhir-r4/README.md` (suite table now lists the R5 suites and
explains why they live in this module), `onfhir-definitions-r5/README.md`
(the "ships without suites" paragraph replaced by a pointer to the new
suites), split-plan Section 7.3 onfhir-r4 test-dependency row and the Phase 5A
test bullet (five suites, 54 tests).

### Follow-on - dedicated onfhir-r5 parser module (executed 2026-08-05, owner-requested)

The owner subsequently chose a clearer release boundary: R5 consumers should
depend on an R5-named parser module even while its implementation remains
compatible with R4. The reactor now includes `io.onfhir:onfhir-r5_2.13`, whose
`R5Parser` extends `R4Parser`, supplies R5 5.0.0 default primitive and complex
type sets, and is the public override point for future R5 differences.

The defaults were prepared from the official HL7 R5 datatype page and checked
against the non-abstract types in the packaged 5.0.0 `profiles-types.json`.
The resulting universes are 21 primitive types (the 20 ordinary primitives
plus special-purpose `xhtml`) and 42 distinct complex types. Quantity aliases
normalize to `Quantity`, while reusable `MarketingStatus` and
`ProductShelfLife` structures are included from the official type bundle.

The three R5 test files moved without behavior changes from package
`io.onfhir.r4` to `io.onfhir.r5`; the fixture and direct SearchParameter test
now instantiate `R5Parser`. One assertion was added to pin both parser default
sets exactly to the definition-derived configuration. The module split is now:

| Module | Suites | Tests |
| --- | ---: | ---: |
| `onfhir-r4` | 3 | 34 |
| `onfhir-r5` | 2 | 21 |

`onfhir-r4` no longer has a test dependency on `onfhir-definitions-r5`.
`onfhir-r5` has a compile dependency on `onfhir-r4` for implementation reuse,
while `onfhir-definitions-r5`, `onfhir-config`, and specs2 remain test-only.
The new coordinate was added to the reactor, BOM, forbidden-import and license
gates, MiMa new-artifact list, and staged-release inventory.

Verification for this follow-on:

- focused R5 reactor run: 21 tests, zero failures or errors;
- full tests-skipped artifact build: BUILD SUCCESS for all 14 reactor projects;
- packaged `onfhir-r5_2.13-4.0.0.jar` contains `R5Parser` plus
  `META-INF/LICENSE` and `META-INF/NOTICE`;
- forbidden-import gate: PASS, zero Akka/Pekko findings in all ten Scala
  libraries;
- dependency-license gate: PASS, 32 external dependencies reviewed;
- the full `mvn test` gate reaches an unrelated owner-edited temporary `DUMP`
  assertion in `R4SearchParameterConfiguratorTest` and therefore is not green
  in this working tree. MiMa likewise reports only concurrent
  `FHIRSearchParameter.components` API changes outside this follow-on; the new
  R5 artifact is correctly classified as having no 3.3 baseline.

### Status

COMPLETE for the requested definitions and parser artifacts. The dedicated R5
follow-on is focused-test and artifact verified; the workspace-wide test and
MiMa gates await resolution of the concurrent changes recorded immediately
above. Nothing was staged, committed, published, or pushed by this work; the
tree is left for owner review.

Additional historical open items for the owner:

1. Two recorded product defects, pinned by tests rather than fixed: the numeric
   `BaseFhirConfig.fhirVersion` is never populated, and `CodeBindingRestriction`
   renders an empty system-code pair for a text-only CodeableConcept. See the
   Phase 3 defects section.
2. The incidental `scripts/check-staged-release.ps1` suffix correction, flagged
   for review because it is outside this plan's scope.
3. The optional public `R4Configurator` follow-up described above.
4. Repofyr switching to consume `onfhir-definitions-r4` and deleting its
   embedded copies, which design decision 8 already defers.

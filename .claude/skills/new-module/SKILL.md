---
name: new-module
description: Checklist for adding a new module or resources-only artifact to the onfhir-libs reactor so every cross-cutting registration is updated together. Use when creating a new module.
---

# Add a module to the reactor

A new module touches more files than its own directory. Work through ALL
of these; the easy ones to forget are the two gate scripts.

1. **Root `pom.xml`**: add the directory to `<modules>`.
2. **Module `pom.xml`**:
   - parent `io.onfhir:onfhir-libs-parent`, version via `${revision}`;
   - sibling dependencies use `${onfhir.libs.version}`;
   - logging: `slf4j-api` only - never an SLF4J provider;
   - test wiring: specs2 in `test` scope and
     `<testSourceDirectory>src/test/scala</testSourceDirectory>` (modules
     without it silently run zero tests);
   - no Akka/Pekko anywhere, including transitives (the Enforcer rule and
     gate scripts will catch it, but do not rely on them).
3. **Artifact naming**: compiled Scala artifacts carry the `_2.13` suffix.
   Resources-only artifacts carry NO suffix (they contain no compiled
   code) and their packaged file names carry the FHIR release so
   artifacts can share a classpath.
4. **BOM**: add the artifact to `onfhir-libs-bom`.
5. **Gate scripts** (both have per-artifact tables):
   - `scripts/check-binary-compatibility.ps1`: add to `$artifacts`; a
     first-time artifact also goes in `$newArtifacts`;
   - `scripts/check-staged-release.ps1`: add to `$artifacts` with its
     packaging.
6. **Root `README.md`**: add the module table row.
7. **Module `README.md`** (required content): purpose, scope and
   non-goals, Maven coordinates, principal public APIs, relationships to
   other modules, and a minimal usage example.
8. **Resources-only modules additionally**:
   - `-text` entries in `.gitattributes` for packaged resources so
     published bytes are identical on every build host;
   - `NOTICE` entry if the content is third-party (e.g. HL7 FHIR
     definitions, CC0 1.0), plus the license note in the module README;
   - release profile attaches a marker sources JAR and an empty javadoc
     JAR (Maven Central requires the classifiers).
9. **Records**: `CHANGELOG.md` entry; migration guide row if consumers
   are affected; MiMa baseline via the `mima-update` skill (new artifacts
   get a `NEW-ARTIFACT` entry).
10. **Verify**: run the `verify` skill; also
    `mvn -B -pl <module> -am test` for the module alone.

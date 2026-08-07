---
name: release-stage
description: Stage a signed local release of the onfhir-libs reactor and verify it with check-staged-release.ps1. Follows RELEASING.md sections 1-3 and stops hard before anything publishes or pushes.
---

# Stage and verify a release candidate

Implements RELEASING.md sections 1-3. HARD STOP at section 4: never run a
remote `mvn deploy`, never upload to Maven Central, never `git push` -
publishing requires explicit maintainer action outside this skill.

## 1. Pre-flight

- Run the `verify` skill; all gates must PASS.
- Confirm `CHANGELOG.md` has a complete entry for this version and that
  binary-incompatible changes have migration-guide and MiMa-reconciliation
  rows (see `mima-update`).
- Confirm the reactor version (`revision` property in the root `pom.xml`)
  is the version being staged.

## 2. Stage signed artifacts

```
mvn -B -Prelease deploy -DaltDeploymentRepository=staging::file:///<absolute-staging-path>
```

- The target is a LOCAL file-based repository (e.g. under `C:\tmp`); this
  is not a publish.
- Signing requires the SRDC release GPG key on this machine; headless
  signing works via loopback pinentry. If GPG prompts or fails, stop and
  report - do not disable signing to get a green run (an unsigned
  rehearsal is only useful if the maintainer asked for one, via
  `-Dgpg.skip=true` and `check-staged-release.ps1 -SkipSignatures`).

## 3. Verify the staging repository

```
powershell -File scripts/check-staged-release.ps1 -RepositoryPath <staging-path> -Version <version>
```

Expect: `check-staged-release: PASS - <n> <version> artifacts verified.`
The script checks every coordinate for POM presence, Apache-2.0 (and no
GPL) metadata, binary/sources/javadoc JARs, packaged `META-INF/LICENSE`
and `META-INF/NOTICE`, and a good signature on every file.

## 4. Consumer rehearsal (majors only; report, then hand over)

For a MAJOR release or a publishing-mechanics change, staged artifacts
must be proven against the release chain (RELEASING.md section 3): the
FULL Repofyr reactor (all modules, not only server-r4) resolving
`io.onfhir` only from the staging repository, then spark-on-fhir and CRT.
These builds live in sibling repositories; if asked to run them, purge
`io/onfhir` from the rehearsal local Maven repository first so nothing
resolves from a stale cache. For a routine minor/patch release the
rehearsal is optional - note that in the hand-over report instead of
running it by default.

Finish by reporting: staged path, artifact count, gate verdicts, and what
remains for the maintainer (RELEASING.md section 4).

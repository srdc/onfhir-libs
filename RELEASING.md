# Releasing the onFHIR reusable libraries

Maintainer runbook. Publishing and pushing are never automated: every step
in section 4 requires explicit maintainer action, and agents must stop at
the end of section 3.

The reactor version is the `revision` property in the root `pom.xml`
(flatten-maven-plugin `oss` mode resolves it into published POMs). The
library version line is independent of Repofyr server versions.

## Versioning policy

All published coordinates, the BOM included, release together at one
version.

- **Patch** (`4.0.x`): fixes only, no API change.
- **Minor** (`4.x.0`): additive, backward binary-compatible API (new
  functions, classes, or modules).
- **Major** (`5.0.0`, ...): intentional binary breaks, shipped with a new
  migration guide and MiMa reconciliation.

Binary breaks are allowed ONLY in a major release, and the binary
compatibility gate enforces it: after each release the MiMa baseline
retargets to that release (section 5), so within a major line the accepted
baseline contains only `COMPATIBLE` and `NEW-ARTIFACT` entries - any other
finding is either a regression to fix or work parked for the next major.
Deprecate in a minor, remove in the following major. Majors are
event-driven: ship one when breaking changes have accumulated, not on a
calendar. Published releases are immutable on Maven Central; fixes always
roll forward as a new version, never in place.

## 1. Pre-flight

- `git log`/`git status`: confirm the tree is the state you intend to
  release (this repository is sometimes worked on by parallel sessions).
- `CHANGELOG.md`: entry for this version is complete; stamp the release
  date.
- If any relocation or binary-incompatible change shipped since the last
  release: the migration guide under `docs/migration/` and the MiMa
  reconciliation under `docs/compatibility/` cover it.
- Full verification suite is green:
  1. `mvn -B test` (full reactor, zero failures);
  2. `scripts/check-forbidden-imports.ps1`;
  3. `scripts/check-library-dependency-licenses.ps1`;
  4. `scripts/check-binary-compatibility.ps1`.
- Fresh-checkout rehearsal: clone into a temporary directory and run the
  reactor tests there. This catches working-copy-only state and
  line-ending contract violations (a `core.autocrlf` checkout once turned
  multi-line test fixtures into silent no-ops; `.gitattributes` now pins
  LF, and the fresh clone proves it).

## 2. Stage a signed release locally

1. The SRDC release GPG key must be importable on the build machine.
   Headless signing works through loopback pinentry (`--pinentry-mode
   loopback`) so `maven-gpg-plugin` does not prompt.
2. Deploy the full reactor to a file-based staging repository:

   ```shell
   mvn -B clean -Prelease deploy -DaltDeploymentRepository=staging::file:///<absolute-staging-path>
   ```

   `clean` is MANDATORY, never an optimisation to skip. Maven copies
   resources into `target/classes` but never removes ones that disappeared
   from `src`, so an incremental staging build silently packages deleted
   files. This actually happened while staging 4.0.0: `onfhir-client`
   dropped its `application.conf` in `15e85d8`, and a staging run without
   `clean` still shipped the August 3 copy left behind in
   `target/classes` - the exact file whose removal was the point of the fix,
   and one that would have changed config resolution for every consumer.
   The same applies to orphaned `.class` files from deleted or renamed
   sources. `check-staged-release.ps1` cannot catch any of this: it checks
   presence, metadata and signatures, not whether the content matches the
   commit being released.

   The two-part `id::url` form and the all-or-nothing upload both come from
   the `maven-deploy-plugin` pin in the root POM (`deploy.plugin.version`,
   `deployAtEnd`). Do not drop the pin, and do not reintroduce a layout
   segment - the two forms are mutually exclusive:

   - Unpinned, Maven 3.8.x binds deploy plugin 2.7, which requires
     `id::layout::url` (`staging::default::file:///...`) and uploads module
     by module. A failure part way through the reactor then leaves a
     partially deployed version behind, which cannot be overwritten in a
     repository that forbids redeploy.
   - Pinned, 3.x removed layout support and rejects that older form with
     "Invalid legacy syntax and layout for alternative repository", so a
     command copied from before the pin fails at the parent module.

3. Verify the staging repository:

   ```shell
   powershell -File scripts/check-staged-release.ps1 -RepositoryPath <staging-path> -Version <version>
   ```

   This checks every published coordinate for: POM presence with Apache-2.0
   (and no GPL) metadata, binary/sources/javadoc JARs, packaged
   `META-INF/LICENSE` and `META-INF/NOTICE`, and a good `.asc` signature on
   every file. Keep the script's artifact list in sync with the reactor
   when modules are added.

## 3. Consumer rehearsal (major releases)

Required before a MAJOR release, and for any release that changes
publishing mechanics (a new module, coordinate changes, POM or release
profile restructuring). Optional otherwise: consumers pin their versions,
so a routine minor/patch release cannot break them retroactively, and the
internal gates in section 1 plus the staging checks in section 2 are the
acceptance bar. Ongoing downstream visibility comes from the consumer
repositories' own CI (scheduled builds or Dependabot bumps against the
latest published libraries) after release, without gating this one.

When the rehearsal runs, prove the staged artifacts against the release
chain before anything is published:

1. **Repofyr**: build the FULL server reactor (`mvn clean test`, all
   modules - not only `server-r4`; a partial rehearsal once missed a
   compile break in `server-stu3`) against the staging repository, with
   every `io.onfhir` artifact purged from the rehearsal's local Maven
   repository so the libraries can only resolve from staging.
2. **spark-on-fhir**: run its test suite against the staged (or newly
   published) versions.
3. **CRT**: run its launch/smoke verification.

## 4. Publish (maintainer only, explicitly authorized)

1. Publish the staged `4.x` artifacts to Maven Central through the SRDC
   account.
2. Tag `v<version>` and push the repository and tag.
3. Create the GitHub release: changelog excerpt plus a link to the
   migration guide.

## 5. Post-publish

- Convert each entry in `docs/release/known-limitations.md` into a GitHub
  issue and link the issue numbers back into that file.
- Retarget binary compatibility at the new release. Do this after EVERY
  release: it is what makes the versioning policy enforceable, because the
  next development cycle is then compared against the newest published API
  and any break shows up immediately instead of at the next major. Update
  the `$PreviousVersion` default and the baseline path in
  `scripts/check-binary-compatibility.ps1`, regenerate with
  `-UpdateBaseline`, and (for a major) start a new reconciliation document
  under `docs/compatibility/`; the 3.3 one stays as the 4.0.0 record.
- Bump `revision` in the root `pom.xml` to the next development version.
- Announce as appropriate (release notes, downstream consumers).

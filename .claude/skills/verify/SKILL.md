---
name: verify
description: Run the full onfhir-libs verification suite - reactor tests plus the gate scripts - in the right order with correct invocation and environment handling. Use when asked to verify the build, run the gates, or check release readiness.
---

# Verify onfhir-libs

Run, in order, and report each verdict verbatim:

1. **Full reactor tests**

   ```
   mvn -B test
   ```

   Expect zero failures and zero errors across all modules.

2. **Source/resource boundary (Akka/Pekko-free)**

   ```
   powershell -File scripts/check-forbidden-imports.ps1
   ```

   Expect: `check-forbidden-imports: PASS - library modules are Akka/Pekko free.`

3. **Dependency license allow-list**

   ```
   powershell -File scripts/check-library-dependency-licenses.ps1
   ```

   Reads `target/generated-sources/license/THIRD-PARTY.txt`. If it is
   missing, run `mvn -B validate -DskipTests` first, then re-run the gate.

4. **Binary compatibility (MiMa)**

   ```
   powershell -File scripts/check-binary-compatibility.ps1
   ```

   Add `-SkipBuild` when the artifacts were just built (e.g. right after
   step 1 plus `mvn -B package -DskipTests`). A diff against the accepted
   baseline means either a regression (fix the code) or an intentional
   break that is missing its paper trail - never pass `-UpdateBaseline`
   directly; use the `mima-update` skill.

## Environment rules (learned the hard way)

- Run gate scripts bare. Do NOT pipe their output (`| Select-String`,
  `2>&1`, `| tee`): under Windows PowerShell 5.1 with
  `$ErrorActionPreference = "Stop"`, any native stderr line (including a
  harmless JVM warning) becomes a terminating NativeCommandError. Filter
  the captured output afterwards instead.
- `onfhir-client` tests start a loopback stub server on a unix-domain
  socket. If the default temp path is too long (common on Windows), set
  `JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\tmp` for the `mvn test`
  run - and REMOVE that variable before running the gate scripts under
  PowerShell 5.1, because the JVM's "Picked up JDK_JAVA_OPTIONS" stderr
  note trips the rule above.
- Module-scoped builds need `-am` while the working tree is ahead of the
  installed artifacts: `mvn -B -pl <module> -am test`.
- A killed or crashed Maven run can corrupt zinc incremental state under
  `target/`, producing bogus "X is not a member of package Y" errors. Fix:
  `mvn -B -pl <module> clean`, then rebuild.
- This repository may be worked on by parallel sessions: check `git log`
  before assuming the tree state.

---
name: mima-update
description: Accept an intentional binary break by regenerating the MiMa baseline together with its reconciliation and migration-guide entries in the same change. Use when check-binary-compatibility reports a diff that is intended.
---

# Update the MiMa acceptance baseline

The baseline (`docs/compatibility/mima-3.3-accepted.txt`) is a reviewed
record of intentional breaks, not a suppression file. Binary breaks land
ONLY in a major release (see RELEASING.md, "Versioning policy"): during a
minor/patch cycle the only acceptable baseline additions are
`NEW-ARTIFACT` entries for new modules, and any reported break is a
regression to fix in code. A baseline update is valid ONLY when the same
change also updates:

1. `docs/compatibility/mima-3.3-reconciliation.md` - the issue-family row
   explaining the break and pointing at the migration guide section;
2. the migration guide under `docs/migration/` - the consumer-facing
   old-vs-new row (and `CHANGELOG.md` if the change is user-visible).

## Procedure

1. Run the gate bare and capture the diff it prints:

   ```
   powershell -File scripts/check-binary-compatibility.ps1
   ```

2. Classify EVERY new finding first:
   - **Unintended** - it is a regression; fix the code instead of the
     baseline.
   - **Intended** - allowed only when preparing the next MAJOR release;
     in a minor/patch cycle, park the change instead.
3. Write the reconciliation row and the migration-guide entry for each
   intended break. If a new artifact appears, it must also be registered
   in the script's `$artifacts`/`$newArtifacts` tables (see the
   `new-module` skill).
4. Regenerate the baseline:

   ```
   powershell -File scripts/check-binary-compatibility.ps1 -UpdateBaseline
   ```

5. Re-run bare and confirm `check-binary-compatibility: PASS`.
6. Keep all three files (baseline, reconciliation, migration guide) in the
   same commit so the record cannot drift.

## Notes

- Run the script bare (no piping) - see the `verify` skill's environment
  rules.
- The generated report's header lines are part of the baseline text; if
  the script's header strings change, the baseline must be regenerated or
  edited in lockstep.
- CI regenerates the report and fails on ANY difference from the baseline,
  including formatting.

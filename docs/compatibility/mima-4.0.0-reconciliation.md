# MiMa 4.0.0 Compatibility Reconciliation

The accepted machine baseline (`mima-4.0.0-accepted.txt`) compares the current
reusable JARs with the published `4.0.0` artifacts on Maven Central. The
baseline was retargeted from `3.3` to `4.0.0` immediately after the 4.0.0
release (RELEASING.md section 5), so each development cycle in the 4.x line is
measured against the newest published API instead of an increasingly distant
one. That retarget is what makes the versioning policy enforceable: a break
introduced against 4.0.0 shows up on the next gate run rather than at the next
major.

## What belongs in this baseline

Within the 4.x line the baseline should contain nothing but `COMPATIBLE`:

- A **patch** (`4.0.x`) fixes behaviour without changing the API.
- A **minor** (`4.x.0`) only adds, and additions are binary compatible, so they
  produce no findings.
- A **break** belongs in the next major. Deprecate in a minor, remove in the
  following major.

Any non-`COMPATIBLE` entry that appears here is therefore one of two things: a
regression to fix in the code, or work parked for 5.0.0 that must not be
accepted into this baseline. Neither is something to wave through with
`-UpdateBaseline`; that switch requires a reconciliation row here and a
migration-guide entry in the same change (see the `mima-update` skill).

| MiMa report group | Intended change / migration-guide section |
|---|---|
| _(none yet)_ | The 4.0.0 baseline starts clean - all fourteen compared artifacts are `COMPATIBLE` with the published release. |

## Historical record

The 3.x-to-4.0.0 transition is not restated here. Its accepted breaks, and the
reasoning connecting each report group to a migration-guide section, remain in
[`mima-3.3-reconciliation.md`](mima-3.3-reconciliation.md) alongside the machine
baseline `mima-3.3-accepted.txt`. Both are kept as the permanent record of what
4.0.0 changed, and neither is regenerated again.

One retarget detail worth knowing: `$newArtifacts` in
`scripts/check-binary-compatibility.ps1` is now empty. Against the 3.3 baseline
it excused seven coordinates that had no published counterpart, and an artifact
named in that list is skipped rather than compared - so it must stay empty
unless a genuinely new coordinate ships, or the gate would silently stop
checking it.

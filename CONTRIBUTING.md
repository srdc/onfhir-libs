# Contributing

Thank you for contributing to the onFHIR reusable libraries. Read `AGENTS.md`
and the active migration records under `docs/plans` before changing a public
contract.

## Development certificate of origin

Every commit must carry a `Signed-off-by` trailer certifying the
[Developer Certificate of Origin 1.1](https://developercertificate.org/).
Add it with:

```shell
git commit -s
```

By signing off, you certify that you wrote the contribution or otherwise have
the right to submit it under the project's applicable license. The historical
contributor/IP approval for Apache-2.0 relicensing is recorded in
`docs/release/library-relicensing-audit.md`.

## Before submitting

- Keep reusable-library code independent of Akka, Pekko, and server runtime
  concerns.
- Record library module relocations and public API changes in the migration
  table in `docs/plans/library-server-split-plan-v2.md`.
- Run the relevant module tests and the full library reactor.
- Run `powershell -File scripts/check-forbidden-imports.ps1`.
- Ensure release JARs retain `META-INF/LICENSE` and `META-INF/NOTICE`.

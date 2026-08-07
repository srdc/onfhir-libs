# Contributing

Thank you for contributing to the onFHIR reusable libraries. Read `AGENTS.md`
and the [3.x to 4.0.0 migration guide](docs/migration/3.x-to-4.0.0.md) before
changing a public contract.

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
- Record user-visible changes in `CHANGELOG.md`. A binary-incompatible
  change or class relocation additionally needs, in the same change, a row
  in the migration guide under `docs/migration/` and a reconciled MiMa
  baseline under `docs/compatibility/`. Binary breaks are only accepted
  for the next major release.
- Run the relevant module tests and the full library reactor.
- Run `powershell -File scripts/check-forbidden-imports.ps1`.
- Ensure release JARs retain `META-INF/LICENSE` and `META-INF/NOTICE`.

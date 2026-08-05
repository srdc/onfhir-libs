# onfhir-client Tier 1 Test Plan

Status: DONE (Phases 0-5 executed; findings 1-9 and 11 fixed in src/main on
owner request, 2026-08-05; nothing committed)
Owner: Tuncay Namli. Executor: AI agent session in this repository.
Last updated: 2026-08-05

## Goal

Give `onfhir-client` a deterministic, CI-executed test suite ("Tier 1") that
verifies the module's real contract: every request builder emits
spec-conformant HTTP requests (method, path, query, headers, body), and
canned spec-conformant responses parse into the right client-side objects.
No external network, no real FHIR server, no credentials.

Background: today only two test classes actually execute under Maven
(`FhirClientBoundaryTest`, `OnFhirNetworkClientTransportTest`). The broad
suites (`OnFhirNetworkClientTest`, `IdentityServiceClientTest`,
`TerminologyServiceClientTest`) are silently skipped because they are specs2
objects/classes without a JUnit runner and require a live server or
credentials. Those three files are Tier 2/3 (manual/integration) and are OUT
of scope here - do not modify or delete them.

## Hard constraints

1. Touch only the `onfhir-client` module, and only `src/test/**` within it.
   Do NOT modify `src/main/**`. If a test reveals a suspected product bug,
   do not fix it silently: record it in the Findings section of this plan,
   write the test to assert the CURRENT actual behavior with a
   `// NOTE: documents current behavior, see plan Findings` comment, and move on.
2. No new Maven dependencies. specs2 (core + junit) is already provided; the
   mock server uses JDK `com.sun.net.httpserver.HttpServer` (already the
   pattern in `OnFhirNetworkClientTransportTest`).
3. Every new test MUST be a `class` (never an `object`) named `*Test`,
   annotated `@RunWith(classOf[JUnitRunner])`, extending
   `org.specs2.mutable.Specification`. Surefire includes
   `**/*Test.*`, `**/*Spec.*`, `**/When*.*`, `**/*Suite.*` and detects tests
   through the JUnit provider - a specs2 class without `@RunWith` is silently
   ignored. After each phase, confirm the new classes appear in
   `onfhir-client/target/surefire-reports/`.
4. Do not reuse the names `OnFhirNetworkClientTest`,
   `IdentityServiceClientTest`, `TerminologyServiceClientTest` - new suites
   use a `*ContractTest` naming to avoid clashing with the Tier 2 files.
5. Tests must bind only to `127.0.0.1` with port `0` (ephemeral) and must not
   reach the external network. Use `sequential` in every spec that shares
   mock-server state (see the transport test).
6. All files ASCII-only (Windows PowerShell 5.1 compatibility rule).
7. Do not commit; leave the working tree for review.

## Read these files before writing code

Fixture pattern and existing coverage (do not duplicate what it covers:
encoding, auth interceptors, retry, timeout, redirect, SSL, token caching,
XML rejection):

- `onfhir-client/src/test/scala/io/onfhir/client/OnFhirNetworkClientTransportTest.scala`

Contract under test:

- `onfhir-client/src/main/scala/io/onfhir/client/parsers/FHIRRequestMarshaller.scala`
- `onfhir-client/src/main/scala/io/onfhir/client/parsers/FHIRResponseUnmarshaller.scala`
- `onfhir-client/src/main/scala/io/onfhir/api/client/*.scala` (all builders + `FHIRBundle.scala`)
- `onfhir-client/src/main/scala/io/onfhir/client/OnFhirNetworkClient.scala` (esp. `next()` and `apply(config)`)
- `onfhir-client/src/main/scala/io/onfhir/client/TerminologyServiceClient.scala`
- `onfhir-client/src/main/scala/io/onfhir/client/IdentityServiceClient.scala`
- `onfhir-client/src/main/scala/io/onfhir/client/util/FhirResourceMutator.scala`
- `onfhir-client/src/main/scala/io/onfhir/client/model/ClientHttpSettings.scala`
- From onfhir-common (read-only, for expected header/param rendering):
  `FHIRRequest`, `FHIRResponse`, `FHIROperationResponse`, `OrderedQuery`,
  `EntityTagList`, `FHIR_CONTENT_TYPES`.

## Verified facts (already confirmed against source; rely on them)

Wire behavior implemented by `FHIRRequestMarshaller` + `JdkHttpTransport`:

- Every request carries `Accept: application/fhir+json` and a non-empty
  `X-Request-Id` header. Entity requests get `Content-Type` from the entity.
- Methods: create/batch/transaction -> POST; update -> PUT; patch -> PATCH;
  delete -> DELETE; read/vread/history/search/capabilities/search-page -> GET
  (search may be overridden to POST via `byHttpPost()`); operations default
  to POST but use GET when the builder collected only simple (query) params.
- Paths: create `[base]/[type]`; read `[base]/[type]/[id]`;
  vread `[base]/[type]/[id]/_history/[vid]`;
  history instance `[base]/[type]/[id]/_history`, type `[base]/[type]/_history`;
  capabilities `[base]/metadata`; batch/transaction POST to `[base]`;
  compartment search `[base]/[ctype]/[cid]/[type]`;
  POST search `[base]/[type]/_search` with `application/x-www-form-urlencoded`
  body holding the parameters (no query in the URL);
  operation `[base][/type[/id]]/$opname` (the `$` is legal in the path).
- Conditional headers: conditional create -> `If-None-Exist` header (never
  URL query); conditional update/patch/delete -> query string on the URL;
  version-aware update (default `forceVersionControl = true`) -> `If-Match:
  W/"<meta.versionId>"` only when the resource carries `meta.versionId`;
  read `ifModifiedSince` -> `If-Modified-Since` (HTTP date),
  `ifNoneMatch(v)` -> `If-None-Match: W/"v"`;
  `returnMinimal()`/`returnOperationOutcome()`/`strictHandling()`/
  `lenientHandling()` -> `Prefer` header.
- JSON Patch body: the wire body is the raw JSON ARRAY of patch operations
  (the builder's internal `{"patches": [...]}` wrapper is unwrapped by the
  marshaller), content type = FHIR JSON Patch media type
  (`FHIR_CONTENT_TYPES.FHIR_JSON_PATCH_CONTENT_TYPE`). FHIRPath Patch body is
  a `Parameters` resource with `operation` parts, content type FHIR JSON.
- Batch/transaction body: `Bundle` with `type` = `"batch"`/`"transaction"`;
  each entry has `request.method`, `request.url` RELATIVE to the base (e.g.
  `Patient`, `Patient/p1`, `Patient?identifier=...`); `fullUrl` appears only
  for entries created via `entry(fullUrlUuid, ...)` (must be `urn:uuid:...`,
  otherwise `FhirClientException` is thrown immediately); child conditional
  settings appear inside `entry.request` as `ifNoneExist`/`ifMatch`/
  `ifNoneMatch`/`ifModifiedSince` fields.
- `where` semantics on search-like builders: repeated calls with the same
  parameter name produce repeated query params (AND); one call with varargs
  joins values with a comma (OR).
- Pagination: `FhirSearchRequestBuilder.setPage/_searchafter/_searchbefore/
  setPaginationParam` set one explicit param. `OnFhirNetworkClient.next(bundle)`:
  if the original builder had an explicit pagination param, it extracts that
  param's value from the bundle's `next` link and re-issues the original
  request with it; otherwise it issues `getSearchPage(<next link>)`.
- Base URL validation happens per request inside the marshaller
  (absolute http/https, no query/fragment); violations surface as failed
  futures.
- Assert wire-level values via the mock server, not `builder.request.requestUri`
  (that field is not what the marshaller sends for most interactions).

Module versions: Scala 2.13, specs2, Maven module dir `onfhir-client`
(artifact `onfhir-client_2.13`). Run everything from the repo root
`C:\srdc\codes\onfhir-io\onfhir-libs`.

## Phase 0 - Shared mock-server fixture

Status: DONE

Create `onfhir-client/src/test/scala/io/onfhir/client/testutil/MockFhirServer.scala`:

- Wraps `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0`; exposes
  `baseUrl` ending with `/fhir`.
- Records every request as a case class
  `RecordedRequest(method, rawPath, rawQuery, headers, body)`; exposes
  `lastRequest`, `requests` (ordered), `requestCount`, `reset()`.
- Response scripting, simplest thing that works:
  `stub(method, pathSuffix)(status, body, contentType = "application/fhir+json; charset=UTF-8", extraHeaders = Map())`
  plus an optional FIFO queue variant `stubSequence(...)` for pagination
  scenarios (first call gets response 1, second gets response 2, ...).
  Default response when nothing matches: 200 + minimal Patient JSON.
- Helper builders for canned FHIR payloads used across suites:
  `searchSetBundle(total, matches, includes, nextLink)`, `historyBundle(...)`,
  `transactionResponseBundle(entries)`, `parametersResource(...)`. Keep them
  as plain string/JObject builders inside the fixture or a small
  `CannedResponses` object - no cleverness.
- Lifecycle helper trait (e.g. `WithMockFhirServer extends BeforeAfterAll`)
  starting/stopping the server, mixed into each suite.

Do NOT refactor `OnFhirNetworkClientTransportTest` to use the fixture; leave
that file untouched.

Verification: module compiles - `mvn -pl onfhir-client -am -DskipTests compile`.

## Phase 1 - Pure unit tests (no HTTP)

Status: DONE

1. `onfhir-client/src/test/scala/io/onfhir/client/util/FhirResourceMutatorTest.scala`
   - `addElement` on an existing path; on a non-matching path (no-op);
     `isArray = true` creates an array; repetitive target appends.
   - `addElementOrThrowExc` throws `IllegalArgumentException` on
     non-matching path.
   - `addRootElement`; `insertElement` at head/middle/tail;
     `insertElement` out-of-bounds -> `IndexOutOfBoundsException`; insert on
     non-repetitive element -> `IllegalArgumentException`.
   - `deleteElement` for a plain element and for one item of an array;
     `replaceElement`; `moveElement` incl. bounds errors; each
     `...OrThrowExc` variant's throwing case.
   - Chaining (fluent) mutations and both implicit conversions
     (`Resource -> FhirMutableResource -> Resource`).
   - Reuse `src/test/resources/observation-glucose.json` /
     `patient-with-link.json` where convenient; otherwise inline JObjects.
2. `onfhir-client/src/test/scala/io/onfhir/client/model/ClientHttpSettingsTest.scala`
   - Defaults: connect 10s, no request timeout, 5 retries, no SSL context.
   - `fromConfig` with an `http { ... }` block; with direct keys (no `http`
     wrapper); with missing keys -> defaults.
   - `require` violations: zero/negative connect timeout, non-positive
     request timeout, negative maxRetries -> `IllegalArgumentException`.

Verification: `mvn -pl onfhir-client -am test` green; both classes listed in
surefire-reports.

## Phase 2 - Request contract tests (wire assertions via mock)

Status: DONE

All suites live in `onfhir-client/src/test/scala/io/onfhir/api/client/` and
assert the recorded request (method, raw path, raw query, headers, JSON-parsed
body) using the facts listed above. Cover at minimum:

1. `FhirCrudRequestContractTest`
   - create: POST `[base]/Patient`, FHIR JSON content type, body round-trips;
     `.where(...)` -> `If-None-Exist` header and NO url query;
     `returnMinimal()` / `returnOperationOutcome()` -> `Prefer` values.
   - update: PUT `[base]/Patient/p1`; resource with `meta.versionId` ->
     `If-Match: W/"<v>"`; `forceVersionControl = false` -> no `If-Match`;
     resource without versionId -> no `If-Match`; conditional update (no id +
     `where`) -> PUT `[base]/Patient?...`.
   - read: `ifModifiedSince` / `ifNoneMatch` headers; `summary("text")` ->
     `_summary=text`; `elements("gender","birthDate")` ->
     `_elements=gender,birthDate`.
   - vread path; delete instance vs conditional delete;
     capabilities -> GET `[base]/metadata`, `mode("normative")` -> `mode` param.
   - Every request: `Accept` + `X-Request-Id` present.
2. `FhirSearchRequestContractTest`
   - AND vs OR `where` semantics on the raw query; `_count`; `_sort` with
     asc/desc mix; compartment path; `strictHandling`/`lenientHandling`
     Prefer values; `setPage`/`setSearchAfter`/`setSearchBefore`/custom
     pagination param; `byHttpPost()` -> POST `.../_search`,
     form-encoded body carries the params, URL has no query.
3. `FhirPatchRequestContractTest`
   - `jsonPatch()` ops -> PATCH with raw JSON array body + JSON Patch content
     type; `fhirPathPatch()` ops -> `Parameters` body with correct
     `operation` parts and `value[Type]` keys; `patchContent` with a
     Parameters resource / a single JSON-patch object / a JSON-patch array;
     conditional patch -> query string.
4. `FhirBatchTransactionContractTest`
   - Bundle `type` per `batch()`/`transaction()`; entry `request.method`/
     relative `request.url` for create/update/conditional-delete children;
     `fullUrl` only for uuid entries; non-`urn:uuid:` fullUrl ->
     `FhirClientException`; child conditional create -> `entry.request.ifNoneExist`;
     `entriesFromBundle` reproduces entries from an existing request bundle.
5. `FhirOperationRequestContractTest`
   - Simple params only -> GET with query, no body; `addParam`/
     `addResourceParam`/`addMultiParam` -> POST `Parameters` body with
     `value[Type]` / `resource` / `part` shapes; system vs type vs instance
     paths via `on(...)`.

## Phase 3 - Response parsing and pagination (canned responses via mock)

Status: DONE

1. `FhirSearchSetResponseContractTest` (package `io.onfhir.api.client`)
   - Canned searchset: `total`, `search.mode` match vs include ->
     `searchResults` vs `includedResults` (keyed `Type/id`),
     `getSearchResultsWithResourceType`.
   - `hasNext()`/`getNext()` from `link` relation `next`.
   - `next(bundle)` both modes: (a) original request used `setPage(...)` ->
     assert the follow-up request re-issues the original query with the page
     param taken from the next link; (b) no explicit page param -> assert the
     follow-up request matches the next link path+query (serve absolute links
     pointing at the mock base; also cover `getSearchPage` with a
     server-relative link).
   - `toIterator()` over two pages; `executeAndMergeBundle()` over three
     pages merges `searchResults` and `includedResults`.
   - Error paths: HTTP 400 + OperationOutcome -> `executeAndReturnBundle()`
     fails with `FhirClientException` carrying the `FHIRResponse`;
     `execute()` alone succeeds with `isError == true` and parsed
     `outcomeIssues`.
2. `FhirHistoryResponseContractTest`
   - Request side: instance/type `_history` paths; `_since`/`_at`/`_list`/
     `_count` params.
   - Canned history bundle -> `getHistory()` versions and timestamps,
     `getHistory(rid)`, `getHistories` grouping for type-level history;
     pagination via `next(...)` with `setPaginationParam`.
3. `FhirTransactionResponseContractTest` (may merge into Phase 2 suite 4)
   - Canned transaction-response bundle -> `responses` correlation via
     `fullUrl`, `getResponse(uuid)`, `hasAnyError()` true/false,
     `hasAnyNonTransientError()`/`getUUIDsOfTransientErrors()` (derive
     transient semantics from `FHIRResponse` in onfhir-common - read it).
4. `FhirOperationResponseContractTest` (may merge into Phase 2 suite 5)
   - Canned `Parameters` output -> `executeAndReturnOperationOutcome()` gives
     `FHIROperationResponse.getOutputParam("return")`; error status -> failed
     future with `FhirClientException`.
5. `OnFhirNetworkClientConfigContractTest` (package `io.onfhir.client`)
   - `OnFhirNetworkClient(config)` with `serverBaseUrl` + `authz.method=basic`
     -> requests carry the Basic header (wire-assert against mock).
   - `authz.method=oauth2` -> token fetched once from the mock token endpoint,
     `Bearer` header applied (mirror the transport test's `/token` handler).
   - Unsupported `authz.method` -> `FhirClientException` at construction.
   - Invalid base URL (non-http scheme, or query in base) -> request future
     fails.

## Phase 4 - Service client contract tests

Status: DONE

1. `onfhir-client/src/test/scala/io/onfhir/client/TerminologyServiceClientContractTest.scala`
   - Read `TerminologyServiceClient` first and derive the exact expected
     request per method (path `.../$lookup`, `$translate`, `$expand`,
     `$validate-code`; GET-with-query vs POST-Parameters).
   - Cover: `lookup(code, system)` found -> `Some(Parameters)`; unknown-code
     handling per class behavior (read it; likely error response -> None) -
     assert whatever the code actually does and note it;
     `lookup(coding)`; one `translate` overload with concept-map URL and one
     with source/target; `validateCode(url, code, system)`; `expand(url, ...)`
     and `expandWithId(...)`.
2. `onfhir-client/src/test/scala/io/onfhir/client/IdentityServiceClientContractTest.scala`
   - `findMatching("Patient", id, Some(sys))` -> search with
     `identifier=sys|id` and `_elements=link`; canned single-match bundle ->
     `Some(<id>)`; empty bundle -> `None`.
   - Patient link disambiguation: canned bundle with one patient carrying a
     disqualifying link type and one with `replaces`/`seealso` or no link ->
     the correct id is chosen (mirror the class logic; reuse
     `patient-with-link.json` / `patient-with-link2.json` if they fit).
   - Non-Patient type -> `_summary=text` query and first-match id.
   - With an in-memory `IFhirIdentityCache` stub: second call answered from
     cache (mock `requestCount` stays at 1) and `storeIdentity` invoked once.

## Phase 5 - Full verification and wrap-up

Status: DONE

1. From repo root: `mvn -pl onfhir-client -am test` then full `mvn test`.
2. `powershell -File scripts/check-forbidden-imports.ps1` (count must not
   increase; the fixture uses only JDK classes).
3. Confirm every new class has a surefire report; list them in the Findings
   section together with total example counts.
4. Update this plan: set all Status headers, fill Findings, note any
   documented-current-behavior assertions that deserve a follow-up decision.
5. Do not commit; summarize the diff for review.

## Findings

### Suites added and example counts

All 13 new classes produce a surefire report in
`onfhir-client/target/surefire-reports/`. Module total went from 15 to 199
examples (the two pre-existing executing classes are marked `existing`; counts
include the two requestUri examples added with the bug-fix pass).

| Class | Examples |
| --- | --- |
| `io.onfhir.client.util.FhirResourceMutatorTest` | 32 |
| `io.onfhir.client.model.ClientHttpSettingsTest` | 9 |
| `io.onfhir.api.client.FhirCrudRequestContractTest` | 22 |
| `io.onfhir.api.client.FhirSearchRequestContractTest` | 18 |
| `io.onfhir.api.client.FhirPatchRequestContractTest` | 10 |
| `io.onfhir.api.client.FhirBatchTransactionContractTest` | 14 |
| `io.onfhir.api.client.FhirOperationRequestContractTest` | 10 |
| `io.onfhir.api.client.FhirSearchSetResponseContractTest` | 15 |
| `io.onfhir.api.client.FhirHistoryResponseContractTest` | 10 |
| `io.onfhir.api.client.FhirOperationResponseContractTest` | 7 |
| `io.onfhir.client.OnFhirNetworkClientConfigContractTest` | 11 |
| `io.onfhir.client.TerminologyServiceClientContractTest` | 16 |
| `io.onfhir.client.IdentityServiceClientContractTest` | 10 |
| `io.onfhir.api.client.FhirClientBoundaryTest` (existing) | 1 |
| `io.onfhir.client.OnFhirNetworkClientTransportTest` (existing) | 14 |

Fixture: `io.onfhir.client.testutil.MockFhirServer` (+ `RecordedRequest`,
`MockResponse`, `WithMockFhirServer`) and `io.onfhir.client.testutil.CannedResponses`.
JDK classes only, so `check-forbidden-imports.ps1` stays at 0 findings in every
module (it only scans `src/main` anyway).

Phase 3 suites 3 and 4 were merged as the plan allowed: the batch/transaction
response assertions live in `FhirBatchTransactionContractTest`; the operation
response assertions got their own `FhirOperationResponseContractTest` next to
the request suite.

Verification runs: `mvn -pl onfhir-client -am test` green (199 in the module),
full `mvn test` green across the whole reactor,
`powershell -File scripts/check-forbidden-imports.ps1` PASS. The module suite was
re-run twice more with no flakes.

### Product defects found

Each was initially pinned by a test asserting the CURRENT behavior with a
`// NOTE: documents current behavior, see plan Findings` comment, per hard
constraint 1. Follow-up (2026-08-05): the owner authorized fixing these in
`src/main`. Items 1-9 and 11 are FIXED (resolution noted per item) and their
pinning tests were flipped to assert the corrected contract; item 10 is
deliberately left as is. After the fixes: `mvn test` green across the reactor
(onfhir-client at 199 examples), `check-forbidden-imports.ps1` PASS,
`check-binary-compatibility.ps1` PASS (all changes are behavior-level or
additive).

1. **`capabilities().mode(..)` never reaches the wire.**
   `FhirMetadataRequestBuilder.compile()` puts `mode` into
   `FHIRRequest.queryParams`, but `FHIRRequestMarshaller.getRequestUri` hard
   codes `basePath/metadata -> None` for `FHIR_INTERACTIONS.CAPABILITIES`, so the
   query is dropped. The builder method is effectively dead.
   Fixed: the capabilities branch of `getRequestUri` now renders the query,
   and the test ("render the mode parameter") asserts `mode=normative` on the
   wire.

2. **`TerminologyServiceClient.expand(url, version, ..)` drops `url` and `version`.**
   The method builds `operation("expand").on("ValueSet")` and only ever adds
   `filter`/`offset`/`count`. `EXPAND_OPERATION_REQUEST_PARAMS.URL` and `.VERSION`
   are declared but never used, so the request is an unqualified type-level
   `$expand` that no server can answer meaningfully.
   Fixed: `expand(url, version, ..)` now sends `url` and `valueSetVersion`;
   the test ("send the canonical url and version when expanding by url")
   asserts both.

3. **`_sort` is emitted as repeated query parameters.**
   `FhirSearchRequestBuilder.compile()` maps `_sort` to a List, which
   `OrderedQuery.fromMultiMap` renders as `_sort=birthdate&_sort=-name`. FHIR R4
   specifies a single comma separated `_sort` value; servers are free to honour
   only one of the repeats.
   Fixed: `compile()` joins all sort fields into one comma separated `_sort`
   value; the test asserts `_sort=birthdate,-name`.

4. **The `location` fallback for outcome issues is unreachable.**
   `FHIRResponseUnmarshaller.parseOutcomeIssue` tries
   `extractValueOption[Seq[String]](issue, "expression").orElse(... "location")`,
   but `extractValueOption` is `extractOpt`, and json4s extracts a missing
   collection as `Some(Nil)` rather than `None`. So the `orElse` branch never
   runs and a DSTU2/STU3 style `location`-only issue loses its path.
   Fixed: issue parsing treats an empty `expression` as absent
   (`.filter(_.nonEmpty)`) so the `location` fallback applies; the test asserts
   the fallback fires.

5. **Child outcome issues in batch/transaction responses are parsed with a
   non-FHIR schema.** `FHIRTransactionBatchBundle.parseEntryAsResponse` does
   `(rsp \ "outcome" \ "issue").extract[Seq[OutcomeIssue]]`, so `details` (a
   CodeableConcept in FHIR) silently extracts to `None`, and there is no
   `details.text -> diagnostics` fallback that `FHIRResponseUnmarshaller` applies
   to top level responses. Callers get less information from a batch child error
   than from the same error returned directly.
   Fixed: `FHIRTransactionBatchBundle` now parses child outcomes through the
   new shared `io.onfhir.api.client.OperationOutcomeParser` (also used by
   `FHIRResponseUnmarshaller`): `details.coding.code` -> details,
   `details.text` -> diagnostics fallback, `location` -> expression fallback.
   Test renamed to "parse a spec conformant details element of a child
   outcome".

6. **Type level history groups create entries under the resource type.**
   `FHIRHistoryBundle` reads the resource id from the entry resource only when
   `request.method` is the literal `"CREATE"`; a spec conformant `POST` entry
   falls through to `url.split('/').last`, which for `url = "Patient"` is
   `"Patient"`. So all created resources in a type level history collapse into
   one bogus `"Patient"` group.
   Fixed: `POST` entries are treated like `CREATE` and take the id from the
   contained resource. Test renamed to "group create entries by the id of the
   contained resource".

7. **`executeAndReturnOperationOutcome()` ignores the HTTP status.**
   Unlike `executeAndReturnResource()`/`executeAndReturnBundle()`, it never checks
   `httpStatus.isFailure()`, so a 400 with an OperationOutcome body resolves
   successfully with that OperationOutcome stored as the `return` output param.
   Callers must remember to check `isError` themselves.
   Fixed: it now fails the future with
   `FhirClientException("Problem in FHIR operation!", Some(response))` on an
   error status, consistent with the other `executeAndReturn*` methods. This is
   a deliberate behavioral change for callers that relied on the lenient
   behavior (including the `toExecutionFHIROperationResponse` implicit). Test
   renamed to "fail executeAndReturnOperationOutcome with a FhirClientException
   on an error status".

8. **`SearchSetIterator` publishes its state from an async callback.**
   `latestBundle` is a plain (non-volatile) `var` assigned inside
   `future onComplete`, so right after the caller's `Await` returns, `hasNext`
   and `next()` can still observe `None` and re-request the first page.
   `executeAndMergeBundle()` is unaffected (it is fully future chained).
   Fixed: `latestBundle` is now `@volatile` and updated by mapping the returned
   future, so it is set before the caller observes completion; the test asserts
   direct visibility (no `eventually`).

9. **`executeAndMergeBundle()` merges pages in reverse order.**
   `getMergedBundle` recurses to the last page and then folds backwards
   (`r2.mergeResults(r)`), so the merged `searchResults` are ordered last page
   first. Any caller relying on server-side `_sort` gets the pages inverted.
   Fixed: `getMergedBundle` now prepends each page, preserving the server's
   page order, and keeps the last page as the merged bundle's identity so
   `hasNext()` stays false; the test asserts both.

10. **Negative identity lookups are not cached.**
    `IdentityServiceClient.findMatching` only calls `cache.storeIdentity` for a
    resolved id, so every unresolved identifier re-queries the FHIR server on
    every call. This may be intentional (a miss can become a hit later) - worth an
    explicit decision either way.
    Test: `IdentityServiceClientContractTest`, "not store anything when the
    identity cannot be resolved".
    Decision: left as is. Caching misses would need an `IFhirIdentityCache` API
    change, and a miss may legitimately become a hit later; the pinning test
    stays.

11. **Cosmetic: doubled slash in `requestUri` for update and patch.**
    `FhirUpdateRequestBuilder` and `FhirPatchRequestBuilder` build
    `s"$base/$rtype/${rid.map("/" + _).getOrElse("")}"`, producing
    `.../Patient//p1`. The wire is unaffected (the marshaller recomputes the URI)
    but the value leaks into `FHIRRequest.getRequestLocation()` and
    `getSummaryString()`, i.e. into logs and batch error messages.
    `FhirDeleteRequestBuilder` gets this right.
    Fixed: removed the doubled slash in the update and patch builders; now
    asserted by "build the logged request uri without a doubled slash" tests in
    the crud and patch suites.

### Ordering constraints worth documenting for users

Not defects, but easy to get wrong and now covered by tests:

- On a conditional patch, `where(..)` must be called BEFORE `jsonPatch()` /
  `fhirPathPatch()`: those methods call `compile()` immediately, and the returned
  patch builder's own `compile()` no longer folds `conditionalParams` into the
  query.
- `FhirHistoryRequestBuilder.list(..)` returns `FhirRequestBuilder`, not
  `FhirHistoryRequestBuilder`, so it must come last in a chain (no
  `executeAndReturnBundle()` after it). Same for `FhirReadRequestBuilder.summary`
  and `.elements`.

## Out of scope (later tiers)

- Tier 2: gate `OnFhirNetworkClientTest` / `IdentityServiceClientTest` on an
  env var (e.g. `FHIR_CLIENT_IT_URL`) with specs2 `skipAll`, or move to
  failsafe `*IT` + Maven profile; document how to start a local server.
- Tier 3: env-var-gated LOINC credentials for `TerminologyServiceClientTest`;
  optional scheduled read-only smoke suite against a public server.
- Coverage tooling (scoverage/jacoco) - not configured in this reactor.

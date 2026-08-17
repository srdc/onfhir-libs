# onfhir-client

This module provides a reusable Scala client for constructing and sending FHIR
search, CRUD, history, batch/transaction, and operation requests. Maven
coordinate: `io.onfhir:onfhir-client_2.13`.

Principal APIs are `OnFhirNetworkClient`, `IOnFhirClient`, `BaseFhirClient`,
the interaction-specific request builders, bundle wrappers, authentication
interceptors, and the `TerminologyServiceClient` and `IdentityServiceClient`
service facades. It depends on Common and Path. It uses the JDK 11+
`java.net.http.HttpClient`; it does not depend on Akka/Pekko, start a server,
perform persistence, or validate resources against profiles.

The client exchanges FHIR JSON (`application/fhir+json`) only; XML content is
rejected. It is FHIR-release agnostic: resources are handled as raw JSON
(`io.onfhir.api.Resource`, a json4s `JObject`), so it can talk to R4, R5 or
STU3 endpoints alike.

## Installation

Requires JDK 11 or newer and Scala 2.13.

Maven:

```xml
<dependency>
    <groupId>io.onfhir</groupId>
    <artifactId>onfhir-client_2.13</artifactId>
    <version>4.0.0</version>
</dependency>
```

sbt:

```scala
libraryDependencies += "io.onfhir" %% "onfhir-client" % "4.0.0"
```

## Getting started

The entry point is [io.onfhir.client.OnFhirNetworkClient](./src/main/scala/io/onfhir/client/OnFhirNetworkClient.scala).
Constructors receive a Scala `ExecutionContext` implicitly. The client keeps a
single underlying JDK `HttpClient` transport, so applications should construct
the `OnFhirNetworkClient` once and share it wherever they access the target
FHIR API.

```scala
import io.onfhir.api.Resource
import io.onfhir.api.client.FHIRSearchSetBundle
import io.onfhir.client.OnFhirNetworkClient
import io.onfhir.util.JsonFormatter._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.io.Source

// Supply the executor used for asynchronous client work
implicit val executionContext: ExecutionContext = ExecutionContext.global

// Construct the client (no authentication)
val fhirClient: OnFhirNetworkClient = OnFhirNetworkClient("http://127.0.0.1:8080/fhir")

// Create a FHIR resource
val patient: Resource = Source.fromFile("patient.json").mkString.parseJson
var persistedPatient: Resource =
  Await.result(fhirClient.create(patient).executeAndReturnResource(), 5.seconds)

// Update a FHIR resource
// ... modify some elements of persistedPatient ...
persistedPatient =
  Await.result(fhirClient.update(persistedPatient).executeAndReturnResource(), 5.seconds)

// Search for FHIR resources
val bundle: FHIRSearchSetBundle =
  Await.result(
    fhirClient
      .search("Patient")
      .where("gender", "male")
      .executeAndReturnBundle(),
    5.seconds)

bundle.searchResults.map(p => ...) // Access the resources in the FHIR search-set bundle
```

All request builders produce `Future`-based results; the examples use `Await`
only for brevity.

## Creating a client

`OnFhirNetworkClient` provides the following factory methods:

```scala
OnFhirNetworkClient(serverBaseUrl)                      // No authentication, default settings
OnFhirNetworkClient(serverBaseUrl, interceptor)         // Single HTTP request interceptor (e.g. authentication)
OnFhirNetworkClient(serverBaseUrl, Seq(intcp1, intcp2)) // Multiple interceptors, applied in registration order
OnFhirNetworkClient(serverBaseUrl, clientHttpSettings)  // Custom HTTP transport settings
OnFhirNetworkClient(config)                             // Everything from Typesafe config (see below)
```

There are also fluent helpers that return a new client with the corresponding
authentication interceptor attached:

```scala
OnFhirNetworkClient("https://fhir.example.com/fhir")
  .withBasicAuthentication("myuser", "mypassword")
//.withFixedBasicTokenAuthentication(encodedToken)   // Authorization: Basic <token>
//.withFixedBearerTokenAuthentication(bearerToken)   // Authorization: Bearer <token>
//.withOpenIdBearerTokenAuthentication(clientId, clientSecret, requiredScopes, tokenEndpoint)
```

### HTTP transport settings

Transport behavior is configured with
[io.onfhir.client.model.ClientHttpSettings](./src/main/scala/io/onfhir/client/model/ClientHttpSettings.scala):

| Setting          | Config key        | Default     | Description                                                                                      |
|------------------|-------------------|-------------|--------------------------------------------------------------------------------------------------|
| `connectTimeout` | `connect-timeout` | 10 seconds  | TCP connection establishment timeout                                                              |
| `requestTimeout` | `request-timeout` | none        | Total timeout for a single HTTP request; the request fails when exceeded                          |
| `maxRetries`     | `max-retries`     | 5           | Automatic retries after transport (I/O) failures for replayable HTTP methods (see error handling)  |
| `sslContext`     | code only         | JDK default | Custom `SSLContext`, e.g. for mutual TLS or a custom trust store                                   |

```scala
import io.onfhir.client.model.ClientHttpSettings
import java.time.Duration

val settings =
  ClientHttpSettings(
    connectTimeout = Duration.ofSeconds(5),
    requestTimeout = Some(Duration.ofSeconds(60)),
    maxRetries = 2)

val fhirClient = OnFhirNetworkClient("https://fhir.example.com/fhir", settings)
```

The transport uses HTTP/1.1 and never follows redirects automatically; a 3xx
response is returned to the caller as-is (with its `Location` header).

### Creating a client from configuration

You can configure the endpoint, authentication and HTTP settings in your
Typesafe config file and construct the client from it:

```hocon
# Your <application.conf> file
onfhir.client {
  # FHIR service base url
  serverBaseUrl = "https://fhir.example.com/fhir"

  authz {
    # Authorization method: 'basic' or 'oauth2'
    method = "oauth2"

    # For method = "oauth2" (client credentials flow)
    client_id = "myclient"
    client_secret = "mysecret"
    # Scopes to request for the access token
    scopes = ["system/Patient.rs", "system/Observation.rs"]
    # URL of the OAuth2.0/OpenID Connect token endpoint of the authorization server
    token_endpoint = "https://authzserver.com/token"
    # Client authentication method: 'client_secret_basic', 'client_secret_post' or 'client_secret_jwt'
    token_endpoint_auth_method = "client_secret_basic"

    # For method = "basic" instead, supply:
    # username = "myuser"
    # password = "mypassword"
  }

  http {
    connect-timeout = 10s
    # request-timeout = 60s
    max-retries = 5
  }
}
```

```scala
import com.typesafe.config.ConfigFactory

val config = ConfigFactory.load()
val fhirClient = OnFhirNetworkClient(config.getConfig("onfhir.client"))
```

The `authz` and `http` blocks are optional; without `authz` the client sends
requests unauthenticated, and without `http` the defaults above apply.

## Constructing FHIR requests

You start constructing a request with the methods on
[io.onfhir.api.client.IOnFhirClient](./src/main/scala/io/onfhir/api/client/IOnFhirClient.scala)
(implemented by `OnFhirNetworkClient` via `BaseFhirClient`). Each method
returns a request builder specific to the FHIR interaction on which you can
set further options and finally execute the request.

| Method signature                                         | FHIR interaction  | Description/Example                                                                                                                                       |
|----------------------------------------------------------|-------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `capabilities()`                                         | capabilities      | Retrieve the server's CapabilityStatement (GET [base]/metadata). Use `.mode(...)` for the `mode` parameter (e.g. `full`, `normative`, `terminology`).       |
| `create(r:Resource)`                                     | create            | Create the given FHIR resource.                                                                                                                             |
| `read(rtype:String, rid:String)`                         | read              | Read a resource by FHIR resource type and identifier, e.g. `_.read("Patient", "p1")`                                                                        |
| `update(r:Resource, forceVersionControl:Boolean = true)` | update            | Update the given FHIR resource (should have `Resource.id` unless used as conditional update). See the note on version-aware updates below.                  |
| `delete(rtype:String, rid:String)`                       | delete            | Delete the FHIR resource given by resource type and identifier.                                                                                             |
| `delete(r:Resource)`                                     | delete            | Delete the given FHIR resource; type and identifier are extracted from the content.                                                                         |
| `delete(rtype:String)`                                   | delete            | Start a conditional delete request for the given resource type (add conditions with `where`).                                                               |
| `vread(rtype:String, rid:String, vid:String)`            | vread             | Read a specific version of a resource, e.g. `_.vread("Encounter", "e1", "3")`                                                                               |
| `patch(rtype:String, rid:String)`                        | patch             | Start a patch request for the given resource type and identifier.                                                                                           |
| `patch(r:Resource)`                                      | patch             | Start a patch request for the given resource; type and identifier are extracted from the content.                                                           |
| `patch(rtype:String)`                                    | patch             | Start a conditional patch request for the given resource type (add conditions with `where`).                                                                |
| `history(rtype:String, rid:String)`                      | history-instance  | Read the change history of a specific resource.                                                                                                             |
| `history(rtype:String, rid:String, count:Int)`           | history-instance  | Same with pagination parameter `_count`.                                                                                                                    |
| `history(r:Resource)`                                    | history-instance  | Read the history of the given resource; type and identifier are extracted from the content.                                                                 |
| `history(r:Resource, count:Int)`                         | history-instance  | Same with pagination parameter `_count`.                                                                                                                    |
| `history(rtype:String)`                                  | history-type      | Type-level history request for the given resource type.                                                                                                     |
| `history(rtype:String, count:Int)`                       | history-type      | Same with pagination parameter `_count`.                                                                                                                    |
| `search(rtype:String)`                                   | search            | Start a FHIR search request on the given resource type with the server's default page size.                                                                 |
| `search(rtype:String, count:Int)`                        | search            | Start a FHIR search request with the given page size (`_count`).                                                                                            |
| `getSearchPage(link:String)`                             | search (page)     | Retrieve a search-set page from a full or server-relative URL (e.g. a `Bundle.link.url` you stored earlier).                                                |
| `operation(opName:String)`                               | operation         | Start a FHIR operation request with the given operation name (without the `$` prefix), e.g. `_.operation("validate")`                                       |
| `batch()`                                                | batch             | Start a FHIR batch request.                                                                                                                                 |
| `transaction()`                                          | transaction       | Start a FHIR transaction request.                                                                                                                           |

### FHIR search requests

For search requests you can use the following builder methods to construct the
final request:

* `where(param:String, values:String*)`: Add a search parameter statement,
  e.g. `_.where("gender", "male")` --> `?gender=male`. Calling `where`
  repeatedly with the same parameter name produces repeated query parameters
  (AND semantics), while passing multiple values in a single call joins them
  with a comma (OR semantics), e.g.
  `_.where("code", "a").where("code", "b")` --> `?code=a&code=b` but
  `_.where("code", "a", "b")` --> `?code=a,b`.
* `where(parsedQuery:Map[String, List[String]])`: Add already-parsed query
  parameters.
* `forCompartment(ctype:String, cid:String)`: Turn the search into a
  compartment search, e.g. `_.forCompartment("Patient", "p1")`.
* `byHttpPost()`: Send the search via HTTP POST (`.../_search`) instead of the
  default HTTP GET.
* `sortOnAsc(params:String*)` / `sortOnDesc(params:String*)`: Sort the result
  set by the given search parameters in ascending/descending order (`_sort`).
* `strictHandling()` / `lenientHandling()`: Ask the server for strict or
  lenient parameter handling (`Prefer: handling=strict|lenient`).
* Pagination:
  * `setPage(page:Int)`: Set the page number via onFHIR's `_page` parameter.
  * `setSearchAfter(offset:String)` / `setSearchBefore(offset:String)`: Use
    offset-based pagination (`_searchafter`/`_searchbefore`).
  * `setPaginationParam(paramName:String, value:String|Int)`: Use any custom
    pagination parameter of the target server.

```scala
// Search blood pressure measurements of patient 'p1' after January 1 2024
fhirClient
  .search("Observation")
  .forCompartment("Patient", "p1")
  .where("code", "http://loinc.org|85354-9")
  .where("date", "ge2024-01-01")
  .sortOnDesc("date")
```

See [Handling responses](#handling-responses) for retrieving further pages of
the search result set.

### FHIR read requests

For FHIR read requests the following options can be used for conditional read
(see the [FHIR standard](https://hl7.org/fhir/R5/http.html#cread) for details)
and element filtering:

* `ifModifiedSince(since:Instant)`: Return the resource only if it was
  modified after the given time (otherwise the server responds `304 Not
  Modified`).
* `ifNoneMatch(version:Long)`: Return the resource only if its current version
  differs from the given one.
* `summary(mode:String)`: Ask for a subset of the resource via the `_summary`
  parameter, e.g. `_.summary("text")`.
* `elements(el:String*)`: Ask only for the listed elements via the `_elements`
  parameter, e.g. `_.elements("gender", "birthDate")`.

### FHIR create, update and delete requests

The following options can be used while constructing create, update, patch and
batch/transaction requests. See the
[related section](https://hl7.org/fhir/R5/http.html#ops) in the FHIR standard
for details.

* `returnMinimal()`: Indicate that the response can be minimal
  (`Prefer: return=minimal`); the response then carries only headers, not the
  created/updated resource content.
* `returnOperationOutcome()`: Ask the server to return an OperationOutcome
  resource with hints and warnings about the operation instead of the full
  resource (`Prefer: return=OperationOutcome`).

For conditional create, update, delete and patch requests, provide the
conditional statement with `where`, as in search requests. For conditional
create the condition is sent in the `If-None-Exist` header; for the others it
is sent as the query part of the request URL.

```scala
// Update the observation matching the given identifier, or create it if none exists
fhirClient
  .update(updatedObservation)
  .where("identifier", "http://my-lab-system|123")
```

Version-aware update: by default (`forceVersionControl = true`) the client
extracts the current version from the given resource content (`meta.versionId`)
and sends it in the `If-Match` header, so the update only succeeds if the
server-side version still matches (optimistic locking, see the
[FHIR concurrency documentation](https://hl7.org/fhir/R5/http.html#concurrency)).
Pass `forceVersionControl = false` to disable this behavior.

### FHIR patch requests

For patch requests, use one of the following to choose the patch type:

* `patchContent(patch:JValue)`: Directly provide the patch content; a
  `Parameters` resource is interpreted as FHIRPath Patch, a JSON object or
  array as JSON Patch.
* `fhirPathPatch()`: Continue building a
  [FHIRPath Patch](https://hl7.org/fhir/R5/fhirpatch.html) request.
* `jsonPatch()`: Continue building a
  [JSON Patch](https://tools.ietf.org/html/rfc6902) request.

For FHIRPath Patch, the following operations are available. Values are given
as a tuple of the FHIR data type name and the JSON value, e.g.
`"code" -> JString("final")`.

* `patchAdd(path:String, name:String, value:(String, JValue))`: Add an element
  with the given name and typed value under the given path.
* `patchInsert(path:String, index:Int, value:(String, JValue))`: Insert a
  value into the repetitive element at the given path and index.
* `patchReplace(path:String, value:(String, JValue))`: Replace the content at
  the given path.
* `patchMove(path:String, source:Int, destination:Int)`: Move an item within
  the repetitive element at the given path from the source index to the
  destination index.
* `patchDelete(path:String)`: Delete the element at the given path.

```scala
import org.json4s.JsonAST._

// Update the status of Observation 'obs1' to 'final' if its current status is 'preliminary'
fhirClient
  .patch("Observation", "obs1")
  .where("status", "preliminary")
  .fhirPathPatch()
  .patchReplace("Observation.status", "code" -> JString("final"))

// Mark the active HbA1c goal of patient 'p1' as achieved, set the status date and
// register the observation that triggered the achievement as an outcome
val achieved =
  JObject(
    "coding" -> JArray(List(
      JObject(
        "system" -> JString("http://terminology.hl7.org/CodeSystem/goal-achievement"),
        "code" -> JString("achieved")
      )
    ))
  )

fhirClient
  .patch("Goal")
  .where("subject", "Patient/p1")
  .where("target-measure", "http://loinc.org|4548-4")
  .where("lifecycle-status", "active")
  .fhirPathPatch()
  .patchAdd("Goal", "achievementStatus", "CodeableConcept" -> achieved)
  .patchReplace("Goal.statusDate", "date" -> JString("2024-06-14"))
  .patchInsert("Goal.outcome", 0,
    "CodeableReference" -> JObject("reference" -> JObject("reference" -> JString("Observation/obs1"))))
```

Similarly, for JSON Patch the following operations are available. Paths are
[JSON Pointer](https://tools.ietf.org/html/rfc6901) expressions.

* `patchAdd(path:String, value:JValue)`: Add the JSON value at the given path.
* `patchCopy(from:String, path:String)`: Copy the JSON content from one path
  to another.
* `patchMove(from:String, path:String)`: Move the JSON content from one path
  to another.
* `patchReplace(path:String, value:JValue)`: Replace the JSON content at the
  given path with the given value.
* `patchRemove(path:String)`: Delete the content at the given path.

```scala
// Set the status of the observation to 'preliminary' and add a new coding
// at index 1 of the first component's code
fhirClient
  .patch("Observation", "obs1")
  .jsonPatch()
  .patchReplace("/status", JString("preliminary"))
  .patchAdd("/component/0/code/coding/1", JObject("system" -> JString("test"), "code" -> JString("test")))
```

### FHIR history requests

On history requests (instance or type level) you can use the following options:

* `since(instant:Instant)`: Only include changes after the given time (`_since`).
* `at(time:ZonedDateTime)`: Only include changes at the given time (`_at`).
* `list(l:String*)`: Only include changes for resources in the given lists (`_list`).
* `setPaginationParam(paramName:String, value:String|Int)`: Set a custom
  pagination parameter, as in search requests.

### FHIR batch/transaction requests

For batch/transaction requests, add child requests with the following methods:

* `entry(rbFunction:IOnFhirClient => FhirRequestBuilder)`: Provide a function
  that builds the child request from the client.
* `entry(fullUrlUuid:String, rbFunction:IOnFhirClient => FhirRequestBuilder)`:
  Same, but also assigns the given UUID (must be in `urn:uuid:...` format) as
  `Bundle.entry.fullUrl` of the child request, so you can match responses with
  requests.
* `entriesFromBundle(bundle:JObject)`: Add all request entries of an existing
  FHIR Bundle to this request.

```scala
import java.util.UUID
import io.onfhir.api.client.FHIRTransactionBatchBundle

// Execute multiple requests in a batch
fhirClient
  .batch()
  .entry(_.create(diagnosticReport))
  .entry(_.create(bloodGlucoseObservation))
  .entry(_.update(bloodGlucoseGoal))
  .entry(
    _
      .delete("AdverseEvent")
      .where("patient", "Patient/p1")
  )

// Execute multiple requests in a transaction, assigning UUIDs to correlate responses
val bloodGlucoseUuid = s"urn:uuid:${UUID.randomUUID()}"
val diagnosticReportUuid = s"urn:uuid:${UUID.randomUUID()}"

val responseBundle: FHIRTransactionBatchBundle =
  Await.result(
    fhirClient
      .transaction()
      .entry(bloodGlucoseUuid, _.create(bloodGlucoseObservation))
      .entry(diagnosticReportUuid, _.create(diagnosticReport))
      .executeAndReturnBundle(),
    5.seconds)

// Check the individual responses
if (responseBundle.hasAnyError())
  throw new RuntimeException("Transaction partially failed!")
val createdObservation = responseBundle.getResponse(bloodGlucoseUuid).responseBody
```

The parsed [FHIRTransactionBatchBundle](./src/main/scala/io/onfhir/api/client/FHIRBundle.scala)
provides `responses` (a sequence of `fullUrl -> FHIRResponse` pairs),
`getResponse(fullUrl)`, `hasAnyError()`, `hasAnyNonTransientError()` and
`getUUIDsOfTransientErrors()` to inspect the outcome.

### FHIR operation requests

Build FHIR operation calls (e.g. `$validate`, `$expand`, custom operations)
with the following methods:

* `on(rtype:String, rid:Option[String] = None)`: Target resource type and
  optionally instance for the operation; omit for system-level operations.
* `addSimpleParam(name:String, values:String*)`: Add a primitive input
  parameter passed as a URL query parameter.
* `addParam(name:String, value:(String, JValue))`: Add a typed input parameter
  placed into the `Parameters` request body, e.g.
  `addParam("start", "date" -> JString("2024-01-01"))`.
* `addResourceParam(name:String, value:JObject)`: Add a resource-valued input
  parameter.
* `addMultiParam(name:String, parts:JArray)`: Add a multi-part input parameter
  (`Parameters.parameter.part`).

If only simple (query) parameters are used, the operation is invoked with
HTTP GET; otherwise a `Parameters` resource is constructed and sent with
HTTP POST.

```scala
import io.onfhir.api.model.FHIROperationResponse

// Validate an Observation resource against the server ($validate)
val opResponse: FHIROperationResponse =
  Await.result(
    fhirClient
      .operation("validate")
      .on("Observation")
      .addSimpleParam("mode", "general")
      .addResourceParam("resource", observationResource)
      .executeAndReturnOperationOutcome(),
    5.seconds)

val outcome = opResponse.getOutputParam("return")
```

`executeAndReturnOperationOutcome()` parses the response as a
`FHIROperationResponse` from which you can access output parameters via
`getOutputParam(name)`.

### Capability statement

```scala
// Retrieve the CapabilityStatement of the server
val capabilityStatement: Resource =
  Await.result(fhirClient.capabilities().executeAndReturnResource(), 5.seconds)
```

## Handling authentication

`OnFhirNetworkClient` can be supplied with interceptor(s) that enrich each
HTTP request before it is sent, e.g. with the headers required by the
authentication protocol of the target FHIR server. An interceptor implements
[io.onfhir.client.IHttpRequestInterceptor](./src/main/scala/io/onfhir/client/IHttpRequestInterceptor.scala)
(a single `processRequest` method), so you can also implement your own for
custom requirements. Interceptors are applied in registration order. The
following implementations are provided:

### [BasicAuthenticationInterceptor](./src/main/scala/io/onfhir/client/intrcp/BasicAuthenticationInterceptor.scala)

Use this if the target FHIR API supports HTTP Basic authentication. The
constructor receives the username and password given to your system for FHIR
API access; the header is computed for you.

```scala
val authInterceptor = new BasicAuthenticationInterceptor("myuser", "mypassword")
val fhirClient = OnFhirNetworkClient("http://127.0.0.1:8080/fhir", authInterceptor)
// or equivalently
val fhirClient2 = OnFhirNetworkClient("http://127.0.0.1:8080/fhir").withBasicAuthentication("myuser", "mypassword")
```

### [FixedBasicTokenInterceptor](./src/main/scala/io/onfhir/client/intrcp/FixedBasicTokenInterceptor.scala)

Use this if you already have the Base64-encoded credentials and want to send
them as a fixed `Authorization: Basic <token>` header.

```scala
val fhirClient = OnFhirNetworkClient("http://127.0.0.1:8080/fhir").withFixedBasicTokenAuthentication(encodedToken)
```

### [FixedBearerTokenInterceptor](./src/main/scala/io/onfhir/client/intrcp/FixedBearerTokenInterceptor.scala)

If the target FHIR server supports Bearer token authentication
(`Authorization: Bearer ...`) and your system is given a fixed bearer token,
or you obtain the token yourself, use this interceptor.

```scala
val fixedToken = ...
val authInterceptor = new FixedBearerTokenInterceptor(fixedToken)
val fhirClient = OnFhirNetworkClient("http://127.0.0.1:8080/fhir", authInterceptor)
```

### [BearerTokenInterceptorFromTokenEndpoint](./src/main/scala/io/onfhir/client/intrcp/BearerTokenInterceptorFromTokenEndpoint.scala)

If the target FHIR server uses Bearer token authentication and your system is
expected to obtain the token from a token endpoint with client credentials
(SMART backend services style; OAuth2.0/OpenID Connect client-credentials
flow), use this interceptor. It retrieves the access token, caches it, and
refreshes it shortly before expiry; concurrent requests share a single token
refresh.

Constructor parameters:

* `clientId`: Client identifier assigned by the authorization server
* `clientSecret`: Client secret given by the authorization server
* `requiredScopes`: Scopes you need in the token to access the intended FHIR resources
* `authzServerTokenEndpoint`: URL of the OAuth2.0/OpenID Connect token endpoint
* `clientAuthenticationMethod`: Client authentication method for the token
  request (see [OpenID Client Authentication](https://openid.net/specs/openid-connect-core-1_0.html#ClientAuthentication)).
  Supported methods: `client_secret_basic` (default), `client_secret_post`,
  `client_secret_jwt`.

```scala
val authInterceptor =
  BearerTokenInterceptorFromTokenEndpoint(
    clientId = "myclient",
    clientSecret = "mysecret",
    requiredScopes = Seq("patient/Observation.rs", "patient/Condition.rs"),
    authzServerTokenEndpoint = "https://authzserver.com/token",
    clientAuthenticationMethod = "client_secret_basic"
  )
val fhirClient = OnFhirNetworkClient("http://127.0.0.1:8080/fhir", authInterceptor)
```

You can also configure the OAuth2.0/OpenID parameters in your configuration
file under `onfhir.client.authz` (see
[Creating a client from configuration](#creating-a-client-from-configuration))
and construct only the interceptor from it:

```scala
val config: Config = ConfigFactory.load()
val authInterceptor =
  BearerTokenInterceptorFromTokenEndpoint
    .getFromConfig(config.getConfig("onfhir.client.authz"), requiredScopes = Seq("patient/*.rs"))
val fhirClient = OnFhirNetworkClient("http://127.0.0.1:8080/fhir", authInterceptor)
```

Every configuration entry point in this module takes an already-scoped subtree
and reads relative keys: `OnFhirNetworkClient(config)` expects `onfhir.client`,
and the interceptor factories expect its `authz` block. Scope the config
yourself, as above; no key path is hardcoded in the library. `getFromConfig`
takes the requested scopes as an argument, while
`BearerTokenInterceptorFromTokenEndpoint(authzConfig)` reads them from the
subtree's `scopes` key.

## Handling responses

The library provides several alternatives to process the FHIR response. The
basic one is calling `execute()`, which returns the whole response as
[io.onfhir.api.model.FHIRResponse](../onfhir-common/src/main/scala/io/onfhir/api/model/FHIRResponse.scala):

```scala
val fhirResponse: Future[FHIRResponse] =
  fhirClient
    .create(observationResource)
    .execute()

fhirResponse
  .foreach {
    // When the interaction is rejected due to an error
    case r: FHIRResponse if r.isError =>
      // Parsed OperationOutcome issues are available for error responses
      logger.error(s"Observation cannot be created. HttpStatus: ${r.httpStatus.intValue()} Issues: ${r.outcomeIssues}")
    // Otherwise it is successful
    case r: FHIRResponse =>
      // e.g. '... at location http://localhost:8080/fhir/Observation/21325325'
      logger.debug(s"Observation is created successfully at location ${r.location.get}")
      // The created resource is available if the request is configured to return it
      val createdResource = r.responseBody.get
      ...
  }
```

Apart from the status and the optional resource body, `FHIRResponse` provides
the parsed `location`, `lastModified`, `newVersion` (ETag) and
`outcomeIssues` (parsed OperationOutcome issues) of the response.

For FHIR CRUD interactions where the response optionally returns a FHIR
resource, there are two further options:

* `executeAndReturnResource()`: Returns directly the FHIR resource
  created/updated/patched/read if the interaction is successful and a resource
  is returned; otherwise the future fails with `FhirClientException` (which
  also includes the `FHIRResponse` itself).
* `executeAndReturnResourceOption()`: Returns the optional FHIR resource if
  the interaction is successful, `None` otherwise.

```scala
fhirClient
  .create(patientWithoutId)
  .executeAndReturnResource()
  .recover {
    case fce: FhirClientException =>
      logger.error(fce.msg, fce)
      ...
  }

// For optional resource return
fhirClient
  .read("Patient", "123")
  .executeAndReturnResourceOption()
  .flatMap {
    // No such patient
    case None => ...
    case Some(patientResource) => ...
  }
```

For search-like interactions (FHIR search and history) there are further
options:

* `executeAndReturnBundle()`: Returns a future `FHIRSearchSetBundle` where you
  can access matched entries (`searchResults`) and included resources
  (`includedResults`), and make another call for the next page.
* `toIterator()`: Returns an iterator to iterate over the search result pages.
* `executeAndMergeBundle()`: Handles pagination internally by repeatedly
  retrieving the next page and returns a single merged `FHIRSearchSetBundle`
  with all results.

Retrieving pages explicitly with `OnFhirNetworkClient.next(bundle)`:

```scala
import io.onfhir.path.FhirPathEvaluator

var resultSetBundle: FHIRSearchSetBundle =
  Await.result(
    fhirClient
      .search("Observation", count = 50)
      .where("code", "http://loinc.org|5014-4")
      .where("_include", "Observation:patient")
      .executeAndReturnBundle(),
    5.seconds)

var continue = true
while (continue) {
  // Access the returned observations and the included patients owning them
  resultSetBundle.searchResults.foreach { obs =>
    val patientRef = FhirPathEvaluator().evaluateOptionalString("Observation.subject.reference", obs).get
    val patient = resultSetBundle.includedResults.get(patientRef)
    ...
  }
  continue = resultSetBundle.hasNext()
  if (continue)
    resultSetBundle = Await.result(fhirClient.next(resultSetBundle), 5.seconds)
}
```

Iterator usage:

```scala
val resultSetItr: Iterator[Future[FHIRSearchSetBundle]] =
  fhirClient
    .search("Observation", count = 50)
    .where("code", "http://loinc.org|5014-4")
    .where("_include", "Observation:patient")
    .toIterator()

while (resultSetItr.hasNext) {
  val resultSetBundle = Await.result(resultSetItr.next(), 5.seconds)
  val observations = resultSetBundle.searchResults
  ...
}
```

Retrieving the whole result set at once:

```scala
val allResultsBundle =
  Await.result(
    fhirClient
      .search("Observation", count = 50)
      .forCompartment("Patient", "123")
      .executeAndMergeBundle(),
    30.seconds)

val allObservationsOfPatient = allResultsBundle.searchResults
```

For history requests, `executeAndReturnBundle()` returns a
`FHIRHistoryBundle` providing the versions via `getHistory()` (instance
level), `getHistory(rid)` or `getHistories` (type level).

Finally, implicit conversions are defined for all these execution methods, so
you may omit the execution method entirely and let the expected type of the
assigned variable choose it:

```scala
// No need to call executeAndReturnBundle explicitly; the implicit conversion
// applies it as the expression is bound to a Future[FHIRSearchSetBundle]
val resultSetBundle: Future[FHIRSearchSetBundle] =
  fhirClient
    .search("Observation", count = 50)
    .where("code", "http://loinc.org|5014-4")
```

## Error handling, timeouts and retries

* `execute()` completes successfully even for HTTP error statuses; inspect
  `FHIRResponse.isError`, `httpStatus` and `outcomeIssues`.
* The returned future fails with
  [FhirClientException](./src/main/scala/io/onfhir/api/client/FhirClientException.scala)
  when the request cannot be built or sent (connection failure, timeout,
  interceptor failure) or when the response cannot be parsed. The underlying
  transport error is available via `getCause`; if a FHIR response was
  received, it is available via `serverResponse`.
* The convenience methods (`executeAndReturnResource()`,
  `executeAndReturnBundle()`, etc.) convert HTTP error responses into failed
  futures with `FhirClientException` carrying the `FHIRResponse`.
* I/O failures on replayable HTTP methods (GET, HEAD, OPTIONS, PUT, DELETE,
  TRACE) are retried automatically up to `max-retries` (default 5). Requests
  with HTTP POST (create, batch/transaction, POST-search, operations with
  body) and PATCH are never retried automatically.
* A total per-request timeout is only imposed when `request-timeout` is
  configured; the connect timeout applies in any case.

## Terminology service client

[io.onfhir.client.TerminologyServiceClient](./src/main/scala/io/onfhir/client/TerminologyServiceClient.scala)
wraps an `IOnFhirClient` configured for a FHIR terminology server and
implements the `IFhirTerminologyService` interface from `onfhir-common` with
the standard terminology operations:

* `lookup(code, system, ...)` / `lookup(coding, ...)`: CodeSystem `$lookup`;
  returns the resulting `Parameters` resource, or `None` if the code is unknown.
* `translate(...)`: ConceptMap `$translate` with overloads for code+system or
  Coding/CodeableConcept inputs, and for a known concept map URL or
  source/target value set URLs.
* `expand(url, ...)` / `expandWithId(id, ...)` / `expandWithValueSet(valueSet, ...)`:
  ValueSet `$expand`.
* `validateCode(url, ...)`: ValueSet `$validate-code`.

```scala
import io.onfhir.client.{OnFhirNetworkClient, TerminologyServiceClient}

val terminologyClient =
  new TerminologyServiceClient(
    OnFhirNetworkClient("https://fhir.loinc.org").withBasicAuthentication(user, password))

// $lookup: e.g. returns display 'Bicarbonate [Moles/volume] in Serum or Plasma'
val lookupResult: Future[Option[Resource]] = terminologyClient.lookup("1963-8", "http://loinc.org")

// $validate-code against a value set
val validationResult: Future[Resource] =
  terminologyClient.validateCode("http://loinc.org/vs", code = "2339-0", system = Some("http://loinc.org"))
```

Because it implements `IFhirTerminologyService`, it can be plugged into other
onFHIR libraries, e.g. into the FHIRPath evaluator of `onfhir-path`
(`FhirPathEvaluator.withTerminologyService(...)`) to support FHIRPath
terminology functions (`memberOf`, `%terminologies.lookup(...)`, etc.).

## Identity service client

[io.onfhir.client.IdentityServiceClient](./src/main/scala/io/onfhir/client/IdentityServiceClient.scala)
resolves business identifiers to FHIR resource identifiers by querying the
target server over the `identifier` search parameter. It implements the
`IFhirIdentityService` interface from `onfhir-common`.

* `findMatching(resourceType, identifier, system = None)`: Returns the
  `Resource.id` of the matching resource, if any. For `Patient` resources it
  also evaluates `Patient.link` (e.g. `replaces`, `seealso`) to return the
  identifier of the current record.
* An optional `IFhirIdentityCache` can be supplied to cache resolved
  identities and skip repeated queries.

```scala
import io.onfhir.client.IdentityServiceClient

val identityService = new IdentityServiceClient(fhirClient)

val patientId: Future[Option[String]] =
  identityService.findMatching("Patient", "12345", Some("urn:oid:1.2.36.146.595.217.0.1"))
```

Like the terminology client, it can be plugged into the FHIRPath evaluator via
`FhirPathEvaluator.withIdentityService(...)`.

## Easy mutation of FHIR content

The module also provides a utility,
[io.onfhir.client.util.FhirResourceMutator](./src/main/scala/io/onfhir/client/util/FhirResourceMutator.scala),
to mutate FHIR content parsed into json4s objects with FHIRPath-Patch-like
operations. Import `io.onfhir.client.util.FhirResourceMutator._` to access
these methods implicitly on `io.onfhir.api.Resource` (`JObject`):

* `addElement(fhirPath, name, value, isArray = false)`: Add an element with
  the given name under the element(s) found at the given FHIRPath.
* `addRootElement(name, value, isArray = false)`: Add an element at the root
  of the resource.
* `insertElement(fhirPath, index, value)`: Insert a value into the repetitive
  element at the given path and index.
* `replaceElement(fhirPath, value)`: Replace the content at the given path.
* `moveElement(fhirPath, source, destination)`: Move an item within a
  repetitive element.
* `deleteElement(fhirPath)`: Delete the element at the given path.

Each method has an `...OrThrowExc` variant (e.g. `addElementOrThrowExc`) that
throws an `IllegalArgumentException` if the given FHIRPath does not match any
element, instead of leaving the resource unchanged. Check the method
documentation in the class definition for details.

```scala
import io.onfhir.client.util.FhirResourceMutator._
import java.time.LocalDate
import org.json4s.JsonAST.JString

val episodeOfCare: Resource =
  Await.result(fhirClient.read("EpisodeOfCare", "eps1").executeAndReturnResource(), 5.seconds)

// Make changes on the EpisodeOfCare resource
val updatedEpisode: Resource =
  episodeOfCare
    .addElement("EpisodeOfCare", "status", JString("finished"))                    // Set status as finished
    .addElement("EpisodeOfCare.period", "end", JString(LocalDate.now().toString))  // Set the end time

// Call FHIR update
fhirClient.update(updatedEpisode)
```

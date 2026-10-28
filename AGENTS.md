# Repository Guidelines

Burp Suite extension that exposes Burp tools to AI clients over MCP. Kotlin/JVM only. No Node, Python, or npm.

## Project Overview

`net.portswigger.mcp.ExtensionBase` loads inside Burp (Montoya API, compileOnly). A Ktor Netty server speaks MCP over Streamable HTTP at `/mcp` and legacy SSE at `/`. Default bind is `http://127.0.0.1:9876`. Clients that only speak stdio use the vendored proxy in `libs/mcp-proxy-all.jar` (`--sse-url http://host:port`), embedded into the extension jar at build time.

Version and coordinates live in `gradle.properties` (`group=net.portswigger`, `version=1.3.0`). BApp metadata is `BappManifest.bmf` (`MinPlatformVersion: 26`, `ProOnly: False`, `BuildCommand: ./gradlew embedProxyJar`). License GPL-3.0. No changelog.

## Architecture & Data Flow

1. Burp calls `ExtensionBase.initialize(api)`. It wires `McpConfig(api.persistence().extensionData(), api.logging())`, `KtorServerManager(api)`, `ProxyJarManager`, and `ConfigUi(config, providers)`, registers Suite tab `MCP`, and auto-starts when `config.enabled`. Unload: `serverManager.shutdown()`, `configUi.cleanup()`, `config.cleanup()`.
2. `KtorServerManager.start` runs on a single-thread executor: `install(CORS)` (localhost/127.0.0.1 on the configured port), then an `intercept(ApplicationCallPipeline.Call)` that 403s bad `Origin`/`Host`/`Referer` and browser `User-Agent`s (helpers `isValidOrigin/Host/Referer`, `isBrowserRequest` are private to that file), then `mcpStreamableHttp { mcpServer }` + `routing { mcp { mcpServer } }`, then `mcpServer.registerTools(api, config)`. One `Server` serves both transports.
3. A tool call hits the kotlin-sdk `Server`; `mcpTool` decodes args with the param class serializer, runs the `suspend` lambda, wraps `String`/`List<ContentBlock>` in `CallToolResult`. Exceptions → `CallToolResult(TextContent("Error: …"), isError = true)`; `CancellationException` is rethrown so client disconnects cancel the tool.
4. Mutating and data-access tools first pass `HttpRequestSecurity.checkHttpRequestPermission` / `DataAccessSecurity.checkDataAccessPermission` (both `suspend`); the Swing handlers prompt via `Dialogs.requestOption` (`suspendCancellableCoroutine`; cancellation disposes the dialog). Auto-approve lists (`host`, `host:port`, `*.domain`) live in persisted config.
5. History/organizer reads map Montoya types to `@Serializable` DTOs (`schema/serialization.kt`) and JSON capped at `MAX_HISTORY_ITEM_LENGTH = 5_000` (`schema/HistoryItemJson.kt`).
6. `capture_screenshot` runs entirely in one `withContext(Dispatchers.Swing)` block: optional `selectTabByTitle(frame, tab)` (Swing tree walk, reveals enclosing `JTabbedPane`s, returns the tab's content component) then `captureComponent` → PNG `ImageContent`.
7. Client install: `Provider.install` extracts the embedded proxy jar (`ProxyJarManager`, SHA-256 vs `.version` sidecar) and writes the client config.

`ServerState`: `Starting`, `Running`, `Stopping`, `Stopped`, `Failed(exception)`.

## Key Directories

| Path                                   | Purpose                                                                                         |
| -------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `src/main/kotlin/net/portswigger/mcp/` | `ExtensionBase`, `KtorServerManager`, `ServerManager`/`ServerState`, `SwingDispatcher`          |
| `…/tools/`                             | Tool DSL (`McpTool.kt`), every tool + param class (`Tools.kt`), `Screenshots.kt`                |
| `…/config/`                            | `McpConfig`, `ConfigUi`, `ConfigValidation`, `Design.kt`; `components/` Swing panels            |
| `…/security/`                          | `HttpRequestSecurity`, `DataAccessSecurity`, `SecurityUtils` (credential redaction)             |
| `…/schema/`                            | `JsonSchema.kt` (reflection → MCP input schema), DTOs, history truncation                       |
| `…/providers/`                         | `Provider`, `ClaudeDesktopProvider`, `ManualProxyInstallerProvider`, `ProxyJarManager`          |
| `src/test/kotlin/net/portswigger/mcp/` | JUnit 5 tests mirroring main packages; `TestSseMcpClient`, `TestStdioMcpClient`                 |
| `libs/`                                | Vendored `mcp-proxy-all.jar` (git-tracked; required by `embedProxyJar` and `ProxyEndToEndTest`) |
| `gradle/`                              | `libs.versions.toml`, wrapper                                                                   |
| `docs/`                                | Transient plan/tutorial docs; not referenced by code                                            |
| `screenshot/`                          | Throwaway PNG captures from live checks; not gitignored, not referenced                         |

Package root is `net.portswigger.mcp`. One concern per subpackage. Do not add a second top-level package.

## Development Commands

Windows: `.\gradlew.bat`. Elsewhere: `./gradlew`.

| Command                                                          | What it does                                                                                                                                                    |
| ---------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `./gradlew test`                                                 | JUnit 5 full suite. CI runs this first.                                                                                                                         |
| `./gradlew test --tests "net.portswigger.mcp.tools.ToolsKtTest"` | One class. Method names contain spaces; quote the filter.                                                                                                       |
| `./gradlew embedProxyJar`                                        | Ship jar: `shadowJar` then `jar uf build/libs/burp-mcp-all.jar -C libs mcp-proxy-all.jar`. Throws `Proxy JAR not found` if `libs/mcp-proxy-all.jar` is missing. |
| `./gradlew build`                                                | Depends on `shadowJar`. Does not embed the proxy.                                                                                                               |

`embedProxyJar` resolves `jar` from `PATH`, not from the Gradle toolchain. If `jar` is absent run the two steps by hand with the full JDK path. Output Burp loads: `build/libs/burp-mcp-all.jar` (Extensions → Installed → reload after rebuild). No lint, format, or coverage task.

Refresh the proxy: build `shadowJar` in the separate `mcp-proxy` repo, copy `mcp-proxy-all.jar` into `libs/`, run `embedProxyJar`.

## Code Conventions & Common Patterns

Kotlin official style, 4-space indent. No ktlint/detekt/spotless; do not add one unless asked.

**New MCP tool.** Add one `@Serializable` param class in `tools/Tools.kt` (`data class` unless it has no properties). Register inside `Server.registerTools` with `mcpTool<YourParams>("LLM-facing description") { … }`. Tool name = class name in snake_case via `toLowerSnakeCase` (`SendHttp1Request` → `send_http1_request`). Return `String` or `List<ContentBlock>`; `mcpUnitTool` for side-effect-only tools (returns `"Executed tool"`). Zero-arg tools use `mcpTool("output_project_options", "…")` — note these named overloads do not catch exceptions. Pagination: implement `Paginated` (`count`, `offset`) and use `mcpPaginatedTool`. Nullable param properties become optional in the input schema (`KClass.asInputSchema`). Gate Pro-only tools with `api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL`.

**HTTP bodies.** Call `normalizeHttpContent` before send (`send_http1_request`, `create_repeater_tab`, `send_to_intruder`): prelude line endings → CRLF, body bytes untouched. HTTP/2 tools take `pseudoHeaders`/`headers` maps; never put headers in the body parameter.

**Config.** Persist with delegates on `PersistedObject`: `var enabled by storage.boolean(true)`. Setters that matter to the UI call `notifyTargetsChanged` / `notifyDataAccessChanged`; subscribe with `addTargetsChangeListener` / `addDataAccessChangeListener`, hold the `ListenerHandle`, `remove()` it in `cleanup()`.

**Async.** Tool lambdas are `suspend`; call approval checks directly, never wrap them in `runBlocking`. Insert `currentCoroutineContext().ensureActive()` after a blocking Burp call when the tool keeps doing work. Swing dialogs go through `Dialogs.requestOption` (`suspendCancellableCoroutine` + `SwingUtilities.invokeLater`). UI and screenshot code runs under `withContext(Dispatchers.Swing)` (`SwingDispatcher.kt`). Do not touch Swing off that dispatcher; do one EDT block per tool call, not several.

**DI.** Constructor injection only, no framework. `HttpRequestSecurity.approvalHandler` / `DataAccessSecurity.approvalHandler` are swappable objects for tests. Small interfaces: `ServerManager`, `Provider`, `UserApprovalHandler`, `DataAccessApprovalHandler`.

**Errors.** Log with `api.logging().logToOutput` / `logToError`. Startup failure → `ServerState.Failed`. Tool failure → `isError` result, never a thrown exception to the client. User-facing denials are plain strings (`"Send HTTP request denied by Burp Suite"`, `"<No active editor>"`, `"<No tab titled '…' found in the Burp window>"`). `filterConfigCredentials` redacts `password`, `certificate_password`, `hashed_key` and fails closed on unparseable JSON.

**UI.** Colors, fonts, spacing come from `config/Design.kt` (`UIManager` Burp keys with fallbacks). No hardcoded colors in panels. Override `updateUI()` when theme can change.

**Do not** bump the hardcoded `Implementation("burp-suite", "1.1.2")` in `KtorServerManager` unless you mean to; it is not `gradle.properties` `version`.

## Important Files

| File                                                   | Why it matters                                                                                                  |
| ------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------- |
| `src/main/kotlin/net/portswigger/mcp/ExtensionBase.kt` | Burp entry, wiring, unload cleanup. `application.mainClass`.                                                    |
| `…/KtorServerManager.kt`                               | Bind, CORS, header checks, both MCP transports                                                                  |
| `…/tools/McpTool.kt`                                   | Tool DSL, name derivation, error wrapping                                                                       |
| `…/tools/Tools.kt`                                     | All 30 tools and their param classes; `normalizeHttpContent`                                                    |
| `…/tools/Screenshots.kt`                               | `captureComponent`, `selectTabByTitle`, `toPngImageContent`                                                     |
| `…/config/McpConfig.kt`                                | Persisted settings, listeners                                                                                   |
| `…/security/HttpRequestSecurity.kt`                    | Send-request approval incl. `*.domain` matching                                                                 |
| `…/security/SecurityUtils.kt`                          | Credential redaction                                                                                            |
| `…/schema/JsonSchema.kt`                               | Param class → MCP input schema                                                                                  |
| `…/providers/ProxyJarManager.kt`                       | Extract embedded proxy; SHA-256 vs `.version`                                                                   |
| `build.gradle.kts`                                     | Toolchain, `shadowJar`, `EmbedProxyJarTask`, test task                                                          |
| `gradle/libs.versions.toml`                            | Kotlin 2.4.0, Ktor 3.3.1, mcp kotlin-sdk 0.15.0, kotlinx-serialization 1.9.0, montoya-api 2025.10, mockk 1.14.6 |
| `libs/mcp-proxy-all.jar`                               | Must exist before a ship build or e2e test                                                                      |
| `.github/workflows/ci.yml`                             | `test` → `embedProxyJar` → upload jar                                                                           |

## Runtime/Tooling Preferences

- Kotlin 2.4 on JVM 21 (`java.toolchain.version=21` custom property, foojay resolver). `-Xjsr305=strict`.
- Gradle wrapper 9.2.0 only; configuration cache and parallel on. Repositories: mavenCentral, `FAIL_ON_PROJECT_REPOS`. No lockfile, no dependency verification.
- `kotlin.stdlib.default.dependency=false`: stdlib is an explicit `implementation`; add it to any new source set.
- Montoya API is `compileOnly`; never shade it. Everything else is shaded into the fat jar (`jar` task disabled, reproducible archives).
- No Docker, Makefile, Node, or Python. CI: `ubuntu-latest`, Temurin 21, `./gradlew test` then `./gradlew embedProxyJar`; test reports upload only on failure.

## Testing & QA

JUnit 5 (`useJUnitPlatform`) + MockK. Assertions are `org.junit.jupiter.api.Assertions` (only `security/ApprovalCancellationTest.kt` uses `kotlin.test`; prefer JUnit's). `ktor-server-test-host` is on the classpath and unused; do not switch to it. Encoding is forced UTF-8.

- Mirror the main package under `src/test/kotlin/net/portswigger/mcp/`. Name class tests `<Subject>Test.kt`; file-level function tests `<File>KtTest.kt` (`tools/ToolsKtTest.kt`). Backtick-sentence test names; group with `@Nested inner class`.
- Mock `PersistedObject` over a `mutableMapOf<String, Any>()` with `answers { storage[firstArg()] … }` — copy `config/McpConfigTest.kt`.
- Protocol-level tool tests (`ToolsKtTest`): mock `MontoyaApi` relaxed, `mockkStatic` for `HttpHeader`/`HttpService`/`HttpRequest` in `init`, start `KtorServerManager` on `ServerSocket(0)` port, connect `TestSseMcpClient`, call `client.callTool(name, mapOf(...))`, assert with `expectTextContent(...)`; stop in `@AfterEach`. Top-level helpers are stubbed with `mockkStatic("net.portswigger.mcp.tools.ScreenshotsKt")` etc. and `unmockkStatic` after.
- `McpServerIntegrationTest` covers both transports (`SseClientTransport` at `/`, `StreamableHttpClientTransport` at `/mcp`).
- `ProxyEndToEndTest` spawns `java -jar libs/mcp-proxy-all.jar --sse-url …`; fails fast if the jar is missing; class `@Timeout(30s)`.
- Swing helpers are tested headless with real `JTabbedPane`/`JPanel` (`ScreenshotsTest`); anything needing a display is verified live in Burp, not in CI.
- Cancellation tests (`ApprovalCancellationTest`) block the EDT with a `CountDownLatch`, `mockkObject(Dialogs)`, and verify no dialog is shown after `cancelAndJoin()`.
- No coverage plugin or gate. Do not add one unless asked.

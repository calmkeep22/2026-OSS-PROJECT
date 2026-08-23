# Module architecture

> Implemented baseline: 2026-08-22

This document describes the code that exists now. The A/B interface documents may contain future broker-integration proposals; those proposals do not override the dependency rules below until their public contracts are merged.

## Dependency direction

```text
desktop-javafx
  ├─ application ──> finance-domain
  ├─ mock-trading ─> application
  ├─ fake-adapters ─> application
  ├─ accessibility
  ├─ sonification
  ├─ sonification-java-sound ─> sonification
  └─ ai-insight-http ─> ai-insight-api ─> finance-domain

kiwoom-adapter ──> broker-api ──> finance-domain
       └─────────> application

persistence-sqlite ─> application / finance-domain / anomaly-detection

windows-secret-store ─> file-secret-store ─> secret-store-api
```

Dependencies must point from UI/infrastructure toward ports and pure domain models. `finance-domain`, `application`, `broker-api`, `accessibility`, `sonification`, `ai-insight-api`, and `secret-store-api` must never import JavaFX or a concrete adapter. The `sonification` core also must not import Java Sound; `sonification-java-sound` is the replaceable output adapter. `ai-insight-api` must not import an HTTP client; `ai-insight-http` is the replaceable transport. The root `verifyModuleBoundaries` task enforces these rules.

## Package layout inside modules

Modules that outgrew a single package are split by role, following the `accessibility`
precedent (`port` / `notification` / `infrastructure`).

| Module | Packages |
|---|---|
| `finance-domain` | `market`, `order`, `account`, `orderbook`, plus shared identity types at the root |
| `kiwoom-adapter` | `query`, `mapping`, `config`, `http`, `stream` |
| `sonification` | `playback`, `analysis`, `timing`, `model`, `port` |
| `broker-api` | `auth`, `error`, `resilience` |

`finance-domain` keeps only types every area needs at the root: `SecurityId`, `Exchange`,
`TradingEnvironment`, `PriceDirection`, `OrderSide`. `OrderSide` lives there rather than in
`order` because an execution also has a side, so `market.Trade` needs it — putting it in
`order` would make market data depend on ordering.

The layering inside `finance-domain` is enforced, not merely intended:

```text
market     depends on nothing
order      depends on nothing
account    depends on market
orderbook  depends on market
```

`verifyModuleBoundaries` carries a second rule map keyed by source directory rather than by
module, so a single package can be constrained. Reversing one of these arrows fails the build.

## Canonical contracts

| Concern | Canonical type or port | Removed duplicate |
|---|---|---|
| Order input | `OrderCommand` | `OrderRequest` |
| Order preview | `TradePreview` | `OrderPreview` |
| Order result/lifecycle | `Order` + `OrderLifecyclePort` | `OrderReceipt`, `OrderPort` |
| Account and positions | `Account`, `Balance`, `Position`, `AccountPort` | `PortfolioSnapshot`, `Holding`, `PortfolioPort` |
| Latest stock detail | `StockQueryPort` | history method removed from this port |
| Historical prices | `CandleQueryPort` + `Candle` | duplicate history query |
| Real-time prices | `MarketDataStreamPort` + `Quote` | adapter-specific stream interface |
| Secret storage | `SecretStore` | platform-specific copies of the API |
| AI analysis | `AiInsightPort` + `AiInsight` | screen-side assembly of forecast/anomaly/similarity |
| News and Q&A | `NewsPort` + `NewsDigest`, `ChatAnswer` | folding news into the analysis port |

## Composition rule

`DesktopApplication` must not construct concrete broker, fake, persistence, speech, sound, or sonification adapters. `desktop.composition.DesktopServices` is the single composition root and supplies ports/use cases to the JavaFX layer.

Switching mock/live modes therefore changes composition, not screens or view models.

## Adapter verification

Reusable behavioral contracts live under `modules/application/src/testFixtures`:

- `MarketDataStreamPortContract` is run by fake and Kiwoom stream tests.
- `OrderLifecyclePortContract` is run by the mock-trading engine and must also be run by any future live lifecycle adapter.

An adapter-specific test may add protocol/mapping/retry checks, but cannot replace the common contract.

`SonificationPortContract` lives under `modules/sonification/src/testFixtures` and is run by every
graph-audio output adapter. It verifies volume limits, explicit overflow behavior, lifecycle, and
closed-state rejection. The Java Sound adapter adds device-failure, pitch interpolation, and
queue-saturation tests.

## Sonification output

- `sonification`: pure models, analysis, reduction, time mapping, playback orchestration, and
  `SonificationPort`.
- `sonification-java-sound`: 16kHz PCM Java Sound adapter.

The composition root owns and closes `SonificationPort`. `StreamingGraphSonifier` only borrows the
exclusive port, removes its listener, and stops playback when closed. Asynchronous adapters must
declare a `SonificationOverflowPolicy` and report discarded frames so the UI can provide an
equivalent text state.

## AI analysis

- `ai-insight-api`: contracts only — `AiInsightPort`, `NewsPort`, and the values they carry.
- `ai-insight-http`: calls the Python service over HTTP on loopback.
- `ai-service/server.py`: wraps the Python library. The desktop composition root starts it as a
  child process and stops it on exit.

Analysis and news are **two ports, not one**. News goes out to a third party (Google News RSS),
so it can fail on its own while forecasting and anomaly detection are healthy. A single port would
make a news outage look like a total AI outage, and the two need different timeouts — news is
tens of seconds on a cold fetch, analysis is under a second.

Caveats are computed by the value, not chosen by the screen. `AiInsight.requiredCaveats()`
returns everything that must be said alongside a result: low confidence, a direction forecast that
validation could not distinguish from chance, and the fact that a similar chart is not a forecast.
A screen cannot show the number without the caveat, because it never receives them separately.

`Forecast.meaningful` carries whether validation cleared that specific prediction. Direction is
false in practice — arbitrage erases it — and the value says so rather than the UI remembering to.

## Secret storage

- `secret-store-api`: public platform-independent contract and buffer wiping utilities.
- `file-secret-store`: encrypted atomic file persistence; requires a `SecretCodec`.
- `windows-secret-store`: DPAPI codec and Windows factory.

Protection capabilities use `SecretProtectionLevel`; Windows DPAPI is
`OS_USER_PROTECTED`, not unconditionally hardware-backed. The desktop connection screen depends
only on `SecretStore`, while `DesktopServices` selects the Windows implementation. Unsupported
systems receive a fail-closed unavailable store, never plaintext persistence.

Unsupported operating systems fail closed. A plaintext fallback implementation is intentionally not provided.

## Public surface

Each module README lists supported entry points. Implementation helpers should be package-private where practical. In particular, Kiwoom WebSocket sessions, connectors, protocol parsing, and reconnect scheduling are internal; consumers use `KiwoomMarketDataStream`.

## Required checks

```powershell
./gradlew.bat clean test verifyModuleBoundaries
```

CI runs these checks on Windows and Linux. Java library modules also publish source and Javadoc artifacts to the local build repository through `publishAllPublicationsToLocalBuildRepository`.

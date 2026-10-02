# MB CLOB

Java 27 modular skeleton for a simplified Central Limit Order Book, split into deployable modules for gateway, engine, ledger, and command log concerns.

## Requirements

- Java 27 installed with SDKMAN.
- Gradle wrapper configured for Gradle 9.8.0.
- If Java 27 is not installed locally, Gradle can provision it through the Foojay toolchain resolver.

```bash
sdk install java 27-open
sdk use java 27-open
./gradlew test
```

The first phase intentionally contains only a compilable skeleton. Matching, balance reservation, Kafka adapters, snapshots, and replay will be added in later phases.

## Modules

| Module | Purpose |
|---|---|
| `shared` | Shared contracts and small value objects used by all services. |
| `command-log` | Future command/event log port and Kafka adapter boundary. |
| `engine` | Future matching engine and order book runtime. |
| `ledger` | Future ledger and balance projection runtime. |
| `gateway` | Future external entry point for HTTP/WebSocket/Kafka command publishing. |

## Current Verification

```bash
./gradlew test
```

Local verification requires Java 27. The checked-in wrapper metadata targets Gradle 9.8.0 because that version supports Java 27.

## Local Kafka

```bash
docker compose up -d
./gradlew :engine:runEngine
./gradlew :gateway:runGateway
```

Publish a command through the gateway:

```bash
curl -i -X POST 'http://localhost:8080/commands' \
  -H 'Content-Type: text/plain' \
  --data-binary '8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-1|55=BTC/BRL|54=1|44=50000000|38=100000000|'
```

For local readability the gateway accepts `|` as a field delimiter and normalizes it to the FIX SOH delimiter before publishing to Kafka.

The engine currently consumes `commands`, maps inbound FIX into typed engine commands, validates order intake for known instruments and positive price/quantity, stores accepted orders in an in-memory order book by instrument, accepts cancel requests for open orders, publishes an acceptance or rejection FIX message to `events`, and prints the processing result. Matching and ledger updates are intentionally not implemented yet.

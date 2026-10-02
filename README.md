# MB CLOB

Projeto modular em Java 27 para um Central Limit Order Book simplificado, separado em módulos que podem evoluir para deploy e escala independentes: gateway, engine, ledger e command log.

## Requisitos

- Java 27 instalado com SDKMAN.
- Gradle wrapper configurado para Gradle 9.8.0.
- Se Java 27 não estiver instalado localmente, o Gradle pode provisionar via Foojay toolchain resolver.

```bash
sdk install java 27.0.0-amzn
sdk use java 27.0.0-amzn
rtk ./gradlew test
```

O projeto já contém gateway FIX, adapters Kafka, engine com book em memória/matching básico, funding com idempotência persistida, reserva pré-matching no ledger PostgreSQL, journal Kafka de liquidação, consumer assíncrono de liquidação no ledger, recover do book por replay do `book-journal` e codec Protobuf para snapshots binários do book.

## Módulos

| Módulo | Responsabilidade |
|---|---|
| `shared` | Parsing FIX e pequenos value objects compartilhados. |
| `command-log` | Portas e adapters Kafka para comandos/eventos. |
| `engine` | Intake de ordens FIX, reserva de saldo, book em memória, matching, eventos de execução e journal de liquidação. |
| `ledger` | Contas, saldos `available/locked`, funding, reserva, liberação e consumo assíncrono de liquidação persistidos em PostgreSQL via Spring Data JPA. |
| `gateway` | Entrada HTTP que recebe FIX textual e publica no Kafka. |

## Verificação

```bash
rtk ./gradlew test
```

A verificação local requer Java 27. O wrapper versionado usa Gradle 9.8.0.

Para um passo a passo completo de execução local, testes manuais, inspeção de Kafka/PostgreSQL e troubleshooting, veja [docs/RUNBOOK.md](docs/RUNBOOK.md).

## Local Kafka

```bash
rtk docker compose up -d
rtk ./gradlew :ledger:runLedgerSettlements
rtk ./gradlew :engine:runEngine
rtk ./gradlew :gateway:runGateway
```

O `docker-compose` sobe Kafka e PostgreSQL. O ledger usa por padrão:

```text
MB_LEDGER_JDBC_URL=jdbc:postgresql://localhost:5432/mb
MB_LEDGER_USERNAME=mb
MB_LEDGER_PASSWORD=mb
```

Publicar funding e depois uma ordem pelo gateway:

```bash
curl -i -X POST 'http://localhost:8080/commands' \
  -H 'Content-Type: text/plain' \
  --data-binary '8=FIX.4.4|35=U1|49=gateway|56=engine|1=account-A|11=funding-1|55=BRL|38=5000000000000000|'

curl -i -X POST 'http://localhost:8080/commands' \
  -H 'Content-Type: text/plain' \
  --data-binary '8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-1|55=BTC/BRL|54=1|44=50000000|38=100000000|'
```

Para facilitar testes locais, o gateway aceita `|` como delimitador e normaliza para SOH antes de publicar no Kafka.

O engine consome `commands`, reconstrói o book no startup via replay do tópico `book-journal`, converte FIX inbound em comandos tipados, credita funding `35=U1` de forma idempotente pelo `ClOrdID(11)`, valida instrumentos conhecidos e preço/quantidade positivos, reserva saldo no ledger, mantém um order book em memória por instrumento, executa matching básico com prioridade preço-tempo, persiste mutações aceitas do book no `book-journal`, publica instruções de liquidação FIX-like no tópico `settlements`, aceita cancelamentos de ordens abertas, libera reserva no cancelamento, publica ExecutionReports FIX em `events` e já possui codec Protobuf para persistir snapshots compactos em uma etapa posterior.

O ledger persiste saldos disponíveis/bloqueados em PostgreSQL. Funding, reserva pré-matching e cancelamento já estão conectados ao engine; o consumidor `runLedgerSettlements` aplica `U2/U3` do tópico `settlements` idempotentemente.

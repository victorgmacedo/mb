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

O projeto já contém gateway FIX, adapters Kafka, engine com book em memória/matching básico, ledger em memória e reserva pré-matching. Liquidação dos trades no ledger, snapshots e replay ainda serão adicionados em fases posteriores.

## Módulos

| Módulo | Responsabilidade |
|---|---|
| `shared` | Parsing FIX e pequenos value objects compartilhados. |
| `command-log` | Portas e adapters Kafka para comandos/eventos. |
| `engine` | Intake de ordens FIX, reserva de saldo, book em memória, matching e eventos de execução. |
| `ledger` | Contas, saldos `available/locked`, reserva, liberação e liquidação em memória. |
| `gateway` | Entrada HTTP que recebe FIX textual e publica no Kafka. |

## Verificação

```bash
rtk ./gradlew test
```

A verificação local requer Java 27. O wrapper versionado usa Gradle 9.8.0.

## Local Kafka

```bash
rtk docker compose up -d
rtk ./gradlew :engine:runEngine
rtk ./gradlew :gateway:runGateway
```

Publicar um comando pelo gateway:

```bash
curl -i -X POST 'http://localhost:8080/commands' \
  -H 'Content-Type: text/plain' \
  --data-binary '8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-1|55=BTC/BRL|54=1|44=50000000|38=100000000|'
```

Enquanto comandos de funding não existirem, o engine iniciado localmente começa com ledger vazio e rejeita novas ordens por saldo insuficiente.

Para facilitar testes locais, o gateway aceita `|` como delimitador e normaliza para SOH antes de publicar no Kafka.

O engine consome `commands`, converte FIX inbound em comandos tipados, valida instrumentos conhecidos e preço/quantidade positivos, reserva saldo no ledger, mantém um order book em memória por instrumento, executa matching básico com prioridade preço-tempo, aceita cancelamentos de ordens abertas, libera reserva no cancelamento e publica ExecutionReports FIX em `events`.

O ledger já possui domínio em memória para saldos disponíveis/bloqueados e liquidação de trades. A reserva pré-matching já está conectada ao engine; liquidação dos fills e comandos de funding ainda estão pendentes.

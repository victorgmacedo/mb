# Memória do Projeto

Este projeto é uma implementação modular Java 27 de um CLOB sob o pacote `br.com.mb`.

Sempre execute comandos de shell via `rtk` neste workspace, por exemplo:

```bash
rtk ./gradlew clean test
rtk git status --short
```

## Arquitetura Atual

Módulos:

- `shared`: parsing FIX e pequenos value objects compartilhados.
- `command-log`: adapters Kafka para produzir e consumir comandos/eventos.
- `gateway`: entrada HTTP que aceita FIX textual e publica FIX normalizado no Kafka `commands`.
- `engine`: recupera book via `book-journal`, consome comandos FIX, credita funding, valida intake, reserva saldo no ledger, mantém order books em memória, executa matching básico, publica journal de liquidação em Kafka, publica eventos FIX e possui codec Protobuf para snapshots binários.
- `ledger`: domínio de saldos com `available`, `locked`, reserva, liberação e consumo assíncrono de liquidação persistido em PostgreSQL via Spring Data JPA.

O pacote base padrão é `br.com.mb`.

## Fluxo em Execução

```mermaid
flowchart LR
    Client --> Gateway
    Gateway --> Commands[Kafka commands]
    Commands --> Engine
    Engine --> Postgres[(PostgreSQL ledger)]
    Engine --> Events[Kafka events]
    Engine --> Settlements[Kafka settlements]
    Engine --> BookJournal[Kafka book-journal]
```

## Comportamento Atual do Engine

- Suporta FIX inbound `35=D` NewOrderSingle, `35=F` OrderCancelRequest e `35=U1` FundingCredit interno.
- Mantém um `OrderBook` em memória por instrumento.
- Instrumentos conhecidos atualmente: `BTC/BRL`, `ETH/BRL`, `ETH/BTC`.
- O catálogo do engine mapeia instrumento para base/cotação.
- Antes do matching, compra reserva `price * quantity` no ativo de cotação e venda reserva `quantity` no ativo base.
- Cancelamento aceito libera a reserva restante da ordem aberta.
- Funding `35=U1` credita saldo disponível usando `Account(1)`, `ClOrdID(11)`, `Asset(55)` e `Amount(38)`.
- Funding duplicado com o mesmo `ClOrdID(11)` não duplica crédito, pois o ledger persiste o comando processado em PostgreSQL.
- Funding duplicado com mesmo `ClOrdID(11)` e payload divergente é rejeitado.
- Usa prioridade preço-tempo.
- Usa preço do maker para trades.
- Persiste mutações aceitas do book no tópico `book-journal`: `35=U4` para ordem aceita e `35=U5` para cancelamento aceito.
- Cada ordem aceita recebe `entrySequence` monotônico, publicado em `U4` como tag interna `10003`.
- Cada ordem aceita recebe `enteredAt` em ISO-8601/UTC, publicado em `U4` como tag interna `10004` para auditoria.
- No startup, o engine reconstrói `EngineState` por replay de `book-journal`.
- Publica cada trade no tópico `settlements` como FIX-like `35=U2`.
- Compra taker executada abaixo do preço limite publica liberação de price improvement como FIX-like `35=U3`.
- Publica FIX `ExecutionReport(35=8)` para ordens aceitas/descansando, fills e cancelamentos.
- Publica FIX `BusinessMessageReject(35=j)` para rejeições de validação/negócio.
- Persiste checkpoints Protobuf em PostgreSQL (`bytea`), com offsets por partição, schema, checksum e timestamp; restaura o último válido antes do replay incremental.
- `runBookSnapshots` gera checkpoints periódicos em processo separado a partir do journal, fora do matching; suporta `--args=--once`.
- Ainda não implementa prevenção de self-trade.

## Comportamento Atual do Ledger

- O runtime padrão usa `PostgresLedgerFactory` e `JpaLedger`.
- Persistência em PostgreSQL na tabela `ledger_balances`.
- Usa Spring Data JPA 4.1.x via Spring Boot BOM 4.1.1.
- Configuração padrão local: `jdbc:postgresql://localhost:5432/mb`, usuário `mb`, senha `mb`.
- Variáveis suportadas: `MB_LEDGER_JDBC_URL`, `MB_LEDGER_USERNAME`, `MB_LEDGER_PASSWORD`, `MB_LEDGER_HBM2DDL_AUTO`, `MB_LEDGER_SHOW_SQL`.
- Mantém `AssetBalance` por conta/ativo com buckets `available` e `locked`.
- `credit` aumenta saldo disponível.
- `reserve` move saldo disponível para bloqueado.
- `release` devolve saldo bloqueado para disponível.
- `settle` liquida trades consumindo saldos bloqueados conforme o lado do maker.
- O ledger valida todos os saldos bloqueados necessários antes de mutar contas na liquidação.
- `LedgerSettlementApplication` consome `settlements` e aplica `U2/U3` idempotentemente usando `ExecID(17)`.

## Comandos Importantes

```bash
rtk ./gradlew clean test
rtk docker compose config
rtk docker compose up -d
rtk ./gradlew :ledger:runLedgerSettlements
rtk ./gradlew :engine:runEngine
rtk ./gradlew :engine:runBookSnapshots
rtk ./gradlew :gateway:runGateway
```

## Estilo de Commit Usado Até Aqui

Manter commits pequenos e temáticos. Exemplos existentes:

- `feat: add shared FIX message parser`
- `feat: add Kafka command log adapters`
- `feat: publish FIX commands through gateway`
- `feat: consume FIX commands in engine`
- `feat: validate engine order intake`
- `feat: store accepted orders in memory book`
- `feat: match limit orders in memory book`

## Pendências Mapeadas

- snapshots reais do book:
  - definir compactação/retenção segura do `book-journal`
  - definir limpeza de checkpoints antigos e persistir identidade/epoch do tópico para detectar recriação
- consistência engine/ledger:
  - reconciliar liquidações rejeitadas pelo consumidor
  - criar persistência de eventos/instruções de liquidação com status
  - definir política de retry, DLQ e compensação
  - verificar consistência entre book recuperado e saldos `locked`
- matching mais completo:
  - prevenção de self-trade
  - avaliar suporte a market, IOC, FOK e post-only
  - melhorar controle de status parcial/final
  - garantir execução determinística por instrumento/partição
- gateway FIX mais robusto:
  - validação FIX mais próxima do protocolo
  - session handling se evoluir para FIX real, incluindo logon/logout, heartbeat, sequência e resend
  - autenticação/autorização de contas
- escala por instrumento:
  - definir particionamento Kafka por instrumento
  - garantir ownership de instrumento por pod
  - implementar rebalance/recover quando um pod cai
  - impedir que dois engines processem o mesmo instrumento ao mesmo tempo
- persistência operacional:
  - migrations com Flyway ou Liquibase
  - schema explícito para ledger e snapshots
  - índices, constraints e versionamento
  - separar configurações dev/prod
- observabilidade:
  - logs estruturados
  - métricas de latência de matching, profundidade do book, lag Kafka, rejeições e settlements pendentes
  - health checks
  - tracing entre gateway, engine e ledger
- testes de integração:
  - Testcontainers para Kafka/PostgreSQL
  - teste end-to-end de gateway -> commands -> engine -> settlements/events -> ledger
  - teste de recovery com replay
  - teste de idempotência com mensagens duplicadas
  - teste de concorrência no ledger
- hardening:
  - graceful shutdown
  - backpressure
  - retry Kafka
  - DLQ para mensagens inválidas
  - segurança de secrets
  - Dockerfiles por módulo
  - pipeline CI

## Próximo Trabalho Provável

A próxima implementação recomendada é melhorar a consistência engine/ledger: persistir o status das instruções de liquidação e definir retry/reconciliação de settlements rejeitados. Snapshots e recovery incremental já estão implementados; configuração e limites estão em `docs/BOOK-SNAPSHOTS.md`.

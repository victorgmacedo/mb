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
- `engine`: consome comandos FIX, credita funding, valida intake, reserva saldo no ledger, mantém order books em memória, executa matching básico e publica eventos FIX.
- `ledger`: domínio inicial de saldos em memória com `available`, `locked`, reserva, liberação e liquidação.

O pacote base padrão é `br.com.mb`.

## Fluxo em Execução

```mermaid
flowchart LR
    Client --> Gateway
    Gateway --> Commands[Kafka commands]
    Commands --> Engine
    Engine --> Events[Kafka events]
```

## Comportamento Atual do Engine

- Suporta FIX inbound `35=D` NewOrderSingle, `35=F` OrderCancelRequest e `35=U1` FundingCredit interno.
- Mantém um `OrderBook` em memória por instrumento.
- Instrumentos conhecidos atualmente: `BTC/BRL`, `ETH/BRL`, `ETH/BTC`.
- O catálogo do engine mapeia instrumento para base/cotação.
- Antes do matching, compra reserva `price * quantity` no ativo de cotação e venda reserva `quantity` no ativo base.
- Cancelamento aceito libera a reserva restante da ordem aberta.
- Funding `35=U1` credita saldo disponível usando `Account(1)`, `ClOrdID(11)`, `Asset(55)` e `Amount(38)`.
- Usa prioridade preço-tempo.
- Usa preço do maker para trades.
- Publica FIX `ExecutionReport(35=8)` para ordens aceitas/descansando, fills e cancelamentos.
- Publica FIX `BusinessMessageReject(35=j)` para rejeições de validação/negócio.
- Ainda não liquida trades no ledger. Também não implementa idempotência de funding, prevenção de self-trade, snapshots ou replay.

## Comportamento Atual do Ledger

- Mantém `LedgerAccount` por conta.
- Mantém `AssetBalance` por ativo com buckets `available` e `locked`.
- `credit` aumenta saldo disponível.
- `reserve` move saldo disponível para bloqueado.
- `release` devolve saldo bloqueado para disponível.
- `settle` liquida trades consumindo saldos bloqueados conforme o lado do maker.
- O ledger valida todos os saldos bloqueados necessários antes de mutar contas na liquidação.

## Comandos Importantes

```bash
rtk ./gradlew clean test
rtk docker compose config
rtk docker compose up -d
rtk ./gradlew :engine:runEngine
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

- idempotência de funding por `ClOrdID(11)`
- liquidação dos trades no ledger após matching
- liberação de sobras do taker após fills
- liberação de diferença de quote por price improvement em compras
- teste de conservação de ativos atravessando engine e ledger

## Próximo Trabalho Provável

A próxima fase grande deve concluir a integração de liquidação entre engine e ledger:

- liquidação após trades
- liberação de sobras e diferenças por price improvement
- idempotência de funding por `ClOrdID(11)`

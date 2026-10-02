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
- `engine`: consome comandos FIX, valida intake, mantém order books em memória, executa matching básico e publica eventos FIX.
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

- Suporta FIX inbound `35=D` NewOrderSingle e `35=F` OrderCancelRequest.
- Mantém um `OrderBook` em memória por instrumento.
- Instrumentos conhecidos atualmente: `BTC/BRL`, `ETH/BRL`, `ETH/BTC`.
- Usa prioridade preço-tempo.
- Usa preço do maker para trades.
- Publica FIX `ExecutionReport(35=8)` para ordens aceitas/descansando, fills e cancelamentos.
- Publica FIX `BusinessMessageReject(35=j)` para rejeições de validação/negócio.
- Ainda não integra saldo/ledger ao fluxo do engine. Também não implementa prevenção de self-trade, snapshots ou replay.

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

## Próximo Trabalho Provável

A próxima fase grande deve integrar ledger/reserva de saldo ao engine:

- reserva de quote para compras e base para vendas antes do matching
- liquidação após trades
- liberação de sobras e diferenças por price improvement
- teste de conservação de ativos atravessando engine e ledger

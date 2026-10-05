@/Users/victor/.codex/RTK.md

# Memória do projeto

CLOB simplificado Java 25 sob br.com.mb. Sempre prefixe comandos shell com rtk.

## Arquitetura atual

Módulos Gradle: shared (Asset/FIX/HTTP), command-log (Kafka), engine (book, estratégias, consultas), ledger (saldos em memória), gateway (HTTP e proxy).

Dois processos: gateway e engine. Fluxo gateway -> Kafka commands -> engine -> Kafka events. Consultas de book/saldo vão do gateway para o engine HTTP. Ledger pertence ao mesmo engine, com liquidação síncrona. Uma partição de commands e um engine ativo.

Books ficam em memória entre comandos. OrderBook calcula PlacementPlan sem mutar makers; BalanceReservations monta BalanceChanges; InMemoryLedger valida somente saldos afetados antes de aplicar o lote; EngineState efetiva o plano. O monitor do handler serializa comandos/consultas. Nenhuma reconstrução/cópia de todos os books por comando.

Strategy por D/F/U1/U6, prioridade preço-tempo, preço do maker, reserva available/locked, cancelamento libera restante, STP rejeita ordem integral. Unidades long positivas, sem escala implícita, com overflow financeiro verificado. Catálogo BTC/BRL, ETH/BRL, ETH/BTC.

Reenvios são deduplicados por MsgType:ClOrdID apenas na sessão. Queda de publicação pode repetir eventos. Reinício apaga saldo/book/cache, usa grupo Kafka novo e posição no fim do tópico antes da prontidão HTTP. Comandos enviados durante indisponibilidade podem não pertencer à sessão nova. Não há failover, fencing, snapshots, replay, PostgreSQL, jOOQ, Protobuf, JPMS ou build nativo.

## Comandos

```bash
./gradlew clean test
docker compose up -d kafka kafka-init
./gradlew :engine:runEngine
./gradlew :gateway:runGateway
python3 scripts/verify-exercise.py
```

Documentos atuais em README.md e docs/. Preserve commits pequenos e temáticos (feat/refactor/test/docs). Não reintroduza infraestrutura distribuída sem requisito explícito. Escopo é o exercício técnico, com Kafka mantido por escolha do usuário.

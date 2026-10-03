# CLOB — livro de ofertas e matching

Implementação modular em Java 25 de um CLOB simplificado para ordens limitadas. Cobre inserção, cancelamento, matching e transferência de ativos, além de crédito, débito e consultas de saldo e book por HTTP.

O projeto usa biblioteca padrão para HTTP, concorrência e JDBC. Kafka mantém os logs; PostgreSQL persiste saldos, idempotência e checkpoints; Protobuf codifica snapshots. Não usa Spring, JPA ou Hibernate.

## Compilar e testar

Pré-requisitos: JDK 25, Docker com Compose e, para a demonstração automatizada, Python 3. O Gradle Wrapper está versionado; a toolchain também pode ser provisionada pelo resolver Foojay.

```bash
./gradlew clean test
docker compose up -d
```

Os testes de integração PostgreSQL usam schemas temporários próprios:

```bash
MB_LEDGER_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/mb \
MB_SNAPSHOT_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/mb \
./gradlew test --rerun-tasks
```

## Verificar todas as operações

Com Kafka e PostgreSQL saudáveis, execute:

```bash
python3 scripts/verify-exercise.py
```

O script cria tópicos e schema exclusivos, inicia os serviços em portas disponíveis, verifica crédito/débito idempotentes, o exemplo de 1 BTC por 500 mil BRL, saldo reservado, matching parcial, preço do maker, cancelamento, rejeições e recovery de snapshot. Remove seus serviços e dados ao terminar. Os logs ficam em `build/verify_*`.

## Executar os serviços manualmente

Após `docker compose up -d`, use um terminal para cada processo:

```bash
./gradlew :ledger:runLedgerSettlements
./gradlew :engine:runEngine
./gradlew :gateway:runGateway
```

O gateway escuta em `http://localhost:8080`. O engine serve a visão interna do book em `127.0.0.1:8081`. Snapshots periódicos são opcionais:

```bash
./gradlew :engine:runBookSnapshots
# Ou apenas um checkpoint:
./gradlew :engine:runBookSnapshots --args=--once
```

## Operações HTTP

`POST /commands` recebe FIX textual, aceitando `|` ou SOH como delimitador. HTTP 202 confirma que o comando foi publicado no Kafka; o engine responde em `events` com `35=8` para sucesso ou `35=j` para rejeição. A liquidação de trades é assíncrona: consulte até observar o resultado esperado.

Use identificadores novos para ordens e cancelamentos. Crédito e débito são idempotentes por tipo e `ClOrdID(11)`; reutilizar o identificador com payload diferente é rejeitado.

```bash
# Crédito: comprador A recebe BRL; vendedor B recebe BTC.
curl -i http://localhost:8080/commands -H 'Content-Type: text/plain' \
  --data '8=FIX.4.4|35=U1|49=gateway|1=A|11=fund-A|55=BRL|38=600000|'
curl -i http://localhost:8080/commands -H 'Content-Type: text/plain' \
  --data '8=FIX.4.4|35=U1|49=gateway|1=B|11=fund-B|55=BTC|38=1|'

# Venda limitada e compra: 1 BTC por 500 mil BRL.
curl -i http://localhost:8080/commands -H 'Content-Type: text/plain' \
  --data '8=FIX.4.4|35=D|49=gateway|1=B|11=sell-1|55=BTC/BRL|54=2|44=500000|38=1|'
curl -i http://localhost:8080/commands -H 'Content-Type: text/plain' \
  --data '8=FIX.4.4|35=D|49=gateway|1=A|11=buy-1|55=BTC/BRL|54=1|44=500000|38=1|'

# Débito do saldo disponível; nunca utiliza saldo bloqueado.
curl -i http://localhost:8080/commands -H 'Content-Type: text/plain' \
  --data '8=FIX.4.4|35=U6|49=gateway|1=A|11=debit-A|55=BRL|38=50000|'

# Ordem sem cruzamento e cancelamento pela mesma conta.
curl -i http://localhost:8080/commands -H 'Content-Type: text/plain' \
  --data '8=FIX.4.4|35=D|49=gateway|1=A|11=resting-A|55=BTC/BRL|54=1|44=100|38=1|'
curl -i http://localhost:8080/commands -H 'Content-Type: text/plain' \
  --data '8=FIX.4.4|35=F|49=gateway|1=A|11=cancel-A|55=BTC/BRL|41=resting-A|'

# Consultas: disponível, bloqueado, total e níveis/ordens do book.
curl 'http://localhost:8080/accounts/A/balances?asset=BRL'
curl 'http://localhost:8080/accounts/A/balances?asset=BTC'
curl 'http://localhost:8080/books?instrument=BTC%2FBRL'
```

Após essas operações e a liquidação, A tem 1 BTC e 50000 BRL disponíveis; B tem 500000 BRL e nenhum BTC. O book fica vazio e não restam reservas.

Saldo de conta/ativo sem registro retorna zero. Instrumento conhecido sem ordens retorna listas vazias; desconhecido retorna 404. Consultas malformadas retornam 400, métodos incompatíveis 405 e indisponibilidade de dependências 503.

## Docker e executáveis nativos

Há uma imagem nativa por processo: gateway, engine, settlements e snapshots. `shared` e `command-log` são bibliotecas incluídas nos executáveis. As imagens finais não incluem JVM.

```bash
docker build --target gateway -t mb-gateway:native .
docker build --target engine -t mb-engine:native .
docker build --target settlements -t mb-settlements:native .
docker build --target snapshots -t mb-snapshots:native .
docker compose -f docker-compose.yml -f compose.native.yml up -d
```

Para verificar os executáveis nativos com os mesmos cenários e dados isolados:

```bash
python3 scripts/verify-exercise.py --mode native
```

Build e configuração em [docs/NATIVE.md](docs/NATIVE.md).

## Decisões e limites

- Instrumentos: `BTC/BRL`, `ETH/BRL`, `ETH/BTC`; apenas ordens limitadas.
- Preço e quantidade usam `long` positivo, sem ponto flutuante. Os exemplos usam unidades inteiras de BTC e BRL. Não existe escala decimal implícita; uma integração fracionária precisa definir unidades e escala explicitamente.
- Matching usa prioridade preço-tempo e preço do maker. A quantidade executada sai de ambas as ordens; a parte restante pode descansar no book.
- Compras reservam preço-limite × quantidade na cotação; vendas reservam quantidade no ativo base. Cancelamentos liberam o restante e compras com melhoria de preço recebem a diferença.
- Uma ordem que alcançaria contraparte da mesma conta é rejeitada integralmente antes de reservar saldo ou executar trades.
- O ambiente demonstrativo usa uma partição e um engine ativo. Replicação e fencing não estão implementados.
- As transações JDBC garantem atomicidade no ledger. Book, Kafka e ledger não formam uma transação distribuída; reconciliação de falhas entre esses componentes continua sendo evolução de produção.

Arquitetura em [docs/DESIGN.md](docs/DESIGN.md), operação em [docs/RUNBOOK.md](docs/RUNBOOK.md), checkpoints em [docs/BOOK-SNAPSHOTS.md](docs/BOOK-SNAPSHOTS.md) e responsabilidades de todas as classes de produção em [docs/CLASS-FLOWS.md](docs/CLASS-FLOWS.md). Documentos em `docs/superpowers` registram decisões históricas; não substituem estas instruções atuais.

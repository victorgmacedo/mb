.DEFAULT_GOAL := help
.PHONY: help run build kafka stop test verify clean engine gateway

help: ## Lista os comandos disponíveis
	@rtk proxy python3 -c 'from pathlib import Path; [print(line.split("##", 1)[0].split(":", 1)[0].ljust(12) + line.split("##", 1)[1].strip()) for line in Path("Makefile").read_text().splitlines() if "##" in line and not line.startswith("\t")]'

run: build kafka ## Sobe o projeto; Ctrl+C encerra engine e gateway
	rtk proxy python3 scripts/run-local.py

build: ## Compila e prepara os classpaths de execução
	rtk ./gradlew :engine:writeRuntimeClasspath :gateway:writeRuntimeClasspath

kafka: ## Inicia Kafka e cria os tópicos
	rtk docker compose up -d --wait kafka
	rtk docker compose run --rm kafka-init

stop: ## Para Kafka (encerre make run com Ctrl+C primeiro)
	rtk docker compose stop kafka

test: ## Executa os testes Java
	rtk ./gradlew test

verify: kafka ## Executa a verificação ponta a ponta em uma sessão isolada
	rtk proxy python3 scripts/verify-exercise.py

clean: ## Remove os artefatos de build
	rtk ./gradlew clean

engine: ## Executa só o engine em primeiro plano
	rtk ./gradlew :engine:runEngine

gateway: ## Executa só o gateway em primeiro plano
	rtk ./gradlew :gateway:runGateway

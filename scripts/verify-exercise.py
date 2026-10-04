#!/usr/bin/env python3
"""Verify every exercise operation against isolated Kafka topics and a PostgreSQL schema.
Requires the infrastructure from docker-compose.yml. Uses only Python's standard library.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import threading
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
KAFKA = "/opt/kafka/bin/"


def run(*args, check=True, timeout=120):
    return subprocess.run(args, cwd=ROOT, check=check, timeout=timeout, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["jvm", "native"], default="jvm")
    parser.add_argument("--measure-memory", action="store_true", help="Sample running services during the exercise flow")
    args = parser.parse_args()
    token = "verify_" + uuid.uuid4().hex[:12]
    logs = ROOT / "build" / token
    logs.mkdir(parents=True)
    schema = token
    topics = {name: token + "-" + name for name in ["commands", "events", "settlements", "book-journal"]}
    processes = {}
    containers = []
    handles = []
    created_topics = []
    created_schema = False
    memory_stop = threading.Event()
    memory_samples = []
    memory_errors = []
    memory_thread = None
    memory_started = None
    gateway_port, engine_port = free_port(), free_port()
    env = os.environ.copy()
    env.update({"MB_LEDGER_JDBC_URL": "jdbc:postgresql://localhost:5432/mb?currentSchema=" + schema,
                "MB_LEDGER_USERNAME": "mb", "MB_LEDGER_PASSWORD": "mb", "LOG_LEVEL": "WARN",
                "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092", "ENGINE_CONSUMER_GROUP_ID": token,
                "LEDGER_SETTLEMENT_CONSUMER_GROUP_ID": token + "-settlements",
                "GATEWAY_HOST": "127.0.0.1", "GATEWAY_PORT": str(gateway_port),
                "ENGINE_BOOK_PORT": str(engine_port), "ENGINE_BOOK_HOST": "127.0.0.1",
                "ENGINE_BOOK_URL": "http://127.0.0.1:" + str(engine_port)})
    for name, topic in topics.items():
        env["KAFKA_" + name.upper().replace("-", "_") + "_TOPIC"] = topic
    classes = {"gateway": "br.com.mb.gateway.GatewayApplication", "engine": "br.com.mb.engine.EngineApplication",
               "settlements": "br.com.mb.ledger.LedgerSettlementApplication",
               "snapshots": "br.com.mb.engine.BookSnapshotsApplication"}
    modules = {"gateway": "gateway", "engine": "engine", "snapshots": "engine", "settlements": "ledger"}
    network = None

    def sql(command):
        return run("docker", "exec", "mb-postgres", "psql", "-U", "mb", "-d", "mb",
                   "-v", "ON_ERROR_STOP=1", "-c", command)

    def stop(role):
        if args.mode == "native":
            name = token.replace("_", "-") + "-" + role
            if name in containers:
                run("docker", "stop", "-t", "15", name)
                (logs / (role + ".log")).write_text(run("docker", "logs", name).stdout)
                run("docker", "rm", name)
                containers.remove(name)
        elif role in processes:
            process = processes.pop(role)
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)

    def start(role, once=False):
        if args.mode == "native":
            container_env = env.copy()
            container_env.update({"KAFKA_BOOTSTRAP_SERVERS": "kafka:19092",
                                  "MB_LEDGER_JDBC_URL": "jdbc:postgresql://postgres:5432/mb?currentSchema=" + schema,
                                  "GATEWAY_HOST": "0.0.0.0", "GATEWAY_PORT": "8080",
                                  "ENGINE_BOOK_HOST": "0.0.0.0", "ENGINE_BOOK_PORT": "8081",
                                  "ENGINE_BOOK_URL": "http://" + token.replace("_", "-") + "-engine:8081"})
            command = ["docker", "run", "--network", network]
            name = token.replace("_", "-") + "-" + role
            command += ["--rm"] if once else ["--detach", "--name", name]
            if role == "gateway": command += ["-p", "127.0.0.1:" + str(gateway_port) + ":8080"]
            for key, value in container_env.items():
                if key.startswith(("KAFKA_", "MB_LEDGER_", "GATEWAY_", "ENGINE_", "LEDGER_SETTLEMENT_")) or key == "LOG_LEVEL":
                    command += ["-e", key + "=" + value]
            command += ["mb-" + role + ":native"]
            if once: command += ["--once"]
            output = run(*command, timeout=120)
            if once: (logs / "snapshots.log").write_text(output.stdout)
            else: containers.append(name)
        else:
            classpath = (ROOT / modules[role] / "build/runtime-classpath.txt").read_text()
            java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
            command = [java, "-cp", classpath, classes[role]] + (["--once"] if once else [])
            handle = (logs / (role + ".log")).open("a")
            handles.append(handle)
            if once:
                subprocess.run(command, cwd=ROOT, env=env, stdout=handle, stderr=subprocess.STDOUT, check=True, timeout=120)
            else:
                processes[role] = subprocess.Popen(command, cwd=ROOT, env=env, stdout=handle,
                                                   stderr=subprocess.STDOUT, start_new_session=True)

    def sample_memory():
        values = {}
        if args.mode == "jvm":
            for role, process in processes.copy().items():
                result = run("ps", "-o", "rss=", "-p", str(process.pid), check=False)
                if result.returncode == 0 and result.stdout.strip():
                    values[role] = int(result.stdout.strip()) / 1024
        else:
            names = containers.copy()
            if names:
                result = run("docker", "stats", "--no-stream", "--format", "{{json .}}", *names, check=False, timeout=15)
                for line in result.stdout.splitlines():
                    if not line.startswith("{"): continue
                    row = json.loads(line)
                    size = row["MemUsage"].split("/")[0].strip()
                    match = re.fullmatch(r"([0-9.]+)([A-Za-z]+)", size)
                    if match:
                        factor = {"B": 1 / 1048576, "KiB": 1 / 1024, "MiB": 1, "GiB": 1024}[match[2]]
                        values[row["Name"].rsplit("-", 1)[-1]] = float(match[1]) * factor
        if values:
            memory_samples.append({"seconds": round(time.monotonic() - memory_started, 2),
                                   "mib": values, "total_mib": sum(values.values())})

    def monitor_memory():
        while not memory_stop.is_set():
            try: sample_memory()
            except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as exception:
                memory_errors.append(str(exception))
            memory_stop.wait(1)

    def save_memory():
        memory_stop.set()
        if memory_thread is not None: memory_thread.join(timeout=20)
        if memory_samples:
            roles = ["gateway", "engine", "settlements"]
            report = {"mode": args.mode, "workload": "exercise flow; three persistent services; snapshots run once separately",
                      "metric": "process RSS" if args.mode == "jvm" else "Docker memory usage excluding cache",
                      "peak_mib": {role: round(max(sample["mib"].get(role, 0) for sample in memory_samples), 2) for role in roles},
                      "peak_simultaneous_total_mib": round(max(sample["total_mib"] for sample in memory_samples), 2),
                      "last_complete_sample": next((sample for sample in reversed(memory_samples) if all(role in sample["mib"] for role in roles)), None),
                      "samples": memory_samples, "sampling_errors": memory_errors}
            (logs / "runtime-memory.json").write_text(json.dumps(report, indent=2))
            print("Runtime memory peaks (MiB): " + json.dumps(report["peak_mib"]))

    base = "http://127.0.0.1:" + str(gateway_port)

    def get(path):
        with urllib.request.urlopen(base + path, timeout=5) as response:
            return json.load(response)

    def wait_for(description, predicate, seconds=40):
        deadline = time.monotonic() + seconds
        last = None
        while time.monotonic() < deadline:
            try:
                if predicate(): return
            except (OSError, ValueError) as exc:
                last = exc
            for role, process in processes.items():
                if process.poll() is not None: raise RuntimeError(role + " exited; see " + str(logs))
            time.sleep(0.2)
        raise AssertionError("Timeout: " + description + "; last error=" + str(last) + "; logs=" + str(logs))

    def send(kind, id, account, fields=""):
        body = "8=FIX.4.4|35=" + kind + "|49=gateway|1=" + account + "|11=" + id + "|" + fields
        request = urllib.request.Request(base + "/commands", data=body.encode(), headers={"Content-Type": "text/plain"})
        with urllib.request.urlopen(request, timeout=5) as response:
            assert response.status == 202

    def balance(account, asset):
        return get("/accounts/" + account + "/balances?asset=" + asset)

    def expect_balance(account, asset, available, locked=0):
        wait_for("balance " + account + "/" + asset,
                 lambda: balance(account, asset) == {"accountId": account, "asset": asset,
                     "available": available, "locked": locked, "total": available + locked})

    def book():
        return get("/books?instrument=BTC%2FBRL")

    def orders():
        return [order for side in ["bids", "asks"] for level in book()[side] for order in level["orders"]]

    try:
        if args.mode == "jvm":
            result = run(str(ROOT / "gradlew"), ":gateway:writeRuntimeClasspath", ":engine:writeRuntimeClasspath",
                         ":ledger:writeRuntimeClasspath", timeout=240)
            (logs / "build.log").write_text(result.stdout)
        else:
            network = next(iter(json.loads(run("docker", "inspect", "mb-postgres").stdout)[0]["NetworkSettings"]["Networks"]))
        sql("CREATE SCHEMA " + schema)
        created_schema = True
        for topic in topics.values():
            run("docker", "exec", "mb-kafka", KAFKA + "kafka-topics.sh", "--bootstrap-server", "localhost:9092",
                "--create", "--topic", topic, "--partitions", "1", "--replication-factor", "1")
            created_topics.append(topic)
        for role in ["settlements", "engine", "gateway"]: start(role)
        wait_for("gateway and engine readiness", lambda: book()["instrument"] == "BTC/BRL", seconds=90)
        expect_balance("unknown", "BRL", 0)
        if args.measure_memory:
            memory_started = time.monotonic()
            memory_thread = threading.Thread(target=monitor_memory, daemon=True)
            memory_thread.start()
            time.sleep(10)
        send("U1", "fund-buyer", "A", "55=BRL|38=600000|")
        send("U1", "fund-seller", "B", "55=BTC|38=1|")
        expect_balance("A", "BRL", 600000)
        expect_balance("B", "BTC", 1)
        send("U1", "fund-buyer", "A", "55=BRL|38=600000|")
        send("D", "sell", "B", "55=BTC/BRL|54=2|44=500000|38=1|")
        wait_for("resting sell", lambda: any(o["clientOrderId"] == "sell" for o in orders()))
        expect_balance("B", "BTC", 0, 1)
        send("D", "buy", "A", "55=BTC/BRL|54=1|44=500000|38=1|")
        expect_balance("A", "BTC", 1)
        expect_balance("B", "BRL", 500000)
        expect_balance("A", "BRL", 100000)
        expect_balance("B", "BTC", 0)
        wait_for("fully filled book", lambda: not orders())
        print("PASS: credit, duplicate credit, insertion, matching, quantity removal and asset transfer")
        send("U6", "debit", "A", "55=BRL|38=50000|")
        expect_balance("A", "BRL", 50000)
        send("U6", "debit", "A", "55=BRL|38=50000|")
        send("U6", "debit", "A", "55=BRL|38=50001|")
        send("U6", "insufficient", "A", "55=BRL|38=50001|")
        send("D", "resting-buy", "A", "55=BTC/BRL|54=1|44=100|38=10|")
        expect_balance("A", "BRL", 49000, 1000)
        send("F", "foreign-cancel", "B", "55=BTC/BRL|41=resting-buy|")
        send("D", "own-sell", "A", "55=BTC/BRL|54=2|44=100|38=1|")
        # A valid command after rejections provides an observable processing barrier.
        send("U6", "barrier", "A", "55=BRL|38=1|")
        expect_balance("A", "BRL", 48999, 1000)
        assert [o["clientOrderId"] for o in orders()] == ["resting-buy"]
        start("snapshots", once=True)
        stop("engine")
        start("engine")
        wait_for("durable book recovery", lambda: [o["clientOrderId"] for o in orders()] == ["resting-buy"], seconds=90)
        send("F", "cancel", "A", "55=BTC/BRL|41=resting-buy|")
        expect_balance("A", "BRL", 49999)
        wait_for("cancelled book", lambda: not orders())
        print("PASS: debit, deduplication, insufficient funds, ownership, self-trade, durable book recovery and cancellation")
        send("U1", "partial-fund", "B", "55=BTC|38=4|")
        expect_balance("B", "BTC", 4)
        send("D", "partial-sell", "B", "55=BTC/BRL|54=2|44=100|38=4|")
        wait_for("partial maker", lambda: any(o["clientOrderId"] == "partial-sell" for o in orders()))
        send("D", "partial-buy", "A", "55=BTC/BRL|54=1|44=110|38=10|")
        expect_balance("A", "BTC", 5)
        expect_balance("B", "BRL", 500400)
        expect_balance("A", "BRL", 48939, 660)
        wait_for("remaining quantity", lambda: len(orders()) == 1 and orders()[0]["remainingQuantity"] == 6)
        send("F", "partial-cancel", "A", "55=BTC/BRL|41=partial-buy|")
        expect_balance("A", "BRL", 49599)
        wait_for("empty book", lambda: not orders())
        print("PASS: partial fills, maker price, price improvement and release of remaining reservation")
        events = run("docker", "exec", "mb-kafka", KAFKA + "kafka-console-consumer.sh", "--bootstrap-server",
                     "localhost:9092", "--topic", topics["events"], "--from-beginning", "--timeout-ms", "5000", check=False)
        (logs / "events.log").write_text(events.stdout)
        for rejection in ["conflicting duplicate command", "insufficient available balance",
                          "order belongs to another account", "self-trade prevented"]:
            assert rejection in events.stdout, "Missing business rejection: " + rejection
        if args.mode == "native":
            stats = run("docker", "stats", "--no-stream", "--format", "{{.Name}} {{.MemUsage}}", *containers)
            (logs / "native-memory.txt").write_text(stats.stdout)
        print("All exercise checks passed (" + args.mode + "). Logs: " + str(logs))
    finally:
        save_memory()
        for role in ["gateway", "engine", "settlements"]: stop(role)
        for handle in handles: handle.close()
        for topic in created_topics:
            run("docker", "exec", "mb-kafka", KAFKA + "kafka-topics.sh", "--bootstrap-server", "localhost:9092",
                "--delete", "--topic", topic, check=False)
        for group in [token, token + "-settlements"]:
            run("docker", "exec", "mb-kafka", KAFKA + "kafka-consumer-groups.sh", "--bootstrap-server", "localhost:9092",
                "--delete", "--group", group, check=False)
        if created_schema: sql("DROP SCHEMA " + schema + " CASCADE")


if __name__ == "__main__":
    main()

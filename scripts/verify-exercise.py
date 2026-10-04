#!/usr/bin/env python3
"""Verify the exercise through HTTP -> Kafka -> engine, using only the local Kafka container."""
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
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
    token = "verify_" + uuid.uuid4().hex[:12]
    logs = ROOT / "build" / token
    logs.mkdir(parents=True)
    topics = {name: token + "-" + name for name in ["commands", "events"]}
    processes, handles, created_topics = {}, [], []
    gateway_port, engine_port = free_port(), free_port()
    env = os.environ.copy()
    env.update({"KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
                "KAFKA_COMMANDS_TOPIC": topics["commands"], "KAFKA_EVENTS_TOPIC": topics["events"],
                "GATEWAY_HOST": "127.0.0.1", "GATEWAY_PORT": str(gateway_port),
                "ENGINE_HOST": "127.0.0.1", "ENGINE_PORT": str(engine_port),
                "ENGINE_URL": "http://127.0.0.1:" + str(engine_port)})

    def stop(role):
        process = processes.pop(role, None)
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try: process.wait(timeout=40)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)

    def start(role):
        classpath = (ROOT / role / "build/runtime-classpath.txt").read_text()
        java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
        class_name = "br.com.mb." + role + "." + role.capitalize() + "Application"
        handle = (logs / (role + ".log")).open("a")
        handles.append(handle)
        processes[role] = subprocess.Popen([java, "-cp", classpath, class_name], cwd=ROOT, env=env,
            stdout=handle, stderr=subprocess.STDOUT, start_new_session=True)

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
            except (OSError, ValueError) as exc: last = exc
            for role, process in processes.items():
                if process.poll() is not None: raise RuntimeError(role + " exited; see " + str(logs))
            time.sleep(0.2)
        raise AssertionError("Timeout: " + description + "; last error=" + str(last) + "; logs=" + str(logs))

    def send(kind, id, account, fields=""):
        body = "8=FIX.4.4|35=" + kind + "|49=gateway|1=" + account + "|11=" + id + "|" + fields
        request = urllib.request.Request(base + "/commands", data=body.encode(), headers={"Content-Type": "text/plain"})
        with urllib.request.urlopen(request, timeout=5) as response: assert response.status == 202

    def balance(account, asset): return get("/accounts/" + account + "/balances?asset=" + asset)

    def expect_balance(account, asset, available, locked=0):
        wait_for("balance " + account + "/" + asset, lambda: balance(account, asset) == {
            "accountId": account, "asset": asset, "available": available, "locked": locked, "total": available + locked})

    def book(): return get("/books?instrument=BTC%2FBRL")
    def orders(): return [order for side in ["bids", "asks"] for level in book()[side] for order in level["orders"]]

    try:
        result = run(str(ROOT / "gradlew"), ":gateway:writeRuntimeClasspath", ":engine:writeRuntimeClasspath", timeout=240)
        (logs / "build.log").write_text(result.stdout)
        for topic in topics.values():
            run("docker", "exec", "mb-kafka", KAFKA + "kafka-topics.sh", "--bootstrap-server", "localhost:9092",
                "--create", "--topic", topic, "--partitions", "1", "--replication-factor", "1")
            created_topics.append(topic)
        start("engine")
        start("gateway")
        wait_for("gateway and engine readiness", lambda: book()["instrument"] == "BTC/BRL", seconds=90)
        expect_balance("unknown", "BRL", 0)
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
        send("F", "cancel", "A", "55=BTC/BRL|41=resting-buy|")
        expect_balance("A", "BRL", 49999)
        wait_for("cancelled book", lambda: not orders())
        print("PASS: debit, deduplication, insufficient funds, ownership, self-trade and cancellation")
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
        # Restart is explicitly a new, empty in-memory session, without replay of previous commands.
        send("D", "before-restart", "A", "55=BTC/BRL|54=1|44=100|38=1|")
        wait_for("resting order before restart", lambda: len(orders()) == 1)
        stop("engine")
        start("engine")
        wait_for("empty session after restart", lambda: not orders() and balance("A", "BRL")["total"] == 0, seconds=90)
        send("U1", "fresh-fund", "A", "55=BRL|38=123|")
        expect_balance("A", "BRL", 123)
        print("PASS: restart resets books and balances without replaying old commands")
        print("All exercise checks passed. Logs: " + str(logs))
    finally:
        for role in ["gateway", "engine"]: stop(role)
        for handle in handles: handle.close()
        for topic in created_topics:
            run("docker", "exec", "mb-kafka", KAFKA + "kafka-topics.sh", "--bootstrap-server", "localhost:9092",
                "--delete", "--topic", topic, check=False)
        # Remove only consumer groups created by these engine sessions.
        engine_log = logs / "engine.log"
        if engine_log.exists():
            for line in engine_log.read_text().splitlines():
                if line.startswith("Engine ready;"):
                    group = line.split("Kafka session: ", 1)[1]
                    run("docker", "exec", "mb-kafka", KAFKA + "kafka-consumer-groups.sh", "--bootstrap-server", "localhost:9092",
                        "--delete", "--group", group, check=False)


if __name__ == "__main__":
    main()

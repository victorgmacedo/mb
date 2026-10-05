#!/usr/bin/env python3
"""Run the local engine and gateway; shut down only our child processes."""
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PROCESSES = []


def start(module, main):
    classpath = (ROOT / module / "build/runtime-classpath.txt").read_text().strip()
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not java:
        raise RuntimeError("Java não encontrado. Instale JDK 25 ou configure JAVA_HOME.")
    process = subprocess.Popen([java, "-cp", classpath, main], cwd=ROOT, start_new_session=True)
    PROCESSES.append(process)
    return process


def ensure_free(host, port):
    with socket.socket() as connection:
        connection.settimeout(1)
        if connection.connect_ex((host, port)) == 0:
            raise RuntimeError(f"Porta {host}:{port} já está ocupada. Encerre a instância existente.")


def wait_ready(url, process):
    deadline = time.monotonic() + 60
    # Bypass system proxy settings for the local readiness probe.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Processo terminou durante o startup (exit {process.returncode}).")
        try:
            with opener.open(url, timeout=1) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.25)
    raise RuntimeError(f"Timeout aguardando {url}.")


def stop_children():
    for process in reversed(PROCESSES):
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
    deadline = time.monotonic() + 35
    for process in reversed(PROCESSES):
        try:
            process.wait(timeout=max(0.1, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def interrupted(_signal, _frame):
    raise KeyboardInterrupt


def main():
    signal.signal(signal.SIGTERM, interrupted)
    engine_host = os.environ.get("ENGINE_HOST", "127.0.0.1")
    gateway_host = os.environ.get("GATEWAY_HOST", "0.0.0.0")
    engine_port = int(os.environ.get("ENGINE_PORT", "8081"))
    gateway_port = int(os.environ.get("GATEWAY_PORT", "8080"))
    engine_local = "127.0.0.1" if engine_host == "0.0.0.0" else engine_host
    gateway_local = "127.0.0.1" if gateway_host == "0.0.0.0" else gateway_host
    try:
        ensure_free(engine_local, engine_port)
        ensure_free(gateway_local, gateway_port)
        os.environ.setdefault("ENGINE_URL", f"http://{engine_local}:{engine_port}")
        engine = start("engine", "br.com.mb.engine.EngineApplication")
        wait_ready(f"http://{engine_local}:{engine_port}/books?instrument=BTC%2FBRL", engine)
        gateway = start("gateway", "br.com.mb.gateway.GatewayApplication")
        wait_ready(f"http://{gateway_local}:{gateway_port}/", gateway)
        print(f"\nConsole: http://{gateway_local}:{gateway_port}/ · Ctrl+C para encerrar.\n", flush=True)
        while True:
            for process in PROCESSES:
                if process.poll() is not None:
                    raise RuntimeError(f"Processo encerrou inesperadamente (exit {process.returncode}).")
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("\nEncerrando engine e gateway. Kafka permanece ativo; use make stop.", flush=True)
        return 0
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Erro: {error}", file=sys.stderr)
        return 1
    finally:
        stop_children()


if __name__ == "__main__":
    sys.exit(main())

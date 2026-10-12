#!/usr/bin/env python3
import argparse
import os
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

MAGIC = 0x44444D43
VERSION = 1
HEADER = struct.Struct(">IIII")
OPEN, CLOSE, OPENED, CLOSED = 2, 6, 102, 104
REQUESTS = {1, 2, 3, 4, 5, 6}
RESPONSES = {101, 102, 103, 104, 199}
MAX_PAYLOAD = 2 * 2560 * 1440 * 3 // 2 + 64 * 1024 + 16 * 1024 * 1024
VIDEO_SRC = Path("app/src/main/java/com/droiddeck/launcher/video")
VIDEO_TEST = Path("app/src/test/java/com/droiddeck/launcher/video")
PACKAGE = "com.droiddeck.launcher.video"
SERVER_CLASS = f"{PACKAGE}.MediaCodecFakeServer"
RUST_PACKAGE = "opennow-streamer-platform-linux"
RUST_TEST = "mediacodec_interop"
RELEASED = re.compile(r"^RELEASED active=(\d+) max_active=(\d+) closes=(\d+)$")
STOPPED = re.compile(r"^STOPPED active=(\d+) max_active=(\d+) closes=(\d+)$")


class CheckFailed(Exception):
    pass


def log(message):
    print(message, flush=True)


def require_tool(name):
    path = shutil.which(name)
    if not path:
        raise CheckFailed(f"required tool not found: {name}")
    return path


def java_major():
    out = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr
    match = re.search(r'version "(\d+)', out)
    if not match:
        raise CheckFailed(f"cannot parse java version: {out.strip()}")
    return int(match.group(1))


class Wire:
    def __init__(self):
        self.lock = threading.Lock()
        self.events = []
        self.violations = []
        self.notes = []
        self.active = {}
        self.opened = 0
        self.closes = 0
        self.connections = 0

    def record(self, conn, direction, kind):
        with self.lock:
            self.events.append((conn, direction, kind))
            if direction == "req" and kind == OPEN:
                if self.active:
                    self.violations.append(
                        f"conn {conn} sent OPEN while codec of conn {sorted(self.active)} had no CLOSED acknowledgement")
            elif direction == "resp" and kind == OPENED:
                self.opened += 1
                self.active[conn] = True
            elif direction == "resp" and kind == CLOSED:
                self.closes += 1
                self.active.pop(conn, None)

    def note(self, text):
        with self.lock:
            self.notes.append(text)

    def violation(self, text):
        with self.lock:
            self.violations.append(text)


class Framer:
    def __init__(self, wire, conn, direction):
        self.wire = wire
        self.conn = conn
        self.direction = direction
        self.valid = REQUESTS if direction == "req" else RESPONSES
        self.header = b""
        self.remaining = 0
        self.broken = False
        self.error = None

    def feed(self, data):
        index = 0
        while index < len(data) and not self.broken:
            if self.remaining:
                take = min(self.remaining, len(data) - index)
                if self.error is not None:
                    self.error += data[index:index + take]
                self.remaining -= take
                index += take
                if self.error is not None and not self.remaining:
                    code = struct.unpack(">I", self.error[:4])[0] if len(self.error) >= 4 else 0
                    self.wire.note(f"conn {self.conn} ERROR code={code} {self.error[4:].decode('utf-8', 'replace')}")
                    self.error = None
                continue
            need = HEADER.size - len(self.header)
            chunk = data[index:index + need]
            self.header += chunk
            index += len(chunk)
            if len(self.header) < HEADER.size:
                continue
            magic, version, kind, payload = HEADER.unpack(self.header)
            self.header = b""
            if magic != MAGIC or version != VERSION or kind not in self.valid or payload > MAX_PAYLOAD:
                self.wire.violation(
                    f"conn {self.conn} {self.direction}: bad header magic={magic:#x} version={version} kind={kind} payload={payload}")
                self.broken = True
                return
            self.wire.record(self.conn, self.direction, kind)
            self.remaining = payload
            if kind == 199 and self.direction == "resp":
                self.error = b""


class Proxy:
    def __init__(self, listen_path, server_path, wire):
        self.server_path = server_path
        self.wire = wire
        self.stop = threading.Event()
        self.listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.listener.bind(listen_path)
        self.listener.listen(8)
        self.listener.settimeout(0.2)
        self.threads = []
        self.accepter = threading.Thread(target=self.accept_loop, daemon=True)
        self.accepter.start()

    def accept_loop(self):
        while not self.stop.is_set():
            try:
                client, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            with self.wire.lock:
                self.wire.connections += 1
                conn = self.wire.connections
            upstream = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            try:
                upstream.connect(self.server_path)
            except OSError as failure:
                self.wire.violation(f"conn {conn}: cannot reach mock server: {failure}")
                client.close()
                upstream.close()
                continue
            for src, dst, direction in ((client, upstream, "req"), (upstream, client, "resp")):
                thread = threading.Thread(target=self.pump, args=(conn, src, dst, direction), daemon=True)
                thread.start()
                self.threads.append(thread)

    def pump(self, conn, src, dst, direction):
        framer = Framer(self.wire, conn, direction)
        try:
            while True:
                data = src.recv(65536)
                if not data:
                    break
                framer.feed(data)
                dst.sendall(data)
        except OSError:
            pass
        finally:
            try:
                dst.shutdown(socket.SHUT_WR)
            except OSError:
                pass
            if direction == "req" and not framer.broken and (framer.remaining or framer.header):
                self.wire.violation(f"conn {conn}: client closed in the middle of a request")

    def close(self):
        self.stop.set()
        self.listener.close()
        self.accepter.join(2)
        for thread in self.threads:
            thread.join(2)


class MockServer:
    def __init__(self, classpath, path, connections):
        self.path = path
        self.lines = []
        self.ready = threading.Event()
        self.process = subprocess.Popen(
            ["java", "-cp", classpath, SERVER_CLASS, path, str(connections)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
        self.errors = []
        self.readers = [threading.Thread(target=self.read_stdout, daemon=True),
                        threading.Thread(target=self.read_stderr, daemon=True)]
        for reader in self.readers:
            reader.start()

    def read_stdout(self):
        for line in self.process.stdout:
            line = line.rstrip("\r\n")
            self.lines.append(line)
            if not self.ready.is_set() and line in ("READY", f"READY {self.path}"):
                self.ready.set()

    def read_stderr(self):
        for line in self.process.stderr:
            self.errors.append(line.rstrip("\r\n"))

    def wait_ready(self, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if self.ready.wait(0.05):
                return
            if self.process.poll() is not None:
                break
        raise CheckFailed(
            f"mock server did not print an exact READY line within {seconds}s "
            f"(exit={self.process.poll()}, stdout={self.lines[:5]}, stderr={self.errors[:5]})")

    def wait_exit(self, seconds):
        try:
            self.process.wait(seconds)
        except subprocess.TimeoutExpired:
            raise CheckFailed(
                f"mock server still running {seconds}s after the Rust test; it exits on its own after the expected connection count "
                f"(stdout={self.lines[-5:]})")
        for reader in self.readers:
            reader.join(2)
        return self.process.returncode

    def kill(self):
        if self.process.poll() is None:
            try:
                os.killpg(self.process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            self.process.wait()


def run_group(command, env, timeout, cwd=None):
    process = subprocess.Popen(
        command, env=env, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, start_new_session=True)
    try:
        output, _ = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        output, _ = process.communicate()
        raise CheckFailed(f"timed out after {timeout}s: {' '.join(command)}\n{tail(output)}")
    return process.returncode, output


def tail(text, lines=60):
    return "\n".join(text.splitlines()[-lines:])


def discover_sources(droiddeck, android_jar):
    sources, junit_classes = [], []
    for directory in (VIDEO_SRC, VIDEO_TEST):
        root = droiddeck / directory
        if not root.is_dir():
            raise CheckFailed(f"missing source directory: {root}")
        for file in sorted(root.glob("*.java")):
            text = file.read_text()
            if "import org.junit" in text or "import static org.junit" in text or "import org.hamcrest" in text:
                junit_classes.append((file, f"{PACKAGE}.{file.stem}"))
            elif re.search(r"^import (static )?android\.", text, re.M) and not android_jar:
                log(f"excluded (needs android jar): {file.name}")
            else:
                sources.append(file)
    return sources, junit_classes


def compile_all(sources, junit_classes, jars, out):
    files = list(sources)
    if jars["junit"]:
        files += [path for path, _ in junit_classes]
    classpath = os.pathsep.join(str(j) for j in jars.values() if j)
    command = ["javac", "-Xlint:-options", "-d", str(out)]
    if classpath:
        command += ["-cp", classpath]
    command += [str(f) for f in files]
    code, output = run_group(command, os.environ.copy(), 180)
    if code:
        raise CheckFailed(f"javac failed:\n{tail(output)}")
    log(f"compiled {len(files)} Java sources")


def run_pure_tests(junit_classes, jars, out):
    if not jars["junit"]:
        log("pure JVM JUnit tests: SKIPPED (no --junit-jar or JUNIT_JAR)")
        return
    if not junit_classes:
        log("pure JVM JUnit tests: none found")
        return
    classpath = os.pathsep.join([str(out)] + [str(j) for j in jars.values() if j])
    names = [name for _, name in junit_classes]
    code, output = run_group(
        ["java", "-cp", classpath, "org.junit.runner.JUnitCore", *names], os.environ.copy(), 180)
    if code:
        raise CheckFailed(f"pure JVM JUnit tests failed:\n{tail(output)}")
    log("pure JVM JUnit tests: " + next((l for l in output.splitlines() if l.startswith("OK (")), "OK"))


def run_rust(args, socket_path):
    manifest = Path(args.opennow) / "native/opennow-streamer/Cargo.toml"
    command = [
        "cargo", "test", "--manifest-path", str(manifest), "-p", RUST_PACKAGE,
        "--test", RUST_TEST, "--", "--nocapture", "--test-threads=1"]
    env = os.environ.copy()
    env["OPENNOW_MEDIACODEC_INTEROP_SOCKET"] = socket_path
    code, output = run_group(command, env, args.cargo_timeout)
    if code:
        raise CheckFailed(f"Rust integration test failed (exit {code}):\n{tail(output)}")
    results = re.findall(r"test result: ok\. (\d+) passed; (\d+) failed; (\d+) ignored", output)
    passed = sum(int(r[0]) for r in results)
    if not results or passed < 1 or any(int(r[1]) for r in results):
        raise CheckFailed(f"Rust integration test ran no passing test:\n{tail(output)}")
    log(f"Rust integration test {RUST_PACKAGE}::{RUST_TEST}: {passed} passed")


def validate_wire(wire, min_opens, connections):
    problems = list(wire.violations)
    if wire.connections != connections:
        problems.append(f"expected {connections} connections, saw {wire.connections}")
    if wire.opened < min_opens:
        problems.append(f"expected at least {min_opens} successful OPENED exchanges (reopen), saw {wire.opened}")
    if wire.active:
        problems.append(f"codec of conn {sorted(wire.active)} never received a CLOSED acknowledgement")
    if wire.closes != wire.opened:
        problems.append(f"{wire.opened} OPENED responses but {wire.closes} CLOSED acknowledgements")
    if problems:
        raise CheckFailed("wire lifecycle violations:\n  " + "\n  ".join(problems))
    log(f"wire lifecycle: {wire.connections} connections, {wire.opened} OPENED, {wire.closes} CLOSED, "
        f"every OPEN preceded by the previous CLOSED acknowledgement")


def validate_server_log(lines, opened):
    released = [RELEASED.match(l) for l in lines if l.startswith("RELEASED")]
    stopped = [STOPPED.match(l) for l in lines if l.startswith("STOPPED")]
    problems = []
    if any(m is None for m in released + stopped):
        problems.append("unparsable RELEASED/STOPPED line")
    released = [tuple(map(int, m.groups())) for m in released if m]
    stopped = [tuple(map(int, m.groups())) for m in stopped if m]
    if len(released) != opened:
        problems.append(f"{opened} codecs opened but {len(released)} RELEASED logs")
    if released and released[-1][0] != 0:
        problems.append(f"active={released[-1][0]} after the last release")
    if any(r[1] != 1 for r in released):
        problems.append("max_active exceeded 1 or was never reached")
    if len(stopped) != 1:
        problems.append(f"expected one STOPPED line, saw {len(stopped)}")
    elif stopped[0] != (0, 1, opened):
        problems.append(f"STOPPED active/max_active/closes={stopped[0]}, expected (0, 1, {opened})")
    if problems:
        raise CheckFailed("server release log violations:\n  " + "\n  ".join(problems))
    log(f"server release log: {len(released)} RELEASED, max_active=1, STOPPED active=0")


def main(argv=None):
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Run the production Java MediaCodec protocol handler "
                                     "behind a fake codec against the real OpenNOW Rust client.")
    parser.add_argument("--opennow", required=True, help="OpenNOW checkout")
    parser.add_argument("--android-jar", default=os.environ.get("ANDROID_JAR"))
    parser.add_argument("--junit-jar", default=os.environ.get("JUNIT_JAR"))
    parser.add_argument("--hamcrest-jar", default=os.environ.get("HAMCREST_JAR"))
    parser.add_argument("--min-opens", type=int, default=2)
    parser.add_argument("--connections", type=int, default=2)
    parser.add_argument("--ready-timeout", type=float, default=10)
    parser.add_argument("--cargo-timeout", type=float, default=1800)
    parser.add_argument("--server-grace", type=float, default=5)
    args = parser.parse_args(argv)

    for name in ("java", "javac", "cargo"):
        require_tool(name)
    if java_major() < 16:
        raise CheckFailed("java 16 or newer is required for UnixDomainSocketAddress")
    droiddeck = root
    opennow = Path(args.opennow).resolve()
    if not (opennow / "native/opennow-streamer/Cargo.toml").is_file():
        raise CheckFailed(f"not an OpenNOW checkout: {opennow}")
    args.opennow = str(opennow)
    jars = {}
    for key, value in (("android", args.android_jar), ("junit", args.junit_jar), ("hamcrest", args.hamcrest_jar)):
        if value and not Path(value).is_file():
            raise CheckFailed(f"{key} jar not found: {value}")
        jars[key] = Path(value).resolve() if value else None

    work = Path(tempfile.mkdtemp(prefix="ddmc-"))
    server = proxy = None
    try:
        classes = work / "classes"
        classes.mkdir()
        sources, junit_classes = discover_sources(droiddeck, jars["android"])
        compile_all(sources, junit_classes, jars, classes)
        run_pure_tests(junit_classes, jars, classes)

        server_path = str(work / "server.sock")
        proxy_path = str(work / "client.sock")
        runtime = os.pathsep.join([str(classes)] + [str(j) for j in jars.values() if j])
        server = MockServer(runtime, server_path, args.connections)
        server.wait_ready(args.ready_timeout)
        log(f"mock server READY on {server_path}")
        wire = Wire()
        proxy = Proxy(proxy_path, server_path, wire)

        try:
            run_rust(args, proxy_path)
        except CheckFailed as failure:
            log("wire trace: " + " ".join(f"{c}:{d}:{k}" for c, d, k in wire.events[:40]))
            log("wire notes: " + " | ".join(wire.notes[:10]))
            log("server stdout: " + " | ".join(server.lines[:10]))
            log("server stderr: " + " | ".join(server.errors[:10]))
            raise failure
        proxy.close()
        proxy = None
        validate_wire(wire, args.min_opens, args.connections)

        code = server.wait_exit(args.server_grace)
        if code != 0:
            raise CheckFailed(f"mock server exited with {code}; stderr={server.errors[:10]}")
        validate_server_log(server.lines, wire.opened)
        log("mock server terminated on its own: exit=0")
        if server.errors:
            log("mock server stderr: " + " | ".join(server.errors[:5]))
    finally:
        if proxy:
            proxy.close()
        if server:
            server.kill()
        shutil.rmtree(work, ignore_errors=True)
    log("PASS: Java protocol handler and Rust client interoperate over a Unix socket "
        "(fake codec: proves interoperability, not hardware decode)")


if __name__ == "__main__":
    try:
        main()
    except CheckFailed as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        sys.exit(1)

import atexit
import json
import os
import re
import selectors
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


class LayoutUnavailable(RuntimeError):
    pass


_readers = {}


class Reader:
    def __init__(self, serial):
        self.adb = [str(Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"), "-s", serial]
        socket_name = None
        try:
            info = self.command("shell", "am", "broadcast", "-a", "com.android.cli.interact.instrumentation.GET_INFO")
            m = re.search(r'socket_name: "([^"\n]+)"', info)
            if m:
                socket_name = m[1]
        except Exception:
            pass

        if socket_name is None:
            # Try starting instrumentation server directly
            subprocess.Popen([*self.adb, "shell", "am", "instrument", "-w",
                             "com.android.cli.interact.instrumentation/.InstrumentationServer"],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                try:
                    info = self.command("shell", "am", "broadcast", "-a", "com.android.cli.interact.instrumentation.GET_INFO")
                    m = re.search(r'socket_name: "([^"\n]+)"', info)
                    if m:
                        socket_name = m[1]
                        break
                except Exception:
                    pass
                time.sleep(0.5)

        if socket_name is None:
            deadline = time.monotonic() + 60
            while True:
                try:
                    result = subprocess.run(["android", "layout", "--device", serial, "--flat", "--no-idle"],
                                            capture_output=True, text=True,
                                            timeout=max(1, min(20, deadline - time.monotonic())), check=True)
                    if isinstance(json.loads(result.stdout), list):
                        break
                except (json.JSONDecodeError, subprocess.TimeoutExpired, subprocess.CalledProcessError):
                    pass
                if time.monotonic() >= deadline:
                    raise LayoutUnavailable(f"{serial}: Android CLI returned no readable layout")
                time.sleep(0.5)
            info = self.command("shell", "am", "broadcast", "-a", "com.android.cli.interact.instrumentation.GET_INFO")
            m = re.search(r'socket_name: "([^"\n]+)"', info)
            if m is None:
                raise LayoutUnavailable(f"{serial}: Android CLI instrumentation is unavailable")
            socket_name = m[1]

        bundles = list((Path.home() / ".android/cli/bundles").glob("*/main.jar"))
        jar = os.environ.get("ANDROID_CLI_JAR") or (str(max(bundles, key=lambda p: p.stat().st_mtime)) if bundles else None)
        if jar is None:
            raise LayoutUnavailable("Set ANDROID_CLI_JAR to the installed Android CLI main.jar")
        self.port = self.command("forward", "tcp:0", f"localabstract:{socket_name}").strip()
        # Reuse the CLI protocol in one runtime; process startup exceeds reflex-game deadlines.
        self.process = subprocess.Popen(
            ["java", "--class-path", jar, str(Path(__file__).with_name("AndroidLayoutReader.java")), self.port],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
        )
        atexit.register(self.close)

    def command(self, *args):
        return subprocess.run([*self.adb, *args], capture_output=True, text=True, timeout=30, check=True).stdout

    def read(self):
        self.process.stdin.write("layout\n")
        self.process.stdin.flush()
        with selectors.DefaultSelector() as ready:
            ready.register(self.process.stdout, selectors.EVENT_READ)
            if not ready.select(timeout=45):
                raise LayoutUnavailable("Android CLI layout reader timed out")
        line = self.process.stdout.readline()
        if not line:
            raise LayoutUnavailable("Android CLI layout reader stopped")
        return json.loads(line)

    def close(self):
        self.process.terminate()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        subprocess.run([*self.adb, "forward", "--remove", f"tcp:{self.port}"],
                       capture_output=True, timeout=15, check=False)


def fallback_nodes(serial):
    cmd = [str(Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"), "-s", serial]
    try:
        subprocess.run([*cmd, "shell", "rm", "-f", "/sdcard/formation-layout-fallback.xml"], capture_output=True, timeout=10, check=False)
        subprocess.run([*cmd, "shell", "uiautomator dump /sdcard/formation-layout-fallback.xml >/dev/null 2>&1"],
                       capture_output=True, timeout=30, check=False)
        xml = subprocess.run([*cmd, "shell", "cat /sdcard/formation-layout-fallback.xml"],
                             capture_output=True, text=True, timeout=10, check=False).stdout
        if xml.startswith("<?xml"):
            return ET.fromstring(xml)
    except (subprocess.TimeoutExpired, ET.ParseError) as error:
        raise LayoutUnavailable(f"{serial}: neither Android CLI nor UIAutomator could read the screen") from error
    raise LayoutUnavailable(f"{serial}: neither Android CLI nor UIAutomator could read the screen")


def nodes(serial):
    reader = _readers.get(serial)
    try:
        if reader is None:
            reader = _readers[serial] = Reader(serial)
        records = reader.read()
        # A cold activity can be visible before accessibility publishes its root. Keep the
        # existing reader alive during this bounded transition instead of restarting instrumentation.
        for _ in range(10):
            if records:
                break
            time.sleep(0.5)
            records = reader.read()
        if not isinstance(records, list) or not records:
            raise LayoutUnavailable(f"{serial}: Android CLI returned an empty layout")
    except (LayoutUnavailable, OSError, json.JSONDecodeError, subprocess.TimeoutExpired, subprocess.CalledProcessError):
        if reader is not None:
            try:
                reader.close()
            except (OSError, subprocess.TimeoutExpired):
                pass
        _readers.pop(serial, None)
        return fallback_nodes(serial)
    return records_to_nodes(records)


def records_to_nodes(records):
    root = ET.Element("hierarchy")
    converted = []
    for record in records:
        node = ET.Element("node", {
            "class": record.get("class", "android.view.View"),
            "text": record.get("text", ""),
            "content-desc": record.get("content-desc", ""),
            "bounds": record.get("bounds", "[0,0][0,0]"),
            **{flag: str(flag.upper().replace("-", "_") in record.get("interactions", [])).lower()
               for flag in ("clickable", "scrollable", "password")},
            "focused": str("FOCUSED" in record.get("state", [])).lower(),
            "selected": str("SELECTED" in record.get("state", [])).lower(),
            "checked": str("CHECKED" in record.get("state", [])).lower(),
        })
        converted.append((node, tuple(map(int, re.findall(r"\d+", node.get("bounds"))))))
    # The CLI returns flat accessible nodes; restore containment for the journey's card queries.
    for node, (x1, y1, x2, y2) in converted:
        area = (x2 - x1) * (y2 - y1)
        containers = [(parent, (px2 - px1) * (py2 - py1))
                      for parent, (px1, py1, px2, py2) in converted
                      if parent is not node and px1 <= x1 and py1 <= y1 and px2 >= x2 and py2 >= y2
                      and (px2 - px1) * (py2 - py1) > area]
        parent = min(containers, key=lambda pair: pair[1])[0] if containers else root
        parent.append(node)
    return root


def cli_nodes(serial):
    """Use the supported CLI for journeys that do not require the persistent fast reader."""
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            result = subprocess.run(["android", "layout", "--device", serial, "--flat", "--no-idle"],
                                    capture_output=True, text=True, timeout=min(15, max(1, deadline - time.monotonic())), check=True)
            records = json.loads(result.stdout)
            if isinstance(records, list) and records:
                return records_to_nodes(records)
        except (json.JSONDecodeError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        time.sleep(0.5)
    return fallback_nodes(serial)

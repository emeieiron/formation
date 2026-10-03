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
        for attempt in range(3):
            result = subprocess.run(["android", "layout", "--device", serial, "--flat", "--no-idle"],
                                    capture_output=True, text=True, timeout=15, check=True)
            try:
                json.loads(result.stdout)
                break
            except json.JSONDecodeError:
                if attempt == 2:
                    raise LayoutUnavailable(f"{serial}: Android CLI returned no readable layout")
                time.sleep(0.2)
        info = self.command("shell", "am", "broadcast", "-a", "com.android.cli.interact.instrumentation.GET_INFO")
        socket = re.search(r'socket_name: "([^"\n]+)"', info)
        if socket is None:
            raise LayoutUnavailable(f"{serial}: Android CLI instrumentation is unavailable")
        bundles = list((Path.home() / ".android/cli/bundles").glob("*/main.jar"))
        jar = os.environ.get("ANDROID_CLI_JAR") or (str(max(bundles, key=lambda p: p.stat().st_mtime)) if bundles else None)
        if jar is None:
            raise LayoutUnavailable("Set ANDROID_CLI_JAR to the installed Android CLI main.jar")
        self.port = self.command("forward", "tcp:0", f"localabstract:{socket[1]}").strip()
        # Reuse the CLI protocol in one runtime; process startup exceeds reflex-game deadlines.
        self.process = subprocess.Popen(
            ["java", "--class-path", jar, str(Path(__file__).with_name("AndroidLayoutReader.java")), self.port],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
        )
        atexit.register(self.close)

    def command(self, *args):
        return subprocess.run([*self.adb, *args], capture_output=True, text=True, timeout=15, check=True).stdout

    def read(self):
        self.process.stdin.write("layout\n")
        self.process.stdin.flush()
        with selectors.DefaultSelector() as ready:
            ready.register(self.process.stdout, selectors.EVENT_READ)
            if not ready.select(timeout=15):
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


def nodes(serial):
    reader = _readers.get(serial)
    if reader is None:
        reader = _readers[serial] = Reader(serial)
    records = reader.read()
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

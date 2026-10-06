import re
import time
from concurrent.futures import ThreadPoolExecutor


class OverdriveFailed(RuntimeError):
    pass


def snapshot(phone):
    root = phone.nodes()
    texts = [node.get("text", "") for node in root.iter("node")]
    descriptions = [(node, node.get("content-desc", "")) for node in root.iter("node")]
    clue = next((re.search(r"Wave (\d+)\. Clue for .+?: (Triangle|Circle|Cross|Diamond)", text)
                 for _, text in descriptions if text.startswith("Wave ")), None)
    dial = next(((node, re.search(r"Top: (\w+)\. Clockwise edges: ([\w, ]+)", text))
                 for node, text in descriptions if text.startswith("Rotate clockwise. Top:")), None)
    if clue is None or dial is None or dial[1] is None:
        return {"wave": None, "text": texts}
    return {"wave": int(clue[1]), "clue": clue[2], "top": dial[1][1],
            "edges": [edge.strip() for edge in dial[1][2].split(",")],
            "node": dial[0], "ready": "Get ready" not in texts}


def play_overdrive(seeker, guest, log):
    phones, relayed = (seeker, guest), set()
    deadline = time.monotonic() + 100
    with ThreadPoolExecutor(max_workers=2) as executor:
        while time.monotonic() < deadline:
            views = list(executor.map(snapshot, phones))
            if any(view["wave"] is None for view in views):
                texts = views[0].get("text", [])
                if any(re.fullmatch(r"Unlock [\d,.]+ SKR", text) for text in texts):
                    log("Overdrive: 12 paired waves completed through visible partner clues")
                    return
                if "Run it back" in texts:
                    raise OverdriveFailed("Overdrive did not complete; inspect the saved screens")
                time.sleep(0.1)
                continue
            if views[0]["wave"] != views[1]["wave"] or not all(view["ready"] for view in views):
                time.sleep(0.1)
                continue
            wave = views[0]["wave"]
            if wave in relayed:
                time.sleep(0.1)
                continue

            def align(index):
                view, target = views[index], views[1 - index]["clue"]
                turns = (view["edges"].index(view["top"]) - view["edges"].index(target)) % 4
                if turns:
                    # One shell for every turn: separate adb calls outlast the last waves' flights.
                    x1, y1, x2, y2 = map(int, re.findall(r"\d+", view["node"].get("bounds")))
                    tap = f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}"
                    phones[index].shell("; ".join([tap] * turns))

            list(executor.map(align, range(2)))
            relayed.add(wave)
            log(f"Overdrive: relayed and aligned wave {wave}")
            if wave == 3:
                list(executor.map(lambda phone: phone.screenshot("overdrive-play"), phones))
            time.sleep(0.1)
    raise OverdriveFailed("Overdrive exceeded the journey deadline")

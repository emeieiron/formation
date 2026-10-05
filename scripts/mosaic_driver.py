"""Seal Mosaic's seams through real touch input on every emulator.

Each phone's piece and seam strips come from its accessibility tree. For every seam, both phones receive a
swipe toward their shared edge at the same moment, lifting at the strip's midpoint so the two halves agree on
alignment. Emulators can't be laid on a table, so this checks the touch path and pairing, not placement.
"""
import re
import time
from concurrent.futures import ThreadPoolExecutor

BARS = {"Top": 0, "Middle": 1, "Bottom": 2}
COLUMNS = {6: 2, 9: 3, 18: 6}


class MosaicFailed(RuntimeError):
    pass


def bounds(node):
    return list(map(int, re.findall(r"\d+", node.get("bounds"))))


def place(label, columns):
    bar, part = re.fullmatch(r"Mosaic piece: (\w+) bar · (.+)", label).groups()
    row = BARS[bar]
    if part in ("left half", "left end"):
        return row, 0
    if part in ("right half", "right end"):
        return row, columns - 1
    if part == "centre":
        return row, 1
    return row, int(re.fullmatch(r"piece (\d+) of \d+", part).group(1)) - 1


def read(phone):
    root = phone.nodes()
    # Android explains full screen once per app; the notice covers the piece until dismissed.
    notice = next((node for node in root.iter("node") if node.get("text") == "Got it"), None)
    if notice is not None:
        phone.tap(notice)
        root = phone.nodes()
    piece = next((node for node in root.iter("node") if (node.get("content-desc") or "").startswith("Mosaic piece: ")), None)
    strips = {}
    for node in root.iter("node"):
        match = re.fullmatch(r"(Left|Top|Right|Bottom) seam, (sealed|open)", node.get("content-desc") or "")
        if match:
            strips[match[1]] = (node, match[2] == "sealed")
    return piece, strips


def swipe(phone, strip, piece):
    left, top, right, bottom = bounds(strip)
    screen = bounds(piece)
    cx, cy = (screen[0] + screen[2]) // 2, (screen[1] + screen[3]) // 2
    x, y = (left + right) // 2, (top + bottom) // 2
    # Start well inside the piece and slide outward across the strip, lifting just past it.
    if right - left >= bottom - top:
        direction = 1 if y > cy else -1
        start, end = (x, y - direction * 420), (x, y + direction * (bottom - top))
    else:
        direction = 1 if x > cx else -1
        start, end = (x - direction * 420, y), (x + direction * (right - left), y)
    phone.shell(f"input swipe {start[0]} {start[1]} {end[0]} {end[1]} 260")


def play_mosaic(phones, players, log, timeout=420):
    columns = COLUMNS[players]
    rows = players // columns
    deadline = time.monotonic() + timeout
    with ThreadPoolExecutor(max_workers=len(phones)) as pool:
        while time.monotonic() < deadline:
            views = list(pool.map(read, phones))
            if all(piece is not None for piece, _ in views):
                break
            time.sleep(0.5)
        else:
            raise MosaicFailed("Mosaic pieces never appeared")
        at = {place(piece.get("content-desc"), columns): (phone, piece)
              for phone, (piece, _) in zip(phones, views)}
        if len(at) != players:
            raise MosaicFailed(f"Pieces overlap: {sorted(at)}")
        log("Mosaic: " + ", ".join(f"{phone.serial}={piece.get('content-desc')[14:]}" for phone, piece in at.values()))
        seams = [((r, c), (r, c + 1), "Right", "Left") for r in range(rows) for c in range(columns - 1)]
        seams += [((r, c), (r + 1, c), "Bottom", "Top") for r in range(rows - 1) for c in range(columns)]
        for index, (first, second, edge, opposite) in enumerate(seams):
            (a, _), (b, _) = at[first], at[second]
            for attempt in range(5):
                if time.monotonic() > deadline:
                    raise MosaicFailed("Mosaic exceeded the journey deadline")
                (piece_a, strips_a), (piece_b, strips_b) = pool.map(read, (a, b))
                if piece_a is None or piece_b is None:
                    log("Mosaic: the round ended")
                    return
                if strips_a[edge][1] and strips_b[opposite][1]:
                    break
                list(pool.map(lambda job: swipe(*job), ((a, strips_a[edge][0], piece_a), (b, strips_b[opposite][0], piece_b))))
                time.sleep(0.8)
            else:
                raise MosaicFailed(f"The seam between {first} and {second} never sealed")
            log(f"Mosaic: sealed {first}–{second}")
            if index == 0:
                list(pool.map(lambda phone: phone.screenshot("mosaic-play"), phones))

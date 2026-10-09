"""Exercise Caravan without shortening its target or bypassing the host rules."""
import json
import re
import time
from concurrent.futures import ThreadPoolExecutor

from autoplay_driver import start_autoplay


class CaravanFailed(RuntimeError):
    pass


def bounds(node):
    return list(map(int, re.findall(r"\d+", node.get("bounds", ""))))


def read_my_steps(phone, root=None):
    nodes = list((root if root is not None else phone.nodes()).iter("node"))
    for index, node in enumerate(nodes):
        if node.get("text") == "YOUR STEPS":
            for following in nodes[index + 1:index + 6]:
                text = following.get("text", "")
                if text.isdigit():
                    return int(text)
    raise CaravanFailed(f"{phone.role}: personal step count is missing")


def read_roster_steps(root, name):
    parents = {child: parent for parent in root.iter("node") for child in parent}
    for label in root.iter("node"):
        if label.get("text") != name:
            continue
        at = parents.get(label)
        while at is not None:
            counts = [int(match.group(1)) for node in at.iter("node")
                      if (match := re.fullmatch(r"(\d+) st", node.get("text", "")))]
            if len(counts) == 1:
                return counts[0]
            at = parents.get(at)
        # The supported CLI may flatten non-interactive rows. Associate the name with the
        # single step label to its right on the same rendered row, never a nearby player's count.
        label_bounds = bounds(label)
        if len(label_bounds) == 4:
            _, top, right, bottom = label_bounds
            counts = []
            for node in root.iter("node"):
                match = re.fullmatch(r"(\d+) st", node.get("text", ""))
                area = bounds(node)
                if match and len(area) == 4:
                    left, count_top, _, count_bottom = area
                    if left >= right and min(bottom, count_bottom) > max(top, count_top):
                        counts.append(int(match.group(1)))
            if len(counts) == 1:
                return counts[0]
    raise CaravanFailed(f"Roster has no unambiguous count for {name}")


def wait_for_stage(phone, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = phone.nodes()
        if phone.find("YOUR STEPS", root=root) is not None:
            return
        phone.dismiss_stalls(root)
        time.sleep(0.5)
    phone.screenshot("missing-caravan-stage")
    raise CaravanFailed(f"{phone.role}: never entered Caravan")


def back_to_steps(phone):
    for _ in range(8):
        root = phone.nodes()
        if phone.find("YOUR STEPS", root=root) is not None:
            return
        area = next((node for node in root.iter("node") if node.get("scrollable") == "true"), None)
        if area is None:
            break
        left, top, right, bottom = bounds(area)
        height, x = bottom - top, (left + right) // 2
        phone.shell(f"input swipe {x} {top + height // 4} {x} {top + height * 3 // 4} 500")
        time.sleep(0.3)
    raise CaravanFailed(f"{phone.role}: could not return to personal steps")


def step_position(phone):
    node = phone.scroll_to("Tap to Step")
    left, top, right, bottom = bounds(node)
    if right <= left or bottom <= top:
        raise CaravanFailed(f"{phone.role}: step button has no touch target")
    return (left + right) // 2, (top + bottom) // 2


def advance_to(phone, target, timeout=90):
    deadline = time.monotonic() + timeout
    x, y = step_position(phone)
    while time.monotonic() < deadline:
        count = read_my_steps(phone)
        if count >= target:
            return count
        phone.shell(f"input tap {x} {y}")
        time.sleep(0.4)
    raise CaravanFailed(f"{phone.role}: did not reach {target} steps")


def play_caravan(phones, players, log, smoke=False):
    if len(phones) != players or not 6 <= players <= 16:
        raise CaravanFailed("Caravan requires 6–16 distinct participant phones")
    log(f"Caravan: waiting for all {players} phones")
    with ThreadPoolExecutor(max_workers=players) as pool:
        list(pool.map(wait_for_stage, phones))
    time.sleep(5)
    for phone in phones:
        phone.screenshot("caravan-initial")
    seeker = phones[0]
    host_name = json.loads(seeker.pref("profile"))["name"]
    initial = read_my_steps(seeker)
    advanced = advance_to(seeker, initial + 5)
    # Assert remote roster propagation rather than merely logging screen text.
    for guest in phones[1:]:
        guest.scroll_to(host_name)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if read_roster_steps(guest.nodes(), host_name) == advanced:
                break
            time.sleep(0.5)
        else:
            raise CaravanFailed(f"{guest.role}: never observed the host's {advanced} steps")
        back_to_steps(guest)
    log("Caravan: all guests observed the host's count")

    x, y = step_position(seeker)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        root = seeker.nodes()
        if seeker.find("WAIT FOR SQUAD", prefix=True, root=root) is not None:
            break
        seeker.shell(f"input tap {x} {y}")
        time.sleep(0.4)
    else:
        raise CaravanFailed("The leader was not stopped by the pack rule")
    limited = read_my_steps(seeker)
    if limited != 30:
        raise CaravanFailed(f"Expected the leader to stop at 30 steps, got {limited}")
    seeker.shell(f"input tap {x} {y}")
    time.sleep(0.8)
    if read_my_steps(seeker) != limited:
        raise CaravanFailed("A step was counted while the leader was waiting")

    with ThreadPoolExecutor(max_workers=players - 1) as pool:
        list(pool.map(lambda phone: advance_to(phone, 16), phones[1:]))
    resumed = advance_to(seeker, limited + 1)
    if resumed <= limited:
        raise CaravanFailed("The leader did not resume after the squad caught up")
    for phone in phones:
        phone.screenshot("caravan-active-gameplay")
    log("Caravan: pack limit, rejected steps, and catch-up recovery passed")
    if smoke:
        return
    start_autoplay(phones, log)
    log("Caravan: continuing to 15,000 steps each with the normal gameplay rules")


def wait_for_caravan_win(seeker, log, timeout):
    deadline = time.monotonic() + timeout
    next_report = 0
    while time.monotonic() < deadline:
        root = seeker.nodes()
        unlock = seeker.find(r"Unlock [\d,.]+ SKR", prefix="regex", root=root)
        if unlock is not None:
            return unlock
        if seeker.find("Run it back", root=root) is not None:
            raise CaravanFailed("The Caravan attempt ended without a win")
        if time.monotonic() >= next_report:
            log(f"Caravan: host has {read_my_steps(seeker, root)} / 15,000 steps")
            next_report = time.monotonic() + 60
        time.sleep(2)
    raise CaravanFailed("Caravan did not reach a win within --play-timeout")

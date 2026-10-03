#!/usr/bin/env python3
# End-to-end test across two running emulators: one plays a simulated Seeker, the other a guest.
#
#   scripts/e2e.py --title GAME_TITLE --code GAME_CODE [--chain simulated|localnet|testnet] [--wallet none|connect|ADDRESS]
#                  [--seeker SERIAL] [--guest SERIAL] [--no-build] [--approve]
#                  [--layout uiautomator|android] [--driver autoplay|overdrive]
#
# --wallet connect taps Connect in the guest's lobby and waits for the wallet app's approval; with
# --approve it taps the wallet's own Connect button too (it never types a password). On chains, the
# guest's wallet balance is checked before and after. Screens are saved to program/target/e2e.
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape, unescape
from reward_fixtures import SKR, load_rewards
from overdrive_driver import OverdriveFailed, play_overdrive
from android_layout import LayoutUnavailable

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk")), "platform-tools", "adb")
APP = "xyz.mcxross.formation"
OUT = os.path.join(ROOT, "program", "target", "e2e")
PROGRAM = "3AzZbKhGFcnaBRRenDDdNSdVumjKoXSkNHsPVeo5q6GW"
TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
RPC = {"localnet": "http://127.0.0.1:8899", "testnet": "https://api.testnet.solana.com"}
APP_RPC = {"localnet": ("http://10.0.2.2:8899", "localnet"), "testnet": ("https://api.testnet.solana.com", "testnet")}
WALLET_APPS = ("com.solflare.mobile", "app.phantom")


class Failed(Exception):
    pass


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


class Phone:
    def __init__(self, serial, role, layout="uiautomator"):
        self.serial, self.role = serial, role
        self.layout = layout

    def adb(self, *args, check=True, text=True, stdin=None):
        out = subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, text=text, input=stdin)
        if check and out.returncode:
            raise Failed(f"{self.role}: adb {' '.join(args)}: {out.stderr}")
        return out.stdout

    def shell(self, command):
        return self.adb("shell", command)

    def nodes(self):
        if self.layout == "android":
            from android_layout import nodes
            return nodes(self.serial)
        for _ in range(5):
            self.shell("rm -f /sdcard/formation-ui.xml; uiautomator dump /sdcard/formation-ui.xml >/dev/null 2>&1; true")
            xml = self.adb("shell", "cat /sdcard/formation-ui.xml", check=False)
            if xml.startswith("<?xml"):
                return ET.fromstring(xml)
            time.sleep(0.5)
        raise Failed(f"{self.role}: couldn't read the screen")

    def find(self, text=None, desc=None, prefix=False, root=None):
        for node in (root if root is not None else self.nodes()).iter("node"):
            value = node.get("text") if text is not None else node.get("content-desc")
            wanted = text if text is not None else desc
            if value and (re.fullmatch(wanted, value) if prefix == "regex" else value.startswith(wanted) if prefix else value == wanted):
                return node
        return None

    def wait(self, text=None, desc=None, prefix=False, timeout=30):
        deadline = time.time() + timeout
        while time.time() < deadline:
            node = self.find(text, desc, prefix)
            if node is not None:
                return node
            time.sleep(0.7)
        self.screenshot(f"missing-{(text or desc)[:24]}")
        raise Failed(f"{self.role}: never showed {text or desc!r}")

    def tap(self, node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        self.shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")

    def tap_text(self, text, prefix=False, timeout=30):
        self.tap(self.wait(text, prefix=prefix, timeout=timeout))

    def visible(self, text, prefix=False):
        return self.find(text, prefix=prefix) is not None

    def scroll_to(self, text):
        for _ in range(6):
            root = self.nodes()
            node = self.find(text, root=root)
            if node is not None:
                return node
            area = next((node for node in root.iter("node") if node.get("scrollable") == "true"), None)
            if area is None:
                break
            left, top, right, bottom = bounds(area)
            x, height = (left + right) // 2, bottom - top
            self.shell(f"input swipe {x} {top + height * 3 // 4} {x} {top + height // 4} 400")
            time.sleep(0.2)
        return self.wait(text, timeout=10)

    def screenshot(self, name):
        os.makedirs(OUT, exist_ok=True)
        png = self.adb("exec-out", "screencap", "-p", text=False)
        path = os.path.join(OUT, f"{self.role}-{name}.png")
        with open(path, "wb") as f:
            f.write(png)
        return path

    def prefs(self):
        return self.adb("shell", f"run-as {APP} cat shared_prefs/formation.xml", check=False)

    def pref(self, key):
        match = re.search(rf'<string name="{re.escape(key)}">([^<]*)</string>', self.prefs())
        return match and unescape(match.group(1), {"&quot;": '"', "&apos;": "'"})

    def set_prefs(self, **values):
        self.shell(f"am force-stop {APP}")
        xml = self.prefs() or "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
        for key, value in values.items():
            xml = re.sub(rf'\s*<string name="{re.escape(key)}">[^<]*</string>', "", xml)
            if value is not None:
                xml = xml.replace("</map>", f'    <string name="{key}">{escape(value)}</string>\n</map>')
        self.adb("shell", f"run-as {APP} sh -c 'cat > shared_prefs/formation.xml'", stdin=xml)

    def launch(self):
        self.shell(f"am force-stop {APP}")
        self.shell(f"am start -S --activity-clear-task -n {APP}/.MainActivity")
        time.sleep(3)

    def foreground(self):
        out = self.adb("shell", "dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'", check=False)
        return next((p for p in (APP, *WALLET_APPS) if p in out), out.strip())


def rpc(chain, method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(RPC[chain], body, {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=30)).get("result")


def skr_balance(chain, owner):
    result = rpc(chain, "getTokenAccountsByOwner", [owner, {"programId": TOKEN}, {"encoding": "jsonParsed", "commitment": "confirmed"}])
    return sum(int(a["account"]["data"]["parsed"]["info"]["tokenAmount"]["amount"]) for a in result["value"])


def open_rewards(chain, seeker, code):
    filters = [{"dataSize": 337}, {"memcmp": {"offset": 56, "bytes": seeker}}, {"memcmp": {"offset": 246, "bytes": "1"}}]
    accounts = rpc(chain, "getProgramAccounts", [PROGRAM, {"encoding": "base64", "commitment": "confirmed", "filters": filters}])
    import base64

    found = 0
    for a in accounts:
        data = base64.b64decode(a["account"]["data"][0])
        if data[192] == 2 and int.from_bytes(data[195:197], "little") == code and int.from_bytes(data[238:246], "little") > time.time() + 600:
            found += 1
    return found


def build(chain):
    args = ["./gradlew", "-q", ":androidApp:assembleDebug"]
    if chain in APP_RPC:
        url, cluster = APP_RPC[chain]
        args += [f"-Pformation.rpcUrl={url}", f"-Pformation.cluster={cluster}"]
    log("building " + " ".join(args[2:]))
    subprocess.run(args, cwd=os.path.join(ROOT, "app"), check=True)


def onboard(phone, name, light):
    if not phone.visible("Get started"):
        return
    log(f"{phone.role}: onboarding as {name}")
    phone.tap_text("Get started")
    edit = next(n for n in phone.nodes().iter("node") if n.get("class") == "android.widget.EditText")
    phone.tap(edit)
    if not any(n.get("class") == "android.widget.EditText" and n.get("focused") == "true"
               for n in phone.nodes().iter("node")):
        raise Failed(f"{phone.role}: name field did not receive focus")
    phone.shell(f"input text {name}")
    phone.tap_text(light)
    phone.tap_text("Continue")
    phone.wait("Scan QR", timeout=15)


def host_duo(seeker, title):
    row = next((n for n in seeker.nodes().iter("node")
                if (n.get("content-desc") or n.get("text") or "").endswith(" players")), None)
    if row is None:
        raise Failed("seeker: no rewards on the shelf")
    y = (bounds(row)[1] + bounds(row)[3]) // 2
    # Slow, short drags so the row doesn't fling past a card; first back to the start, then along.
    for _ in range(8):
        seeker.shell(f"input swipe 250 {y} 1150 {y} 150")
    for _ in range(24):
        for card in seeker.nodes().iter("node"):
            texts = [value for n in card.iter("node")
                     for value in (n.get("text"), n.get("content-desc")) if value]
            x1, _, x2, _ = bounds(card)
            if card.get("clickable") == "true" and title in texts and "2 players" in texts and x2 - x1 > 500:
                seeker.tap(card)
                seeker.tap_text("Start Formation")
                return
        seeker.shell(f"input swipe 1000 {y} 500 {y} 700")
        time.sleep(0.5)
    raise Failed(f"seeker: no {title} duo on the shelf")


def bounds(node):
    return list(map(int, re.findall(r"\d+", node.get("bounds"))))


def pretend_seeker(phone):
    log(f"{phone.role}: Settings → Developer → Pretend to be a Seeker")
    avatar = next(n for n in phone.nodes().iter("node") if n.get("clickable") == "true" and bounds(n)[3] < 400 and bounds(n)[0] > 1000)
    phone.tap(avatar)
    phone.wait("Profile", timeout=10)
    for _ in range(6):
        row = phone.find("Pretend to be a Seeker")
        if row is not None:
            break
        phone.shell("input swipe 672 2400 672 900 400")
        time.sleep(0.5)
    else:
        raise Failed(f"{phone.role}: no Pretend to be a Seeker setting (debug build?)")
    _, top, _, bottom = bounds(row)
    toggle = next(
        n for n in phone.nodes().iter("node")
        if n.get("clickable") == "true" and bounds(n)[0] > 1000 and bounds(n)[1] < bottom + 60 and bounds(n)[3] > top - 60
    )
    phone.tap(toggle)
    time.sleep(1)
    if not phone.pref("seeker"):
        raise Failed(f"{phone.role}: the Pretend to be a Seeker toggle didn't stick")
    phone.shell("input keyevent KEYCODE_BACK")
    time.sleep(1)


def approve_wallet(guest, auto, timeout=180):
    deadline = time.time() + timeout
    asked = False
    while time.time() < deadline:
        if guest.foreground() == APP and guest.visible("Lands in", prefix=True):
            return
        if guest.foreground() in WALLET_APPS:
            root = guest.nodes()
            if any(n.get("password") == "true" for n in root.iter("node")) or guest.find(desc="Tap to cancel authentication", root=root) is not None:
                if not asked:
                    log("guest: unlock the wallet app on the guest emulator to continue")
                    asked = True
            elif auto:
                for label in ("Connect", "Approve", "Confirm"):
                    node = guest.find(label, root=root)
                    if node is not None:
                        guest.tap(node)
                        break
            elif not asked:
                log("guest: approve the connection in the wallet app on the guest emulator")
                asked = True
        time.sleep(1)
    raise Failed("guest: the wallet never connected")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chain", choices=["simulated", "localnet", "testnet"], default="simulated")
    parser.add_argument("--title", required=True, help="registered game's visible title")
    parser.add_argument("--code", type=int, required=True, help="registered game's vault code")
    parser.add_argument("--wallet", default="none")
    parser.add_argument("--seeker")
    parser.add_argument("--guest")
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument("--approve", action="store_true")
    parser.add_argument("--keep-chain", action="store_true", help="leave the local validator running afterwards")
    parser.add_argument("--offline", action="store_true", help="the Seeker loses its connection at unlock and recovers after a restart")
    parser.add_argument("--layout", choices=["uiautomator", "android"], default="uiautomator")
    parser.add_argument("--driver", choices=["autoplay", "overdrive"], default="autoplay")
    args = parser.parse_args()
    if not args.title.strip() or not 6 <= args.code <= 65535:
        parser.error("Use a nonempty game title and a vault code within 6..65535")
    fixtures = load_rewards()
    if args.chain in ("simulated", "localnet") and not any(f.code == args.code and f.players == 2 for f in fixtures):
        parser.error("FORMATION_REWARDS must provide a duo reward for this registered game")

    running = re.findall(r"^(emulator-\d+)\s+device$", subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout, re.M)
    if len(running) < 2 and not (args.seeker and args.guest):
        sys.exit("Start two emulators first")
    seeker = Phone(args.seeker or running[0], "seeker", args.layout)
    guest = Phone(args.guest or running[1], "guest", args.layout)
    title, code = args.title, args.code
    validator = None
    saved = None
    try:
        if not args.no_build:
            build(args.chain)
        apk = os.path.join(ROOT, "app", "androidApp", "build", "outputs", "apk", "debug", "androidApp-debug.apk")
        for i, phone in enumerate((seeker, guest)):
            phone.adb("install", "-r", apk)
            phone.adb("forward", f"tcp:{47000 + i}", "tcp:47000")
        # The test changes these; put back whatever the person had, such as a connected wallet.
        saved = {phone: {k: phone.pref(k) for k in ("ledger", "wallet", "sim.opportunities")} for phone in (seeker, guest)}
        ledger = "SIMULATED" if args.chain == "simulated" else "SOLANA"
        wallet = None if args.wallet in ("none", "connect") else args.wallet
        seeker.set_prefs(ledger=ledger)
        if args.chain == "simulated":
            rewards = [
                {"id": str(uuid.uuid4()), "challenge": f.challenge, "reward": f.amount * SKR,
                 "players": f.players, "ownerBps": f.owner_bps,
                 "difficulty": ("EASY", "NORMAL", "HARD", "EXTREME")[f.difficulty],
                 "expiresAt": int(time.time() * 1000) + f.days * 86_400_000,
                 "sponsor": "Test", "title": f.title or None}
                for f in fixtures
            ]
            seeker.set_prefs(**{"sim.opportunities": json.dumps(rewards)})
        guest.set_prefs(ledger=ledger, wallet=wallet)
        for phone, name, light in ((seeker, "Aaron", "Nova"), (guest, "Maya", "Jade")):
            phone.launch()
            onboard(phone, name, light)
        if not (seeker.pref("seeker") and json.loads(seeker.pref("seeker")).get("simulated")):
            pretend_seeker(seeker)
        seeker_wallet = json.loads(seeker.pref("seeker"))["wallet"]
        host_name = json.loads(seeker.pref("profile"))["name"]
        log(f"seeker {seeker.serial} wallet {seeker_wallet}; guest {guest.serial}")

        if args.chain == "localnet":
            log("starting a local validator")
            validator = subprocess.Popen([os.path.join(ROOT, "scripts", "localnet.py"), seeker_wallet], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            for _ in range(60):
                try:
                    if rpc("localnet", "getHealth", []) == "ok":
                        break
                except OSError:
                    pass
                time.sleep(1)
        if args.chain == "testnet" and open_rewards("testnet", seeker_wallet, code) == 0:
            log("locking fresh testnet rewards for the seeker")
            for command in (["seeker", seeker_wallet], ["drops", seeker_wallet]):
                subprocess.run([os.path.join(ROOT, "scripts", "testnet.py"), *command], check=True)
        if args.chain != "simulated":
            seeker.launch()

        seeker.wait("Scan QR", timeout=20)
        log(f"seeker: hosting a {title} duo")
        host_duo(seeker, title)
        seeker.wait("JOIN CODE", timeout=20)

        guest.launch()
        log("guest: looking for the Formation nearby")
        guest.wait(host_name, timeout=60)
        guest.tap_text("Join")
        guest.wait("Waiting for host", timeout=20)
        guest.scroll_to("YOUR SHARE IF YOU UNLOCK IT")
        if args.wallet == "connect":
            guest.tap_text("Connect")
            approve_wallet(guest, args.approve)
        payout = guest.find("Lands in", prefix=True)
        payout_wallet = re.search(r"Lands in (\S+)", payout.get("text")).group(1) if payout is not None else None
        chain_wallet = wallet or (guest.pref("wallet") if args.wallet == "connect" else None)
        before = skr_balance(args.chain, chain_wallet) if chain_wallet and args.chain != "simulated" else None
        log(f"guest: share lands in {payout_wallet or 'nowhere yet (claim later)'}")

        seeker.tap_text("Begin", timeout=30)
        for phone in (seeker, guest):
            phone.tap_text("I'm ready", timeout=20)
        if args.driver == "overdrive":
            play_overdrive(seeker, guest, log)
        else:
            for phone in (seeker, guest):
                phone.tap(phone.wait(desc="Motion pad", timeout=20))
                phone.tap_text("Autoplay", timeout=10)
            log("playing on autoplay")
        unlock = seeker.wait(r"Unlock [\d,.]+ SKR", prefix="regex", timeout=240)
        if args.offline:
            offline_unlock(seeker, guest, unlock)
            finish(args, seeker, guest, chain_wallet, before)
            return
        seeker.tap(unlock)
        log("seeker: unlocking")
        seeker.wait("Your share went straight to your Seeker's wallet.", timeout=90)
        if chain_wallet:
            guest.wait("It's in your wallet", prefix=True, timeout=60)
        else:
            guest.scroll_to("YOU EARNED")
        finish(args, seeker, guest, chain_wallet, before)
    except (Failed, OverdriveFailed, LayoutUnavailable) as e:
        for phone in (seeker, guest):
            phone.screenshot("failed")
        log(f"FAIL: {e} (screens in {os.path.relpath(OUT, ROOT)})")
        sys.exit(1)
    finally:
        for phone, prefs in (saved or {}).items():
            phone.set_prefs(**prefs)
        if validator and not args.keep_chain:
            validator.terminate()
        if args.offline:
            seeker.shell("svc wifi enable; svc data enable")


def offline_unlock(seeker, guest, unlock):
    log("seeker: going offline and unlocking")
    seeker.shell("svc wifi disable; svc data disable")
    time.sleep(2)
    seeker.tap(unlock)
    seeker.wait("Couldn't unlock yet", timeout=90)
    guest.wait("Couldn't unlock yet", timeout=30)
    log("both phones report the win is saved; killing both apps")
    for phone in (seeker, guest):
        phone.shell(f"am force-stop {APP}")
    seeker.shell("svc wifi enable; svc data enable")
    time.sleep(5)
    log("seeker: back online, relaunching")
    seeker.launch()
    deadline = time.time() + 120
    while time.time() < deadline and "pending.unlocks" in seeker.prefs() and "opportunity" in (seeker.pref("pending.unlocks") or ""):
        time.sleep(3)
    if "opportunity" in (seeker.pref("pending.unlocks") or ""):
        seeker.tap_text("Unlock now", timeout=10)
        time.sleep(4)
        shown = [n.get("text") for n in seeker.nodes().iter("node") if n.get("text")]
        raise Failed(f"seeker: the saved win never unlocked; the screen says {shown[:6]}")
    log("seeker: the saved win unlocked on its own")


def finish(args, seeker, guest, chain_wallet, before):
    seeker.screenshot("won")
    guest.screenshot("won")
    if before is not None:
        share = 0
        for _ in range(30):
            share = skr_balance(args.chain, chain_wallet) - before
            if share > 0:
                break
            time.sleep(2)
        if not share:
            raise Failed(f"guest wallet {chain_wallet} received nothing on {args.chain}")
        log(f"guest wallet {chain_wallet} received {share / 1e6:g} SKR on {args.chain}")
    if args.offline:
        log("guest: relaunching to check the saved share")
        guest.launch()
        open_rewards_screen(guest)
        guest.wait("(?i)claimed" if chain_wallet else "Claim", prefix="regex" if chain_wallet else False, timeout=60)
        log("guest: the share saved at the seal caught up with the chain")
    log("PASS")


def open_rewards_screen(phone):
    avatar = next(n for n in phone.nodes().iter("node") if n.get("clickable") == "true" and bounds(n)[3] < 400 and bounds(n)[0] > 1000)
    phone.tap(avatar)
    phone.wait("Profile", timeout=10)
    for _ in range(6):
        row = phone.find("Your rewards")
        if row is not None:
            break
        phone.shell("input swipe 672 2400 672 900 400")
        time.sleep(0.5)
    _, top, _, bottom = bounds(phone.wait("Your rewards"))
    button = next(n for n in phone.nodes().iter("node") if n.get("text") == "Open" and bounds(n)[1] < bottom + 60 and bounds(n)[3] > top - 60)
    phone.tap(button)
    phone.wait("Rewards", timeout=10)



if __name__ == "__main__":
    main()

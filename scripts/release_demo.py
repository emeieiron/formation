#!/usr/bin/env python3
"""Exercise the installed release through visible UI; never inject profiles, rewards or game state.

Unlock devices and wallets, preserve existing Formation data, and configure emulator networking first.
--fresh clears Formation's data only. Use it only after saving an emulator snapshot or reward recovery.
The guest deliberately connects its wallet after the win to exercise the separate claim transaction.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor

import e2e
from e2e import APP, ACTIVITY, Failed, Phone, bounds, host_group, scroll_until
from overdrive_driver import play_overdrive


class ReleasePhone(Phone):
    def prefs(self):
        raise Failed("Release journeys must not access application preferences")

    def set_prefs(self, **values):
        raise Failed("Release journeys must not inject application state")

    def dismiss_stalls(self, root):
        if self.find(".+ isn.t responding", prefix="regex", root=root) is not None:
            raise Failed(f"{self.role}: Android reported an unresponsive application")
        if self.find("Viewing full screen", root=root) is not None:
            self.tap(self.wait("Got it"))


def labels(root):
    return [n.get("text") or n.get("content-desc") for n in root.iter("node")
            if n.get("text") or n.get("content-desc")]


def public_recipient(phone):
    node = phone.wait(desc=r"Wallet [1-9A-HJ-NP-Za-km-z]{32,44}", prefix="regex", timeout=15)
    return node.get("content-desc").removeprefix("Wallet ")


def onboard(phone, name, light):
    # Let the complete story play at its normal pace.
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        root = phone.nodes()
        edit = next((n for n in root.iter("node") if n.get("class") == "android.widget.EditText"), None)
        if edit is not None:
            break
        phone.dismiss_stalls(root)
        if phone.find("GAMES", root=root) is not None:
            raise Failed(f"{phone.role}: expected fresh onboarding but found Home")
        time.sleep(.4)
    else:
        raise Failed(f"{phone.role}: onboarding story did not reach the profile form")
    phone.tap(edit)
    focused = next((n for n in phone.nodes().iter("node")
                    if n.get("class") == "android.widget.EditText" and n.get("focused") == "true"), None)
    if focused is None:
        raise Failed(f"{phone.role}: profile field did not receive focus")
    phone.shell(f"input text {name}")
    phone.shell("input keyevent 4")
    phone.tap(phone.scroll_to(light))
    time.sleep(1)
    phone.tap(phone.scroll_to("Continue"))
    scroll_until(phone, "GAMES", timeout=30)


def claim_wallet(phone, amount, log, timeout=90):
    # Approval must show Formation and the exact configured test mint/amount. A changed prompt fails.
    deadline = time.monotonic() + timeout
    approved, trusted, connected = False, False, False
    while time.monotonic() < deadline:
        root = phone.nodes()
        text = labels(root)
        phone.dismiss_stalls(root)
        if "Claimed" in text:
            if not approved:
                raise Failed("The claim completed without observing its wallet approval")
            return
        for value in text:
            if any(part in value for part in ("No connection to Solana", "couldn't finish", "too long",
                                             "didn't go through", "Cancelled in the wallet", "failed")):
                raise Failed(f"{phone.role}: {value}")
        skip = phone.find(desc="Skip for now", root=root)
        if skip is not None:
            phone.tap(skip)
            time.sleep(.3)
            continue
        if "formation.mcxross.xyz" in text:
            connect = next((n for n in root.iter("node") if n.get("class") == "android.widget.Button"
                            and (n.get("text") == "Connect" or n.get("content-desc") == "Connect")), None)
            if connect is not None and not connected:
                phone.tap(connect)
                connected = True
                log("guest: approved Formation wallet connection")
            if "+" + str(amount) in text and "ARR9...7YU6" in text:
                slide = next((n for n in root.iter("node") if n.get("class") == "android.widget.Button"
                              and n.get("content-desc") == "Slide to approve"), None)
                if slide is not None:
                    if approved:
                        raise Failed("The release claim requested a second transaction approval")
                    left, top, right, bottom = bounds(slide)
                    width = int(phone.adb("shell", "wm size").strip().split("x")[-2].split()[-1])
                    phone.shell(f"input swipe {(left + right) // 2} {(top + bottom) // 2} "
                                f"{width - left} {(top + bottom) // 2} 500")
                    approved = True
                    log(f"guest: approved +{amount} devnet reward tokens")
                    time.sleep(.6)
                    continue
                trust = phone.find(desc="I trust this site", root=root)
                if trust is not None and not trusted:
                    phone.tap(trust)
                    trusted = True
        time.sleep(.2)
    raise Failed("The guest claim did not complete within the release journey deadline")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="emulator-5580")
    parser.add_argument("--guest", default="emulator-5582")
    parser.add_argument("--apk", type=Path, required=True, help="downloaded, signed release APK")
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--fresh", action="store_true", help="clear Formation data after preserving existing rewards")
    parser.add_argument("--candidate", action="store_true", help="allow a development APK for fix verification; never use for the final demo")
    parser.add_argument("--win-hold", type=float, default=6, help="hold the sealed win before tapping Unlock; use 35 to verify the recovery loop")
    parser.add_argument("--emulator-record", action="store_true", help="capture native video/audio through the emulator console (180 second maximum)")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    e2e.OUT = str(args.out)
    events = []
    result = {"started_at": int(time.time()), "apk_sha256": args.sha256, "release": not args.candidate, "devices": {}, "passed": False}

    def log(message):
        event = {"at": time.time(), "message": message}
        events.append(event)
        (args.out / "events.json").write_text(json.dumps(events, indent=2) + "\n")
        print(time.strftime("%H:%M:%S"), message, flush=True)

    phones = [ReleasePhone(args.host, "host", "android"), ReleasePhone(args.guest, "guest", "android")]
    host, guest = phones
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != args.sha256:
        raise Failed("Downloaded release APK checksum differs from the expected checksum")
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    build_tools = max((sdk / "build-tools").iterdir(), key=lambda p: [int(x) for x in re.findall(r"\d+", p.name)])
    badging = subprocess.check_output([str(build_tools / "aapt2"), "dump", "badging", str(args.apk)], text=True)
    if ("application-debuggable" in badging and not args.candidate) or "versionName='0.1.0'" not in badging:
        raise Failed("The supplied APK must be the non-debuggable v0.1.0 release")
    try:
        for i, phone in enumerate(phones):
            installed = phone.adb("shell", "pm", "path", APP).strip().removeprefix("package:")
            digest = hashlib.sha256(phone.adb("exec-out", "cat", installed, text=False)).hexdigest()
            if digest != args.sha256:
                raise Failed(f"{phone.role}: installed APK differs from the downloaded release")
            result["devices"][phone.role] = {"serial": phone.serial, "installed_sha256": digest}
            phone.adb("forward", f"tcp:{47000 + i}", "tcp:47000")
            if args.fresh:
                if phone.adb("shell", "pm", "clear", APP).strip() != "Success":
                    raise Failed(f"{phone.role}: could not reset Formation")
        if args.emulator_record:
            with ThreadPoolExecutor(max_workers=2) as executor:
                def start_capture(phone):
                    path = (args.out / f"{phone.role}-native.webm").resolve()
                    response = phone.adb("emu", "screenrecord", "start", "--size", "1344x2992",
                                         "--bit-rate", "20M", "--fps", "30", "--time-limit", "180", str(path))
                    if "KO:" in response:
                        raise Failed(f"{phone.role}: native capture could not start: {response}")
                    result["devices"][phone.role]["capture_started_at"] = time.time()
                list(executor.map(start_capture, phones))
        time.sleep(2)
        with ThreadPoolExecutor(max_workers=2) as executor:
            list(executor.map(lambda p: p.adb("shell", "am", "start", "-n", f"{APP}/{ACTIVITY}"), phones))
            log("Both verified apps launched for fresh onboarding (" + ("candidate" if args.candidate else "release") + ")")
            list(executor.map(lambda item: onboard(*item), [(host, "Maya", "Jade"), (guest, "Theo", "Nova")]))
        log("Both onboarding stories and profiles completed")
        time.sleep(3)
        host.tap(scroll_until(host, "Want to host? Become a test Seeker"))
        host.wait("PLAYABLE", timeout=120)
        log("host: test Seeker authorized through Home")
        host_group(host, "Overdrive", 2)
        host.wait("JOIN CODE", timeout=30)
        log("host: Overdrive Formation created")
        scroll_until(guest, "Maya", timeout=60)
        guest.tap(scroll_until(guest, "Join"))
        guest.wait("Waiting(…| for host)", prefix="regex", timeout=30)
        log("guest: joined Maya's Formation without binding a wallet")
        time.sleep(5)
        host.tap_text("Begin")
        for phone in phones:
            phone.wait("I'm ready")
        time.sleep(4)
        with ThreadPoolExecutor(max_workers=2) as executor:
            list(executor.map(lambda p: p.tap_text("I'm ready"), phones))
        log("Both participants ready; driving only visible partner clues")
        play_overdrive(host, guest, log)
        for phone in phones:
            phone.wait("FORMATION COMPLETE")
            phone.wait("Overdrive complete")
            phone.wait("12/12")
        log("Both devices completed all 12 waves and sealed the win")
        time.sleep(args.win_hold)
        host.wait(r"Unlock [\d,.]+ SKR", prefix="regex", timeout=2)
        guest.wait("Waiting for Maya to unlock the reward…", timeout=2)
        host.tap_text(r"Unlock [\d,.]+ SKR", prefix="regex")
        scroll_until(host, "Your share went straight to your Seeker's wallet.", timeout=90)
        scroll_until(guest, "It's yours to claim. Check Rewards for the deadline.", timeout=45)
        log("host: reward received; guest: explicit claim ready")
        time.sleep(6)
        guest.tap_text(r"Claim [\d,.]+ SKR", prefix="regex")
        guest.tap_text("Claim")
        guest.tap_text("Connect wallet")
        claim_wallet(guest, 75, log)
        log("guest: Claimed receipt visible")
        time.sleep(8)
        guest.tap_text("Done")
        guest.wait("Claimed")
        result["devices"]["guest"]["recipient"] = public_recipient(guest)
        host.tap_text("Done")
        host.tap(scroll_until(host, "Rewards"))
        host.wait("Claimed")
        result["devices"]["host"]["recipient"] = public_recipient(host)
        if not all(result["devices"][role].get("recipient") for role in ("host", "guest")):
            raise Failed("Both reward histories must identify their public recipient")
        for phone in phones:
            text = labels(phone.nodes())
            if any(part in value for value in text for part in (
                "Couldn't reach Solana", "Could not update reward status", "left the composition",
                "payments are pending", "Not saved yet")):
                raise Failed(f"{phone.role}: reward history contains an error: {text}")
            phone.screenshot("receipt")
        time.sleep(8)
        result["passed"] = True
        result["ui_completed_at"] = time.time()
        log("Full UI journey passed; both public recipients saved for independent chain verification")
    except Exception as error:
        result["failure"] = str(error)
        log("FAIL: " + str(error))
        for phone in phones:
            try:
                phone.screenshot("failed")
            except Exception:
                pass
        raise
    finally:
        if args.emulator_record:
            for phone in phones:
                try:
                    phone.adb("emu", "screenrecord", "stop")
                except Exception as error:
                    result["passed"] = False
                    result["capture_failure"] = str(error)
            if result["passed"]:
                for phone in phones:
                    path = args.out / f"{phone.role}-native.webm"
                    probe = json.loads(subprocess.check_output([
                        "ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)], text=True))
                    video = next(s for s in probe["streams"] if s["codec_type"] == "video")
                    elapsed = result["ui_completed_at"] - result["devices"][phone.role]["capture_started_at"]
                    if (video["width"], video["height"]) != (1344, 2992) or float(probe["format"]["duration"]) + 1 < elapsed:
                        result["passed"] = False
                        result["capture_failure"] = "Native capture was downscaled or ended before the journey completed"
                    if not any(s["codec_type"] == "audio" for s in probe["streams"]):
                        result["passed"] = False
                        result["capture_failure"] = "Native capture has no audio stream"
        result["finished_at"] = int(time.time())
        (args.out / "journey.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()

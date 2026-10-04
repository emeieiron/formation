"""Enable the existing debug assistance and capture unobstructed playfields."""
import time


def start_autoplay(phones, log):
    for phone in phones:
        phone.tap(phone.wait(desc="Motion pad", timeout=20))
        phone.tap_text("Autoplay", timeout=10)
        phone.tap(phone.wait(desc="Motion pad", timeout=10))
    log("playing on autoplay")
    time.sleep(2)
    for phone in phones:
        phone.screenshot("play")

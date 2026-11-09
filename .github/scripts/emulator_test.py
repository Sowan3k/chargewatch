#!/usr/bin/env python3
"""End-to-end test of ChargeWatch on an Android emulator. CI runs it after the build.

It installs the release APK, drives the UI with adb, and plays the "other phone"
by talking to ntfy.sh directly:

  1. install the APK and open the app
  2. create a group and read the group code from the QR dialog
  3. check that the phone publishes its battery level, and again after plugging in
  4. post fake reports from a second phone and check both alerts appear
  5. restart the app and check that replaying the backlog does not alert again
  6. leave the group and check the "left" message and that the service stopped

Usage: python3 emulator_test.py ChargeWatch.apk
"""
import json
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

PKG = "io.github.sowan3k.chargewatch"
NTFY = "https://ntfy.sh"
HEADERS = {"User-Agent": "ChargeWatch-CI"}
failures = []


def run(args, binary=False):
    r = subprocess.run(args, capture_output=True, text=not binary, timeout=180)
    return r.stdout if binary else r.stdout + r.stderr


def sh(cmd):
    return run(["adb", "shell", cmd])


def check(ok, what):
    print(("PASS  " if ok else "FAIL  ") + what, flush=True)
    if not ok:
        failures.append(what)
    return ok


def wait(fn, timeout=30, step=1.5):
    end = time.time() + timeout
    while True:
        value = fn()
        if (value is not None and value is not False and value != [] and value != "") or time.time() > end:
            return value
        time.sleep(step)


def ui_nodes():
    sh("rm -f /sdcard/ui.xml")
    sh("uiautomator dump /sdcard/ui.xml")
    xml = sh("cat /sdcard/ui.xml")
    start = xml.find("<?xml")
    if start < 0:
        return []
    try:
        return list(ET.fromstring(xml[start:]).iter("node"))
    except ET.ParseError:
        return []


def screen_text():
    return [n.get("text", "") for n in ui_nodes() if n.get("text")]


def tap(label, timeout=20):
    def find():
        for n in ui_nodes():
            if n.get("text", "").strip().lower() == label.lower():
                return n
        return None

    node = wait(find, timeout)
    if node is None:
        check(False, f"button '{label}' is on screen")
        return False
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    return True


def tap_desc(description, timeout=20):
    node = wait(lambda: next((n for n in ui_nodes() if n.get("content-desc") == description), None), timeout)
    if node is None:
        check(False, f"'{description}' button is on screen")
        return False
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    return True


def screenshot(name):
    with open(f"emulator-{name}.png", "wb") as f:
        f.write(run(["adb", "exec-out", "screencap", "-p"], binary=True))


def ntfy_reports(topic):
    url = f"{NTFY}/{topic}/json?poll=1&since=all"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=30) as r:
            lines = r.read().decode().splitlines()
    except Exception as e:  # noqa: BLE001
        print("ntfy poll failed:", e)
        return []
    reports = []
    for line in lines:
        try:
            event = json.loads(line)
            if event.get("event") == "message":
                reports.append(json.loads(event["message"]))
        except (ValueError, KeyError):
            pass
    return reports


def ntfy_publish(topic, body):
    req = urllib.request.Request(
        f"{NTFY}/{topic}", data=json.dumps(body).encode(), headers=HEADERS, method="POST"
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        r.read()


def notifications():
    return sh("dumpsys notification --noredact")


def service_running():
    return "isForeground=true" in sh(f"dumpsys activity services {PKG}")


def main():
    apk = sys.argv[1]

    # 1. Install and open
    out = run(["adb", "install", "-r", apk])
    print(out.strip())
    if not check("Success" in out, "APK installs on Android 14"):
        return
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    for cmd in ("set ac 0", "set usb 0", "set wireless 0", "set level 60"):
        sh(f"dumpsys battery {cmd}")
    sh(f"am start -W -n {PKG}/.MainActivity")
    check(wait(lambda: "Pair your phones" in screen_text(), 40), "app opens on the pairing screen")
    screenshot("1-pairing")

    # 2. Create a group
    tap("Create group")
    code_re = re.compile(r"^[0-9a-f]{4}(-[0-9a-f]{4}){4}$")
    code = wait(lambda: next((t for t in screen_text() if code_re.match(t)), None), 20)
    if not check(bool(code), "Create group shows a QR code and group code"):
        return
    screenshot("2-qr")
    topic = "chargewatch-" + code.replace("-", "")
    print("topic:", topic)
    check(wait(service_running, 20), "watch service runs as a foreground service")

    # 3. Publishing
    own = wait(
        lambda: [m for m in ntfy_reports(topic) if m.get("level") == 60 and m.get("charging") is False],
        40, 3,
    )
    check(bool(own), "phone publishes its battery to ntfy (60%, on battery)")
    self_id = own[0].get("id") if own else None
    sh("dumpsys battery set ac 1")
    time.sleep(2)
    sh("dumpsys battery set level 61")
    check(
        wait(lambda: any(m.get("level") == 61 and m.get("charging") is True for m in ntfy_reports(topic)), 40, 3),
        "plugging in and charging to 61% is published",
    )

    # 4. Alerts from another phone
    other = {"v": 1, "id": "c1c1c1c1c1c1c1c1", "name": "Test Phone"}
    for level in (78, 79, 80):
        ntfy_publish(topic, {**other, "level": level, "charging": True})
        time.sleep(2)
    check(wait(lambda: "Test Phone is 80% charged" in notifications(), 30), "alert shows: Test Phone is 80% charged")
    for level in (99, 100):
        ntfy_publish(topic, {**other, "level": level, "charging": True})
        time.sleep(2)
    check(wait(lambda: "Test Phone is fully charged" in notifications(), 30), "alert shows: Test Phone is fully charged")

    # 5. Restart: the backlog replays, but no alert may fire again
    sh(f"am force-stop {PKG}")
    time.sleep(2)
    sh(f"am start -W -n {PKG}/.MainActivity")
    check(wait(lambda: any(t.startswith("Connected to") for t in screen_text()), 40), "reconnects to the relay after restart")
    time.sleep(8)
    check("Test Phone is" not in notifications(), "replaying the backlog does not repeat alerts")
    texts = screen_text()
    check("Test Phone" in texts and "100" in texts, "main screen lists Test Phone at 100%")
    check(any(t.startswith("Updated") for t in texts), "main screen shows when Test Phone last updated")
    screenshot("3-main")

    # Settings sheet, then the same screen in dark mode
    if tap_desc("Settings"):
        check(wait(lambda: "Alert level" in screen_text(), 10), "settings sheet opens")
        time.sleep(1)
        screenshot("4-settings")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1)
    sh("cmd uimode night yes")
    time.sleep(4)
    check(wait(lambda: "Test Phone" in screen_text(), 15), "dark mode renders the main screen")
    screenshot("5-main-dark")
    sh("cmd uimode night no")
    time.sleep(4)

    # 6. Leave the group
    sh("input swipe 500 1800 500 400 300")  # scroll to the buttons on small screens
    time.sleep(1)
    tap("Leave group")
    time.sleep(1)
    tap("Leave")
    check(
        wait(lambda: any(m.get("id") == self_id and m.get("left") is True for m in ntfy_reports(topic)), 30, 3),
        "leaving sends a 'left' message to the other phones",
    )
    check(wait(lambda: not service_running(), 20), "service stops after leaving")
    check(wait(lambda: "Pair your phones" in screen_text(), 20), "app returns to the pairing screen")


if __name__ == "__main__":
    try:
        main()
    finally:
        crashes = run(["adb", "logcat", "-b", "crash", "-d"])
        check(PKG not in crashes, "no crashes in logcat")
        if failures:
            print("\n--- logcat (ChargeWatch, errors) ---")
            log = run(["adb", "logcat", "-d", "-v", "brief", "*:W"])
            print("\n".join(line for line in log.splitlines() if "chargewatch" in line.lower() or "AndroidRuntime" in line)[-6000:])
            print(crashes[-4000:])
    print(f"\n{len(failures)} failure(s)")
    sys.exit(1 if failures else 0)

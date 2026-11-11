#!/usr/bin/env python3
"""End-to-end test of ChargeWatch on an Android emulator. CI runs it after the build.

It installs the release APK, drives the UI with adb, and plays the "other phone"
by talking to ntfy.sh directly:

  1. install the APK, open the app and name the phone in onboarding
  2. create a group and read the group code from the QR dialog
  3. check that the phone publishes its battery level, and again after plugging in
  4. post fake reports from a second phone and check both alerts appear
  5. restart the app and check that replaying the backlog does not alert again
  6. close the app with nothing charging: the service stops; a background check delivers a missed alert
  7. leave the group and check the "left" message and that the service stopped
  8. join again, then uninstall, reinstall and join again: the phone must stay one device

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
        if len(failures) <= 2:
            describe_screen()
    return ok


def describe_screen():
    """Prints what is on screen, to explain a failure without the screenshots."""
    top = [l.strip() for l in sh("dumpsys activity activities").splitlines() if "mResumedActivity" in l or "topResumedActivity" in l]
    print("      resumed:", top[:2])
    print("      windows:", [l.strip() for l in sh("dumpsys window windows").splitlines() if "mCurrentFocus" in l][:1])
    print("      texts:", screen_text()[:25])


def wait(fn, timeout=30, step=1.5):
    end = time.time() + timeout
    while True:
        value = fn()
        if (value is not None and value is not False and value != [] and value != "") or time.time() > end:
            return value
        time.sleep(step)


def ui_nodes(dismiss=True):
    sh("rm -f /sdcard/ui.xml")
    sh("uiautomator dump /sdcard/ui.xml")
    xml = sh("cat /sdcard/ui.xml")
    start = xml.find("<?xml")
    if start < 0:
        return []
    try:
        nodes = list(ET.fromstring(xml[start:]).iter("node"))
    except ET.ParseError:
        return []
    # CI emulators sometimes freeze their own launcher, and Android puts an
    # "X isn't responding" dialog over everything. It is not ours: close it and look again.
    if dismiss and any("isn't responding" in (n.get("text") or "") for n in nodes):
        for label in ("Close app", "Wait"):
            node = next((n for n in nodes if n.get("text") == label), None)
            if node is not None:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
                print(f"      dismissed a system 'isn't responding' dialog ({label})", flush=True)
                break
        time.sleep(2)
        return ui_nodes(dismiss=False)
    return nodes


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


def has_desc(description):
    return any(n.get("content-desc") == description for n in ui_nodes())


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


def release_has_version_file():
    url = "https://github.com/Sowan3k/chargewatch/releases/download/latest/version.json"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=30) as r:
            return r.status == 200
    except Exception:  # noqa: BLE001
        return False


def notifications():
    return sh("dumpsys notification --noredact")


def service_running():
    return "ServiceRecord" in sh(f"dumpsys activity services {PKG}")


def service_foreground():
    return "isForeground=true" in sh(f"dumpsys activity services {PKG}")


def main():
    apk = sys.argv[1]

    # 1. Install and open
    out = run(["adb", "install", "-r", apk])
    print(out.strip())
    if not check("Success" in out, "APK installs on Android 14"):
        return
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    # The same as setting battery use to Unrestricted, which the app asks users to do.
    sh(f"dumpsys deviceidle whitelist +{PKG}")
    for cmd in ("set ac 0", "set usb 0", "set wireless 0", "set level 60"):
        sh(f"dumpsys battery {cmd}")
    # CI emulators sometimes show a "System UI isn't responding" dialog after boot.
    # Ask Android not to show background ANR dialogs, and close any that are up.
    sh("settings put secure anr_show_background 0")
    sh("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)
    sh(f"am start -W -n {PKG}/.MainActivity")

    # Onboarding: name the phone (here with the system name), then the pairing step
    check(wait(lambda: "Name this phone" in screen_text(), 40), "onboarding asks for the phone's name")
    screenshot("0-name")
    tap("Use system name")
    time.sleep(1)
    tap("Continue")
    check(wait(lambda: "Pair your phones" in screen_text(), 20), "onboarding moves on to pairing")
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
    check(wait(service_running, 20), "watch service runs while the app is open")
    check(not service_foreground(), "no status notification while nothing is charging")

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
    check(wait(service_foreground, 20), "on power, the status notification appears")
    check(wait(lambda: "This phone is charging" in notifications(), 20), "it says this phone is charging")

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
    sh("cmd statusbar expand-notifications")
    time.sleep(3)
    screenshot("6-alerts")
    sh("cmd statusbar collapse")
    time.sleep(1)

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
        check("Appearance" in screen_text(), "settings offer Appearance (system, light, dark)")
        if release_has_version_file():
            check(
                wait(lambda: any("Up to date" in t for t in screen_text()), 20),
                "in-app update check reads the release (this build is newer)",
            )
        else:
            print("SKIP  update check: the release has no version.json yet")
        time.sleep(1)
        screenshot("4-settings")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1)
    # Dark mode two ways: following the system, then with the switch in the header.
    sh("cmd uimode night yes")
    check(wait(lambda: has_desc("Switch to light mode"), 25), "dark mode follows the system setting")
    sh("cmd uimode night no")
    check(wait(lambda: has_desc("Switch to dark mode"), 25), "and returns to light with it")
    if tap_desc("Switch to dark mode"):
        check(wait(lambda: has_desc("Switch to light mode"), 15), "the switch in the header turns dark mode on")
        check("Test Phone" in screen_text(), "dark mode renders the main screen")
        time.sleep(1)
        screenshot("5-main-dark")
        tap_desc("Switch to light mode")
        check(wait(lambda: has_desc("Switch to dark mode"), 15), "and turns it off again")

    # Motion. The rest of the test runs with system animations off, which the app respects by
    # drawing still frames. Turn them on for a moment: the screen must move, and not crash.
    sh("settings put global animator_duration_scale 1")
    sh(f"am force-stop {PKG}")
    time.sleep(1)
    sh(f"am start -W -n {PKG}/.MainActivity")
    # A fresh start plays the opening animation. Keep a few frames of it to look at.
    for n in "abc":
        with open(f"emulator-10-intro-{n}.png", "wb") as f:
            f.write(run(["adb", "exec-out", "screencap", "-p"], binary=True))
    time.sleep(5)
    frame_a = run(["adb", "exec-out", "screencap", "-p"], binary=True)
    time.sleep(1.5)
    frame_b = run(["adb", "exec-out", "screencap", "-p"], binary=True)
    check(len(frame_a) > 10_000 and frame_a != frame_b, "with animations on, the background is moving")
    check(PKG not in run(["adb", "logcat", "-b", "crash", "-d"]), "and nothing crashes while it moves")
    with open("emulator-9-motion.png", "wb") as f:
        f.write(frame_b)
    sh("settings put global animator_duration_scale 0")
    sh(f"am force-stop {PKG}")
    time.sleep(1)
    sh(f"am start -W -n {PKG}/.MainActivity")
    check(wait(lambda: any(t.startswith("Connected to") for t in screen_text()), 40), "with animations off again, the screen holds still and reconnects")

    # 6. Nothing charging and the app closed: the service stops, with no notification and
    #    no connection. The background job checks about every 15 minutes (forced here).
    sh("dumpsys battery set ac 0")  # this phone off power; Test Phone is already full
    sh("input keyevent KEYCODE_HOME")
    check(wait(lambda: not service_running(), 30), "with nothing charging and the app closed, the service stops")
    check("is charging" not in notifications() and "Watching" not in notifications(), "and the status notification is gone")
    for level, plugged in ((50, False), (79, True), (80, True)):
        ntfy_publish(topic, {**other, "level": level, "charging": plugged})
        time.sleep(2)
    time.sleep(4)
    check("Test Phone is 80% charged" not in notifications(), "nothing stays connected while nothing charges")
    print(sh(f"cmd jobscheduler run -f {PKG} 1").strip())
    check(wait(lambda: "Test Phone is 80% charged" in notifications(), 40), "the background check delivers the missed alert")
    check(wait(service_foreground, 30), "and goes live while Test Phone charges")
    check(wait(lambda: "Test Phone is charging" in notifications(), 20), "the notification names the charging phone")
    sh("cmd statusbar expand-notifications")
    time.sleep(3)
    screenshot("8-charging")
    sh("cmd statusbar collapse")
    sh(f"am start -W -n {PKG}/.MainActivity")
    time.sleep(2)

    # One phone, one identity. First: the code of the group it is already in changes nothing.
    link = f"chargewatch:{code}"

    def open_link():
        sh(f"am start -a android.intent.action.VIEW -d {link} -n {PKG}/.MainActivity")

    def phone_ids():
        return {m.get("id") for m in ntfy_reports(topic)} - {other["id"]}

    def own_reports():
        return sum(1 for m in ntfy_reports(topic) if m.get("id") == self_id and not m.get("left"))

    open_link()
    time.sleep(3)
    check("Join this group?" not in screen_text(), "the code of the group it is already in does not join it again")
    check(phone_ids() == {self_id}, "and the phone is still one device")

    # 7. Leave the group
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

    # 8. Join again, then reinstall and join again: other phones must still see one device.
    count = own_reports()
    open_link()
    check(wait(lambda: "Join this group?" in screen_text(), 20), "a group link asks before joining")
    tap("Join")
    check(wait(lambda: own_reports() > count, 60, 3), "after leaving and joining again the phone reports")
    check(phone_ids() == {self_id}, "leaving and joining again does not add a second device")

    count = own_reports()
    print(run(["adb", "uninstall", PKG]).strip())
    check("Success" in run(["adb", "install", apk]), "the app installs again after an uninstall")
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    sh(f"dumpsys deviceidle whitelist +{PKG}")
    open_link()
    check(wait(lambda: "Join this group?" in screen_text(), 40), "the fresh install opens the group link")
    tap("Join")
    check(wait(lambda: own_reports() > count, 60, 3), "after a reinstall the phone reports under its old identity")
    check(phone_ids() == {self_id}, "a reinstall does not add a second device")

    # The launcher icon as Android draws it (adaptive icon with gradient strokes)
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)
    sh("input swipe 540 1900 540 500 300")
    time.sleep(3)
    screenshot("7-launcher")


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

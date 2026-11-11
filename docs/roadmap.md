# ChargeWatch roadmap

This plan evaluates a set of feature requests against the code as of 1.0.29. The goal is a better version of the same app: small, private, light on battery, and honest about what it knows. It recommends five features for the next release and leaves the rest for later or rules them out.

## Release signing check (1.0.29)

The published `ChargeWatch.apk` was downloaded from the `latest` release and checked on 2026-11-11.

Its SHA-256 is `966deaa0ab4a7b78f63bf0f2b9225de71b7521a66335ec306b755e2727f52967` and its size is 437,008 bytes, both matching `version.json`. That is what the in-app updater checks before it installs.

The v3 signer, which Android 9 and newer use, is `55ae2f76712ae8fb9525bf09ef77acff3ab7f14051e42638b60005ea51cbf5b5` (CN=ChargeWatch release, RSA 4096). Its signature and the APK content digests verify. The v1 and v2 signer, which Android 8.0 and 8.1 use, is the retired key `8131bb47...f44b`, as intended.

The proof of rotation leads from the retired key to the release key. Each step is signed by the key before it. The retired key keeps only the installed-data right, with no rollback. The CI run for this build (run 29) passed the emulator rotation test. An old-key copy updated to the release and kept its data, and the old-key copy could not replace it afterwards.

Two cases remain untested. The test's "old app" is the current code signed with the old key, not a real 1.0.25 binary. No test runs on Android 8.x either. Both should work from the signatures alone. A one-off check would close them: install the 1.0.25 artifact from Actions run 25 (if it has not expired) or an old phone's copy, then update in place.

## What already exists

| Request | State today |
| --- | --- |
| 1. Per-device alert levels | Missing. One global level (`Prefs.threshold`). The 100% alert cannot be turned off. |
| 2. Notification options | Partial. No duplicate alerts on fluctuation is done and tested (once per charge, a 5% re-arm margin, persisted flags, message-id de-dup). Sound and vibration are only in Android's channel settings. No mute, no start or unplug alerts, no quiet option. |
| 3. Connection health | Partial. A status line, "Updated X ago", an "Offline" pill after two missed heartbeats, and banners for notifications and battery optimization. No relay test, no test message, no single checklist. |
| 4. Clearer alert text | Partial. "Pixel 7 is 80% charged". It shows no charging state and no report age. |
| 5. Brand-specific setup | Partial. A generic text, a Xiaomi Autostart button and a dontkillmyapp.com link. |
| 6. Time to target | Missing. |
| 7. Widget | Missing. |
| 8. Alert on the charging phone itself | Missing. Everything stops when no group is set. |
| 9. Card clarity | Mostly done: pills, grey stale cards, update age, an alert mark on the bar. Cards stay tall with three or more phones. |
| 10. Verifiable releases | Done (see above). |
| 11. Message authentication | Missing. Anyone who knows the group id can post. |
| 12. Real-world tests | Partial. The emulator test covers restarts, backlog replay, background checks and rejoining. It does not cover Doze, network loss, Battery Saver or reboots. |

## Reliability issues found while reviewing

Two issues work against the user's main worry, "is it working?". They belong in the next release whatever else goes in. Both are now fixed, together with feature 1 below.

The heartbeat is not saved. `Prefs.saveDevices` drops each phone's heartbeat. After the app restarts, a phone on battery that promises a report every 60 minutes counts as a 30-minute phone. It turns "Offline" after about 65 minutes instead of 125. The same value decides whether the receiver goes live, so this can also delay alerts.

Late alerts are dropped silently. An alert fires only if the relay received the message less than 20 minutes earlier. The rule stops alerts from firing when the 12-hour backlog replays after joining. It also drops real alerts when a receiver without the battery exemption sleeps in Doze for longer than 20 minutes, which is common on a quiet night. The user then gets no 80% alert at all. A late alert that says it is late is better than none.

## Evaluation

Battery cost and APK size are relative to today's app (437 KB, no live connection while nothing charges).

| Feature | Use | Effort | Battery | APK | Privacy, Android notes | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| 1. Per-device alerts | High | Low to medium | None | ~3 KB | Stored only on this phone, keyed by device id (stable across renames and reinstalls) | Next release |
| 2a. Quiet or audible alerts, mute | High | Low | None | ~2 KB | One more channel ("Quiet alerts"). Sound and vibration stay in Android's channel settings. | Next release |
| 2b. Started charging and unplugged early | Medium to high | Low | None | ~1 KB | Off by default. The unplug alert is fast because the receiver is live during a charge. | Next release |
| 3. Status check and test | High | Medium | Only when used | ~6 KB | The test message is ignored by older versions (no level, so `Relay.parse` rejects it) | Next release |
| 4. Honest alert text | High | Low | None | <1 KB | Uses the relay timestamp already in every message | Next release |
| 5. Brand setup guide | High for Xiaomi and Samsung owners | Low | None | ~3 KB | Text plus existing settings links. No new permissions. | Next release |
| 6. Time to target | Medium | Medium | None | ~2 KB | Uses reports already received. Hide it at charge limits. | Second release |
| 9. Compact cards, delayed state | Medium | Low | None | ~1 KB | | Delayed state next release (part of 3), compact layout second |
| 8. Local self-alert | Medium | Medium | None while in a group | ~2 KB | Without a group, the lifecycle gating has to change. That is the risky part. | Second release, in-group first |
| 12. More emulator scenarios | High | Medium | n/a | 0 | CI time only | Doze and network loss next release, the rest second |
| 7. Widget | Low to medium | Medium | Low if passive | ~8 KB | Must not poll. Show clock times, not "X min ago". | Third release, optional |
| 11. Encryption and authentication | Low to medium | Medium to high | None | ~3 KB (javax.crypto) | Needs a new pairing code format and a mixed-version plan | Third release, design first |

### What to leave out

Each of these would cost more than it gives, so the plan leaves them out:

- An in-app sound picker or vibration editor. Android's channel settings already do this better, and apps cannot change a channel's sound after creating it.
- Firebase or any push service for instant delivery while the receiver sleeps. It needs a backend or a Firebase project and breaks the no-backend promise.
- Polling more often than every 15 minutes, exact alarms, or a permanent connection to improve delivery during Doze. Each one costs battery all day to save a few minutes now and then.
- A widget that refreshes on a timer or runs its own service.
- A scrolling widget list (RemoteViewsService). Three fixed rows are enough.
- Per-device keys or key exchange for message authentication. One shared group secret is enough for this threat model.
- Any Accessibility service, root feature, usage-stats permission or charge control.

## Recommended next release (1.1): five features

All five are local changes. Only the test message adds a field to the protocol, and older versions ignore it. Pairing is unchanged.

### 1. Honest, informative alerts (done)

Includes the two reliability fixes above.

Alert titles name the state: "Samsung S24 reached 80% · Charging", "Samsung S24 is full · Still on the charger". The notification's time is set to when the report reached the relay (`setWhen`), so the shade shows when it happened, not when the phone noticed. If delivery was more than 2 minutes late, the text says so: "Reported 18 min ago. Android delayed the check."

Late alerts: a transition found up to 2 hours late still alerts, with that wording, if this phone already knew the device before the check. This keeps backlog replays after joining quiet. If 80% and 100% both happened during the gap, only the newest is shown, because both alerts share one notification slot per phone. The heartbeat is saved with each device.

Real-world behavior. A receiver in your pocket overnight without Unrestricted battery wakes at 03:40 and finds that the S24 hit 80% at 03:05. Today you get nothing. With this change you get "Samsung S24 reached 80% · Charging · Reported 35 min ago". With the app open, or the receiver live, alerts arrive within seconds and read as before, with no "reported" line.

### 2. Per-device alert settings

Tapping a phone's card opens its sheet. The sheet holds the alert level (Off, the existing presets, or a custom 5 to 99%), "Alert when full" (on by default), the style (Sound or Quiet), and Mute: for 1 hour, until this charge ends, or until I unmute. The global setting becomes the default for phones without their own setting, so existing users see no change. The card shows the phone's own alert mark and a muted icon when muted.

Quiet alerts go to a new low-importance channel. They appear in the shade without sound or vibration. Sound and vibration for audible alerts stay in Android's channel settings, linked from the sheet.

Real-world behavior. The S24 alerts at 80% with sound. The Poco alerts at 90% quietly. The Pixel alerts only when full. You mute the Poco "until this charge ends" while it charges overnight beside you. It alerts again on its next charge without you remembering to unmute it. Muting never stops the card from updating.

### 3. Status check and test message

A "Check connection" row in Settings, also shown from a card that has gone quiet, opens one sheet with plain answers:

- Relay: reachable, with the round trip in ms, from one poll of the group's topic when the sheet opens. Nothing runs in the background.
- This phone: when it last sent a report, and whether that send failed.
- Each phone: its last report, and Fresh, Delayed or Not reporting.
- Setup: notifications on, the alert channel not blocked, battery Unrestricted, background not restricted, Battery Saver, Data Saver. Each item has a button to the setting.

Two buttons follow. "Show a test notification" checks this phone's notifications at once. "Send a test to my other phones" posts a small test message. Each other phone shows "Test from Poco X8 · sent 2 min ago", so you see the real delivery delay of the real path. One test per minute at most.

The "Offline" pill becomes three states that do not claim more than the app knows. Up to one heartbeat plus 10 minutes, it shows "Updated 12 min ago". Up to two heartbeats plus 5 minutes, it shows "Delayed": "No report for 50 min. Android may be holding it back." After that, it shows "Not reporting": "Last report 3 h ago. It may be off, offline or asleep."

Real-world behavior. The S24 charges in another room and the card says "Updated 4 min ago · Charging". You tap the test button. The S24 buzzes within seconds, because it is live while charging, and you stop wondering. A Samsung receiver with ChargeWatch in Sleeping apps shows a red line under Setup with a button to fix it.

### 4. Background setup guide for your brand

The "Background and battery" sheet detects `Build.MANUFACTURER` and shows three or four short steps for that brand. It covers Samsung (Never sleeping apps, battery Unrestricted), Xiaomi, Redmi and Poco (Autostart, No restrictions, lock in Recents), Oppo and Realme (Allow background activity, Auto launch), Vivo and iQOO (High background power use, Autostart) and OnePlus (Battery optimization: Don't optimize, Auto launch). It also explains why Android may delay alerts. A button opens the nearest settings screen the app can reach. Steps note that menu names vary by version. Other brands get the generic text and the dontkillmyapp.com link.

Real-world behavior. A Poco owner opens the sheet and sees the Autostart step first with a button that opens it, instead of a paragraph about five brands.

### 5. Optional "started charging" and "unplugged early" alerts

Both are per device and off by default. "Started charging" tells you when a phone goes on power. "Unplugged before its alert level" tells you when a phone stops charging below its mark. It catches a loose cable or a dead socket. It waits for one more report or 2 minutes before alerting, so a cable that wiggles does not spam, and it uses the same once-per-charge logic as other alerts.

Real-world behavior. The unplugged alert is the fast one: while a phone charges, the receiver is live, so it arrives within seconds. "Started charging" can take up to about 15 minutes when the receiver is idle, because the receiver only learns about it at its next background check. The text then says how long ago it happened.

### Tests for this release

Unit tests cover per-device thresholds and re-arming, the full toggle, mute expiry, late-alert rules (known device versus backlog replay), the heartbeat round trip through `Prefs`, test-message parsing (old versions ignore it), and freshness states. The emulator test adds per-device levels, a quiet alert, a test message, Doze (`dumpsys deviceidle force-idle`) with a late alert after unforce, and a network drop (`svc wifi disable` / `svc data disable`) during a charge. Existing tests stay unchanged except where alert wording changes.

Expected size: under 460 KB. No new permissions, dependencies or background work.

## Second release (1.2)

Time to target. Keep the last reports of the current charge per phone, with relay timestamps, in memory and in prefs. Phones report every 1% above 50% while charging, so the data is there, even when it arrives late in one batch. Show "about 25 min to 80%" only with at least three reports spanning 5 minutes or more. Use the recent rate and slow it down above 80%. Show "Estimating…" before that. Hide the estimate when the level has not moved for 20 minutes, which usually means a charge limit such as 80% or 85%. The full-charge estimate is labeled rough.

Compact cards. With three or more other phones, cards switch to one line each: the name, a large level, the charging pill, a thin bar and the update age.

Local alert on this phone. In a group, the service is already live while this phone charges and already reads its battery, so the alert costs nothing. Without the battery exemption, it comes from the 15-minute check instead. A second step can allow it without a group. That needs care in `BootReceiver`, `Background.check` and `WatchService`, which all stop when no group is set today.

More scenarios: reboot during a charge, Battery Saver on, two phones charging at once, and repeated charge sessions.

## Third release (1.3), optional

Widget. Up to three rows ("Samsung S24 · 78% · Charging · 14:32"), updated only when the service or a background check already receives news. It shows the clock time of the last report, because it cannot refresh "X min ago" without waking the phone.

Encryption and authentication. Today the group id is both the topic and the only secret. Anyone who has it can read and post. That includes the relay operator and anyone who sees server logs. The fix is a separate 128-bit key in the QR code that never goes to the relay. Messages are encrypted and authenticated with AES-GCM (in Android, no dependency). The sender's own timestamp and a counter go inside, so replays and forged times are rejected.

It protects against the relay operator and anyone who only learns the topic name. It cannot protect against someone who holds the QR code, as with any shared-key pairing. The costs: a new code format (`chargewatch:<group>.<key>`), older versions cannot join new groups (they are told to update), existing groups stay plaintext until re-created, and debugging gets harder. The harm prevented, a fake "100%" alert or a leaked phone name and level, is small. So this waits until the usability work is done.

## Delivery expectations

This is what users can expect, based on how the code works. The emulator scenarios above will check the cases marked as testable.

| Situation | Alert timing |
| --- | --- |
| Receiver has the app open | Seconds |
| Another phone charging, receiver battery Unrestricted | Seconds for the 80%, full and unplugged alerts once the receiver is live. It goes live at its next check after the charge starts, usually within 15 minutes. |
| Receiver screen locked for hours, Unrestricted | Same as above. Exempt apps keep network access in Doze. |
| Receiver not Unrestricted | Up to about 15 to 20 minutes, longer in deep Doze. A transition found up to 2 hours late still alerts and says when the phone reported. Before 1.1, anything later than 20 minutes was dropped. |
| Wi-Fi to mobile data | The stream reconnects at once on the network change. Nothing is lost (12-hour relay cache). |
| Network lost for a while | The app waits for the network and resumes from the last message id. Alerts that are still relevant fire, marked late. |
| Phone rebooted | The boot receiver runs a check and goes live if a phone is charging. |
| Battery Saver on the receiver | Background checks can be deferred longer. The live connection keeps working if it was already running. |
| Several phones charging | Each phone has its own alert slot. The status notification names the first and counts the rest. |
| Charge interrupted below the mark | Alerts re-arm. The next charge alerts again. With option 5 on, you hear about the unplug. |
| Repeated charge sessions | One alert per mark per session. A phone hovering at 99/100% or held at 80% does not repeat. |

ChargeWatch never stops a phone from charging and cannot promise instant delivery while Android holds the receiver asleep. The app and its notifications should keep saying so.

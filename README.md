# ScanPesa

ScanPesa reads M-PESA payment details from signs, stickers and receipts with the camera. It works out whether they are **Send Money**, **Paybill**, **Till (Buy Goods)** or **Pochi la Biashara**. Then it opens the app you pick (M-PESA app, SIM Toolkit or One App), goes to the right screen and types the numbers in.

**It always stops before the amount. You type the amount and PIN yourself.**

- Kotlin, Jetpack Compose (Material 3), minSdk 26, targetSdk 37
- CameraX + ML Kit on-device text recognition (works offline)
- AccessibilityService driven by an editable `flows.json`
- Room history (last 50 scans)

---

## 1. Open the project in Android Studio

1. Install a recent **Android Studio** (it must support AGP 9.5 / Gradle 9.9).
2. Choose **File › Open** and select this folder (the one with `settings.gradle.kts`).
3. Let Gradle sync. If Android Studio asks to install **SDK Platform 37.2** or Build-Tools, accept. Compose 1.13 needs compileSdk 37.1 or later. `targetSdk` is 37.
4. Gradle runs on JDK 17 or newer. Android Studio's bundled JDK is fine.

From the command line (Windows):

```bat
gradlew.bat assembleDebug      :: builds app\build\outputs\apk\debug\app-debug.apk
gradlew.bat test               :: runs the parser + flow-engine unit tests
```

`local.properties` holds `sdk.dir`. Android Studio writes it for you.

## 2. Install on your phone over USB

1. On the phone, open **Settings › About phone** and tap **Build number** 7 times. That turns on Developer options.
2. Go to **Settings › System › Developer options** and turn on **USB debugging**.
3. Plug the phone in. Tap **Allow** on the "Allow USB debugging?" prompt.
4. Then do one of these:
   - In Android Studio, pick your phone in the device list and press **Run ▶**, or
   - From a terminal:
     ```bat
     %LOCALAPPDATA%\Android\Sdk\platform-tools\adb devices
     %LOCALAPPDATA%\Android\Sdk\platform-tools\adb install -r app\build\outputs\apk\debug\app-debug.apk
     ```

## 3. Enable the accessibility service

The first time you open ScanPesa, it asks for the camera and then explains the auto-fill service. Tap **Open Accessibility Settings**, then:

- **Stock Android / Pixel:** Accessibility › *Downloaded apps* › **ScanPesa auto-fill** › On
- **Samsung:** Accessibility › *Installed apps* › **ScanPesa auto-fill** › On
- **Tecno / Infinix / itel / Xiaomi:** Accessibility › *Downloaded apps* (or *More downloaded services*) › **ScanPesa auto-fill**

On Android 13 and later, sideloaded apps can't turn on accessibility until you allow it. If the switch is greyed out ("Restricted setting"), go to **Settings › Apps › ScanPesa**, tap **⋮ › Allow restricted settings**, then try again.

The home screen shows **Auto-fill is ON / OFF / ON but not running**. "Not running" means your phone's battery saver stopped the service (common on Tecno, Infinix, itel and Xiaomi phones). Switch it OFF and ON again, and set **App info › Battery › Unrestricted** for ScanPesa.

After each Go, the home screen shows **Last auto-fill**: either done, or the step where it stopped and why. Tap **Show steps** and then **Share this log** to send it to whoever is tuning the flows.

### Accessibility disclosure (shown in the app, as Google Play requires)

> **How ScanPesa uses Accessibility**
> - **What it does:** When you tap Go, ScanPesa opens the app you picked (M-PESA app, SIM Toolkit or One App). It taps the menu items for you and types in only the phone, paybill, account or till number you just confirmed.
> - **Where it stops:** It stops as soon as the amount screen appears. You always type the amount and your PIN yourself.
> - **What it never does:** It never reads, stores or types your PIN or any password field. It never types an amount. It never presses Send, Pay or Confirm.
> - **Which apps:** It only works inside those three Safaricom/SIM apps, and only for up to 20 seconds after you tap Go. Then it stops and tells you which step it reached.
> - **Your data:** It reads the on-screen labels of those apps only to find the right buttons and fields. Nothing is collected, stored or sent anywhere, and ScanPesa has no internet access to its own servers. Debug mode (off by default) shows those labels on your own phone so you can fix menu wording.
> - **Your choice:** You can switch it off at any time in Settings › Accessibility. ScanPesa still scans without it, and you then type the numbers yourself.

## 4. Find the real package names with adb

The flows assume these packages:

| Target | Assumed package |
|---|---|
| M-PESA app | `com.safaricom.mpesa.lifestyle` |
| One App | `com.safaricom.mysafaricom` |
| SIM Toolkit | `com.android.stk` (SIM 2 on some dual-SIM phones: `com.android.stk2`) |

Check them on **your** phone:

```bash
# Git Bash / macOS / Linux
adb shell pm list packages | grep -i -E "safaricom|stk"
```
```bat
:: Windows cmd / PowerShell (no grep)
adb shell "pm list packages | grep -i -E 'safaricom|stk'"
```

Example output:
```
package:com.safaricom.mpesa.lifestyle
package:com.safaricom.mysafaricom
package:com.android.stk
package:com.android.stk2
```

To find which package is on screen right now, open the app on the phone and run:
```bash
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp"
```
**No adb?** Open **Settings › Apps used** in ScanPesa. It shows which app each target opens, and you can pick a different one from a list of installed apps (Safaricom/SIM apps are listed first).

Some phones use their own SIM Toolkit package (for example `com.sec.android.app.stk` or `com.mediatek.stk`). If a name differs, edit `packages` / `packagesSim2` in `flows.json` (see below). The service watches only the packages listed there. After you import a new file, turn the service off and on again.

## 5. Fix a flow with Debug mode when Safaricom changes its menus

Flows live in [`app/src/main/assets/flows.json`](app/src/main/assets/flows.json). Each target has one step list per payment type:

```json
{ "name": "Pay Bill", "action": "click", "texts": ["pay bill", "paybill"] }
{ "name": "Enter business no.", "action": "input", "value": "number", "texts": ["business no", "business number"] }
{ "name": "OK", "action": "press", "texts": ["ok", "next", "continue"] }
{ "name": "Amount screen", "action": "await_amount" }
```

| action | what it does |
|---|---|
| `click` | taps the item whose text or content description matches one of `texts` |
| `input` | finds the field whose hint, label or view id matches `texts`, then types `number` or `account` with ACTION_SET_TEXT |
| `press` | taps a Next/OK/Continue button. Buttons in `forbiddenButtons` (Send, Pay, Confirm…) are never pressed |
| `await_amount` | finishes as soon as the amount field or a PIN screen appears |
| `"optional": true` | skipped if the next step already matches the screen, or if the step never appears |

Matching ignores case, punctuation and leading menu numbers ("2. Send Money" matches `"send money"`). An exact match wins over a partial one. `{SIM}` is replaced with the SIM slot from Settings.

**Tuning steps:**

1. Open **Settings** and tap the **"ScanPesa 1.2.0"** line at the bottom 7 times. Debug options appear.
2. Turn on **Debug mode**.
3. Open the M-PESA app, SIM Toolkit or One App yourself and go through the menus by hand.
4. Each screen is dumped as lines like:
   ```
   LinearLayout {click}
     TextView text="Lipa na M-PESA" id=com.android.stk:id/text1
     EditText hint="Enter business no." id=…/input {edit}
   ```
   You can read them on the phone (**Settings › Open Debug screen**, tap a dump, **Share**) or live on your PC:
   ```bash
   adb logcat -s ScanPesaDump
   ```
   PIN/password fields are never dumped.
5. Copy the exact labels into `texts`. Then do one of these:
   - **No rebuild:** **Settings › Save built-in flows.json…**, edit the file on the phone or PC, then **Import flows.json…**. If the JSON is invalid, ScanPesa says where.
   - **Rebuild:** edit `app/src/main/assets/flows.json` and reinstall.
6. Run a scan with **Go**. When a job fails, a notification names the step, for example *Auto-fill stopped: Pay Bill — Could not find "pay bill" / "paybill" on screen*. The Debug screen's **Automation log** shows every step that passed.

Per-target options: `clearTask` (restart the app at its home screen; true for SIM Toolkit, false for the M-PESA app and One App so you stay logged in) and `loginWaitSeconds` (45 for the M-PESA app and One App: time for you to unlock the app. ScanPesa types nothing during it).

Timeouts: `jobTimeoutSeconds` (20) is the whole job, plus the login wait. `stepTimeoutSeconds` (8) is how long one step may wait for its screen. If the M-PESA app shows its login PIN first, ScanPesa waits for you to unlock it (within the 20 seconds) and never touches the PIN field.

## Safety design

- Password nodes (`isPassword`) are dropped before anything else sees the screen. They are never read, logged, dumped, clicked or typed into.
- Only the confirmed number and account can be typed. The job model has no amount or PIN field at all.
- An `input` step never types into a field labelled as an amount (`amountLabels`).
- No button is pressed while an amount or PIN field is visible. `forbiddenButtons` are never pressed by `press` steps, or by any step once a number has been typed.
- A job expires after 20 s, fails when a step's screen doesn't appear, and is never saved to disk.
- These rules are unit-tested in `FlowEngineTest`.

## Project layout

```
app/src/main/java/com/scanpesa/app/
  parser/      MpesaParser (pure Kotlin) + PaymentValidator
  automation/  FlowConfig (JSON), FlowEngine (pure, tested), ScanPesaAccessibilityService,
               AndroidNode, NodeDumper, TargetLauncher, JobStore, DebugLog
  data/        Room history, Prefs
  ui/          Compose screens: onboarding, home, scan, confirm, history, settings, debug
app/src/main/assets/flows.json       editable menu flows (approximate, tune on your phone)
app/src/test/                        MpesaParserTest (27 sign strings), FlowEngineTest
```

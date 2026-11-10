# LanLink

Use your Android phone from your Windows PC, or your PC from your phone, over the same Wi-Fi.
See the screen live, control it with mouse, keyboard or touch, send files both ways, and share copied text.

No account, no cloud, no internet needed: the two devices talk directly on your local network.

## Download

Get the latest files from the **[Releases page](../../releases/latest)**:

| File | For |
|---|---|
| `LanLink-Windows.zip` | Windows 10 / 11 (64-bit). Includes its own Java, nothing else to install |
| `LanLink.apk` | Android 7.0 or newer |

## Install

### Windows
1. Unzip `LanLink-Windows.zip` anywhere (e.g. `Documents\LanLink`) and run **`LanLink.exe`**. Keep the whole folder together.
2. Windows may say **"Windows protected your PC"** because the app isn't from a paid, signed publisher. Click **More info → Run anyway**.
3. Windows Firewall asks about network access: tick **Private networks** and click **Allow**.
   Missed it? *Windows Security → Firewall → Allow an app through firewall* → tick LanLink.
   Also make sure your Wi-Fi is set to **Private**, not Public, in Windows network settings.

### Android
1. Copy `LanLink.apk` to the phone, open it, and allow **"Install unknown apps"** when asked.
2. Google Play Protect may warn that the app is unknown. Tap **Install anyway**.

## Use it

Both devices must be on the **same Wi-Fi**.

### Control the phone from the PC
1. On the phone, open LanLink and tap **Start sharing** → choose **Entire screen**. A 6-digit PIN appears.
2. To control it (not just watch), tap **Turn on remote control** and switch LanLink on in **Accessibility** settings.
   Greyed out or *"App was denied access"*? See [Restricted setting](#restricted-setting-android-13).
3. On the PC, open **Connect to a device → Scan network**, double-click the phone, and enter its PIN.

| On the PC | On the phone |
|---|---|
| Click / drag / mouse wheel | Tap / swipe / scroll |
| Right-click / middle-click | Back / Home |
| Typing, Backspace, Enter | Types into the selected text box |
| Ctrl+A, Ctrl+C, Ctrl+X, Ctrl+V | Select all / copy / cut / paste (copy and paste work between PC and phone) |
| Shift+arrows, Ctrl+arrows, Ctrl+Backspace | Select text, jump words, delete a word |
| **Tab / Shift+Tab** (no text box selected) | Open **Recent apps** |
| **← / →** then **Enter** | Pick an app in Recent apps and open it |
| Esc or Alt+← | Back |
| F1 / F2 / F3 / F4 | Home / Recent apps / Notifications / Quick settings |
| PrtSc | Screenshot on the phone |
| Ctrl + mouse wheel, Ctrl+plus/minus | Zoom the phone screen on the PC (**Fit** or Ctrl+0 resets) |

The viewer's **Shortcuts** button shows this list too.

### Control the PC from the phone
1. On the PC, LanLink shares automatically; the **Share this PC** tab shows the PIN.
2. On the phone, open LanLink → **Connect to a device** → tap the PC → enter its PIN.

Two modes, switched with the **Mode** button in the bottom bar:

| Touch mode | Touchpad mode |
|---|---|
| Tap where you want to click | Slide to move the pointer, tap to click |
| Long-press or two-finger tap = right-click | Two-finger tap = right-click |
| Drag one finger = drag | Long-press, then slide = drag |
| Two fingers = scroll, pinch = zoom | Two fingers = scroll, pinch = zoom |

The **Keyboard** button types on the PC; the bar also has Esc, Tab and Win.

### Files and copied text
- **Send a file:** drag it onto the viewer window or use **Send file…** on the PC; tap **File** on the phone.
- Received files go to `Downloads\LanLink` on the PC and `Downloads/LanLink` on the phone.
- Text you copy on the PC appears on the phone. Android doesn't let apps read the clipboard in the background, so phone-to-PC text is sent when you open LanLink or tap **Clipboard**.

### Other options (phone → Settings card)
- **Dim this phone's screen while a PC is viewing**: lowers the phone's brightness to save battery while the PC still sees the normal picture. Needs "Display over other apps".
- **Auto-reconnect**: if the link drops (Wi-Fi blip, phone restarted sharing), the viewer keeps retrying for 2½ minutes. The phone keeps the same PIN, so you don't re-enter it.

## Phone setup tips

### Restricted setting (Android 13+)
Android blocks the Accessibility switch for apps not installed from the Play Store.
1. In **Settings → Accessibility → LanLink**, tap the greyed switch once and press OK.
2. Open **Settings → Apps → LanLink**, tap **⋮** (top right) → **Allow restricted settings**.
3. Go back and switch LanLink on.

If your phone has no ⋮ menu (some Xiaomi/HyperOS versions), you can do the same from a PC with [adb](https://developer.android.com/tools/adb):
```
adb shell appops set com.lanlink.app ACCESS_RESTRICTED_SETTINGS allow
```

### Xiaomi / Redmi / POCO (HyperOS, MIUI)
These phones close apps aggressively, which stops sharing. For LanLink:
- **Lock it in Recent apps**: open Recents, long-press the LanLink card, tap the 🔒.
- **Settings → Apps → LanLink → Battery saver → No restrictions**, and turn **Autostart** on.
- If Accessibility says LanLink is *"malfunctioning"*, switch it off and on again.

## Security and privacy
- Each connection needs the 6-digit PIN of the device being shared. The PIN never crosses the network: the viewer proves it knows it by signing a random challenge (HMAC-SHA256).
- 5 wrong PINs in a row generate a new PIN. **New PIN** and **Disconnect all** are on the share screen.
- The phone shows a notification with a **Stop** button whenever it is shared.
- **The screen stream is not encrypted.** Use LanLink only on your home or another trusted network, never on public Wi-Fi.
- LanLink can control a phone only **after** it's unlocked. It cannot get past the lock screen or app locks, by Android's design.

## Known limits
- **Netflix, Prime Video, banking apps and password screens show black.** Android hides protected content from every screen share.
- **Android 15+ ends screen sharing when the phone locks.** LanLink keeps the screen awake while a PC is connected, but pressing the power button still stops it; tap **Start sharing** again and the PC reconnects.
- The PC shares its main monitor only.
- Typing from phone to PC assumes a US keyboard layout; other characters are pasted via the clipboard.
- With a mouse, a drag on the phone plays back when you release the button.

## Build it yourself
Needs **JDK 17+** and the **Android SDK** (platform 34 and any build-tools; Android Studio installs it). No Gradle.
```
powershell -ExecutionPolicy Bypass -File build-windows.ps1
powershell -ExecutionPolicy Bypass -File build-android.ps1
```
Output goes to `dist/`. The first Android build creates `android/lanlink.keystore` to sign your APK; keep it, so your later builds install over earlier ones. It is git-ignored: never publish it.

Tests (the capture test only takes screenshots; nothing presses keys or moves the mouse):
```
javac --release 17 -d build\check core\src\lanlink\core\*.java
java -cp build\check test\ProtocolTest.java
```

## How it works
- `core/` — shared Java used by both apps: the protocol, PIN check, UDP discovery, file and clipboard transfer.
- `desktop/` — Swing app; `java.awt.Robot` captures the screen and replays input; `jpackage` makes the exe.
- `android/` — MediaProjection captures the screen, an AccessibilityService performs taps, swipes and typing, a custom view shows remote screens.
- TCP port **47801** carries the session (JPEG frames with flow control, input, files); UDP port **47800** is used for discovery.

## License
[MIT](LICENSE)

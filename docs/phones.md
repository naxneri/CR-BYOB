# Phone compatibility

How each tested phone performs on each connection. Results are with a **CR-Scan Otter** on the phone's USB 3
port unless the phone's section names another scanner.

| Phone | Android | Ethernet | Wi-Fi | Wi-Fi Direct | This phone only (Local Mobile App Scanning) | Reported by |
|---|---|:---:|:---:|:---:|:---:|---|
| [Meta Quest 3](#meta-quest-3) | Horizon OS (Android 14) | — | ✅ | — | — | [@naxneri](https://github.com/naxneri) |
| [Google Pixel 10 Pro XL](#google-pixel-10-pro-xl) | 16 | ✅ | ✅ | ✅ | ✅ | [@NickLD](https://github.com/NickLD) |
| [Google Pixel 6](#google-pixel-6) | 17 | — | ✅ | ✅ | — | [@Xalies](https://github.com/Xalies) |
| [Google Pixel 5](#google-pixel-5) | 14 | ✅ | 🟡 | 🟡 | ⚠️ | [@NickLD](https://github.com/NickLD) |
| [Samsung Galaxy S23 Ultra](#samsung-galaxy-s23-ultra) | 16 | — | — | — | ✅ | [@lihomanovdv](https://github.com/lihomanovdv) |
| [Samsung Galaxy Z Flip 8](#samsung-galaxy-z-flip-8) | 17 | — | ✅ | — | — | [@SelfMadeDiesel](https://github.com/SelfMadeDiesel) |

✅ Full performance &nbsp;·&nbsp; 🟡 Works, with limits &nbsp;·&nbsp; ⚠️ Not recommended &nbsp;·&nbsp; — Not tested

At full rate the Otter's three video streams need about **265 Mbit/s** between the phone and the PC, so the
phone's network hardware matters as much as its processor. See
[Choosing a connection](../README.md#choosing-a-connection).

## Tested your phone? Send a device report

Tried CR-BYOB on a phone that isn't listed, or got different results on one that is? File a
[Device report](https://github.com/NickLD/CR-BYOB/issues/new?template=device-report.yml), whether it works or
not, and say which connections you tried. In the app, `Send a device report` (in Session stats, or at the
bottom of the dashboard) opens the form with your scanner, phone, app version and the session's measurements
already filled in: frame rates, dropped frames, latency, power and battery, scanner disconnects, phone heat
and any recent app crashes. You pick the result and add what you saw. The scanner's serial number, Wi-Fi
network names, IP addresses and the log are never included. Your phone gets added here, credited to your
GitHub username.

## Google Pixel 10 Pro XL

Android 16 · Reported by [@NickLD](https://github.com/NickLD)

**Full performance on every connection**: Ethernet, Wi-Fi, Wi-Fi Direct and This phone only.
It carries the full ~265 Mbit/s on Wi-Fi Direct, and on Wi-Fi when the phone is close to the access point.

## Google Pixel 6

Android 17 · CR-Scan Ferret Pro (fw 1.2.7) · Reported by [@Xalies](https://github.com/Xalies) ([#2](https://github.com/NickLD/CR-BYOB/issues/2))

| Connection | Result |
|---|---|
| Ethernet | — Not tested |
| Wi-Fi | ✅ Preview and a full scan, mirror viewer on. |
| Wi-Fi Direct | ✅ Preview and a full scan, mirror viewer on. |
| This phone only (Local Mobile App Scanning) | — Not tested |

Tested with Creality Scan 4.3.3 on Windows. This was the first report for a scanner other than the Otter.

## Google Pixel 5

Android 14 · Reported by [@NickLD](https://github.com/NickLD)

| Connection | Result |
|---|---|
| Ethernet | ✅ Works perfectly. The least demanding link for the phone. |
| Wi-Fi | 🟡 OK, as long as the signal from the access point is strong. |
| Wi-Fi Direct | 🟡 Decent, as long as the phone isn't also connected to a Wi-Fi network. |
| This phone only (Local Mobile App Scanning) | ⚠️ Not recommended for scanning on the phone with the CrealityScan app. |

**Wi-Fi and Wi-Fi Direct at the same time** severely hinders performance. The two share the phone's radio.
The app warns when the phone is still on a Wi-Fi network while Wi-Fi Direct runs. Disconnect from that
network, or use Ethernet.

## Samsung Galaxy S23 Ultra

Android 16 · Reported by [@lihomanovdv](https://github.com/lihomanovdv) ([#12](https://github.com/NickLD/CR-BYOB/issues/12))

| Connection | Result |
|---|---|
| Ethernet | — Not tested |
| Wi-Fi | — Not tested |
| Wi-Fi Direct | — Not tested |
| This phone only (Local Mobile App Scanning) | ✅ Preview and a full scan on the `Max` frame-rate setting: 28 to 30 fps, every frame sent. |

Tested with CrealityScan 3.1.2 on Android, powered from the phone with no external battery.

## Samsung Galaxy Z Flip 8

Android 17 · Reported by [@SelfMadeDiesel](https://github.com/SelfMadeDiesel) ([#20](https://github.com/NickLD/CR-BYOB/issues/20))

| Connection | Result |
|---|---|
| Ethernet | — Not tested |
| Wi-Fi | ✅ Preview and a full scan on the `Max` frame-rate setting over 6 GHz Wi-Fi 7: about 30 fps, 99 to 100% of frames sent. |
| Wi-Fi Direct | — Not tested |
| This phone only (Local Mobile App Scanning) | — Not tested |

Measured over 7 minutes of streaming.

## Meta Quest 3

Horizon OS (Android 14) · CR-Scan Raptor (fw 1.5.7) · Reported by [@naxneri](https://github.com/naxneri)

| Connection | Result |
|---|---|
| Ethernet | — Not tested |
| Wi-Fi | ✅ Full performance over Wi-Fi 6 to Creality Scan on Windows PC. |
| Wi-Fi Direct | — Not tested |
| This phone only (Local Mobile App Scanning) | — Not tested |

Connects directly to the USB-C port of the Meta Quest 3. Uses `horizonos.permission.USB_CAMERA` for external UVC video class devices.


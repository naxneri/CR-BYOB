# Building from source

Gradle (wrapper in `android/`), plain Java, no Kotlin, no AndroidX. Needs JDK 17+ and an Android SDK with the
android-36 platform (`ANDROID_HOME`, or `sdk.dir` in `android/local.properties`).

```bash
cd android
./gradlew assemble                  # all four variants
./gradlew :CRBYOB:testGithubDebugUnitTest

cd android/CRBYOB
./build.sh                          # github debug build, adb install + launch
./build.sh --no-install             # build only -> build/outputs/apk/github/debug/
ADB_SERIAL=192.168.1.50:5555 ./build.sh   # pick the phone when several are attached
VARIANT=PlayDebug ./build.sh        # another variant
```

## Flavors and signing

Two product flavors share the application ID and signing key, so people can move between them:

| Flavor | Distributed via | Updates |
|---|---|---|
| `github` | GitHub Releases (sideload) | in-app check and install from GitHub Releases (a debug build can't install release APKs: different key) |
| `play` | Google Play | through Play only; no self-update code |

Debug builds are signed with the standard `~/.android/debug.keystore`. Release builds come out unsigned
unless you supply your own key: set `CRBYOB_KEYSTORE`, `CRBYOB_KEYSTORE_PASSWORD`, `CRBYOB_KEY_ALIAS` and
`CRBYOB_KEY_PASSWORD`, or create `android/keystore.properties` with `storeFile`, `storePassword`,
`keyAlias` and `keyPassword`.

## Versions

Versions come from git tags (`vMAJOR.MINOR.PATCH`, optionally `-alpha.N` / `-beta.N` / `-rc.N`). `v1.2.3`
builds as versionName `1.2.3`, versionCode `1020399`; untagged commits build as `1.2.3-dev.4+abc1234` with the
last tag's code. `./gradlew -q :CRBYOB:printVersion` prints what the checkout builds as. The scheme is in
`android/CRBYOB/build.gradle.kts`; a unit test checks that the app's own formula agrees with it.

## Bundled files

`models/scanners.json` and `CHANGELOG.md` are copied into the APK's assets by the `bundleSharedAssets` task,
which validates the JSON and, for a release tag, requires a dated changelog section.

## Debug builds

Debug builds log StrictMode violations (disk or network access on the UI thread, leaked resources) to logcat,
and accept these test extras, which release builds ignore because other apps could send them too:

```bash
am start -n com.crbyob.bridge/.MainActivity --ez autostart true        # start the bridge
am start -n com.crbyob.bridge/.MainActivity --ez p2p true              # Wi-Fi Direct on (false: off)
am start -n com.crbyob.bridge/.MainActivity --ez test_pattern true     # synthetic frames, no discovery
am start -n com.crbyob.bridge/.MainActivity --es mirror_host 192.168.1.20[:port]   # open the mirror viewer
am start -n com.crbyob.bridge/.MainActivity --ez show_when_locked true # show over the lock screen
am start -n com.crbyob.bridge/.MainActivity --es update_repo owner/name --ez update_check true
# update_repo also takes a URL that serves GitHub's /releases JSON (debug builds allow plain http), e.g. a
# fake API on the PC behind `adb reverse tcp:8765 tcp:8765`: --es update_repo http://127.0.0.1:8765
```

Mirror test hooks: `--ei mirror_port <p>`, `--ez mirror_auto_ui true`, `--ez mirror_reset true`. Debug builds
also run the TCP 8999 link test.

## Logs and private files

- logcat: `adb shell logcat -s CRBYOB` (one JSON object per line). Passwords are logged as `(hidden)`.
- `files/bridge.jsonl` in the app's private dir (rotates at 8 MB); the dashboard's *Raw log* shows the last
  80 lines.
- *Export log* (dashboard) and the device report's *Include anonymous & redacted logs* zip `bridge.1.jsonl`, `bridge.jsonl`,
  `runs/*.json` and a `scanners.json` override with a readable `summary.txt`, redacted by `LogExport`
  (serials, SSIDs, passwords, MAC and IP addresses; IPs become `ip1`, `ip2`, ...).

On debug builds, `adb shell run-as com.crbyob.bridge` gives access to the private dir:

- `stream_map.json`: `advertise` (network choice), `model_pid` (force a model), `ir_decimation` (1–3, pins
  the paired IR decimation), and the experimental `ir_jpeg_over_y8` / `ir_jpeg_quality`.
- `scanners.json`: merged over the bundled model table; see
  [Adding a scanner model](adding-a-scanner-model.md).
- `allow.json`: `{"opcodes": [..], "writes": [[op, prop], ..]}` extends the relay's allow-list.
- `p2p.json`: Wi-Fi Direct name, password and channel.
- `otter_descriptors.bin`: raw USB descriptors of the last opened scanner.

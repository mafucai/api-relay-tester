# RelayScope

Android app for comparing OpenAI-compatible API relay stations.

## Current milestone

The approved browser front-end is the single source of truth for the Android UI. The production APK now loads the same HTML/CSS/JS from `app/src/main/assets/` in a WebView; native Java is reserved for the bridge and Android-only capabilities.

- overview ranking
- model availability matrix
- price and in-site balance deduction multiplier
- cost calculator
- custom inspection interval (any positive number, including decimals)
- local site settings with Android Keystore-backed AES-GCM API-key encryption
- screenshot OCR price import with confirmation and re-upload flow for missing fields
- manual price storage (input/output price + balance multiplier)
- configurable foreground inspection service; the interval accepts positive decimals

The app now has a real network probe for saved sites: `/v1/models`, TTFB, per-model minimal streaming checks, error classification, and bounded retry. Production UI must show real saved-site results or an empty state; the three sample cards belong only to the browser prototype/development fixtures and must not be shipped as production data.

The app also includes a conservative JSON price-source adapter and a foreground inspection service. The service uses the user-selected interval (including decimals) rather than WorkManager's 15-minute floor, restarts when the interval changes, and shows an ongoing Android notification while active. Incomplete price-source rows never overwrite saved prices; configured price sources receive the site's bearer key when required.

OCR behavior is deliberately conservative: the image is processed on-device with ML Kit Chinese text recognition; candidates are shown in an editable confirmation form, incomplete recognition asks for a clearer local crop, and nothing is saved until confirmation.

## Build

The supported build route is GitHub Actions. The ordinary Android build checklist is:

`/workspace/docs/ANDROID-APP-GITHUB-ACTIONS-BUILD.md`

Push this repository to GitHub and `.github/workflows/apk.yml` installs Android SDK 35, runs `assembleRelease`, signs the unsigned APK, verifies it, uploads an artifact, and publishes a GitHub Release asset.

The first `build-4` package was a validation build with a CI temporary signing key and the old native-rendered shell. The next `0.2.0` package is the WebView/empty-state correction build. A production release must replace the temporary key with a fixed keystore stored in GitHub Secrets so later APKs can upgrade the installed package without uninstalling it.

## Security and runtime limitations

API keys remain in app-private Android Keystore-backed storage. They are never part of the repository or workflow. The foreground inspection service needs an ongoing notification and can still be delayed or stopped by Android/vendor power management; a user-selected interval is a scheduling target, not an absolute OS guarantee.

# RelayScope

Android app for comparing OpenAI-compatible API relay stations.

## Current milestone

The native front-end shell mirrors the approved browser prototype:

- overview ranking
- model availability matrix
- price and in-site balance deduction multiplier
- cost calculator
- custom inspection interval (any positive number, including decimals)
- local site settings with Android Keystore-backed AES-GCM API-key encryption
- screenshot OCR price import with confirmation and re-upload flow for missing fields
- manual price storage (input/output price + balance multiplier)
- configurable foreground inspection service; the interval accepts positive decimals

The app now has a real network probe for saved sites: `/v1/models`, TTFB, per-model minimal streaming checks, error classification, and bounded retry. The overview and matrix show the latest real results for user-saved sites; the three sample cards remain demo data for visual comparison.

The app also includes a conservative JSON price-source adapter and a foreground inspection service. The service uses the user-selected interval (including decimals) rather than WorkManager's 15-minute floor, restarts when the interval changes, and shows an ongoing Android notification while active. Incomplete price-source rows never overwrite saved prices; configured price sources receive the site's bearer key when required.

OCR behavior is deliberately conservative: the image is processed on-device with ML Kit Chinese text recognition; candidates are shown in an editable confirmation form, incomplete recognition asks for a clearer local crop, and nothing is saved until confirmation.

## Build

The supported build route is GitHub Actions. Push this repository to GitHub and the workflow in `.github/workflows/apk.yml` produces a real release APK as an artifact.

The API key remains in app-private Android Keystore-backed storage. It is never part of the repository or workflow.

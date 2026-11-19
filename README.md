# VEIL Browser

A privacy-focused Android browser built with Kotlin and Android WebView. VEIL is designed around a minimal Obsidian interface, local preferences, and configurable content blocking.

## Current status

VEIL is an actively developed prototype. It is not yet a production-ready or independently audited privacy browser.

### Available in the current build
- Dark browser interface with address/search input.
- App-language picker with 100 locale options.
- Local bookmarks and browsing-data controls.
- Homepage image selection from device storage.
- Configurable Standard, Strong, and Super hostname-blocking levels.
- EasyList download/update support for a limited subset of network-host rules.
- Local theme, accent, and search-engine preferences.
- GitHub Actions workflow for a universal debug APK.

## In development
- Full multi-tab lifecycle and tab switching.
- Properly isolated private browsing sessions.
- Complete localization of every app screen across the supported languages.
- Broader filter-rule parsing, exception handling, cosmetic filtering, and reliable filter updates.
- Complete application of theme and accent preferences.
- Compatibility and privacy testing across Android versions and device architectures.

## Ad blocking

The current blocker is an early hostname-based implementation. It does not support the complete EasyList/uBlock rule syntax and should not be considered equivalent to Brave Shields or uBlock Origin. First-party ads, cosmetic elements, and script-based trackers may not be blocked. More advanced filtering is a development goal, not a feature claim.

## Privacy

- No account or login is required.
- The source currently includes no analytics SDK or telemetry endpoint.
- Browser preferences and bookmarks are stored locally.
- Filter updates retrieve public filter data.
- Websites visited can still receive connection data and may collect information. VEIL does not make browsing anonymous.

## Build

The Android project uses:
- Kotlin
- Android Gradle Plugin
- Android WebView
- compileSdk 36 / targetSdk 36 / minSdk 23
- Java 17

ABI splits are disabled to produce a universal APK containing the app's supported native configuration. Actual installation and runtime compatibility depend on the device's Android WebView provider and ABI.

## Downloads

Debug builds are published as GitHub Actions artifacts. Open the [Actions page](https://github.com/annnaelena69-cmd/Veil/actions), select a successful run, and download the `VEIL-debug-universal` artifact.

## Project status

VEIL is under active development. Features are marked available only when implemented; planned work is listed separately above. Real-device testing is required before any stable release.

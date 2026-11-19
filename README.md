# VEIL Browser

A privacy-focused Android browser prototype built with Kotlin and Android WebView. VEIL uses a minimal dark interface and keeps browser preferences locally.

## Current implementation

- WebView-based browser with address/search navigation.
- Multiple in-app tabs with independent WebView instances, tab switching, and closing.
- Separate private-browsing activity running in its own Android process with a dedicated WebView data-directory profile on Android 9 and newer. The private profile is cleared when its activity closes. Isolated private mode is unavailable on Android 8 and older.
- Standard, Strong, and Super hostname-blocking levels.
- Manual and scheduled downloads of EasyList and EasyPrivacy, with basic domain-rule and exception extraction.
- Homepage photo selection from device storage and rendering as the local home-page background.
- Local bookmarks and language selection with 100 locale options.
- Theme, accent, and search-engine preference controls.
- No account, login, analytics SDK, or telemetry endpoint is included in the source.

## Known limitations

VEIL is not a finished, audited, or production-ready privacy browser.

### Ad blocking
The current filter implementation extracts hostname rules and some hostname exceptions from EasyList and EasyPrivacy. It does **not** implement the complete Adblock Plus filter syntax, full cosmetic-filter semantics, complete scriptlet coverage, request-type matching, redirect rules, or all exception semantics. A conservative `set-constant` scriptlet subset is supported; unknown scriptlets are ignored and filter text is never evaluated as arbitrary JavaScript. It cannot reliably block first-party ads and is not equivalent to Brave Shields or uBlock Origin. The project does not claim better blocking than Brave.

### Localization
The app currently offers 100 locale choices, but complete translation of every menu, dialog, and status message into all 100 languages is not implemented. English and Indonesian remain the only partially localized UI languages; other languages fall back to English. English and Indonesian have partial UI strings; other languages currently fall back to English.

### Privacy and tabs
Normal tabs use separate WebView instances but share the normal browser profile and cookies. Private browsing uses a separate WebView data directory on Android 9+ and clears that profile on close. This is not a guarantee of anonymity: websites and network providers can still observe connection data.

### Customization and browser features
Theme and accent choices are stored but are not yet applied consistently throughout the native interface. Site-permission controls, a complete download manager UI, advanced history management, reader mode, and full filter controls remain future work.

## Build configuration

- Kotlin and Android WebView
- compileSdk 36 / targetSdk 36 / minSdk 23
- Java 17
- ABI splits disabled for a universal debug APK build

Universal packaging does not guarantee that every device ABI or WebView provider will work. Real-device testing is required, including Android 16/17 and 32-bit/64-bit devices.

## Debug APK

Open [GitHub Actions](https://github.com/annnaelena69-cmd/Veil/actions), select a successful workflow run, and download the `VEIL-debug-universal` artifact.

VEIL is actively developed. Feature availability is described above; planned work should not be treated as completed functionality.

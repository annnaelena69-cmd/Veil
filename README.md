# VEIL Browser

A privacy-focused Android browser prototype built with Kotlin and Android WebView. VEIL uses a minimal dark interface and keeps browser preferences locally.

## Current implementation

- WebView-based browser with address/search navigation.
- Multiple in-app tabs with independent WebView instances, tab switching, and closing.
- Separate private-browsing activity running in its own Android process with a dedicated WebView data-directory profile on Android 9 and newer. The private profile is cleared when its activity closes. Isolated private mode is unavailable on Android 8 and older.
- Standard, Strong, and Super hostname-blocking levels.
- Manual and scheduled downloads of EasyList and EasyPrivacy. The in-app matcher supports a limited ABP subset: anchored/wildcard URL patterns, domain scopes, selected resource types, first/third-party conditions, network exceptions, `badfilter`, basic cosmetic filters, exact `#@#` cosmetic exceptions, and selected page-level exceptions.
- Homepage photo selection from device storage and rendering as the local home-page background.
- Local bookmarks and a language picker with 100 locale choices. The picker is not equivalent to 100 complete UI translations.
- Theme, accent, and search-engine preference controls.
- Per-site allow/deny prompts for camera, microphone, and geolocation, backed by Android runtime permissions. Site preferences can be reset.
- Third-party cookies are disabled for normal WebViews; EasyPrivacy rules and built-in tracker host lists provide additional best-effort blocking.
- No account, login, analytics SDK, or telemetry endpoint is included in the source.

## Known limitations

VEIL is not a finished, audited, or production-ready privacy browser.

### Ad blocking
The matcher is still a partial implementation, not a complete EasyList/EasyPrivacy or ABP engine. WebView does not expose all request types to this interceptor, so request-type classification is approximate and `xmlhttprequest`, websocket, ping, redirect, rewrite, CSP, regex filters, extended cosmetic selectors, and many option combinations are not fully supported. Basic `@@` network exceptions, `$document`, `$genericblock`, `$elemhide`, `$generichide`, `badfilter`, and exact-selector `#@#` exceptions are implemented in the current matcher. A conservative allowlisted `set-constant` scriptlet subset is supported, with matching scoped exceptions; other scriptlets are ignored. Scriptlets are not guaranteed to run before every page script. The engine is not equivalent to Brave Shields or uBlock Origin, and the project does not claim better blocking than Brave.

### Localization
The app currently offers 100 locale choices, but complete translation of every menu, dialog, setting, and status message into all 100 languages is **not implemented**. English and Indonesian are only partially localized; other choices currently fall back to English.

### Site permissions and tracker protection
Camera, microphone, and location requests now prompt the user per site and require Android runtime permission where applicable. Users can remember allow/deny choices in Site permissions. Android WebView does not expose a complete browser notification-permission workflow here, so web notifications are explicitly marked unavailable rather than falsely presented as working. Third-party cookies are disabled, and EasyPrivacy plus built-in tracker host lists are used for best-effort blocking. This is not comprehensive tracker or fingerprinting protection.

### Privacy and tabs
Normal tabs use separate WebView instances but share the normal browser profile and cookies. Private browsing uses a separate WebView data directory on Android 9+ and clears that profile on close. This is not a guarantee of anonymity: websites and network providers can still observe connection data.

### Customization and browser features
Theme and accent choices are stored but are not yet applied consistently throughout the native interface. A complete download manager UI, advanced history management, reader mode, full ABP/EasyList syntax support, notification permission support, and full 100-language localization remain future work.

## Build configuration

- Kotlin and Android WebView
- compileSdk 36 / targetSdk 36 / minSdk 23
- Java 17
- ABI splits disabled for a universal debug APK build

Universal packaging does not guarantee that every device ABI or WebView provider will work. Real-device testing is required, including Android 16/17 and 32-bit/64-bit devices.

## Debug APK

Open [GitHub Actions](https://github.com/annnaelena69-cmd/Veil/actions), select a successful workflow run, and download the `VEIL-debug-universal` artifact.

VEIL is actively developed. Feature availability is described above; planned work should not be treated as completed functionality.


## Language support (v1)

VEIL currently exposes 50 language/locale choices in the app language picker and Android locale configuration. This is a staged rollout, not a claim of complete translation into all 50 languages: the current in-app text catalog is still primarily English with Indonesian translations for selected interface strings. Other selected locales currently fall back to English until their complete UI catalogs are added and tested. Website translation is not part of this setting.

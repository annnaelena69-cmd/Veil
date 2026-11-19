# VEIL Browser

Kotlin Android browser prototype using Android WebView.

## Build targets
- compileSdk 36, targetSdk 36, minSdk 23.
- Intended to run on Android 16 and Android 17 through Android API backward compatibility.
- ABI splits are disabled, so the APK is universal for supported ABIs (ARMv7 32-bit, ARM64, x86 and x86_64) when a compatible Android WebView runtime exists.
- No account, login, analytics SDK or telemetry endpoint is included in this source.

## Implemented in this initial source
- First-launch language picker with 100 language tags.
- Dark Obsidian-first browser shell.
- Three selectable hostname-blocking levels: Standard, Strong and Super Block.
- Gallery image picker with persisted local image URI.
- Theme, accent and search-engine preference controls.
- Local bookmarks, basic tab controls, private-session toggle and clear browsing data.
- GitHub Actions workflow to build a universal debug APK.

## Important limitations
This is an early prototype, not a finished or audited privacy browser. The blocker uses a small starter hostname list, not a full EasyList/uBlock-compatible filter engine. It cannot reliably remove first-party ads, and Super Block may break sites. The gallery URI is saved, but custom-photo rendering as the homepage background is not yet finished. Tab controls and private mode are basic placeholders, not isolated multi-tab profiles. The language picker stores the chosen language, but complete translation of every screen is still pending. Build and test on real Android 16/17 devices and both 32/64-bit hardware before release.

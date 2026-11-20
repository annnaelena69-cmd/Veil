# Veil

Customization integration workspace for the Peanuts Native Android source supplied for this project.

The repository was initially empty. The first source addition is a small, isolated preference layer at
`android/app/src/main/java/dev/peanuts/customization/VeilPreferences.java`.

## Integration contract

- Theme: `system`, `black`, or `photo`.
- Photo: select through Android's Storage Access Framework (`ACTION_OPEN_DOCUMENT`) and pass the returned URI to `VeilPreferences.setPhoto(context, uri)`. Render that URI as the launcher's background and retain a dark scrim so text remains readable.
- Frame limit: `0` (Auto), `60`, `90`, or `120`. The renderer must consume `frameLimit(context)` in its existing frame-pacing path. A preference alone does not unlock higher frame rates.
- Display refresh: an app cannot reliably force HyperOS's global 90/120 Hz mode. The display must expose the mode and the user/system must permit it.
- Mali: keep the existing Vulkan/OpenGL capability checks and device driver selection. Do not bundle an arbitrary Adreno driver or force a driver built for another GPU. Mali support must be validated against the exact Vulkan driver and renderer backend.

## Build status

This repository does not yet contain the full Peanuts Native project. The uploaded source archive contains a much larger Android/native tree, including generated shader databases and native tool binaries. The helper is therefore an integration component, not a standalone APK, and no APK build is claimed by this commit.

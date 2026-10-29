# myEmployee Android liveness UI

This fork adds the optional `LivenessConfig(useAppUi: true)` flag on Android.
It registers application-owned `FaceUiFragment` and
`BaseFaceLivenessProcessingFragment` implementations through the SDK's public
configuration hooks. The default is false, preserving upstream UI behavior.

The host app supplies its bundled Bricolage Grotesque and GT Walsheim fonts;
views fall back to the Android system font when those assets are absent.

Camera/session ownership, passive liveness assessment, server communication,
and retry actions remain inside the unmodified Regula SDK. The custom fragments
do not classify images, bypass failed results, or change matching thresholds.
Start and success screens can be skipped through the existing `skipStep` option;
SDK retry handling remains enabled with `attemptsCount: 0`.

The flag currently affects Android only. iOS retains upstream SDK UI.
Existing SDK licensing and service requirements still apply.

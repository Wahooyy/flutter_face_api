# myEmployee Android liveness UI

This fork adds the optional `LivenessConfig(useAppUi: true)` flag on Android.
It registers application-owned `FaceUiFragment` and
`BaseFaceLivenessProcessingFragment` implementations through the SDK's public
configuration hooks. The default is false, preserving upstream UI behavior.

The Android fragments host a transparent Flutter rendering engine and invoke
the application's `@pragma('vm:entry-point') regulaLivenessUi()` entry point.
The application renders its own shared scaffold, glass app bar, colors, theme,
fonts, buttons and assets. XML contains only invisible SDK state/action targets;
there are no parallel native styles to keep in sync.

The rendering engine does not register plugins or launch application bindings.
It cannot acquire the camera or replace the main engine's Face SDK callbacks.
The `myemployee/regula_ui` channel exposes state, close, SDK-owned retry actions,
and the camera preview rectangle; it cannot create a successful liveness response. The engine is detached
and destroyed with the fragment view.

Camera/session ownership, passive liveness assessment, server communication,
and retry actions remain inside the unmodified Regula SDK. The custom fragments
do not classify images, bypass failed results, or change matching thresholds.
Start and success screens can be skipped through the existing `skipStep` option;
SDK retry handling remains enabled with `attemptsCount: 0`.

The flag currently affects Android only. iOS retains upstream SDK UI.
Existing SDK licensing and service requirements still apply.

## Square preview presentation

The application sends the square preview rectangle after Flutter layout. The
bridge moves the SDK's `cameraConstraintLayout` into that rectangle and hides
only the visual face overlay and animation via alpha. SDK capture, detection,
face-area layout and result callbacks remain running. This presentation adapter
depends on verified resource IDs in Android Face SDK API 8.4.5465; changing that
native version requires rechecking the layout resources and device behavior.
System bars are shown and their insets are forwarded to the Flutter UI to avoid
the SDK's fullscreen mode or inset UI container adding a second header offset.

Optional `appUiData` carries the selected employee image and label exclusively
to the isolated presentation engine. It is not forwarded as SDK request metadata
and is cleared at the liveness completion callback.

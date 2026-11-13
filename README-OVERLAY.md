# Floating AIRI (bubble + circle-to-ask)

Copy these files over your AIRI repo (paths are preserved), commit, push to your GitHub fork, then run
Actions -> "Build Pocket Android (debug APK)" -> Run workflow. Download the APK from the run's Artifacts.

New: overlay/*.kt (4 files), ic_overlay_notification.xml, airi-overlay.ts, overlay-gesture.ts,
pages/overlay.vue, pages/settings/system/overlay.vue, the debug build workflow.
Edited: AndroidManifest.xml, MainActivity.kt, app/build.gradle.kts, settings/system/index.vue.

Untested: written without an Android SDK, so expect to fix compile errors and tune on a device.

TSEHAY ASSIST APP ICON — ANDROID ASSET PACKAGE
================================================

WHAT'S INSIDE

mipmaps/
  mipmap-mdpi/ ... mipmap-xxxhdpi/
    ic_launcher.png              legacy flat icon (pre-Android 8 devices)
    ic_launcher_foreground.png   adaptive icon foreground layer (Android 8+)
    ic_launcher_background.png   adaptive icon background layer (Android 8+)
  mipmap-anydpi-v26/
    ic_launcher.xml              adaptive icon definition
    ic_launcher_round.xml        same, for round-icon slot

full_icon.svg                    source, combined icon (1024x1024)
ic_launcher_foreground.svg       source, foreground only, vector
ic_launcher_background.svg       source, background only, vector
playstore_icon.svg / .png        512x512, no corner rounding (Play Store applies its own mask)

HOW TO DROP THIS INTO THE PROJECT

1. Copy the entire mipmaps/ folder contents into:
   app/src/main/res/
   (merge with existing mipmap-* folders — this will overwrite ic_launcher.png
   and add the new foreground/background/anydpi-v26 files)

2. Confirm AndroidManifest.xml points at the icon (it likely already does):
   android:icon="@mipmap/ic_launcher"
   android:roundIcon="@mipmap/ic_launcher_round"

3. If ic_launcher_round.png files exist anywhere from the old icon, delete
   them — the new adaptive XML supersedes them for API 26+, and legacy
   ic_launcher.png covers everything below that.

4. Rebuild and check the launcher — Android will auto-generate round,
   squircle, or square masks from the adaptive layers depending on the
   device's launcher.

PLAYSTORE ICON
Use playstore_icon_512.png for the Play Store listing "app icon" upload slot
(512x512, 32-bit PNG, no alpha needed).

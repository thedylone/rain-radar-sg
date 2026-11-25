# Rain Radar SG

Android home-screen (and lock-screen, where supported) widget showing the Meteorological Service Singapore / NEA 50 km "rain areas" radar map from [weather.gov.sg](https://www.weather.gov.sg/weather-rain-area-50km/).

The map is stacked exactly like the website: NEA's `base-853.png` base map, the latest 5-minute radar frame at 50% opacity, and NEA's town-name layer (stretched to 853x485, shifted 3px up).

## Features
- Resizable widget with frame time, tap ↻ to refresh, tap map to open the app
- Background refresh every 5 / 10 / 15 / 30 minutes (WorkManager)
- App screen with past-hour playback, intensity legend, town-name toggle
- Pinch, double-tap or +/− to zoom the map; drag to pan when zoomed
- "Show my location" toggle (blue dot, foreground only) and a saved pin: long-press the map or tap "Pin my location". The pin can also be drawn on the widget

## Build
```bash
./gradlew assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk` (signed with the debug key for sideloading). Requires minSdk 31 (Android 12+).

Radar images and map layers © Meteorological Service Singapore (NEA). Not affiliated with NEA.

# Google Maps development setup

Cereveil Guardian uses only the standard Maps SDK for Android. The application still shows the authoritative coordinates, age, accuracy, stale warning, and `geo:` action when the key or renderer is unavailable.

## Local development key

1. In the billing-enabled `cereveil-development` Google Cloud project, enable **Maps SDK for Android** only.
2. Create a dedicated development API key. Restrict its application to Android apps:
   - package: `com.cereveil.guardian.dev`
   - certificate: the SHA-1 fingerprint of the local debug keystore (`./gradlew signingReport`)
3. Restrict its API scope to **Maps SDK for Android**.
4. Put the key in the ignored `.env.local` file:

   ```properties
   GOOGLE_MAPS_API_KEY=your_restricted_development_key
   ```

Never commit or log the key. The Gradle build injects it into the Guardian manifest through `@string/google_maps_key`; Child Mode does not receive it.

## Production

Create a separate key restricted to the production Guardian package and the production signing-certificate SHA-1. Supply `GOOGLE_MAPS_API_KEY` from the release build's external secret environment. Do not reuse the development key or add Places, Routes, Street View, reverse geocoding, or a map ID.

## Verification

Build with `./gradlew :app:assembleGuardianDebug`. On a Google Play-enabled device, open a child's latest-location surface and verify one marker plus one accuracy circle. Then build once without `GOOGLE_MAPS_API_KEY`: the map is omitted while the coordinate/status card and **Open in maps** action remain usable.

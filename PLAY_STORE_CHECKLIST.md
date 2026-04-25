# Play Store Publishing Checklist

## 1. Generate an upload keystore

```
keytool -genkey -v -keystore upload.keystore -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

**Back this up securely** — you can't update the app without it.

## 2. Create `keystore.properties` in the project root

```
storeFile=upload.keystore
storePassword=your_password
keyAlias=upload
keyPassword=your_password
```

This file is gitignored.

## 3. Build the signed AAB

```
./gradlew bundleRelease
```

Upload `app/build/outputs/bundle/release/app-release.aab` to Play Console.

## 4. Play Console requirements

- **Privacy policy URL** (mandatory for location apps) — a GitHub Pages markdown file works
- Store listing: app name, short description (80 chars), full description, 2+ phone screenshots, feature graphic (1024x500)
- Content rating questionnaire
- Data safety form — location collected, used only on-device, not shared/transmitted

## 5. Confirm applicationId

`applicationId` is currently `com.reserveboundary`. This is **permanent** after first publish. To change it, edit `app/build.gradle.kts` before publishing.

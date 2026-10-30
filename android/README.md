# Jhanvi Trends — Android app

Native Kotlin + Jetpack Compose shopping app for https://shop.bhumaji.com.
It talks to the same `/api` the website uses, so products, stock, carts, orders and
accounts are shared between the app and the site.

**Screens:** shop (search, categories, sort, endless scroll) · product · cart · checkout ·
orders and order details · sign in / create account · account (admins get a link to the web admin panel).

## Getting the APK

GitHub Actions (`.github/workflows/android.yml`) builds the app on every push that changes
`android/` and publishes it to the **android-latest** release:

`https://github.com/jttadmin/Jhanvi-Trends/releases/tag/android-latest`

On your phone, open that page while signed in to GitHub, download `JhanviTrends-1.0.<n>.apk`,
open it, and allow "Install unknown apps" for your browser when Android asks.

You can also run it by hand: **Actions → android → Run workflow**.

## Release signing (do this once)

Without a key, CI signs each build with a throwaway debug key, so installing a newer build
means uninstalling the old one first. To get normal in-place updates, create a key once:

```bash
keytool -genkeypair -v -keystore jhanvi-release.jks -alias jhanvi -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 jhanvi-release.jks > jhanvi-release.jks.b64
```

Then add four repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | contents of `jhanvi-release.jks.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password you chose |
| `ANDROID_KEY_ALIAS` | `jhanvi` |
| `ANDROID_KEY_PASSWORD` | the key password (same as the keystore password if you pressed Enter) |

Keep `jhanvi-release.jks` and its password somewhere safe and **never commit it** — the same key is
needed for every future update, including a Play Store release.

## Building locally

Android Studio (Ladybug or newer) → **Open** → `android/`. Or with Gradle 8.9+ and the Android SDK:

```bash
gradle -p android assembleDebug
JT_BASE_URL=https://shop.bhumaji.com gradle -p android assembleRelease
```

`JT_BASE_URL` changes the store the app talks to (default `https://shop.bhumaji.com`).

## Versions

AGP 8.7.3 · Kotlin 2.0.21 · Compose BOM 2024.12.01 · compileSdk/targetSdk 35 · minSdk 26 (Android 8.0+).
Before a Google Play release, raise `targetSdk` to the level Play currently requires.

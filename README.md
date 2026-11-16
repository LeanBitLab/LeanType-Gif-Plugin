# LeanType GIF Plugin

Rich media and GIF search plugin for [LeanType Keyboard](https://github.com/LeanBitLab/LeanType).

## Overview

This plugin provides fast, out-of-process media search and retrieval (GIFs, Stickers, Memes, and Animated Emojis) for the LeanType Keyboard via an IPC AIDL contract (`IGifEngine`) and thumbnail ContentProvider (`com.leanbitlab.leantype.gif.thumbs`).

## Architecture

- **Engine Service**: Out-of-process AIDL service running in isolated namespace `com.leanbitlab.leantype.gif`
- **AIDL Interface**: `com.leanbitlab.leantype.gif.IGifEngine` (Contract v2)
- **Service Action**: `com.leanbitlab.leantype.gif.ENGINE`
- **Thumbnail Provider**: `com.leanbitlab.leantype.gif.thumbs` (read-only ContentProvider with magic-byte validation)
- **Security**:
  - **Bi-directional SHA-256 certificate pinning**: The host keyboard verifies the plugin signature, and the plugin enforces caller UID package name and certificate verification (`CallerCheck`).
  - **Encrypted credential storage**: API keys are encrypted at rest using AES-256-GCM backed by the Android Keystore (`CredentialStore`).
  - **Strict single-ownership PFD streaming**: Media files are passed to the host via read-only `ParcelFileDescriptor` to prevent memory bloat on the keyboard process.
  - **Header validation**: Thumbnails and media files are verified for genuine GIF, WebP, PNG, or JPEG file headers.

## ✨ Features

- **🎬 Multi-Category Rich Media**: Search and browse across GIFs, Stickers, Memes, and Animated Emojis.
- **🕒 Universal Recent Tab**: Quick access to your recently sent GIFs and stickers across all providers.
- **📁 100% Offline Local Media**: Import and browse your own GIF, WebP, PNG files, and ZIP sticker packs with zero internet required.
- **🗂️ Categories & Packs**: Organize imported media inside the plugin. Open a category to see its full grid, group files into named packs, select several files to set a pack, rename titles or move them to another category, without zipping anything first.
- **🔄 Multi-Provider Support**: Seamless support for **GIPHY**, **KLIPY**, and **Local Media**, with 1-tap provider switching directly from the search bar badge.
- **🛡️ Secure API Key Vault**: Your personal API keys are encrypted at rest with hardware-backed Android Keystore keys and never shared outside the plugin process.
- **⚡ Zero-Memory Overhead**: Files are streamed directly to target apps using Android commitContent APIs and read-only file descriptors, keeping keyboard input responsive and smooth.
- **🔋 Battery & Resource Optimized**: Runs in a separate process that automatically disconnects and unbinds when the GIF picker is closed.
- **🔄 Universal Compatibility**: Compatible across **all LeanType flavors** (`Standard`, `Offline`, etc.).

## 📋 System Requirements

- **Operating System**: Android 6.0 (API 23) or higher
- **Host Keyboard**: [LeanType](https://github.com/LeanBitLab/LeanType) v4.2.9+ (All flavors)
- **Permissions**: Internet permission (`android.permission.INTERNET`) to query provider APIs and download media

---

## 📥 Installation & Setup Guide

### 1. Install the GIF Plugin APK
1. Download the latest release APK (`app-release.apk` or `gif_plugin.apk`) from [GitHub Releases](https://github.com/LeanBitLab/LeanType-Gif-Plugin/releases/latest).
2. Install the APK on your Android device. (The plugin runs as an IPC AIDL service application and must be installed as an app).

### 2. Configure Your Provider in LeanType Settings
1. Open LeanType keyboard settings.
2. Navigate to **Settings → GIF Search** (or **Settings → Plugins → GIF Search**).
3. Under **Available Providers**, tap on **GIPHY** or **Klipy**.
4. Enter your provider API Key (see the guide below on how to obtain one).
5. Tap **Save** and ensure the provider is marked as **Active** (or tap **Enable**).

### 3. Using GIF Search on Keyboard
1. Open LeanType in any app that supports rich media input (e.g., messaging and social media apps).
2. Tap the **GIF icon** in the keyboard toolbar or switch to the GIF picker.
3. Switch categories by tapping **GIFs**, **Stickers**, **Memes**, or **Emojis** tabs.
4. Tap the **Provider Badge** (showing `GIPHY` or `KLIPY` on the right of the search box) at any time to instantly toggle between your configured providers!

---

## 🔑 How to Get API Keys

Both providers offer free developer tiers suitable for personal keyboard usage.

> [!IMPORTANT]
> Please read and review the respective provider's Privacy Policy and Developer Terms of Service before creating an account or generating an API key. While LeanType and this plugin do not collect, log, or track your personal data, media queries and network traffic sent to third-party providers are governed by their respective privacy policies.

### 🟣 GIPHY API Key

- **Privacy Policy**: [https://giphy.com/privacy](https://giphy.com/privacy)
- **API Terms of Service**: [https://developers.giphy.com/terms/](https://developers.giphy.com/terms/)

1. Review the [GIPHY Privacy Policy](https://giphy.com/privacy) and [API Terms](https://developers.giphy.com/terms/) before registering.
2. Go to the [GIPHY Developer Portal](https://developers.giphy.com/) and click **Log In** or **Sign Up** to create a free account.
3. Once logged in, open the **Dashboard** at [developers.giphy.com/dashboard](https://developers.giphy.com/dashboard/).
4. Click the **Create an App** button.
5. Select **API** (choose API rather than SDK for direct key access).
6. Fill in the required application details:
   - **App Name**: e.g., `LeanType Keyboard`
   - **App Description**: e.g., `Personal GIF and sticker search integration for LeanType Keyboard`
   - **App Website / URL** (if prompted): Enter `https://github.com/LeanBitLab/LeanType` (or your personal website / GitHub profile)
7. Check the box to accept the **GIPHY API Terms of Service**.
8. Click **Create App**.
9. On your dashboard app card, copy your **API Key**.
10. In LeanType, open **Settings → GIF Search → GIPHY**, paste the key into the API key field, tap **Save**, and ensure it is marked as **Active** (or tap **Enable**).

### 🔵 KLIPY API Key

- **Privacy Policy**: [https://klipy.com/privacy](https://klipy.com/privacy)
- **Terms of Service**: [https://klipy.com/support/terms-services](https://klipy.com/support/terms-services)

1. Review the [KLIPY Privacy Policy](https://klipy.com/privacy) and [Terms of Service](https://klipy.com/support/terms-services) before registering.
2. Go to the [KLIPY Partner Panel](https://partner.klipy.com/) and register for a free account (or log in).
3. In the sidebar navigation menu, click **API Keys**.
4. Click the **Add Platform** button.
5. Fill in the platform registration fields:
   - **Platform Type**: Select **Android** or **Mobile App**
   - **Platform Name**: e.g., `LeanType`
   - **Website / URL**: Enter `https://github.com/LeanBitLab/LeanType` (or your personal website / GitHub profile)
   - **Description**: e.g., `Personal GIF and rich media search integration for LeanType Keyboard`
6. Submit the form to generate your API key. Newly created keys start in **Testing** mode (with a limit of 100 requests per hour, which is well-suited for personal keyboard searches).
7. Copy the generated **API Key**.
8. In LeanType, open **Settings → GIF Search → Klipy**, paste the key into the API key field, tap **Save**, and tap **Enable**.

---

## 🏗️ Building From Source

Prerequisites: Android SDK with Build Tools and JDK 17+.

```bash
# Clone the repository
git clone https://github.com/LeanBitLab/LeanType-Gif-Plugin.git
cd LeanType-Gif-Plugin

# Build Debug APK
./gradlew assembleDebug

# Build Release APK (requires keystore.properties configured)
./gradlew assembleRelease
```

The compiled APK will be located at:
```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## License

Licensed under the [GNU General Public License v3.0](LICENSE).

## 💖 Support the Development

If you find this project helpful, consider supporting the continuous development of the LeanType ecosystem:

<div align="left">
  <a href="https://github.com/sponsors/LeanBitLab" target="_blank">
    <img src="https://img.shields.io/badge/Sponsor-GitHub%20Sponsors-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white" alt="GitHub Sponsors" />
  </a>
  <a href="https://opencollective.com/leanbitlab-org" target="_blank">
    <img src="https://img.shields.io/badge/Donate-Open%20Collective-7B93FE?style=for-the-badge&logo=opencollective&logoColor=white" alt="Open Collective" />
  </a>
</div>

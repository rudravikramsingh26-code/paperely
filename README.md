# Paperly — a simple PDF workspace

Paperly is a focused PDF viewer and image-to-PDF creator. It keeps document handling on the device and avoids accounts, subscriptions, and AI/API tokens.

## Windows desktop app

The Windows edition is a native desktop package built with Electron. It includes the PDF.js viewer and jsPDF creator inside the installer, so opening PDFs and making PDFs from images work offline.

Download the latest **Paperly-*-Setup.exe** from the repository's [Releases](https://github.com/rudravikramsingh26-code/paperely/releases) page. The installer creates Start Menu and desktop shortcuts and registers Paperly as an available PDF opener. Windows may ask you to choose Paperly as the default PDF app.

To build locally, see [desktop/README.md](desktop/README.md).

## Android app

The Android edition is in `android/`. It supports PDF viewing, page navigation, pinch-to-zoom, sharing PDFs, and creating PDFs from images. Build releases are published by the Android GitHub Actions workflow.

## Browser app

The root `index.html` is also an installable Progressive Web App (PWA) for Chrome and Edge.

### Install the PWA
1. Open the published HTTPS site in Chrome or Edge.
2. Load it once while online so the app shell and PDF libraries can be cached.
3. Choose **Install app** from the browser menu.

### Privacy and offline use
- PDFs are processed locally in the browser and are not uploaded by Paperly.
- The browser app shell is cached by its service worker.
- The browser version downloads PDF.js and jsPDF from jsDelivr on first use and caches them after a successful online load.
- The Windows desktop installer bundles both PDF libraries; no network connection is needed for document processing.

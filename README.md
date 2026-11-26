# Paperly — a simple PDF and image workspace

Paperly is a minimalist PDF viewer, image-to-PDF creator and image compressor. It processes documents and images locally and avoids accounts, subscriptions, and AI/API tokens.

## Windows desktop app

The Windows edition bundles PDF.js, jsPDF and JSZip in its installer. It can open PDFs, create PDFs from images, compress images in batches, compare before/after file sizes, and convert common image formats.

Download **Paperly-1.1.0-Setup.exe** from the [Windows release](https://github.com/rudravikramsingh26-code/paperely/releases/tag/paperly-windows-2). See [desktop/README.md](desktop/README.md) for build details.

## Android app

The Android edition is in `android/`. It supports PDF viewing, page navigation, pinch-to-zoom, sharing PDFs, and creating PDFs from images. Build releases are published by the Android GitHub Actions workflow.

## Browser app

The root `index.html` is also an installable Progressive Web App (PWA) for Chrome and Edge.

### Install the PWA
1. Open the published HTTPS site in Chrome or Edge.
2. Load it once while online so the app shell and PDF libraries can be cached.
3. Choose **Install app** from the browser menu.

### Privacy and offline use
- PDF and image processing runs locally in the app; documents are not uploaded by Paperly.
- The browser app shell is cached by its service worker.
- The browser version downloads PDF.js, jsPDF and JSZip from jsDelivr on first use and caches them after a successful online load.
- The Windows desktop installer bundles all three libraries, so document/image processing works offline.

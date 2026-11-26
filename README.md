# Paperly — installable offline PDF reader

## Features
- Open PDFs from your device or drag and drop them
- Page navigation, zoom, text search, and download original
- Read a page or the whole PDF aloud using device voices
- Local-first: documents are processed in your browser, not uploaded
- Installable PWA for Android and Windows

## Deploy with GitHub Pages
The repository includes a GitHub Actions workflow at `.github/workflows/pages.yml`. It builds and deploys the repository root to GitHub Pages when changes are pushed to `main`.

If the first deployment does not start, open **Settings → Pages** and choose **GitHub Actions** as the build and deployment source. Then check the Actions tab.

## Install
1. Open the published HTTPS site in Chrome on Android or Chrome/Edge on Windows.
2. Load it once while online so the app shell and PDF.js library can be cached.
3. Choose **Install app** from the browser menu.

## Offline and privacy notes
- PDFs are read locally in the browser and are not uploaded by this app.
- The app shell is cached by its service worker.
- PDF.js is fetched from jsDelivr and cached after its first successful online load. Open Paperly online once before going offline.
- PDFs must be selected from the device when needed; the app does not permanently store documents.
- Speech uses voices supplied by the browser/device. Scanned image-only PDFs need OCR for search and read-aloud.

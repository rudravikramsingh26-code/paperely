# Paperly for Windows

Paperly for Windows is a minimalist desktop workspace for PDFs and images.

## Included

- Open and read PDF documents, with every page shown in order.
- Create a PDF from multiple JPG, PNG, or WebP images.
- Compress images in batches with low, medium, or high compression.
- Convert common image formats to JPG/JPEG, PNG, or WebP.
- Compare each original and compressed file size, then download the processed images together in one ZIP.
- Keep original image dimensions. JPEG/WebP quality presets are 92%, 80%, and 66%. PNG output is lossless; the quality preset does not reduce PNG size.
- Open a PDF by double-clicking it after choosing Paperly as the Windows PDF app.

Image processing, PDF rendering, and PDF creation run locally on the PC. PDF.js, jsPDF and JSZip are bundled with the installer. Paperly does not need API keys, tokens, subscriptions, or an internet connection to process files.

JPEG output uses a white background where the source has transparency. PNG and WebP preserve transparency.

## Build

From this directory, run:

```powershell
npm install
npm run dist:win
```

The installer is written to `release/Paperly-1.1.0-Setup.exe`.

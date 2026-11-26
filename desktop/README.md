# Paperly for Windows

Paperly for Windows is the desktop edition of the Paperly PDF workspace.

## Included

- Open and read PDF documents, with every page shown in order.
- Create a PDF from multiple JPG, PNG, or WebP images.
- Reorder images by selecting them in the order you want; remove images before export.
- Save the finished PDF using the Windows Save dialog.
- Open a PDF by double-clicking it after choosing Paperly as the Windows PDF app.

PDF rendering and PDF creation run locally on the PC. PDF.js and jsPDF are bundled with the installer; the app does not call an AI service or require API keys, tokens, subscriptions, or a network connection to process files.

## Build

From this directory, run:

```powershell
npm install
npm run dist:win
```

The installer is written to `release/Paperly-1.0.0-Setup.exe`.

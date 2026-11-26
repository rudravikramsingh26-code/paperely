const { app, BrowserWindow, dialog, Menu, session, shell } = require("electron");
const fs = require("node:fs");
const path = require("node:path");

let mainWindow = null;
let pendingPdfPath = findPdfPath(process.argv);

function findPdfPath(args) {
  for (const value of args.slice(1)) {
    if (typeof value !== "string" || !/\.pdf$/i.test(value)) continue;
    const candidate = path.resolve(value);
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate;
  }
  return null;
}

function sendPdfToWindow(filePath) {
  if (!mainWindow || !filePath) return;
  const send = () => {
    try {
      const bytes = new Uint8Array(fs.readFileSync(filePath));
      mainWindow.webContents.send("paperly:open-pdf", {
        name: path.basename(filePath),
        bytes
      });
    } catch (error) {
      dialog.showErrorBox("Paperly could not open this PDF", error.message);
    }
  };
  if (mainWindow.webContents.isLoading()) {
    mainWindow.webContents.once("did-finish-load", send);
  } else {
    send();
  }
}

function runPageAction(elementId) {
  if (!mainWindow) return;
  mainWindow.webContents.executeJavaScript(
    "document.getElementById(" + JSON.stringify(elementId) + ")?.click()"
  ).catch(() => {});
}

function createMenu() {
  const menu = Menu.buildFromTemplate([
    {
      label: "File",
      submenu: [
        { label: "Open PDF…", accelerator: "CmdOrCtrl+O", click: () => runPageAction("openBtn") },
        { label: "Create PDF…", accelerator: "CmdOrCtrl+Shift+N", click: () => runPageAction("createBtn") },
        { type: "separator" },
        { role: "quit", label: "Exit Paperly" }
      ]
    },
    {
      label: "View",
      submenu: [
        { role: "reload" },
        { type: "separator" },
        { role: "zoomIn" },
        { role: "zoomOut" },
        { role: "resetZoom" },
        { type: "separator" },
        { role: "togglefullscreen" }
      ]
    }
  ]);
  Menu.setApplicationMenu(menu);
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 820,
    minWidth: 760,
    minHeight: 600,
    show: false,
    title: "Paperly",
    backgroundColor: "#f7f7f5",
    autoHideMenuBar: true,
    icon: path.join(__dirname, "assets", "paperly.ico"),
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  mainWindow.once("ready-to-show", () => mainWindow.show());
  mainWindow.loadFile(path.join(__dirname, "dist", "index.html"));

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith("https://")) shell.openExternal(url);
    return { action: "deny" };
  });

  mainWindow.webContents.on("will-navigate", (event, url) => {
    if (!url.startsWith("file://")) {
      event.preventDefault();
      if (url.startsWith("https://")) shell.openExternal(url);
    }
  });

  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

const hasSingleInstance = app.requestSingleInstanceLock();
if (!hasSingleInstance) {
  app.quit();
} else {
  app.setName("Paperly");
  app.setAppUserModelId("com.paperly.desktop");

  app.on("second-instance", (_event, commandLine) => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
    const pdfPath = findPdfPath(commandLine);
    if (pdfPath) sendPdfToWindow(pdfPath);
  });

  app.whenReady().then(() => {
    session.defaultSession.on("will-download", (event, item) => {
      const fileName = item.getFilename().toLowerCase().endsWith(".pdf")
        ? item.getFilename()
        : item.getFilename() + ".pdf";
      const defaultFolder = path.join(app.getPath("documents"), "Paperly");
      fs.mkdirSync(defaultFolder, { recursive: true });
      const chosenPath = dialog.showSaveDialogSync(mainWindow, {
        title: "Save your PDF",
        buttonLabel: "Save PDF",
        defaultPath: path.join(defaultFolder, fileName),
        filters: [{ name: "PDF documents", extensions: ["pdf"] }]
      });
      if (!chosenPath) {
        item.cancel();
        return;
      }
      item.setSavePath(chosenPath);
    });

    createMenu();
    createWindow();
    if (pendingPdfPath) {
      const fileToOpen = pendingPdfPath;
      pendingPdfPath = null;
      mainWindow.webContents.once("did-finish-load", () => sendPdfToWindow(fileToOpen));
    }
  });

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });

  app.on("window-all-closed", () => {
    if (process.platform !== "darwin") app.quit();
  });
}

const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("paperlyDesktop", {
  onOpenPdf(callback) {
    if (typeof callback !== "function") return;
    ipcRenderer.on("paperly:open-pdf", (_event, payload) => callback(payload));
  }
});

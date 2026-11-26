const fs = require("node:fs");
const path = require("node:path");
const { build } = require("esbuild");
const svgToIco = require("svg-to-ico");

const desktopDir = __dirname;
const repoRoot = path.resolve(desktopDir, "..");
const outputDir = path.join(desktopDir, "dist");
const assetsDir = path.join(desktopDir, "assets");

async function main() {
  fs.rmSync(outputDir, { recursive: true, force: true });
  fs.mkdirSync(path.join(outputDir, "icons"), { recursive: true });
  fs.mkdirSync(assetsDir, { recursive: true });

  const htmlPath = path.join(repoRoot, "index.html");
  const iconPath = path.join(repoRoot, "icons", "paperly.svg");
  let html = fs.readFileSync(htmlPath, "utf8");
  const match = html.match(/<script type="module">([\s\S]*?)<\/script>/);
  if (!match) throw new Error("Could not find Paperly's browser app script in index.html.");

  let appSource = match[1];
  appSource = appSource.replace(
    /import \* as pdfjsLib from '[^']+';/,
    "import * as pdfjsLib from 'pdfjs-dist/build/pdf.mjs';"
  );
  appSource = appSource.replace(
    /import \{ jsPDF \} from '[^']+';/,
    "import { jsPDF } from 'jspdf';"
  );
  appSource = appSource.replace(
    /import JSZip from '[^']+';/,
    "import JSZip from 'jszip';"
  );
  appSource = appSource.replace(
    /pdfjsLib\.GlobalWorkerOptions\.workerSrc\s*=\s*[^;]+;/,
    "pdfjsLib.GlobalWorkerOptions.workerSrc = './pdf.worker.mjs';"
  );

  if (appSource.includes("https://cdn.jsdelivr.net/npm/pdfjs-dist") ||
      appSource.includes("https://cdn.jsdelivr.net/npm/jspdf") ||
      appSource.includes("https://cdn.jsdelivr.net/npm/jszip")) {
    throw new Error("A CDN import remains; the Windows app must bundle its PDF engines locally.");
  }

  appSource += `
if (window.paperlyDesktop && typeof window.paperlyDesktop.onOpenPdf === "function") {
  window.paperlyDesktop.onOpenPdf((payload) => {
    if (!payload || !payload.bytes || !payload.name) return;
    const file = new File([payload.bytes], payload.name, { type: "application/pdf" });
    wrappedOpenPdf(file);
  });
}
`;

  await build({
    stdin: {
      contents: appSource,
      resolveDir: desktopDir,
      sourcefile: "paperly-renderer.js",
      loader: "js"
    },
    bundle: true,
    platform: "browser",
    format: "iife",
    target: ["chrome130"],
    minify: true,
    outfile: path.join(outputDir, "app.js"),
    logLevel: "info"
  });

  const workerPath = require.resolve("pdfjs-dist/build/pdf.worker.mjs");
  fs.copyFileSync(workerPath, path.join(outputDir, "pdf.worker.mjs"));
  fs.copyFileSync(htmlPath, path.join(outputDir, "index.html"));
  fs.copyFileSync(iconPath, path.join(outputDir, "icons", "paperly.svg"));

  let packagedHtml = fs.readFileSync(path.join(outputDir, "index.html"), "utf8");
  packagedHtml = packagedHtml.replace(
    /<script type="module">[\s\S]*?<\/script>/,
    '<script defer src="./app.js"></script>'
  );
  packagedHtml = packagedHtml.replace(
    /<link rel="manifest" href="\.\/manifest\.webmanifest">\s*/,
    ""
  );
  fs.writeFileSync(path.join(outputDir, "index.html"), packagedHtml, "utf8");

  await svgToIco({
    input_name: iconPath,
    output_name: path.join(assetsDir, "paperly.ico"),
    sizes: [16, 24, 32, 48, 64, 128, 256]
  });

  console.log("Paperly Windows renderer and application icon are ready.");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});

package com.paperly.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(247, 247, 245);
    private static final int INK = Color.rgb(34, 35, 31);
    private static final int MUTED = Color.rgb(119, 120, 112);
    private static final int LINE = Color.rgb(232, 232, 227);
    private static final int WHITE = Color.WHITE;
    private static final int REQ_OPEN_PDF = 101;
    private static final int REQ_PICK_IMAGES = 102;
    private static final int REQ_SAVE_PDF = 103;

    private LinearLayout root;
    private LinearLayout body;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pdfExecutor = Executors.newSingleThreadExecutor();
    private volatile int documentToken = 0;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfDescriptor;
    private ScrollView pageScroll;
    private LinearLayout pageStack;
    private ImageView[] pageImages;
    private View[] pageCards;
    private int[] pageWidths;
    private int[] pageHeights;
    private final Set<Integer> requestedPages = new HashSet<>();
    private final Set<Integer> renderedPages = new HashSet<>();
    private final Set<Integer> wantedPages = new HashSet<>();
    private final List<Uri> selectedImages = new ArrayList<>();
    private LinearLayout imagePreview;
    private EditText fileNameInput;
    private TextView createStatus;
    private File pendingPdfFile;
    private Runnable refreshRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildShell();
        showHome();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(15), dp(24), dp(15));
        header.setBackgroundColor(BG);

        TextView logo = text("paperly.", 22, INK, true);
        logo.setLetterSpacing(-0.045f);
        header.addView(logo, new LinearLayout.LayoutParams(0, -2, 1));

        TextView caption = text("PDF WORKSPACE", 10, MUTED, true);
        caption.setLetterSpacing(0.12f);
        header.addView(caption);

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void showHome() {
        body.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = column();
        content.setPadding(dp(24), dp(42), dp(24), dp(40));

        TextView eyebrow = text("A SIMPLE PDF WORKSPACE", 10, MUTED, true);
        eyebrow.setLetterSpacing(0.16f);
        content.addView(eyebrow);

        TextView title = text("PDF, simply.", 38, INK, true);
        title.setLetterSpacing(-0.055f);
        LinearLayout.LayoutParams titleLp = margins(0, dp(12), 0, dp(8));
        content.addView(title, titleLp);

        TextView subtitle = text("Open a document and see every page.\nOr turn your images into one PDF.", 15, MUTED, false);
        subtitle.setLineSpacing(dp(3), 1f);
        content.addView(subtitle, margins(0, 0, 0, dp(30)));

        content.addView(actionCard("▤", "Open a PDF",
                "View every page in one clean, continuous document.",
                "Choose PDF", true, v -> openPdfPicker()), margins(0, 0, 0, dp(14)));

        content.addView(actionCard("▧", "Create a PDF",
                "Choose your images, give the file a name and save it.",
                "Create PDF", false, v -> showCreate()), margins(0, 0, 0, dp(22)));

        TextView privacy = text("Your files stay on your device.", 12, MUTED, false);
        privacy.setGravity(Gravity.CENTER);
        content.addView(privacy, margins(0, dp(8), 0, 0));

        scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    }

    private View actionCard(String icon, String heading, String description,
                            String action, boolean primary, View.OnClickListener listener) {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(panelBackground());
        TextView symbol = text(icon, 26, INK, true);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(round(Color.rgb(241, 241, 237), 0, 14));
        card.addView(symbol, new LinearLayout.LayoutParams(dp(46), dp(46)));

        TextView h = text(heading, 19, INK, true);
        card.addView(h, margins(0, dp(17), 0, dp(5)));
        TextView p = text(description, 13, MUTED, false);
        p.setLineSpacing(dp(2), 1f);
        card.addView(p, margins(0, 0, 0, dp(17)));

        Button button = button(action, primary);
        button.setOnClickListener(listener);
        card.addView(button, new LinearLayout.LayoutParams(-1, dp(48)));
        return card;
    }

    private void openPdfPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_OPEN_PDF);
    }

    private void openPdf(Uri uri) {
        final int token = ++documentToken;
        requestedPages.clear();
        renderedPages.clear();
        wantedPages.clear();
        showLoadingDocument(displayName(uri));

        pdfExecutor.execute(() -> {
            try {
                closePdfOnWorker();
                ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r");
                if (descriptor == null) throw new IllegalStateException("Could not open this file.");
                PdfRenderer renderer = new PdfRenderer(descriptor);
                int count = renderer.getPageCount();
                int[] widths = new int[count];
                int[] heights = new int[count];
                for (int i = 0; i < count; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    widths[i] = page.getWidth();
                    heights[i] = page.getHeight();
                    page.close();
                }
                if (token != documentToken) {
                    renderer.close();
                    descriptor.close();
                    return;
                }
                pdfRenderer = renderer;
                pdfDescriptor = descriptor;
                runOnUiThread(() -> {
                    if (token == documentToken) buildPdfPages(widths, heights, count);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (token == documentToken) {
                        Toast.makeText(this, "This PDF could not be opened.", Toast.LENGTH_LONG).show();
                        showHome();
                    }
                });
            }
        });
    }

    private void showLoadingDocument(String name) {
        body.removeAllViews();
        LinearLayout screen = column();
        screen.setPadding(dp(20), dp(14), dp(20), dp(12));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("←", false);
        back.setOnClickListener(v -> showHome());
        top.addView(back);
        TextView file = text(name, 14, INK, true);
        file.setSingleLine(true);
        file.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(file, new LinearLayout.LayoutParams(0, -2, 1));
        screen.addView(top);

        TextView loading = text("Opening your document…", 13, MUTED, false);
        loading.setGravity(Gravity.CENTER);
        screen.addView(loading, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(screen, new LinearLayout.LayoutParams(-1, -1));
    }

    private void buildPdfPages(int[] widths, int[] heights, int count) {
        body.removeAllViews();
        pageWidths = widths;
        pageHeights = heights;
        pageImages = new ImageView[count];
        pageCards = new View[count];

        LinearLayout screen = column();
        screen.setPadding(dp(16), dp(10), dp(16), dp(8));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("←", false);
        back.setOnClickListener(v -> showHome());
        top.addView(back);
        LinearLayout titleStack = column();
        TextView name = text(getCurrentDocumentName(), 14, INK, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView countLabel = text(count + (count == 1 ? " page" : " pages"), 11, MUTED, false);
        titleStack.addView(name);
        titleStack.addView(countLabel);
        LinearLayout.LayoutParams stackLp = new LinearLayout.LayoutParams(0, -2, 1);
        stackLp.leftMargin = dp(12);
        top.addView(titleStack, stackLp);
        screen.addView(top, margins(0, 0, 0, dp(12)));

        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(false);
        pageStack = column();
        pageStack.setPadding(dp(3), dp(5), dp(3), dp(18));
        pageScroll.addView(pageStack);
        screen.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(screen, new LinearLayout.LayoutParams(-1, -1));

        int targetWidthDp = Math.max(260, (int) (getResources().getDisplayMetrics().widthPixels /
                getResources().getDisplayMetrics().density) - 46);
        for (int i = 0; i < count; i++) {
            LinearLayout card = column();
            card.setPadding(dp(8), dp(8), dp(8), dp(8));
            card.setBackground(panelBackground());
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundColor(WHITE);
            int h = Math.max(120, targetWidthDp * heights[i] / Math.max(1, widths[i]));
            card.addView(image, new LinearLayout.LayoutParams(-1, dp(h)));
            TextView label = text("PAGE " + (i + 1), 10, MUTED, true);
            label.setGravity(Gravity.CENTER);
            label.setLetterSpacing(0.12f);
            card.addView(label, margins(0, dp(7), 0, 0));
            pageStack.addView(card, margins(0, 0, 0, dp(14)));
            pageImages[i] = image;
            pageCards[i] = card;
        }

        pageScroll.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> schedulePageRefresh());
        pageScroll.post(this::refreshVisiblePages);
    }

    private String currentDocumentName = "Document";
    private String getCurrentDocumentName() { return currentDocumentName; }

    private void schedulePageRefresh() {
        if (refreshRunnable != null) handler.removeCallbacks(refreshRunnable);
        refreshRunnable = this::refreshVisiblePages;
        handler.postDelayed(refreshRunnable, 75);
    }

    private void refreshVisiblePages() {
        if (pageScroll == null || pageCards == null || pageImages == null) return;
        int scrollY = pageScroll.getScrollY();
        int viewport = pageScroll.getHeight();
        if (viewport <= 0) return;
        int low = scrollY - viewport;
        int high = scrollY + viewport * 2;
        Set<Integer> wanted = new HashSet<>();
        for (int i = 0; i < pageCards.length; i++) {
            View card = pageCards[i];
            int top = card.getTop();
            int bottom = card.getBottom();
            if (bottom >= low && top <= high) wanted.add(i);
        }
        wantedPages.clear();
        wantedPages.addAll(wanted);

        for (int i = 0; i < pageImages.length; i++) {
            if (!wanted.contains(i) && renderedPages.contains(i)) {
                ImageView image = pageImages[i];
                if (image != null && image.getDrawable() instanceof BitmapDrawable) {
                    Bitmap bitmap = ((BitmapDrawable) image.getDrawable()).getBitmap();
                    image.setImageDrawable(null);
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                }
                renderedPages.remove(i);
            }
        }

        final int token = documentToken;
        for (int index : wanted) {
            if (renderedPages.contains(index) || requestedPages.contains(index)) continue;
            requestedPages.add(index);
            pdfExecutor.execute(() -> renderPdfPage(index, token));
        }
    }

    private void renderPdfPage(int index, int token) {
        Bitmap bitmap = null;
        try {
            if (token != documentToken || pdfRenderer == null) return;
            PdfRenderer.Page page = pdfRenderer.openPage(index);
            int available = Math.min(1600, Math.max(800, getResources().getDisplayMetrics().widthPixels - dp(40)));
            float scale = available / (float) page.getWidth();
            int w = Math.max(1, Math.round(page.getWidth() * scale));
            int h = Math.max(1, Math.round(page.getHeight() * scale));
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            Bitmap result = bitmap;
            runOnUiThread(() -> {
                requestedPages.remove(index);
                if (token == documentToken && wantedPages.contains(index) &&
                        pageImages != null && index < pageImages.length) {
                    pageImages[index].setImageBitmap(result);
                    renderedPages.add(index);
                } else if (!result.isRecycled()) {
                    result.recycle();
                }
            });
        } catch (Exception e) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            runOnUiThread(() -> requestedPages.remove(index));
        }
    }

    private void showCreate() {
        body.removeAllViews();
        LinearLayout screen = column();
        screen.setPadding(dp(20), dp(14), dp(20), dp(20));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("←", false);
        back.setOnClickListener(v -> showHome());
        top.addView(back);
        TextView heading = text("Create a PDF", 19, INK, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
        titleLp.leftMargin = dp(12);
        top.addView(heading, titleLp);
        screen.addView(top, margins(0, 0, 0, dp(16)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();

        LinearLayout drop = column();
        drop.setGravity(Gravity.CENTER);
        drop.setPadding(dp(18), dp(24), dp(18), dp(24));
        drop.setBackground(round(Color.rgb(252, 252, 250), LINE, 16));
        TextView addTitle = text("Add your images", 16, INK, true);
        TextView addHint = text("JPG, PNG or WebP · Select multiple images", 12, MUTED, false);
        Button add = button("Choose images", true);
        add.setOnClickListener(v -> pickImages());
        drop.addView(addTitle);
        drop.addView(addHint, margins(0, dp(4), 0, dp(14)));
        drop.addView(add);
        content.addView(drop);

        TextView selected = text("Selected images", 13, INK, true);
        content.addView(selected, margins(0, dp(21), 0, dp(9)));

        imagePreview = column();
        content.addView(imagePreview);

        TextView label = text("PDF FILE NAME", 10, MUTED, true);
        label.setLetterSpacing(0.12f);
        content.addView(label, margins(0, dp(24), 0, dp(7)));

        fileNameInput = new EditText(this);
        fileNameInput.setText("My document");
        fileNameInput.setTextSize(15);
        fileNameInput.setSingleLine(true);
        fileNameInput.setTextColor(INK);
        fileNameInput.setHintTextColor(MUTED);
        fileNameInput.setPadding(dp(13), dp(12), dp(13), dp(12));
        fileNameInput.setBackground(round(WHITE, LINE, 11));
        content.addView(fileNameInput, new LinearLayout.LayoutParams(-1, dp(50)));

        Button make = button("Create and save PDF", true);
        make.setOnClickListener(v -> createPdf());
        content.addView(make, margins(0, dp(15), 0, dp(7)));

        createStatus = text("Your images are combined in the order selected.", 12, MUTED, false);
        content.addView(createStatus);

        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(screen, new LinearLayout.LayoutParams(-1, -1));
        refreshImagePreviews();
    }

    private void pickImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_IMAGES);
    }

    private void addSelectedImages(Intent data) {
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                rememberImagePermission(uri);
                if (!selectedImages.contains(uri)) selectedImages.add(uri);
            }
        } else if (data.getData() != null) {
            Uri uri = data.getData();
            rememberImagePermission(uri);
            if (!selectedImages.contains(uri)) selectedImages.add(uri);
        }
        refreshImagePreviews();
    }

    private void rememberImagePermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
    }

    private void refreshImagePreviews() {
        if (imagePreview == null) return;
        imagePreview.removeAllViews();
        if (selectedImages.isEmpty()) {
            TextView empty = text("No images selected yet.", 12, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(16), 0, dp(16));
            imagePreview.addView(empty);
        } else {
            for (int i = 0; i < selectedImages.size(); i += 2) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                Uri left = selectedImages.get(i);
                row.addView(imageCard(left, i), new LinearLayout.LayoutParams(0, -2, 1));
                if (i + 1 < selectedImages.size()) {
                    Uri right = selectedImages.get(i + 1);
                    row.addView(imageCard(right, i + 1), new LinearLayout.LayoutParams(0, -2, 1));
                } else {
                    View spacer = new View(this);
                    row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
                }
                imagePreview.addView(row, margins(0, 0, 0, dp(10)));
            }
        }
    }

    private View imageCard(Uri uri, int index) {
        LinearLayout card = column();
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(round(WHITE, LINE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.rightMargin = dp(5);
        lp.leftMargin = dp(5);
        card.setLayoutParams(lp);

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(Color.rgb(242, 242, 238));
        Bitmap bitmap = decodeImage(uri, 420, 320);
        if (bitmap != null) thumb.setImageBitmap(bitmap);
        card.addView(thumb, new LinearLayout.LayoutParams(-1, dp(106)));

        TextView name = text(displayName(uri), 11, INK, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        card.addView(name, margins(0, dp(7), 0, dp(5)));

        Button remove = button("Remove", false);
        remove.setTextSize(11);
        remove.setOnClickListener(v -> {
            if (index >= 0 && index < selectedImages.size()) selectedImages.remove(index);
            refreshImagePreviews();
        });
        card.addView(remove, new LinearLayout.LayoutParams(-1, dp(36)));
        return card;
    }

    private void createPdf() {
        if (selectedImages.isEmpty()) {
            Toast.makeText(this, "Choose at least one image first.", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = fileNameInput.getText().toString().trim();
        if (name.isEmpty()) name = "My document";
        name = name.replaceAll("(?i)\\.pdf$", "").replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        if (name.isEmpty()) name = "My document";
        final String safeName = name;
        final List<Uri> sources = new ArrayList<>(selectedImages);
        createStatus.setText("Creating your PDF…");
        new Thread(() -> {
            File output = new File(getCacheDir(), "paperly-" + System.currentTimeMillis() + ".pdf");
            PdfDocument document = new PdfDocument();
            try {
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                for (int i = 0; i < sources.size(); i++) {
                    Bitmap bitmap = decodeImage(sources.get(i), 2200, 2800);
                    if (bitmap == null) throw new IllegalStateException("Could not read " + displayName(sources.get(i)));
                    boolean landscape = bitmap.getWidth() > bitmap.getHeight();
                    int pageW = landscape ? 842 : 595;
                    int pageH = landscape ? 595 : 842;
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create();
                    PdfDocument.Page page = document.startPage(info);
                    Canvas canvas = page.getCanvas();
                    canvas.drawColor(WHITE);
                    float margin = 18f;
                    float maxW = pageW - margin * 2;
                    float maxH = pageH - margin * 2;
                    float factor = Math.min(maxW / bitmap.getWidth(), maxH / bitmap.getHeight());
                    float drawW = bitmap.getWidth() * factor;
                    float drawH = bitmap.getHeight() * factor;
                    float left = (pageW - drawW) / 2f;
                    float top = (pageH - drawH) / 2f;
                    canvas.drawBitmap(bitmap, null, new RectF(left, top, left + drawW, top + drawH), paint);
                    document.finishPage(page);
                    bitmap.recycle();
                }
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    document.writeTo(stream);
                }
                document.close();
                runOnUiThread(() -> {
                    pendingPdfFile = output;
                    createStatus.setText("PDF ready. Choose where to save it.");
                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    save.addCategory(Intent.CATEGORY_OPENABLE);
                    save.setType("application/pdf");
                    save.putExtra(Intent.EXTRA_TITLE, safeName + ".pdf");
                    startActivityForResult(save, REQ_SAVE_PDF);
                });
            } catch (Exception e) {
                try { document.close(); } catch (Exception ignored) { }
                if (output.exists()) output.delete();
                runOnUiThread(() -> {
                    if (createStatus != null) createStatus.setText("Could not create the PDF. Try different images.");
                    Toast.makeText(this, "Could not create this PDF.", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private Bitmap decodeImage(Uri uri, int maxWidth, int maxHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void savePendingPdf(Uri destination) {
        if (pendingPdfFile == null || destination == null) return;
        File source = pendingPdfFile;
        new Thread(() -> {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IllegalStateException("Unable to write to selected folder.");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.flush();
                source.delete();
                pendingPdfFile = null;
                runOnUiThread(() -> {
                    if (createStatus != null) createStatus.setText("Saved successfully.");
                    Toast.makeText(this, "PDF saved.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Could not save the PDF.", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String displayName(Uri uri) {
        String result = "Document";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) result = cursor.getString(index);
            }
        } catch (Exception ignored) { }
        if ("Document".equals(result) && uri.getLastPathSegment() != null) result = uri.getLastPathSegment();
        return result;
    }

    private void closePdfOnWorker() {
        try { if (pdfRenderer != null) pdfRenderer.close(); } catch (Exception ignored) { }
        try { if (pdfDescriptor != null) pdfDescriptor.close(); } catch (Exception ignored) { }
        pdfRenderer = null;
        pdfDescriptor = null;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(15), dp(7), dp(15), dp(7));
        button.setBackground(round(primary ? INK : WHITE, primary ? INK : LINE, 11));
        button.setTextColor(primary ? WHITE : INK);
        return button;
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable panelBackground() {
        return round(WHITE, LINE, 17);
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(left, top, right, bottom);
        return lp;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_OPEN_PDF && data.getData() != null) {
            Uri uri = data.getData();
            rememberImagePermission(uri);
            currentDocumentName = displayName(uri);
            openPdf(uri);
        } else if (requestCode == REQ_PICK_IMAGES) {
            addSelectedImages(data);
        } else if (requestCode == REQ_SAVE_PDF && data.getData() != null) {
            savePendingPdf(data.getData());
        }
    }

    @Override
    protected void onDestroy() {
        documentToken++;
        if (refreshRunnable != null) handler.removeCallbacks(refreshRunnable);
        pdfExecutor.execute(this::closePdfOnWorker);
        pdfExecutor.shutdown();
        super.onDestroy();
    }
}

package com.paperly.app;

import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import androidx.core.content.FileProvider;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
    private static final int REQ_DEFAULT_PDF_SETUP = 104;
    private static final int REQ_PICK_COMPRESS_IMAGES = 105;
    private static final int REQ_SAVE_COMPRESSED_ZIP = 106;

    private LinearLayout root;
    private LinearLayout body;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pdfExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final List<Uri> compressionSources = new ArrayList<>();
    private final List<CompressedImage> compressedImages = new ArrayList<>();
    private LinearLayout compressionPreview;
    private TextView compressionStatus;
    private Spinner compressionLevelSpinner;
    private Spinner compressionFormatSpinner;
    private Button compressionRunButton;
    private Button compressionSaveButton;
    private volatile int compressionTaskToken = 0;
    private boolean compressionRunning = false;
    private boolean isImageCompressorOpen = false;
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
    private Uri currentDocumentUri;
    private File currentPreviewFile;
    private Button overviewSaveButton;
    private Button previewSaveButton;
    private View overviewScreen;
    private FrameLayout previewArea;
    private ZoomableImageView previewImage;
    private TextView previewCounter;
    private Bitmap previewBitmap;
    private int previewRequestToken = 0;
    private int currentPageIndex = 0;
    private int overviewScrollY = 0;
    private boolean isPreviewOpen = false;
    private float gestureDownX;
    private float gestureDownY;
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
        handleIncomingIntent(getIntent());
        if (Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            handler.postDelayed(this::maybeOfferDefaultPdfSetup, 450);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String mime = intent.getType();

        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            if (mime != null && mime.startsWith("image/")) {
                openSharedImages(intent, action);
                return;
            }
        }

        Uri incoming = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            incoming = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                incoming = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream instanceof Uri) incoming = (Uri) stream;
            }
            if (incoming == null && intent.getClipData() != null
                    && intent.getClipData().getItemCount() > 0) {
                incoming = intent.getClipData().getItemAt(0).getUri();
            }
        }

        if (incoming != null) {
            rememberImagePermission(incoming);
            openPdf(incoming);
        }
    }

    @SuppressWarnings("deprecation")
    private void openSharedImages(Intent intent, String action) {
        ArrayList<Uri> shared = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
                if (uris != null) shared.addAll(uris);
            } else {
                ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (uris != null) shared.addAll(uris);
            }
        } else {
            Uri uri;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                Object value = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                uri = value instanceof Uri ? (Uri) value : null;
            }
            if (uri != null) shared.add(uri);
        }

        if (shared.isEmpty() && intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null) shared.add(uri);
            }
        }

        selectedImages.clear();
        for (Uri uri : shared) {
            rememberImagePermission(uri);
            if (!selectedImages.contains(uri)) selectedImages.add(uri);
        }
        if (!selectedImages.isEmpty()) showCreate();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(13), dp(24), dp(13));
        header.setBackgroundColor(BG);

        TextView logo = text("paperly.", 22, INK, true);
        logo.setLetterSpacing(-0.045f);
        header.addView(logo, new LinearLayout.LayoutParams(0, -2, 1));

        TextView caption = text("PDF WORKSPACE", 10, MUTED, true);
        caption.setLetterSpacing(0.14f);
        caption.setTextSize(9);
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
        isImageCompressorOpen = false;
        body.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = column();
        content.setPadding(dp(24), dp(42), dp(24), dp(40));

        TextView eyebrow = text("A SIMPLE FILE WORKSPACE", 10, MUTED, true);
        eyebrow.setLetterSpacing(0.16f);
        content.addView(eyebrow);

        TextView title = text("PDF & images, simply.", 34, INK, true);
        title.setLetterSpacing(-0.055f);
        LinearLayout.LayoutParams titleLp = margins(0, dp(12), 0, dp(8));
        content.addView(title, titleLp);

        TextView subtitle = text("Open PDFs, create documents from images, or compress images while keeping their dimensions.", 15, MUTED, false);
        subtitle.setLineSpacing(dp(3), 1f);
        content.addView(subtitle, margins(0, 0, 0, dp(30)));

        content.addView(actionCard("▤", "Open a PDF",
                "View every page in one clean, continuous document.",
                "Choose PDF", true, v -> openPdfPicker()), margins(0, 0, 0, dp(14)));

        content.addView(actionCard("▧", "Create a PDF",
                "Choose your images, give the file a name and save it.",
                "Create PDF", false, v -> showCreate()), margins(0, 0, 0, dp(22)));

        content.addView(actionCard("◩", "Compress images",
                "Reduce image file sizes or convert between common formats.",
                "Compress images", false, v -> showImageCompressor()), margins(0, 0, 0, dp(22)));

        TextView defaultHint = text("To make Paperly your default: open any PDF, choose Paperly, then tap “Always”.", 12, MUTED, false);
        defaultHint.setGravity(Gravity.CENTER);
        defaultHint.setLineSpacing(dp(2), 1f);
        content.addView(defaultHint, margins(0, dp(8), 0, dp(10)));

        TextView privacy = text("Your files stay on your device.", 11, MUTED, false);
        privacy.setGravity(Gravity.CENTER);
        content.addView(privacy);

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

    private void maybeOfferDefaultPdfSetup() {
        android.content.SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        if (preferences.getBoolean("default_pdf_setup_seen", false) || isFinishing()) return;

        preferences.edit().putBoolean("default_pdf_setup_seen", true).apply();
        new android.app.AlertDialog.Builder(this)
                .setTitle("Make Paperly your PDF opener?")
                .setMessage("Android requires you to confirm the default app. Continue, choose one PDF, then select Paperly and tap Always. Paperly will then open PDFs from compatible apps.")
                .setPositiveButton("Set as default", (dialog, which) -> choosePdfForDefaultSetup())
                .setNegativeButton("Not now", null)
                .show();
    }

    private void choosePdfForDefaultSetup() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("application/pdf");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(pick, REQ_DEFAULT_PDF_SETUP);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open the document picker.", Toast.LENGTH_LONG).show();
        }
    }

    private void askAndroidToSetPaperlyAsPdfViewer(Uri uri) {
        rememberImagePermission(uri);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, "application/pdf");
        view.addCategory(Intent.CATEGORY_DEFAULT);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            // Do not wrap this in createChooser: Android's resolver needs to offer its
            // Always option so the user can save Paperly as the preferred PDF handler.
            startActivity(view);
        } catch (Exception e) {
            Toast.makeText(this, "Android could not open this PDF. Try opening it from Files and choosing Paperly.", Toast.LENGTH_LONG).show();
        }
    }

    private void openPdfPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_OPEN_PDF);
    }

    private interface PdfDescriptorProvider {
        ParcelFileDescriptor open() throws Exception;
    }

    private void openPdf(Uri uri) {
        currentDocumentUri = uri;
        currentPreviewFile = null;
        currentDocumentName = displayName(uri);
        openPdfSource(() -> getContentResolver().openFileDescriptor(uri, "r"), currentDocumentName);
    }

    private void openGeneratedPdfPreview(File file, String name) {
        currentDocumentUri = null;
        currentPreviewFile = file;
        pendingPdfFile = file;
        currentDocumentName = name + ".pdf";
        openPdfSource(() -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                currentDocumentName);
    }

    private void openPdfSource(PdfDescriptorProvider source, String name) {
        final int token = ++documentToken;
        requestedPages.clear();
        renderedPages.clear();
        wantedPages.clear();
        currentPageIndex = 0;
        overviewScrollY = 0;
        isPreviewOpen = false;
        previewRequestToken++;
        showLoadingDocument(name);

        pdfExecutor.execute(() -> {
            try {
                closePdfOnWorker();
                ParcelFileDescriptor descriptor = source.open();
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
        overviewScrollY = 0;
        isPreviewOpen = false;

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

        Button share = button("Share", false);
        share.setTextSize(12);
        share.setOnClickListener(v -> shareCurrentPdf());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(-2, dp(42));
        shareLp.leftMargin = dp(5);
        top.addView(share, shareLp);

        overviewSaveButton = null;
        if (currentPreviewFile != null && currentPreviewFile.exists()) {
            overviewSaveButton = button("Save", true);
            overviewSaveButton.setTextSize(12);
            overviewSaveButton.setOnClickListener(v -> startSavePendingPdf());
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-2, dp(42));
            saveLp.leftMargin = dp(5);
            top.addView(overviewSaveButton, saveLp);
        }
        screen.addView(top, margins(0, 0, 0, dp(8)));

        TextView hint = text("Tap any page to preview it.", 12, MUTED, false);
        hint.setPadding(dp(4), dp(3), dp(4), dp(8));
        screen.addView(hint);

        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(false);
        pageScroll.setClipToPadding(false);
        pageScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pageStack = column();
        pageStack.setPadding(dp(3), dp(5), dp(3), dp(18));
        pageScroll.addView(pageStack);
        screen.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        int targetWidthDp = Math.max(260, (int) (getResources().getDisplayMetrics().widthPixels /
                getResources().getDisplayMetrics().density) - 46);
        for (int i = 0; i < count; i++) {
            final int pageIndex = i;
            LinearLayout card = column();
            card.setPadding(dp(8), dp(8), dp(8), dp(8));
            card.setBackground(panelBackground());
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> openPagePreview(pageIndex));

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

        pageScroll.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
            overviewScrollY = y;
            schedulePageRefresh();
        });

        overviewScreen = screen;
        body.addView(screen, new LinearLayout.LayoutParams(-1, -1));
        pageScroll.post(this::refreshVisiblePages);
    }

    private void openPagePreview(int pageIndex) {
        if (pageCards == null || pageIndex < 0 || pageIndex >= pageCards.length) return;
        currentPageIndex = pageIndex;
        if (pageScroll != null) overviewScrollY = pageScroll.getScrollY();
        isPreviewOpen = true;
        previewRequestToken++;

        body.removeAllViews();

        LinearLayout screen = column();
        screen.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button back = button("← Pages", false);
        back.setTextSize(12);
        back.setOnClickListener(v -> closePagePreview());
        top.addView(back);

        LinearLayout titleStack = column();
        TextView name = text(getCurrentDocumentName(), 13, INK, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView mode = text("PAGE PREVIEW", 9, MUTED, true);
        mode.setLetterSpacing(0.12f);
        titleStack.addView(name);
        titleStack.addView(mode);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
        titleLp.leftMargin = dp(10);
        top.addView(titleStack, titleLp);

        Button share = button("Share", false);
        share.setTextSize(12);
        share.setOnClickListener(v -> shareCurrentPdf());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(-2, dp(42));
        shareLp.leftMargin = dp(5);
        top.addView(share, shareLp);

        previewSaveButton = null;
        if (currentPreviewFile != null && currentPreviewFile.exists()) {
            previewSaveButton = button("Save", true);
            previewSaveButton.setTextSize(12);
            previewSaveButton.setOnClickListener(v -> startSavePendingPdf());
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-2, dp(42));
            saveLp.leftMargin = dp(5);
            top.addView(previewSaveButton, saveLp);
        }
        screen.addView(top, margins(0, 0, 0, dp(12)));

        previewArea = new FrameLayout(this);
        previewArea.setBackground(round(Color.rgb(237, 237, 233), LINE, 16));
        previewArea.setClipToPadding(true);

        previewImage = new ZoomableImageView(this);
        previewImage.setBackgroundColor(WHITE);
        previewImage.setPageTurnListener(delta -> navigatePreviewPage(currentPageIndex + delta));
        FrameLayout.LayoutParams imageLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        imageLp.setMargins(dp(12), dp(12), dp(12), dp(12));
        previewArea.addView(previewImage, imageLp);

        screen.addView(previewArea, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView zoomHint = text("Pinch to zoom · Drag to inspect", 11, MUTED, false);
        zoomHint.setGravity(Gravity.CENTER);
        screen.addView(zoomHint, margins(0, dp(8), 0, dp(2)));

        previewCounter = text("", 13, INK, true);
        previewCounter.setGravity(Gravity.CENTER);
        previewCounter.setPadding(0, dp(12), 0, dp(2));
        screen.addView(previewCounter);

        TextView swipeHint = text("Swipe left or right to turn pages", 11, MUTED, false);
        swipeHint.setGravity(Gravity.CENTER);
        screen.addView(swipeHint, margins(0, dp(2), 0, dp(4)));

        body.addView(screen, new LinearLayout.LayoutParams(-1, -1));
        updatePreviewCounter();

        previewArea.post(() -> renderPreviewPage(currentPageIndex));
    }

    private void closePagePreview() {
        if (!isPreviewOpen || overviewScreen == null) return;
        isPreviewOpen = false;
        previewRequestToken++;
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        if (previewImage != null) previewImage.setImageDrawable(null);
        previewArea = null;
        previewImage = null;
        previewCounter = null;
        body.removeAllViews();
        body.addView(overviewScreen, new LinearLayout.LayoutParams(-1, -1));
        if (pageScroll != null) {
            pageScroll.post(() -> {
                pageScroll.scrollTo(0, overviewScrollY);
                refreshVisiblePages();
            });
        }
    }

    private void navigatePreviewPage(int pageIndex) {
        if (!isPreviewOpen || pageCards == null || pageIndex < 0 || pageIndex >= pageCards.length) return;
        currentPageIndex = pageIndex;
        updatePreviewCounter();
        renderPreviewPage(pageIndex);
    }

    private void updatePreviewCounter() {
        if (previewCounter != null && pageCards != null) {
            previewCounter.setText((currentPageIndex + 1) + " of " + pageCards.length);
        }
    }

    private void renderPreviewPage(int pageIndex) {
        if (!isPreviewOpen || previewArea == null || pageIndex < 0 || pageIndex >= pageCards.length) return;
        final int request = ++previewRequestToken;
        final int document = documentToken;
        final int availableWidth = Math.max(1, previewArea.getWidth() - dp(28));
        final int availableHeight = Math.max(1, previewArea.getHeight() - dp(28));
        if (availableWidth <= 1 || availableHeight <= 1) {
            previewArea.post(() -> renderPreviewPage(pageIndex));
            return;
        }

        pdfExecutor.execute(() -> {
            Bitmap bitmap = null;
            PdfRenderer.Page page = null;
            try {
                if (document != documentToken || pdfRenderer == null) return;
                page = pdfRenderer.openPage(pageIndex);
                float scale = Math.min(
                        availableWidth / (float) page.getWidth(),
                        availableHeight / (float) page.getHeight());
                scale = Math.min(scale, 2.0f);
                int width = Math.max(1, Math.round(page.getWidth() * scale));
                int height = Math.max(1, Math.round(page.getHeight() * scale));
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();
                page = null;

                Bitmap result = bitmap;
                runOnUiThread(() -> {
                    if (isPreviewOpen && request == previewRequestToken && document == documentToken
                            && previewImage != null) {
                        Bitmap old = previewBitmap;
                        previewBitmap = result;
                        previewImage.setImageBitmap(result);
                        if (old != null && old != result && !old.isRecycled()) old.recycle();
                    } else if (!result.isRecycled()) {
                        result.recycle();
                    }
                });
            } catch (Exception e) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                runOnUiThread(() -> {
                    if (isPreviewOpen && request == previewRequestToken) {
                        Toast.makeText(this, "Could not render this page.", Toast.LENGTH_SHORT).show();
                    }
                });
            } finally {
                if (page != null) {
                    try { page.close(); } catch (Exception ignored) { }
                }
            }
        });
    }
    private boolean handleSinglePageTouch(View pageView, MotionEvent event, int pageIndex) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureDownX = event.getX();
                gestureDownY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - gestureDownX;
                float dy = event.getY() - gestureDownY;
                if (Math.abs(dx) > dp(42) && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                    // Natural page-turn gesture: swipe left for next, swipe right for previous.
                    navigatePreviewPage(pageIndex + (dx < 0 ? 1 : -1));
                } else if (Math.abs(dx) < dp(14) && Math.abs(dy) < dp(14)) {
                    // Tap zones: left side goes back, right side goes forward.
                    float x = event.getX();
                    float width = Math.max(1, pageView.getWidth());
                    if (x <= width * 0.28f) navigatePreviewPage(pageIndex - 1);
                    else if (x >= width * 0.72f) navigatePreviewPage(pageIndex + 1);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    private String currentDocumentName = "Document";
    private String getCurrentDocumentName() { return currentDocumentName; }

    private void schedulePageRefresh() {
        if (refreshRunnable != null) handler.removeCallbacks(refreshRunnable);
        refreshRunnable = this::refreshVisiblePages;
        handler.postDelayed(refreshRunnable, 75);
    }

    private void refreshVisiblePages() {
        if (pageScroll == null || pageCards == null || pageImages == null || isPreviewOpen) return;
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
        isImageCompressorOpen = false;
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
                    openGeneratedPdfPreview(output, safeName);
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


    private static class CompressedImage {
        final Uri source;
        final String sourceName;
        final long originalBytes;
        final File outputFile;
        final String outputName;
        final String outputMime;
        final long outputBytes;

        CompressedImage(Uri source, String sourceName, long originalBytes,
                        File outputFile, String outputName, String outputMime) {
            this.source = source;
            this.sourceName = sourceName;
            this.originalBytes = originalBytes;
            this.outputFile = outputFile;
            this.outputName = outputName;
            this.outputMime = outputMime;
            this.outputBytes = outputFile.length();
        }
    }

    private void showImageCompressor() {
        compressionTaskToken++;
        compressionRunning = false;
        isImageCompressorOpen = true;
        body.removeAllViews();

        LinearLayout screen = column();
        screen.setPadding(dp(20), dp(14), dp(20), dp(20));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("←", false);
        back.setOnClickListener(v -> {
            compressionTaskToken++;
            compressionRunning = false;
            isImageCompressorOpen = false;
            showHome();
        });
        top.addView(back);
        TextView heading = text("Compress images", 19, INK, true);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(0, -2, 1);
        headingLp.leftMargin = dp(12);
        top.addView(heading, headingLp);
        screen.addView(top, margins(0, 0, 0, dp(16)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();

        LinearLayout drop = column();
        drop.setGravity(Gravity.CENTER);
        drop.setPadding(dp(18), dp(23), dp(18), dp(23));
        drop.setBackground(round(Color.rgb(252, 252, 250), LINE, 16));
        TextView addTitle = text("Choose images to compress", 16, INK, true);
        TextView addHint = text("JPG / JPEG, PNG, WebP and other Android-supported images", 12, MUTED, false);
        addHint.setGravity(Gravity.CENTER);
        Button add = button("Choose images", true);
        add.setOnClickListener(v -> pickCompressionImages());
        drop.addView(addTitle);
        drop.addView(addHint, margins(0, dp(4), 0, dp(14)));
        drop.addView(add);
        content.addView(drop);

        TextView selectedLabel = text("SELECTED IMAGES", 10, MUTED, true);
        selectedLabel.setLetterSpacing(0.12f);
        content.addView(selectedLabel, margins(0, dp(21), 0, dp(8)));
        compressionPreview = column();
        content.addView(compressionPreview);

        TextView settingsLabel = text("COMPRESSION SETTINGS", 10, MUTED, true);
        settingsLabel.setLetterSpacing(0.12f);
        content.addView(settingsLabel, margins(0, dp(21), 0, dp(8)));

        TextView qualityLabel = text("Compression level", 12, MUTED, true);
        content.addView(qualityLabel, margins(0, 0, 0, dp(6)));
        compressionLevelSpinner = createCompressionSpinner(new String[]{
                "Low · Best quality (92%)",
                "Medium · Balanced (80%)",
                "High · Smaller files (66%)"
        });
        compressionLevelSpinner.setSelection(1);
        content.addView(compressionLevelSpinner, new LinearLayout.LayoutParams(-1, dp(50)));

        TextView formatLabel = text("Convert to", 12, MUTED, true);
        content.addView(formatLabel, margins(0, dp(14), 0, dp(6)));
        compressionFormatSpinner = createCompressionSpinner(new String[]{
                "Keep original format",
                "JPG / JPEG",
                "PNG · Lossless",
                "WebP"
        });
        content.addView(compressionFormatSpinner, new LinearLayout.LayoutParams(-1, dp(50)));

        TextView qualityHint = text("Image dimensions are preserved. PNG stays lossless; quality levels apply to JPEG and WebP.", 11, MUTED, false);
        qualityHint.setLineSpacing(dp(2), 1f);
        content.addView(qualityHint, margins(0, dp(10), 0, dp(4)));

        compressionRunButton = button("Compress images", true);
        compressionRunButton.setOnClickListener(v -> compressSelectedImages());
        content.addView(compressionRunButton, margins(0, dp(16), 0, dp(8)));

        compressionSaveButton = button("Save all as ZIP", false);
        compressionSaveButton.setEnabled(false);
        compressionSaveButton.setOnClickListener(v -> startSaveCompressedZip());
        content.addView(compressionSaveButton, margins(0, 0, 0, dp(8)));

        compressionStatus = text("Choose one or more images to begin.", 12, MUTED, false);
        compressionStatus.setLineSpacing(dp(2), 1f);
        content.addView(compressionStatus);

        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(screen, new LinearLayout.LayoutParams(-1, -1));

        AdapterView.OnItemSelectedListener invalidateListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!compressedImages.isEmpty()) {
                    clearCompressedImageResults();
                    refreshCompressionPreview();
                    if (compressionStatus != null) compressionStatus.setText(
                            "Settings changed. Compress the images again to apply them.");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        };
        compressionLevelSpinner.setOnItemSelectedListener(invalidateListener);
        compressionFormatSpinner.setOnItemSelectedListener(invalidateListener);
        refreshCompressionPreview();
    }

    private Spinner createCompressionSpinner(String[] options) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(round(WHITE, LINE, 11));
        spinner.setPadding(dp(10), 0, dp(10), 0);
        return spinner;
    }

    private void pickCompressionImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_COMPRESS_IMAGES);
    }

    private void addCompressionImages(Intent data) {
        clearCompressedImageResults();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                rememberImagePermission(uri);
                if (!compressionSources.contains(uri)) compressionSources.add(uri);
            }
        } else if (data.getData() != null) {
            Uri uri = data.getData();
            rememberImagePermission(uri);
            if (!compressionSources.contains(uri)) compressionSources.add(uri);
        }
        refreshCompressionPreview();
        if (compressionStatus != null) {
            compressionStatus.setText(compressionSources.size()
                    + " image(s) selected. Original dimensions will be kept.");
        }
    }

    private void clearCompressedImageResults() {
        for (CompressedImage item : compressedImages) {
            if (item.outputFile != null && item.outputFile.exists()) item.outputFile.delete();
        }
        compressedImages.clear();
        if (compressionSaveButton != null) compressionSaveButton.setEnabled(false);
    }

    private long getUriSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Exception ignored) { }
        return -1L;
    }

    private String readableBytes(long bytes) {
        if (bytes < 0) return "Size unavailable";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private void refreshCompressionPreview() {
        if (compressionPreview == null) return;
        compressionPreview.removeAllViews();
        if (compressionSources.isEmpty()) {
            TextView empty = text("No images selected yet.", 12, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(14), 0, dp(14));
            compressionPreview.addView(empty);
        } else {
            for (int i = 0; i < compressionSources.size(); i++) {
                Uri uri = compressionSources.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(9), dp(7), dp(9));
                row.setBackground(round(WHITE, LINE, 12));

                LinearLayout details = column();
                TextView name = text(displayName(uri), 12, INK, true);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                details.addView(name);
                String info = readableBytes(getUriSize(uri));
                for (CompressedImage result : compressedImages) {
                    if (result.source.equals(uri)) {
                        info += "  →  " + readableBytes(result.outputBytes);
                        break;
                    }
                }
                details.addView(text(info, 11, MUTED, false), margins(0, dp(3), 0, 0));
                row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));

                Button remove = button("×", false);
                remove.setTextSize(18);
                remove.setPadding(dp(10), 0, dp(10), 0);
                final int index = i;
                remove.setOnClickListener(v -> {
                    compressionTaskToken++;
                    compressionRunning = false;
                    clearCompressedImageResults();
                    if (index < compressionSources.size()) compressionSources.remove(index);
                    refreshCompressionPreview();
                    if (compressionRunButton != null) compressionRunButton.setEnabled(!compressionSources.isEmpty());
                    if (compressionStatus != null) compressionStatus.setText("Image removed.");
                });
                row.addView(remove, new LinearLayout.LayoutParams(dp(42), dp(42)));
                compressionPreview.addView(row, margins(0, 0, 0, dp(8)));
            }
        }
        if (compressionRunButton != null) compressionRunButton.setEnabled(
                !compressionSources.isEmpty() && !compressionRunning);
        if (compressionSaveButton != null) compressionSaveButton.setEnabled(
                !compressionRunning && !compressedImages.isEmpty()
                        && compressedImages.size() == compressionSources.size());
    }

    private String getSourceMime(Uri uri) {
        String mime = getContentResolver().getType(uri);
        if (mime != null) {
            mime = mime.toLowerCase(java.util.Locale.US);
            if ("image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime)) {
                return mime;
            }
        }
        String name = displayName(uri).toLowerCase(java.util.Locale.US);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "image/jpeg";
    }

    private String chooseCompressionMime(Uri source, int formatChoice) {
        if (formatChoice == 1) return "image/jpeg";
        if (formatChoice == 2) return "image/png";
        if (formatChoice == 3) return "image/webp";
        return getSourceMime(source);
    }

    private String extensionForMime(String mime) {
        if ("image/png".equals(mime)) return "png";
        if ("image/webp".equals(mime)) return "webp";
        return "jpg";
    }

    private String safeImageBaseName(String name) {
        String base = name.replaceAll("\\.[^.]*$", "")
                .replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return base.isEmpty() ? "image" : base;
    }

    private File compressOneImage(Uri source, String name, String mime, int quality) throws Exception {
        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(source)) {
            if (input == null) throw new IllegalStateException("Could not open " + name);
            bitmap = BitmapFactory.decodeStream(input);
        }
        if (bitmap == null) throw new IllegalStateException("Unsupported or unreadable image: " + name);

        Bitmap toCompress = bitmap;
        Bitmap flattened = null;
        Bitmap.CompressFormat compressFormat;
        if ("image/png".equals(mime)) {
            compressFormat = Bitmap.CompressFormat.PNG;
        } else if ("image/webp".equals(mime)) {
            compressFormat = Bitmap.CompressFormat.WEBP;
        } else {
            compressFormat = Bitmap.CompressFormat.JPEG;
            if (bitmap.hasAlpha()) {
                flattened = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(flattened);
                canvas.drawColor(WHITE);
                canvas.drawBitmap(bitmap, 0, 0, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
                toCompress = flattened;
            }
        }

        File directory = new File(getCacheDir(), "paperly-compressed");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not prepare temporary storage.");
        }
        File output = File.createTempFile("paperly-image-", "." + extensionForMime(mime), directory);
        boolean success = false;
        try (FileOutputStream stream = new FileOutputStream(output)) {
            int appliedQuality = "image/png".equals(mime) ? 100 : quality;
            success = toCompress.compress(compressFormat, appliedQuality, stream);
            stream.flush();
        } finally {
            if (flattened != null && !flattened.isRecycled()) flattened.recycle();
            if (!bitmap.isRecycled()) bitmap.recycle();
            if (!success && output.exists()) output.delete();
        }
        if (!success) throw new IllegalStateException("Could not encode " + name);
        return output;
    }

    private void compressSelectedImages() {
        if (compressionRunning || compressionSources.isEmpty()) return;
        clearCompressedImageResults();
        final List<Uri> sources = new ArrayList<>(compressionSources);
        final int qualityChoice = compressionLevelSpinner.getSelectedItemPosition();
        final int formatChoice = compressionFormatSpinner.getSelectedItemPosition();
        final int quality = qualityChoice == 0 ? 92 : (qualityChoice == 2 ? 66 : 80);
        final int token = ++compressionTaskToken;
        compressionRunning = true;
        compressionRunButton.setEnabled(false);
        compressionSaveButton.setEnabled(false);
        compressionStatus.setText("Preparing images…");

        imageExecutor.execute(() -> {
            List<CompressedImage> results = new ArrayList<>();
            try {
                for (int i = 0; i < sources.size(); i++) {
                    Uri uri = sources.get(i);
                    String name = displayName(uri);
                    String mime = chooseCompressionMime(uri, formatChoice);
                    long originalBytes = getUriSize(uri);
                    File output = compressOneImage(uri, name, mime, quality);
                    String outputName = safeImageBaseName(name) + "." + extensionForMime(mime);
                    results.add(new CompressedImage(uri, name, originalBytes, output, outputName, mime));
                    final int current = i + 1;
                    runOnUiThread(() -> {
                        if (token == compressionTaskToken && compressionStatus != null) {
                            compressionStatus.setText("Compressing image " + current
                                    + " of " + sources.size() + "…");
                        }
                    });
                }

                runOnUiThread(() -> {
                    if (token != compressionTaskToken || isFinishing()) {
                        for (CompressedImage item : results) if (item.outputFile.exists()) item.outputFile.delete();
                        return;
                    }
                    compressedImages.addAll(results);
                    compressionRunning = false;
                    refreshCompressionPreview();
                    long before = 0L, after = 0L;
                    for (CompressedImage item : compressedImages) {
                        if (item.originalBytes > 0) before += item.originalBytes;
                        after += item.outputBytes;
                    }
                    String sizeText = before > 0
                            ? readableBytes(before) + " → " + readableBytes(after)
                            : readableBytes(after) + " output";
                    compressionStatus.setText("Done. " + compressedImages.size()
                            + " image(s) ready. " + sizeText);
                    if (formatChoice == 2) {
                        compressionStatus.append("\nPNG is lossless; the selected compression level does not lower PNG quality.");
                    }
                });
            } catch (OutOfMemoryError memoryError) {
                for (CompressedImage item : results) if (item.outputFile.exists()) item.outputFile.delete();
                runOnUiThread(() -> {
                    if (token == compressionTaskToken) {
                        compressionRunning = false;
                        refreshCompressionPreview();
                        compressionStatus.setText("This image is too large to process with the available memory. Try fewer images at once.");
                    }
                });
            } catch (Exception e) {
                for (CompressedImage item : results) if (item.outputFile.exists()) item.outputFile.delete();
                runOnUiThread(() -> {
                    if (token == compressionTaskToken) {
                        compressionRunning = false;
                        refreshCompressionPreview();
                        compressionStatus.setText("Could not process an image. Try a common JPG, PNG or WebP file.");
                        Toast.makeText(this, "Image compression failed.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void startSaveCompressedZip() {
        if (compressedImages.isEmpty() || compressedImages.size() != compressionSources.size()) {
            Toast.makeText(this, "Compress your images first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("application/zip");
        save.putExtra(Intent.EXTRA_TITLE, "Paperly-compressed-images.zip");
        startActivityForResult(save, REQ_SAVE_COMPRESSED_ZIP);
    }

    private void saveCompressedImagesZip(Uri destination) {
        final List<CompressedImage> results = new ArrayList<>(compressedImages);
        imageExecutor.execute(() -> {
            try {
                OutputStream output = getContentResolver().openOutputStream(destination, "w");
                if (output == null) throw new IllegalStateException("Could not open the selected save location.");
                try (ZipOutputStream zip = new ZipOutputStream(output)) {
                    Set<String> usedNames = new HashSet<>();
                    byte[] buffer = new byte[64 * 1024];
                    for (CompressedImage item : results) {
                        String entryName = item.outputName;
                        String base = safeImageBaseName(entryName);
                        String extension = "." + extensionForMime(item.outputMime);
                        int suffix = 2;
                        while (!usedNames.add(entryName.toLowerCase(java.util.Locale.US))) {
                            entryName = base + " (" + suffix++ + ")" + extension;
                        }
                        zip.putNextEntry(new ZipEntry(entryName));
                        try (InputStream input = new FileInputStream(item.outputFile)) {
                            int read;
                            while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
                        }
                        zip.closeEntry();
                    }
                    zip.finish();
                }
                runOnUiThread(() -> Toast.makeText(this,
                        "Compressed images saved as a ZIP.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Could not save the compressed images.", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void startSavePendingPdf() {
        if (pendingPdfFile == null || !pendingPdfFile.exists()) {
            Toast.makeText(this, "Create a PDF first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("application/pdf");
        save.putExtra(Intent.EXTRA_TITLE, currentDocumentName);
        startActivityForResult(save, REQ_SAVE_PDF);
    }

    private void shareCurrentPdf() {
        Uri shareUri = currentDocumentUri;
        if (currentPreviewFile != null && currentPreviewFile.exists()) {
            try {
                shareUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", currentPreviewFile);
            } catch (Exception e) {
                Toast.makeText(this, "Could not prepare this PDF to share.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (shareUri == null) {
            Toast.makeText(this, "No PDF is available to share.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/pdf");
        send.putExtra(Intent.EXTRA_STREAM, shareUri);
        send.setClipData(ClipData.newUri(getContentResolver(), "Paperly PDF", shareUri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "Share PDF"));
        } catch (Exception e) {
            Toast.makeText(this, "No compatible app was found to share this PDF.", Toast.LENGTH_LONG).show();
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
                    currentDocumentUri = destination;
                    currentPreviewFile = null;
                    if (previewSaveButton != null) previewSaveButton.setVisibility(View.GONE);
                    if (overviewSaveButton != null) overviewSaveButton.setVisibility(View.GONE);
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

    private class ZoomableImageView extends ImageView {
        private final Matrix imageMatrix = new Matrix();
        private final RectF mappedBounds = new RectF();
        private final ScaleGestureDetector scaleDetector;
        private Bitmap imageBitmap;
        private float userScale = 1f;
        private float downX;
        private float downY;
        private float lastX;
        private float lastY;
        private boolean moved;
        private boolean scalingGesture;
        private PageTurnListener pageTurnListener;

        ZoomableImageView(android.content.Context context) {
            super(context);
            setScaleType(ImageView.ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScaleBegin(ScaleGestureDetector detector) {
                            scalingGesture = true;
                            moved = true;
                            return true;
                        }

                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            applyZoom(detector.getScaleFactor(),
                                    detector.getFocusX(), detector.getFocusY());
                            return true;
                        }
                    });
        }

        void setPageTurnListener(PageTurnListener listener) {
            pageTurnListener = listener;
        }

        @Override
        public void setImageBitmap(Bitmap bitmap) {
            imageBitmap = bitmap;
            super.setImageBitmap(bitmap);
            fitImage();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            fitImage();
        }

        private void fitImage() {
            if (imageBitmap == null || getWidth() <= 0 || getHeight() <= 0) return;
            float fit = Math.min(getWidth() / (float) imageBitmap.getWidth(),
                    getHeight() / (float) imageBitmap.getHeight());
            imageMatrix.reset();
            imageMatrix.setScale(fit, fit);
            imageMatrix.postTranslate(
                    (getWidth() - imageBitmap.getWidth() * fit) / 2f,
                    (getHeight() - imageBitmap.getHeight() * fit) / 2f);
            userScale = 1f;
            setImageMatrix(imageMatrix);
        }


        private void applyZoom(float factor, float focusX, float focusY) {
            float next = Math.max(1f, Math.min(5f, userScale * factor));
            float applied = next / userScale;
            if (Math.abs(applied - 1f) < 0.001f) return;
            imageMatrix.postScale(applied, applied, focusX, focusY);
            userScale = next;
            constrainImage();
            setImageMatrix(imageMatrix);
        }

        private void constrainImage() {
            if (imageBitmap == null) return;
            mappedBounds.set(0, 0, imageBitmap.getWidth(), imageBitmap.getHeight());
            imageMatrix.mapRect(mappedBounds);

            float dx = 0f;
            float dy = 0f;
            if (mappedBounds.width() <= getWidth()) {
                dx = getWidth() / 2f - mappedBounds.centerX();
            } else if (mappedBounds.left > 0) {
                dx = -mappedBounds.left;
            } else if (mappedBounds.right < getWidth()) {
                dx = getWidth() - mappedBounds.right;
            }

            if (mappedBounds.height() <= getHeight()) {
                dy = getHeight() / 2f - mappedBounds.centerY();
            } else if (mappedBounds.top > 0) {
                dy = -mappedBounds.top;
            } else if (mappedBounds.bottom < getHeight()) {
                dy = getHeight() - mappedBounds.bottom;
            }

            imageMatrix.postTranslate(dx, dy);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = event.getX();
                    downY = lastY = event.getY();
                    moved = false;
                    scalingGesture = false;
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    scalingGesture = true;
                    moved = true;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (scaleDetector.isInProgress() || event.getPointerCount() > 1) {
                        scalingGesture = true;
                        moved = true;
                        return true;
                    }
                    if (userScale > 1.01f) {
                        float x = event.getX();
                        float y = event.getY();
                        imageMatrix.postTranslate(x - lastX, y - lastY);
                        constrainImage();
                        setImageMatrix(imageMatrix);
                        lastX = x;
                        lastY = y;
                        moved = true;
                    } else if (Math.abs(event.getX() - downX) > dp(8)
                            || Math.abs(event.getY() - downY) > dp(8)) {
                        moved = true;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (!scalingGesture && userScale <= 1.01f && pageTurnListener != null) {
                        if (Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                            pageTurnListener.onPageTurn(dx < 0 ? 1 : -1);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    moved = false;
                    scalingGesture = false;
                    return true;

                default:
                    return true;
            }
        }
    }

    private interface PageTurnListener {
        void onPageTurn(int delta);
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
        button.setBackground(round(primary ? INK : WHITE, primary ? INK : LINE, 14));
        button.setElevation(0f);
        button.setStateListAnimator(null);
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
        if (requestCode == REQ_DEFAULT_PDF_SETUP && data.getData() != null) {
            askAndroidToSetPaperlyAsPdfViewer(data.getData());
        } else if (requestCode == REQ_OPEN_PDF && data.getData() != null) {
            Uri uri = data.getData();
            rememberImagePermission(uri);
            currentDocumentName = displayName(uri);
            openPdf(uri);
        } else if (requestCode == REQ_PICK_IMAGES) {
            addSelectedImages(data);
        } else if (requestCode == REQ_PICK_COMPRESS_IMAGES) {
            addCompressionImages(data);
        } else if (requestCode == REQ_SAVE_COMPRESSED_ZIP && data.getData() != null) {
            saveCompressedImagesZip(data.getData());
        } else if (requestCode == REQ_SAVE_PDF && data.getData() != null) {
            savePendingPdf(data.getData());
        }
    }

    @Override
    public void onBackPressed() {
        if (isImageCompressorOpen) {
            compressionTaskToken++;
            compressionRunning = false;
            isImageCompressorOpen = false;
            showHome();
            return;
        }
        if (isPreviewOpen) {
            closePagePreview();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        documentToken++;
        previewRequestToken++;
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        if (refreshRunnable != null) handler.removeCallbacks(refreshRunnable);
        pdfExecutor.execute(this::closePdfOnWorker);
        pdfExecutor.shutdown();
        compressionTaskToken++;
        imageExecutor.shutdown();
        clearCompressedImageResults();
        super.onDestroy();
    }
}

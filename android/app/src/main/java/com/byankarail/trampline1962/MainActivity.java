package com.byankarail.trampline1962;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final String REPO = "Byanka-Rail/TRAMP-LINE-1962";
    private static final String RELEASE_API =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final Pattern CONTENT_VERSION = Pattern.compile(
            "TRAMP_LINE_CONTENT_VERSION\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]"
    );
    private static final long MAX_INDEX_BYTES = 12L * 1024L * 1024L;

    private WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private File webDir;
    private File activeIndex;
    private File backupIndex;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webDir = new File(getFilesDir(), "web");
        if (!webDir.exists()) webDir.mkdirs();
        activeIndex = new File(webDir, "index.html");
        backupIndex = new File(webDir, "index.prev.html");

        ensureBaseContent();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !("file".equalsIgnoreCase(scheme) ||
                         "https".equalsIgnoreCase(scheme) ||
                         "http".equalsIgnoreCase(scheme));
            }
        });

        loadGame();

        // Silent background update check. The game remains usable even if GitHub is unreachable.
        new Thread(this::checkForContentUpdate).start();
    }

    private void ensureBaseContent() {
        if (activeIndex.exists() && activeIndex.length() > 0) return;
        try (InputStream in = getAssets().open("index.html");
             FileOutputStream out = new FileOutputStream(activeIndex)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            throw new RuntimeException("기본 게임 파일을 설치하지 못했습니다.", e);
        }
    }

    private void loadGame() {
        String url = "file://" + activeIndex.getAbsolutePath();
        webView.loadUrl(url);
    }

    private String readVersion(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() <= MAX_INDEX_BYTES) {
                out.write(buf, 0, n);
            }
            String html = out.toString(StandardCharsets.UTF_8.name());
            Matcher m = CONTENT_VERSION.matcher(html);
            return m.find() ? normalizeVersion(m.group(1)) : "0";
        } catch (Exception e) {
            return "0";
        }
    }

    private String normalizeVersion(String v) {
        if (v == null) return "0";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v.replaceAll("[^0-9.].*$", "");
    }

    private int compareVersions(String a, String b) {
        String[] aa = normalizeVersion(a).split("\\.");
        String[] bb = normalizeVersion(b).split("\\.");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            int x = i < aa.length && !aa[i].isEmpty() ? Integer.parseInt(aa[i]) : 0;
            int y = i < bb.length && !bb[i].isEmpty() ? Integer.parseInt(bb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private void checkForContentUpdate() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RELEASE_API).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "TRAMP-LINE-1962-Android/2.2.0");

            if (conn.getResponseCode() != 200) return;
            String json = readAll(conn.getInputStream(), 2 * 1024 * 1024);
            JSONObject release = new JSONObject(json);

            String latest = normalizeVersion(release.optString("tag_name", "0"));
            String current = readVersion(activeIndex);
            if (compareVersions(latest, current) <= 0) return;

            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) return;

            String wantedPrefix = "TRAMP_LINE_UPDATE_v" + latest;
            String assetUrl = null;
            String assetName = null;

            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "");
                if (name.startsWith(wantedPrefix) && name.endsWith(".zip")) {
                    assetName = name;
                    assetUrl = a.optString("browser_download_url", null);
                    break;
                }
            }
            if (assetUrl == null) return;

            File zip = new File(getCacheDir(), "tramp_update.zip");
            download(assetUrl, zip);

            File candidate = new File(webDir, "index.new.html");
            if (!extractIndex(zip, candidate)) {
                zip.delete();
                candidate.delete();
                return;
            }

            String candidateVersion = readVersion(candidate);
            if (compareVersions(candidateVersion, latest) < 0) {
                candidate.delete();
                zip.delete();
                return;
            }

            if (backupIndex.exists()) backupIndex.delete();
            if (activeIndex.exists() && !activeIndex.renameTo(backupIndex)) {
                candidate.delete();
                zip.delete();
                return;
            }
            if (!candidate.renameTo(activeIndex)) {
                if (backupIndex.exists()) backupIndex.renameTo(activeIndex);
                zip.delete();
                return;
            }

            zip.delete();

            final String shownVersion = candidateVersion;
            final String shownAsset = assetName;
            main.post(() -> {
                Toast.makeText(
                        MainActivity.this,
                        "콘텐츠 " + shownVersion + " 업데이트 완료",
                        Toast.LENGTH_LONG
                ).show();
                loadGame();
            });
        } catch (Exception ignored) {
            // Network/update failure must never block normal gameplay.
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void download(String url, File outFile) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "TRAMP-LINE-1962-Android/2.2.0");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

        try (BufferedInputStream in = new BufferedInputStream(c.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 20L * 1024L * 1024L) throw new Exception("업데이트 ZIP이 너무 큽니다.");
                out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    private boolean extractIndex(File zipFile, File outFile) {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory()) continue;
                if (!"index.html".equals(name)) continue;

                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = zis.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_INDEX_BYTES) throw new Exception("index.html too large");
                    out.write(buf, 0, n);
                }
                out.flush();
                return total > 0;
            }
        } catch (Exception ignored) {
        }
        outFile.delete();
        return false;
    }

    private String readAll(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > maxBytes) throw new Exception("response too large");
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}

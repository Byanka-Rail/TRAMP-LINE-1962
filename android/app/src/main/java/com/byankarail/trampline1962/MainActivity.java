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
    // 릴리스 없이도 갱신되게: main 브랜치의 index.html 을 직접 본다.
    // (푸시만 하면 폰에 실린다. 릴리스는 브랜치 확인이 안 될 때의 예비 경로.)
    private static final String BRANCH_RAW =
            "https://raw.githubusercontent.com/" + REPO + "/main/index.html";
    private static final String UA = "TRAMP-LINE-1962-Android/2.4.0";
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
        // 처음이면 내장본을 깐다. 이미 있어도 APK 를 새로 깔아 내장본이 더 새거면 그걸로 바꾼다.
        // (전에는 한 번 깔린 파일을 영영 안 건드려서 APK 업그레이드가 콘텐츠를 못 실었다.)
        if (activeIndex.exists() && activeIndex.length() > 0) {
            String bundled = readAssetVersion();
            String active = readVersion(activeIndex);
            if (compareVersions(bundled, active) <= 0) return;
            if (backupIndex.exists()) backupIndex.delete();
            activeIndex.renameTo(backupIndex);
        }
        try (InputStream in = getAssets().open("index.html");
             FileOutputStream out = new FileOutputStream(activeIndex)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            throw new RuntimeException("기본 게임 파일을 설치하지 못했습니다.", e);
        }
    }

    private String readAssetVersion() {
        try (InputStream in = getAssets().open("index.html")) {
            return versionInHead(in);
        } catch (Exception e) { return "0"; }
    }

    private void toast(final String msg) {
        main.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
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

    // 파일 앞쪽 1MB 안에서 TRAMP_LINE_CONTENT_VERSION 을 찾는다.
    private String versionInHead(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (out.size() < HEAD_BYTES && (n = in.read(buf)) > 0) out.write(buf, 0, n);
        Matcher mt = CONTENT_VERSION.matcher(out.toString(StandardCharsets.UTF_8.name()));
        return mt.find() ? normalizeVersion(mt.group(1)) : "0";
    }

    private static final int HEAD_BYTES = 1024 * 1024;

    private void checkForContentUpdate() {
        String r = checkBranchUpdate();
        if (r == null) checkReleaseUpdate();       // 브랜치를 못 봤을 때만 릴리스로
        else if (!r.isEmpty()) toast(r);
    }

    // 성공(최신이든 갱신했든)이면 알릴 말, 확인 자체가 안 됐으면 null.
    private String checkBranchUpdate() {
        HttpURLConnection c = null;
        try {
            String bust = "?t=" + (System.currentTimeMillis() / 60000L);
            c = (HttpURLConnection) new URL(BRANCH_RAW + bust).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Range", "bytes=0-" + (HEAD_BYTES - 1));
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return null;
            String latest;
            try (InputStream in = c.getInputStream()) { latest = versionInHead(in); }
            c.disconnect(); c = null;
            if ("0".equals(latest)) return null;

            String current = readVersion(activeIndex);
            if (compareVersions(latest, current) <= 0) return "콘텐츠 " + current + " · 최신입니다";

            File candidate = new File(webDir, "index.new.html");
            download(BRANCH_RAW + bust, candidate);
            String got = readVersion(candidate);
            if (compareVersions(got, latest) < 0 || candidate.length() < 100000) {
                candidate.delete();
                return null;                       // 받다 끊겼거나 이상하면 릴리스로 넘긴다
            }
            if (!installCandidate(candidate)) return null;
            final String shown = got;
            main.post(() -> {
                Toast.makeText(MainActivity.this, "콘텐츠 " + shown + " 업데이트 완료", Toast.LENGTH_LONG).show();
                loadGame();
            });
            return "";
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private boolean installCandidate(File candidate) {
        if (backupIndex.exists()) backupIndex.delete();
        if (activeIndex.exists() && !activeIndex.renameTo(backupIndex)) {
            candidate.delete();
            return false;
        }
        if (!candidate.renameTo(activeIndex)) {
            if (backupIndex.exists()) backupIndex.renameTo(activeIndex);
            return false;
        }
        return true;
    }

    private void checkReleaseUpdate() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RELEASE_API).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", UA);

            if (conn.getResponseCode() != 200) { toast("갱신 확인 실패 · GitHub 응답 " + conn.getResponseCode()); return; }
            String json = readAll(conn.getInputStream(), 2 * 1024 * 1024);
            JSONObject release = new JSONObject(json);

            String latest = normalizeVersion(release.optString("tag_name", "0"));
            String current = readVersion(activeIndex);
            if (compareVersions(latest, current) <= 0) { toast("콘텐츠 " + current + " · 최신입니다"); return; }

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
            if (assetUrl == null) { toast("릴리스 " + latest + " 에 " + wantedPrefix + "*.zip 이 없습니다"); return; }

            File zip = new File(getCacheDir(), "tramp_update.zip");
            download(assetUrl, zip);

            File candidate = new File(webDir, "index.new.html");
            if (!extractIndex(zip, candidate)) {
                zip.delete();
                candidate.delete();
                toast("갱신 실패 · zip 안에 index.html 이 없습니다");
                return;
            }

            String candidateVersion = readVersion(candidate);
            if (compareVersions(candidateVersion, latest) < 0) {
                candidate.delete();
                zip.delete();
                toast("갱신 실패 · zip 의 판본 " + candidateVersion + " 이 태그 " + latest + " 보다 낮습니다");
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
        } catch (Exception e) {
            // 갱신 실패가 게임을 막아서는 안 된다. 다만 이유는 말해 준다.
            toast("갱신 확인 실패 · " + e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void download(String url, File outFile) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
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

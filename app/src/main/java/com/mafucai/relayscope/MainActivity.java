package com.mafucai.relayscope;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int PICK_PRICE_IMAGE = 42;
    private WebView webView;
    private SiteStore siteStore;
    private PriceStore priceStore;
    private final RelayTester relayTester = new RelayTester();
    private final Map<String, RelayTester.TestResult> results = new HashMap<>();
    private final TextRecognizer textRecognizer = TextRecognition.getClient(
            new ChineseTextRecognizerOptions.Builder().build());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        siteStore = new SiteStore(this);
        priceStore = new PriceStore(this);
        webView = new WebView(this);
        configureWebView(webView);
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void configureWebView(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        view.setWebViewClient(new WebViewClient());
        view.setWebChromeClient(new WebChromeClient());
        view.addJavascriptInterface(new NativeBridge(), "AndroidRelay");
        view.setBackgroundColor(0xFF0E151B);
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void evaluate(String script) {
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(script, null); });
    }

    private String js(String value) {
        return JSONObject.quote(value == null ? "" : value);
    }

    private final class NativeBridge {
        @JavascriptInterface public void addSite(String name, String baseUrl, String apiKey, String priceUrl) {
            if (baseUrl == null || baseUrl.trim().isEmpty()) { toast("接口地址不能为空"); return; }
            boolean added = siteStore.addIfAbsent(new RelaySite(name, baseUrl, apiKey, priceUrl));
            if (!added) { toast("这个接口地址已经添加过了"); return; }
            pushState();
            toast("站点已安全保存，点击开始全站测试");
        }

        @JavascriptInterface public void syncState() {
            pushState();
        }

        private void pushState() {
            JSONArray sites = new JSONArray();
            for (RelaySite site : siteStore.load()) {
                JSONObject item = new JSONObject();
                try { item.put("name", site.name); item.put("baseUrl", site.baseUrl); sites.put(item); }
                catch (Exception ignored) { }
            }
            JSONArray resultArray = new JSONArray();
            for (RelayTester.TestResult result : results.values()) {
                JSONObject item = new JSONObject();
                try {
                    item.put("site", result.siteName);
                    item.put("status", result.status);
                    item.put("detail", result.detail);
                    item.put("ttfb", result.ttfbMs);
                    item.put("models", result.models.size());
                    JSONObject modelResults = new JSONObject();
                    for (Map.Entry<String, String> entry : result.modelResults.entrySet()) modelResults.put(entry.getKey(), entry.getValue());
                    item.put("modelResults", modelResults);
                    resultArray.put(item);
                } catch (Exception ignored) { }
            }
            evaluate("window.onNativeState && window.onNativeState(" + sites.toString() + "," + priceStore.exportJson().toString() + "," + resultArray.toString() + ")");
        }

        @JavascriptInterface public void testAll() {
            List<RelaySite> sites = siteStore.load();
            if (sites.isEmpty()) { toast("还没有中转站，请先添加第一个站点"); return; }
            evaluate("window.onNativeTestStart && window.onNativeTestStart(" + sites.size() + ")");
            final int[] remaining = {sites.size()};
            for (RelaySite site : sites) {
                relayTester.testAsync(site, "gpt-5.6-terra", result -> {
                    String detail = result.detail == null ? result.status : result.detail;
                    results.put(result.siteName, result);
                    evaluate("window.onNativeSiteResult && window.onNativeSiteResult(" + js(result.siteName) + "," + js(result.status) + "," + js(detail) + "," + result.ttfbMs + "," + result.models.size() + ")");
                    if (--remaining[0] == 0) {
                        evaluate("window.onNativeTestDone && window.onNativeTestDone()");
                        pushState();
                        toast("真实测试完成");
                    }
                });
            }
        }

        @JavascriptInterface public void pickPriceImage() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PICK_PRICE_IMAGE);
        }

        @JavascriptInterface public void startInspection(String minutesText) {
            try {
                float minutes = Float.parseFloat(minutesText);
                if (!(minutes > 0) || Float.isInfinite(minutes)) throw new NumberFormatException();
                getSharedPreferences("relayscope", MODE_PRIVATE).edit().putFloat("interval", minutes).apply();
                Intent intent = new Intent(MainActivity.this, InspectionService.class).putExtra("interval_minutes", minutes);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
                toast("后台巡检已开启");
            } catch (NumberFormatException e) { toast("请输入大于 0 的巡检间隔"); }
        }

        @JavascriptInterface public void stopInspection() {
            stopService(new Intent(MainActivity.this, InspectionService.class));
            toast("后台巡检已关闭");
        }

        @JavascriptInterface public void fetchPrices() { toast("自动价格拉取已交给原生适配器"); }
        @JavascriptInterface public void saveManualPrice(String model, String input, String output, String multiplier) {
            try {
                double in = Double.parseDouble(input), out = Double.parseDouble(output), factor = Double.parseDouble(multiplier);
                if (model == null || model.trim().isEmpty() || in < 0 || out < 0 || factor <= 0) throw new NumberFormatException();
                priceStore.upsert(new PriceStore.Price(model.trim(), in, out, factor, "CNY", "手动"));
                pushState();
                toast("价格和余额倍率已保存");
            } catch (NumberFormatException e) { toast("请填写有效价格与倍率"); }
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PRICE_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            textRecognizer.process(image)
                    .addOnSuccessListener(this::handleOcr)
                    .addOnFailureListener(e -> toast("OCR失败，请补传清晰的局部截图"));
        } catch (Exception e) { toast("图片无法读取，请重新选择"); }
    }

    private void handleOcr(Text text) {
        PriceOcrParser.Candidate candidate = PriceOcrParser.parse(text.getText());
        String missing = candidate.missing();
        if (!missing.isEmpty()) {
            toast("无法确认：" + missing + "，请补传局部截图");
            evaluate("window.onNativeOcrIncomplete && window.onNativeOcrIncomplete(" + js(missing) + ")");
            return;
        }
        evaluate("window.onNativeOcrCandidate && window.onNativeOcrCandidate(" + js(candidate.model) + "," + js(candidate.input) + "," + js(candidate.output) + "," + js(candidate.multiplier) + ")");
    }

    @Override protected void onDestroy() {
        if (webView != null) webView.destroy();
        textRecognizer.close();
        super.onDestroy();
    }
}

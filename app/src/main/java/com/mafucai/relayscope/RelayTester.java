package com.mafucai.relayscope;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.net.SocketTimeoutException;

public final class RelayTester {
    public interface Callback { void onResult(TestResult result); }
    public interface ModelCallback { void onModel(String siteName, String model, String status, int completed, int total); }
    public static final class TestResult {
        public final String siteName, status, detail;
        public final long ttfbMs;
        public final List<String> models;
        public final Map<String, String> modelResults;
        public TestResult(String siteName, String status, String detail, long ttfbMs, List<String> models, Map<String, String> modelResults) {
            this.siteName = siteName; this.status = status; this.detail = detail; this.ttfbMs = ttfbMs; this.models = models; this.modelResults = modelResults;
        }
    }
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;
    private static final int MAX_RETRIES = 2;
    // 兜底探活：并发从 8 降到 2，避免 232 模型一次性打爆站点触发限流
    private static final int MODEL_WORKERS = 2;
    // 主路径：站点自带的性能统计（零模型调用、零额度消耗）
    // 路径统一由 RelaySite.perfMetricsUrl()/quotaUrl()/statusUrl() 提供
    // 请求间抖动，避免整齐脉冲被限流
    private static final long JITTER_MIN_MS = 100;
    private static final long JITTER_MAX_MS = 300;
    private static final long MODEL_BATCH_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final int MODEL_CONNECT_TIMEOUT = 5000;
    private static final int MODEL_READ_TIMEOUT = 15000;

    // 取消机制：pending 线程 + 活动 HTTP 连接集合 + 已停止标记
    private final List<Thread> pendingThreads = new CopyOnWriteArrayList<>();
    private final Set<HttpURLConnection> activeConnections = Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    public static final String CANCELLED_STATUS = "已停止";

    private boolean isCancelled() { return cancelled.get(); }
    public void cancelAll() {
        cancelled.set(true);
        for (HttpURLConnection c : activeConnections) { try { c.disconnect(); } catch (Exception ignored) { } }
        activeConnections.clear();
        for (Thread t : pendingThreads) { try { t.interrupt(); } catch (Exception ignored) { } }
        pendingThreads.clear();
    }
    /** 新开始一批测试时清空取消标记。 */
    public void reset() { cancelled.set(false); }

    public void testAsync(final RelaySite site, final String preferredModel, final Callback callback) {
        new Thread(() -> callback.onResult(test(site, preferredModel)), "relay-test").start();
    }

    /** UI 可中断版本：登记线程，可被 cancelAll() 掐断（巡检 health() 不受影响）。 */
    public Thread testAsyncCancelable(final RelaySite site, final String preferredModel, final Callback callback) {
        return testAsyncCancelable(site, preferredModel, callback, null);
    }

    /** 全量测试 + 逐模型完成回调：哪个模型先测完，UI 就先看到哪个。 */
    public Thread testAsyncCancelable(final RelaySite site, final String preferredModel, final Callback callback, final ModelCallback modelCallback) {
        Thread t = new Thread(() -> callback.onResult(test(site, preferredModel, modelCallback)), "relay-test-cancel");
        pendingThreads.add(t);
        t.start();
        return t;
    }

    /** Low-cost probe for background inspection: models endpoint plus one model only. */
    public void healthAsync(final RelaySite site, final Callback callback) {
        new Thread(() -> callback.onResult(health(site)), "relay-health").start();
    }

    public TestResult health(RelaySite site) {
        try {
            ModelsResponse response = withRetry(() -> fetchModels(site));
            if (response.models.isEmpty()) return new TestResult(site.name, "模型为空", "接口可连通，但没有可用模型", response.ttfbMs, response.models, new LinkedHashMap<>());
            String model = response.models.get(0);
            long streamMs = withRetry(() -> probeChat(site, model));
            Map<String, String> results = new LinkedHashMap<>();
            results.put(model, "可用 · " + streamMs + " ms");
            return new TestResult(site.name, "可用", "巡检首包 " + response.ttfbMs + " ms · 代表模型 " + streamMs + " ms", response.ttfbMs, response.models, results);
        } catch (TestException e) {
            return new TestResult(site.name, e.status, e.getMessage(), -1, new ArrayList<>(), new LinkedHashMap<>());
        } catch (Exception e) {
            return new TestResult(site.name, "网络错误", safeMessage(e), -1, new ArrayList<>(), new LinkedHashMap<>());
        }
    }

    private void emitModel(ModelCallback callback, String siteName, String model, String status, int completed, int total) {
        if (callback == null) return;
        try { callback.onModel(siteName, model, status, completed, total); } catch (Exception ignored) { }
    }

    public TestResult test(RelaySite site, String preferredModel) {
        return test(site, preferredModel, null);
    }

    private TestResult test(RelaySite site, String preferredModel, ModelCallback modelCallback) {
        final long batchDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MODEL_BATCH_TIMEOUT_MS);
        try {
            if (isCancelled()) return new TestResult(site.name, CANCELLED_STATUS, "已停止", -1, new ArrayList<>(), new LinkedHashMap<>());
            // 整批硬上限 10 分钟：模型列表也不重试，避免把整批拖长。
            ModelsResponse response = fetchModels(site);
            if (isCancelled()) return new TestResult(site.name, CANCELLED_STATUS, "已停止", -1, new ArrayList<>(), new LinkedHashMap<>());
            if (response.models.isEmpty()) return new TestResult(site.name, "模型为空", "接口可连通，但没有可用模型", response.ttfbMs, response.models, new LinkedHashMap<>());
            // 8 路并发 + 完成队列：按完成顺序取结果，不让慢模型挡住快模型。
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(MODEL_WORKERS);
            CompletionService<String> completion = new ExecutorCompletionService<>(pool);
            Map<Future<String>, String> futures = new LinkedHashMap<>();
            int submitted = 0;
            for (String model : response.models) {
                if (isCancelled()) break;
                // 兜底：模型间加 100-300ms 抖动，避免整齐脉冲被站点限流
                if (submitted > 0) jitterPause();
                submitted++;
                Future<String> future = completion.submit(() -> {
                    try {
                        // 逐模型不重试：失败/超时立刻出结果，避免 232 模型被重试拖垮。
                        long streamMs = probeChat(site, model);
                        return "可用 · " + streamMs + " ms";
                    } catch (SocketTimeoutException e) {
                        return "超时";
                    } catch (TestException e) {
                        return e.status;
                    } catch (Exception e) {
                        return "网络错误";
                    }
                });
                futures.put(future, model);
            }
            Map<String, String> modelResults = new LinkedHashMap<>();
            int available = 0;
            boolean rateLimited = false;
            java.util.Set<String> done = new java.util.HashSet<>();
            while (done.size() < futures.size() && !isCancelled()) {
                long remaining = batchDeadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<String> future = completion.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(500)), TimeUnit.NANOSECONDS);
                if (future == null) continue;
                String model = futures.get(future);
                String value;
                try { value = future.get(); }
                catch (Exception e) { value = "网络错误"; }
                done.add(model);
                modelResults.put(model, value);
                if (value.startsWith("可用")) available++;
                emitModel(modelCallback, site.name, model, value, done.size(), futures.size());
                // 遇 429 立即停批：不再打剩余模型，避免把站点限流推得更深
                if ("限流".equals(value)) { rateLimited = true; break; }
            }
            if (rateLimited) {
                // 取消尚未完成的探测，剩余模型统一标记为「已跳过（限流）」
                for (Map.Entry<Future<String>, String> entry : futures.entrySet()) {
                    if (done.contains(entry.getValue())) continue;
                    entry.getKey().cancel(true);
                    done.add(entry.getValue());
                    modelResults.put(entry.getValue(), "已跳过（限流）");
                    emitModel(modelCallback, site.name, entry.getValue(), "已跳过（限流）", done.size(), futures.size());
                }
            }
            if (done.size() < futures.size() && !isCancelled()) {
                // 整批超时：未完成项标记超时并取消，不再无限等。
                for (Map.Entry<Future<String>, String> entry : futures.entrySet()) {
                    if (done.contains(entry.getValue())) continue;
                    entry.getKey().cancel(true);
                    done.add(entry.getValue());
                    modelResults.put(entry.getValue(), "超时");
                    emitModel(modelCallback, site.name, entry.getValue(), "超时", done.size(), futures.size());
                }
            }
            pool.shutdownNow();
            String status = isCancelled() ? CANCELLED_STATUS : available == response.models.size() ? "可用" : available == 0 ? "不可用" : "部分可用";
            String detail = isCancelled() ? "已停止" : "首包 " + response.ttfbMs + " ms · 流式模型 " + available + "/" + response.models.size();
            return new TestResult(site.name, status, detail, response.ttfbMs, response.models, modelResults);
        } catch (SocketTimeoutException e) {
            return new TestResult(site.name, "超时", "模型列表请求超时（单请求上限 20 秒）", -1, new ArrayList<>(), new LinkedHashMap<>());
        } catch (TestException e) {
            return new TestResult(site.name, e.status, e.getMessage(), -1, new ArrayList<>(), new LinkedHashMap<>());
        } catch (Exception e) {
            return new TestResult(site.name, "网络错误", safeMessage(e), -1, new ArrayList<>(), new LinkedHashMap<>());
        }
    }

    /** 单模型快速测试：不拉模型列表，只测指定模型一发。 */
    public TestResult testSingleModel(RelaySite site, String model) {
        try {
            if (isCancelled()) return new TestResult(site.name, CANCELLED_STATUS, "已停止", -1, new ArrayList<>(model.isEmpty()?java.util.Collections.emptyList():java.util.Collections.singletonList(model)), new LinkedHashMap<>());
            long streamMs = withRetry(() -> probeChat(site, model));
            Map<String, String> modelResults = new LinkedHashMap<>();
            modelResults.put(model, "可用 · " + streamMs + " ms");
            return new TestResult(site.name, "可用", "单模型 " + model + " · " + streamMs + " ms", -1, new ArrayList<>(java.util.Collections.singletonList(model)), modelResults);
        } catch (TestException e) {
            Map<String, String> mr = new LinkedHashMap<>();
            mr.put(model, e.status);
            return new TestResult(site.name, e.status, e.getMessage() + "（模型 " + model + "）", -1, new ArrayList<>(java.util.Collections.singletonList(model)), mr);
        } catch (Exception e) {
            Map<String, String> mr = new LinkedHashMap<>();
            mr.put(model, "网络错误");
            return new TestResult(site.name, "网络错误", safeMessage(e), -1, new ArrayList<>(java.util.Collections.singletonList(model)), mr);
        }
    }

    /** 站点自带性能统计的一条记录。 */
    public static final class PerfMetric {
        public final String model, group, provider;
        public final Double successRate, latencyMs, tps;
        public final Long requests, success, failure;
        public final String status;
        PerfMetric(String model, String group, String provider, Double successRate, Double latencyMs, Double tps, Long requests, Long success, Long failure, String status) {
            this.model = model; this.group = group; this.provider = provider;
            this.successRate = successRate; this.latencyMs = latencyMs; this.tps = tps;
            this.requests = requests; this.success = success; this.failure = failure; this.status = status;
        }
    }

    /**
     * 主路径：读取站点自带的性能统计（NewAPI /api/perf-metrics/summary）。
     * 这是站点自己统计的真实流量数据，零模型调用、零额度消耗、不会触发限流。
     */
    public java.util.List<PerfMetric> fetchPerfMetrics(RelaySite site) throws Exception {
        String url = site.perfMetricsUrl();
        HttpURLConnection connection = open(url, site.apiKey, "GET");
        try {
            int code = connection.getResponseCode();
            String body = readBody(connection, code);
            if (code < 200 || code >= 300) throw classify(code, body);
            if (looksLikeHtml(connection, body)) throw new TestException("网关返回网页", "统计接口返回了 HTML 而非 JSON：可能被网关/人机验证拦截");
            JSONArray data;
            try { data = new JSONObject(body).optJSONArray("data"); }
            catch (JSONException je) { throw new TestException("响应不是 JSON", "统计接口返回了网页而非 JSON"); }
            java.util.List<PerfMetric> out = new java.util.ArrayList<>();
            if (data == null) return out;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String model = item.optString("model", item.optString("model_name", item.optString("name", ""))).trim();
                if (model.isEmpty()) continue;
                out.add(new PerfMetric(
                        model,
                        item.optString("group", item.optString("group_name", item.optString("channel", "default"))),
                        item.optString("provider", item.optString("supplier", "")),
                        optDoubleOrNull(item, "success_rate", "successRate"),
                        optDoubleOrNull(item, "latency", "latency_ms", "avg_latency"),
                        optDoubleOrNull(item, "tps", "tokens_per_second"),
                        optLongOrNull(item, "requests", "request_count", "total"),
                        optLongOrNull(item, "success", "success_count"),
                        optLongOrNull(item, "failure", "failure_count", "failed")));
            }
            return out;
        } finally { activeConnections.remove(connection); connection.disconnect(); }
    }

    /** 额度：NewAPI /api/user/self 的 quota / used_quota（内部单位，需按 quota_per_unit 换算）。 */
    public static final class QuotaResponse {
        public final double remaining, used, quotaPerUnit, exchangeRate;
        public final String currencySymbol;
        QuotaResponse(double remaining, double used, double quotaPerUnit, double exchangeRate, String currencySymbol) {
            this.remaining = remaining; this.used = used; this.quotaPerUnit = quotaPerUnit;
            this.exchangeRate = exchangeRate; this.currencySymbol = currencySymbol;
        }
    }

    /** 读取账号额度；quota_per_unit 等换算参数取自 /api/status（读不到时用 NewAPI 默认值）。 */
    public QuotaResponse fetchQuota(RelaySite site) throws Exception {
        HttpURLConnection connection = open(site.quotaUrl(), site.apiKey, "GET");
        double quota, used;
        try {
            int code = connection.getResponseCode();
            String body = readBody(connection, code);
            if (code < 200 || code >= 300) throw classify(code, body);
            if (looksLikeHtml(connection, body)) throw new TestException("网关返回网页", "额度接口返回了 HTML 而非 JSON");
            JSONObject root;
            try { root = new JSONObject(body); }
            catch (JSONException je) { throw new TestException("响应不是 JSON", "额度接口返回了网页而非 JSON"); }
            JSONObject data = root.optJSONObject("data");
            if (data == null || !data.has("quota")) {
                // 拿不到就留空，绝不写 0 冒充
                throw new TestException("无额度字段", "响应中没有 quota 字段");
            }
            quota = data.optDouble("quota", -1);
            used = data.optDouble("used_quota", 0);
            if (quota < 0) throw new TestException("无额度字段", "quota 不是有效数值");
        } finally { activeConnections.remove(connection); connection.disconnect(); }

        // 换算参数：/api/status（失败则用 NewAPI 默认 500000 / 1 / $）
        double perUnit = 500000, rate = 1;
        String symbol = "$";
        try {
            HttpURLConnection statusConn = open(site.statusUrl(), site.apiKey, "GET");
            try {
                int sc = statusConn.getResponseCode();
                String sb = readBody(statusConn, sc);
                if (sc >= 200 && sc < 300) {
                    JSONObject sroot = new JSONObject(sb);
                    JSONObject sdata = sroot.optJSONObject("data");
                    if (sdata == null) sdata = sroot;
                    if (sdata.has("quota_per_unit")) { double v = sdata.optDouble("quota_per_unit", 0); if (v > 0) perUnit = v; }
                    if (sdata.has("custom_currency_exchange_rate")) { double v = sdata.optDouble("custom_currency_exchange_rate", 0); if (v > 0) rate = v; }
                    String sym = sdata.optString("custom_currency_symbol", "").trim();
                    if (!sym.isEmpty()) symbol = sym;
                    if (perUnit <= 0) perUnit = 500000;
                }
            } finally { activeConnections.remove(statusConn); statusConn.disconnect(); }
        } catch (Exception ignored) { /* 换算参数读不到就用默认，不阻断额度展示 */ }

        return new QuotaResponse(quota / perUnit * rate, used / perUnit * rate, perUnit, rate, symbol);
    }

    private static Double optDoubleOrNull(JSONObject o, String... keys) {
        for (String k : keys) if (o.has(k) && !o.isNull(k)) { try { return o.getDouble(k); } catch (Exception ignored) { } }
        return null;
    }

    private static Long optLongOrNull(JSONObject o, String... keys) {
        for (String k : keys) if (o.has(k) && !o.isNull(k)) { try { return o.getLong(k); } catch (Exception ignored) { } }
        return null;
    }

    /** One API 式判断：非 JSON Content-Type 直接判定网关返回网页，不做猜测解析。 */
    private static boolean looksLikeHtml(HttpURLConnection c, String body) {
        String ct = c.getContentType();
        if (ct != null) { String t = ct.toLowerCase(); if (t.contains("text/html") || t.contains("text/plain") && body.trim().startsWith("<")) return true; }
        String b = body.trim().toLowerCase();
        return b.startsWith("<!doctype") || b.startsWith("<html");
    }

    public ModelsResponse fetchModels(RelaySite site) throws Exception {
        long start = System.nanoTime(); HttpURLConnection connection = open(site.modelsUrl(), site.apiKey, "GET");
        try {
            int code = connection.getResponseCode(); String body = readBody(connection, code);
            if (code < 200 || code >= 300) throw classify(code, body);
            if (looksLikeHtml(connection, body)) throw new TestException("网关返回网页", "接口返回了 HTML 而非 JSON：地址可能填错（缺 /v1）、被网关/人机验证拦截，或站点宕机");
            JSONArray data;
            try { data = new JSONObject(body).optJSONArray("data"); }
            catch (JSONException je) { throw new TestException("响应不是 JSON", "接口返回了网页而非 JSON（可能地址填错、被网关/人机验证拦截），请检查模型接口地址"); }
            List<String> models = new ArrayList<>();
            if (data != null) for (int i=0;i<data.length();i++) { String id=data.optJSONObject(i).optString("id"); if (!id.isEmpty()) models.add(id); }
            return new ModelsResponse(models, (System.nanoTime()-start)/1_000_000);
        } finally { activeConnections.remove(connection); connection.disconnect(); }
    }

    private long probeChat(RelaySite site, String model) throws Exception {
        JSONObject payload = new JSONObject(); payload.put("model", model); payload.put("stream", true);
        JSONArray messages = new JSONArray(); messages.put(new JSONObject().put("role", "user").put("content", "Reply with one word: OK")); payload.put("messages", messages);
        long start = System.nanoTime(); HttpURLConnection connection = openModel(site.chatUrl(), site.apiKey, "POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) { output.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) { String body = readBody(connection, code); if (looksLikeHtml(connection, body)) throw new TestException("网关返回网页", "对话接口返回了 HTML 而非 JSON：可能被网关/人机验证拦截"); TestException classified = classify(code, body); if ("限流".equals(classified.status)) { long wait = retryAfterSeconds(connection); classified = new TestException("限流", wait > 0 ? ("HTTP 429：站点要求 " + wait + " 秒后重试") : "HTTP 429：请求过于频繁（站点已限流）"); } throw classified; }
        try (InputStream input = connection.getInputStream()) { byte[] buffer = new byte[512]; int count=input.read(buffer); if(count<0) throw new TestException("流式空响应", "服务端没有返回 token"); return (System.nanoTime()-start)/1_000_000; }
        finally { activeConnections.remove(connection); connection.disconnect(); }
    }

    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private HttpURLConnection open(String address, String key, String method) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection(); c.setRequestMethod(method); c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(READ_TIMEOUT); c.setRequestProperty("Accept","application/json"); c.setRequestProperty("User-Agent", BROWSER_UA); if(key!=null&&!key.isEmpty())c.setRequestProperty("Authorization","Bearer "+key); activeConnections.add(c); return c;
    }
    private HttpURLConnection openModel(String address, String key, String method) throws Exception {
        HttpURLConnection c = open(address, key, method);
        c.setConnectTimeout(MODEL_CONNECT_TIMEOUT);
        c.setReadTimeout(MODEL_READ_TIMEOUT);
        return c;
    }
    private String readBody(HttpURLConnection c,int code)throws IOException{InputStream in=code>=400?c.getErrorStream():c.getInputStream();if(in==null)return "";try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null&&b.length()<12000)b.append(line);return b.toString();}}
    /** 429 时读取 Retry-After（秒），无则返回 -1。 */
    private long retryAfterSeconds(HttpURLConnection c) {
        try {
            String value = c.getHeaderField("Retry-After");
            if (value == null || value.trim().isEmpty()) return -1;
            return (long) Math.ceil(Double.parseDouble(value.trim()));
        } catch (Exception e) { return -1; }
    }

    /** 批量探活前统一退避，避免整齐脉冲触发站点限流。 */
    private void jitterPause() {
        long span = Math.max(0, JITTER_MAX_MS - JITTER_MIN_MS);
        long wait = JITTER_MIN_MS + (span > 0 ? (long) (Math.random() * span) : 0);
        try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private TestException classify(int code,String body){
        if(code==401||code==403){
            String detail="HTTP "+code+"：密钥无效或无权限";
            // One API 式：错误 body 是 JSON 时透传站点原始 message，让用户看到真实原因
            try { JSONObject o=new JSONObject(body); String m=o.optString("message","").trim(); if(!m.isEmpty()) detail="HTTP "+code+"：站点返回——"+m; }
            catch (JSONException ignored) { }
            return new TestException("认证失败", detail);
        }
        if(code==404)return new TestException("接口/模型不存在", "HTTP 404：请检查地址或模型");
        if(code==429)return new TestException("限流", "HTTP 429：请求过于频繁（站点已限流）");
        if(code>=500)return new TestException("服务端错误", "HTTP "+code);
        return new TestException("请求失败", "HTTP "+code);
    }
    private <T> T withRetry(Callable<T> call)throws Exception{Exception last=null;for(int attempt=0;attempt<=MAX_RETRIES;attempt++){try{return call.call();}catch(TestException e){if(e.status.equals("认证失败")||e.status.equals("接口/模型不存在"))throw e;last=e;}catch(Exception e){last=e;}if(attempt<MAX_RETRIES)try{Thread.sleep(250L*(1L<<attempt));}catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}}throw last;}
    private String safeMessage(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    public static final class ModelsResponse {final List<String> models;final long ttfbMs;ModelsResponse(List<String> m,long t){models=m;ttfbMs=t;}}
    private static final class TestException extends Exception {final String status;TestException(String s,String m){super(m);status=s;}}
}

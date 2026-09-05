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

public final class RelayTester {
    public interface Callback { void onResult(TestResult result); }
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

    public void testAsync(final RelaySite site, final String preferredModel, final Callback callback) {
        new Thread(() -> callback.onResult(test(site, preferredModel)), "relay-test").start();
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

    public TestResult test(RelaySite site, String preferredModel) {
        try {
            ModelsResponse response = withRetry(() -> fetchModels(site));
            if (response.models.isEmpty()) return new TestResult(site.name, "模型为空", "接口可连通，但没有可用模型", response.ttfbMs, response.models, new LinkedHashMap<>());
            // 模型级并发：线程池 4 路，总耗时≈最慢单模型而不是全部之和
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            Map<String, java.util.concurrent.Future<String>> futures = new LinkedHashMap<>();
            for (String model : response.models) {
                futures.put(model, pool.submit(() -> {
                    try {
                        long streamMs = withRetry(() -> probeChat(site, model));
                        return "可用 · " + streamMs + " ms";
                    } catch (TestException e) {
                        return e.status;
                    } catch (Exception e) {
                        return "网络错误";
                    }
                }));
            }
            Map<String, String> modelResults = new LinkedHashMap<>();
            int available = 0;
            for (Map.Entry<String, java.util.concurrent.Future<String>> en : futures.entrySet()) {
                try { String v = en.getValue().get(); modelResults.put(en.getKey(), v); if (v.startsWith("可用")) available++; }
                catch (Exception e) { modelResults.put(en.getKey(), "网络错误"); }
            }
            pool.shutdown();
            String status = available == response.models.size() ? "可用" : available == 0 ? "不可用" : "部分可用";
            String detail = "首包 " + response.ttfbMs + " ms · 流式模型 " + available + "/" + response.models.size();
            return new TestResult(site.name, status, detail, response.ttfbMs, response.models, modelResults);
        } catch (TestException e) {
            return new TestResult(site.name, e.status, e.getMessage(), -1, new ArrayList<>(), new LinkedHashMap<>());
        } catch (Exception e) {
            return new TestResult(site.name, "网络错误", safeMessage(e), -1, new ArrayList<>(), new LinkedHashMap<>());
        }
    }

    /** One API 式判断：非 JSON Content-Type 直接判定网关返回网页，不做猜测解析。 */
    private static boolean looksLikeHtml(HttpURLConnection c, String body) {
        String ct = c.getContentType();
        if (ct != null) { String t = ct.toLowerCase(); if (t.contains("text/html") || t.contains("text/plain") && body.trim().startsWith("<")) return true; }
        String b = body.trim().toLowerCase();
        return b.startsWith("<!doctype") || b.startsWith("<html");
    }

    private ModelsResponse fetchModels(RelaySite site) throws Exception {
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
        } finally { connection.disconnect(); }
    }

    private long probeChat(RelaySite site, String model) throws Exception {
        JSONObject payload = new JSONObject(); payload.put("model", model); payload.put("stream", true);
        JSONArray messages = new JSONArray(); messages.put(new JSONObject().put("role", "user").put("content", "Reply with one word: OK")); payload.put("messages", messages);
        long start = System.nanoTime(); HttpURLConnection connection = open(site.chatUrl(), site.apiKey, "POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) { output.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) { String body = readBody(connection, code); if (looksLikeHtml(connection, body)) throw new TestException("网关返回网页", "对话接口返回了 HTML 而非 JSON：可能被网关/人机验证拦截"); throw classify(code, body); }
        try (InputStream input = connection.getInputStream()) { byte[] buffer = new byte[512]; int count=input.read(buffer); if(count<0) throw new TestException("流式空响应", "服务端没有返回 token"); return (System.nanoTime()-start)/1_000_000; }
        finally { connection.disconnect(); }
    }

    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private HttpURLConnection open(String address, String key, String method) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection(); c.setRequestMethod(method); c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(READ_TIMEOUT); c.setRequestProperty("Accept","application/json"); c.setRequestProperty("User-Agent", BROWSER_UA); if(key!=null&&!key.isEmpty())c.setRequestProperty("Authorization","Bearer "+key); return c;
    }
    private String readBody(HttpURLConnection c,int code)throws IOException{InputStream in=code>=400?c.getErrorStream():c.getInputStream();if(in==null)return "";try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null&&b.length()<12000)b.append(line);return b.toString();}}
    private TestException classify(int code,String body){
        if(code==401||code==403){
            String detail="HTTP "+code+"：密钥无效或无权限";
            // One API 式：错误 body 是 JSON 时透传站点原始 message，让用户看到真实原因
            try { JSONObject o=new JSONObject(body); String m=o.optString("message","").trim(); if(!m.isEmpty()) detail="HTTP "+code+"：站点返回——"+m; }
            catch (JSONException ignored) { }
            return new TestException("认证失败", detail);
        }
        if(code==404)return new TestException("接口/模型不存在", "HTTP 404：请检查地址或模型");
        if(code==429)return new TestException("限流", "HTTP 429：请求过于频繁");
        if(code>=500)return new TestException("服务端错误", "HTTP "+code);
        return new TestException("请求失败", "HTTP "+code);
    }
    private <T> T withRetry(Callable<T> call)throws Exception{Exception last=null;for(int attempt=0;attempt<=MAX_RETRIES;attempt++){try{return call.call();}catch(TestException e){if(e.status.equals("认证失败")||e.status.equals("接口/模型不存在"))throw e;last=e;}catch(Exception e){last=e;}if(attempt<MAX_RETRIES)try{Thread.sleep(250L*(1L<<attempt));}catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}}throw last;}
    private String safeMessage(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    private static final class ModelsResponse {final List<String> models;final long ttfbMs;ModelsResponse(List<String> m,long t){models=m;ttfbMs=t;}}
    private static final class TestException extends Exception {final String status;TestException(String s,String m){super(m);status=s;}}
}

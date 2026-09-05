package com.mafucai.relayscope;

import org.json.JSONException;
import org.json.JSONObject;

public final class RelaySite {
    public final String name;
    public final String baseUrl;
    public final String apiKey;
    public final String priceUrl;

    public RelaySite(String name, String baseUrl, String apiKey) {
        this(name, baseUrl, apiKey, "");
    }

    public RelaySite(String name, String baseUrl, String apiKey, String priceUrl) {
        this.name = clean(name, "未命名站点");
        this.baseUrl = clean(baseUrl, "").replaceAll("/+$", "");
        this.apiKey = clean(apiKey, "");
        this.priceUrl = clean(priceUrl, "");
    }

    private static String clean(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** One API 哲学：地址用户填什么就用什么，不猜测拼接。 */
    public String modelsUrl() {
        return joinUrl("/models");
    }

    public String chatUrl() {
        return joinUrl("/chat/completions");
    }

    private String joinUrl(String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    /** 地址是否以 /v1 结尾，用于结果页提示（不再自动改写地址）。 */
    public boolean looksLikeMissingV1() {
        return !baseUrl.endsWith("/v1");
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("name", name);
        object.put("base_url", baseUrl);
        object.put("api_key", apiKey);
        object.put("price_url", priceUrl);
        return object;
    }

    public static RelaySite fromJson(JSONObject object) {
        return new RelaySite(object.optString("name"), object.optString("base_url"), object.optString("api_key"), object.optString("price_url"));
    }
}

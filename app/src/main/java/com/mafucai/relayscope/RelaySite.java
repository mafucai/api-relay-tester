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

    public String modelsUrl() {
        return baseUrl.endsWith("/v1") ? baseUrl + "/models" : baseUrl + "/v1/models";
    }

    public String chatUrl() {
        return baseUrl.endsWith("/v1") ? baseUrl + "/chat/completions" : baseUrl + "/v1/chat/completions";
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

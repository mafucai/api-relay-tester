package com.mafucai.relayscope;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 持久化测试结果：App 重启后仍保留上次全站/分组测试的模型矩阵与状态。 */
public final class TestResultStore {
    private static final String PREFS = "relayscope_results";
    private static final String KEY = "results";
    private final Context context;

    public TestResultStore(Context context) { this.context = context.getApplicationContext(); }

    public Map<String, RelayTester.TestResult> load() {
        Map<String, RelayTester.TestResult> out = new LinkedHashMap<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                RelayTester.TestResult r = fromJson(array.getJSONObject(i));
                if (r != null) out.put(r.siteName, r);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    public void saveAll(Map<String, RelayTester.TestResult> results) {
        JSONArray array = new JSONArray();
        try {
            for (RelayTester.TestResult r : results.values()) array.put(toJson(r));
        } catch (JSONException ignored) { }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply();
    }

    public void remove(String siteName) {
        Map<String, RelayTester.TestResult> all = load();
        if (all.remove(siteName) != null) saveAll(all);
    }

    private JSONObject toJson(RelayTester.TestResult r) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("site", r.siteName);
        o.put("status", r.status);
        o.put("detail", r.detail);
        o.put("ttfb", r.ttfbMs);
        JSONArray models = new JSONArray();
        for (String m : r.models) models.put(m);
        o.put("models", models);
        JSONObject mr = new JSONObject();
        for (Map.Entry<String, String> e : r.modelResults.entrySet()) mr.put(e.getKey(), e.getValue());
        o.put("modelResults", mr);
        return o;
    }

    private RelayTester.TestResult fromJson(JSONObject o) {
        try {
            List<String> models = new ArrayList<>();
            JSONArray mArr = o.optJSONArray("models");
            if (mArr != null) for (int i = 0; i < mArr.length(); i++) models.add(mArr.optString(i));
            Map<String, String> mr = new LinkedHashMap<>();
            JSONObject mrObj = o.optJSONObject("modelResults");
            if (mrObj != null) {
                java.util.Iterator<String> it = mrObj.keys();
                while (it.hasNext()) { String k = it.next(); mr.put(k, mrObj.optString(k)); }
            }
            String site = o.optString("site");
            if (site.isEmpty()) return null;
            return new RelayTester.TestResult(site, o.optString("status", "网络错误"), o.optString("detail"), o.optLong("ttfb", -1), models, mr);
        } catch (Exception e) { return null; }
    }
}
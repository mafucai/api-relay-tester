package com.mafucai.relayscope;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;

/** Stores URLs and encrypted API keys in app-private preferences. */
public final class SiteStore {
    private static final String PREFS = "relayscope_sites";
    private static final String KEY = "sites";
    private final Context context;
    private final SecretBox secretBox = new SecretBox();

    public SiteStore(Context context) { this.context = context.getApplicationContext(); }

    public List<RelaySite> load() {
        List<RelaySite> result = new ArrayList<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                RelaySite stored = RelaySite.fromJson(array.getJSONObject(i));
                result.add(new RelaySite(stored.name, stored.baseUrl, secretBox.decrypt(stored.apiKey), stored.priceUrl));
            }
            // 兼容旧版明文：成功读取后立即回写加密值。
            save(result);
        } catch (JSONException ignored) { /* 损坏数据退化为空列表 */ }
        return result;
    }

    public boolean addIfAbsent(RelaySite site) {
        List<RelaySite> sites = load();
        for (RelaySite existing : sites) {
            if (existing.baseUrl.equalsIgnoreCase(site.baseUrl)) return false;
        }
        sites.add(site);
        save(sites);
        return true;
    }

    /** 按名称删除站点。返回是否删除了至少一条。 */
    public boolean removeByName(String name) {
        List<RelaySite> sites = load();
        List<RelaySite> kept = new java.util.ArrayList<>();
        boolean changed = false;
        for (RelaySite s : sites) {
            if (s.name.equals(name)) { changed = true; continue; }
            kept.add(s);
        }
        if (changed) save(kept);
        return changed;
    }

    private void save(List<RelaySite> sites) {
        JSONArray array = new JSONArray();
        try {
            for (RelaySite site : sites) {
                RelaySite encrypted = new RelaySite(site.name, site.baseUrl, secretBox.encrypt(site.apiKey));
                array.put(encrypted.toJson());
            }
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply();
    }
}

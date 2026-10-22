package com.dsh.mobile.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 渠道仓库：JSON 存在 SharedPreferences 里。
 * 首次使用时会把老版本的单一 Key 配置迁移成一个渠道，不丢数据。
 */
public class ProviderStore {

    private static final String TAG = "ProviderStore";
    private static final String KEY_LIST = "providers_v2";
    private static final String KEY_ACTIVE = "active_provider_v2";

    private static ProviderStore instance;

    public static synchronized ProviderStore get(Context c) {
        if (instance == null) instance = new ProviderStore(c);
        return instance;
    }

    private final SharedPreferences sp;
    private ArrayList<Provider> cache;

    private ProviderStore(Context c) {
        this.sp = c.getApplicationContext().getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized List<Provider> all() {
        ensure();
        List<Provider> out = new ArrayList<>();
        for (Provider p : cache) out.add(p.copy());
        return out;
    }

    public synchronized int size() {
        ensure();
        return cache.size();
    }

    public synchronized Provider byId(String id) {
        ensure();
        for (Provider p : cache) {
            if (p.id.equals(id)) return p.copy();
        }
        return null;
    }

    public synchronized Provider active() {
        ensure();
        String id = sp.getString(KEY_ACTIVE, null);
        for (Provider p : cache) {
            if (p.id.equals(id)) return p.copy();
        }
        Provider first = cache.get(0).copy();
        sp.edit().putString(KEY_ACTIVE, first.id).apply();
        return first;
    }

    public synchronized String activeId() {
        return active().id;
    }

    public synchronized void setActive(String id) {
        ensure();
        for (Provider p : cache) {
            if (p.id.equals(id)) {
                sp.edit().putString(KEY_ACTIVE, id).apply();
                return;
            }
        }
    }

    public synchronized void save(Provider p) {
        ensure();
        if (p == null) return;
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(p.id)) {
                cache.set(i, p.copy());
                persist();
                return;
            }
        }
        cache.add(p.copy());
        if (sp.getString(KEY_ACTIVE, null) == null) {
            sp.edit().putString(KEY_ACTIVE, p.id).apply();
        }
        persist();
    }

    public synchronized void delete(String id) {
        ensure();
        if (cache.size() <= 1) return; // 至少留一个
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(id)) {
                cache.remove(i);
                break;
            }
        }
        if (id.equals(sp.getString(KEY_ACTIVE, null))) {
            sp.edit().putString(KEY_ACTIVE, cache.get(0).id).apply();
        }
        persist();
    }

    // ------------------------------------------------------------- internals

    private void ensure() {
        if (cache != null && !cache.isEmpty()) return;
        cache = new ArrayList<>();
        String raw = sp.getString(KEY_LIST, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) cache.add(Provider.fromJson(o));
                }
            } catch (Exception e) {
                Log.w(TAG, "bad providers json, will reseed", e);
                cache.clear();
            }
        }
        if (cache.isEmpty()) {
            cache.add(migrateLegacy());
            persist();
            if (sp.getString(KEY_ACTIVE, null) == null) {
                sp.edit().putString(KEY_ACTIVE, cache.get(0).id).apply();
            }
        }
    }

    /** 老版本只有 api_key / base_url / model 三个字段，迁移成第一个渠道 */
    private Provider migrateLegacy() {
        String oldKey = sp.getString("api_key", "");
        String oldBase = sp.getString("base_url", Config.DEFAULT_BASE_URL);
        String oldModel = sp.getString("model", Config.DEFAULT_MODEL);

        Provider p = new Provider();
        p.baseUrl = (oldBase == null || oldBase.trim().isEmpty()) ? Config.DEFAULT_BASE_URL : oldBase.trim();
        p.apiKey = oldKey == null ? "" : oldKey.trim();
        p.model = (oldModel == null || oldModel.trim().isEmpty()) ? Config.DEFAULT_MODEL : oldModel.trim();
        p.name = Presets.guessName(p.baseUrl);
        p.path = "/chat/completions";
        p.toolsEnabled = true;
        p.authStyle = Provider.AUTH_BEARER;
        return p;
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        for (Provider p : cache) arr.put(p.toJson());
        sp.edit().putString(KEY_LIST, arr.toString()).apply();
    }
}

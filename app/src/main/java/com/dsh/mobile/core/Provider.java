package com.dsh.mobile.core;

import org.json.JSONObject;

import java.util.UUID;

/**
 * 一个"渠道"= 一套 baseUrl + 路径 + 鉴权方式 + Key + 模型。
 * 各渠道的 Key 独立保存，切换渠道不会互相覆盖。
 */
public class Provider {

    public static final String AUTH_BEARER = "bearer";
    public static final String AUTH_API_KEY = "api-key";
    public static final String AUTH_NONE = "none";

    public String id = UUID.randomUUID().toString();
    public String name = "新渠道";
    public String baseUrl = "";
    /** 请求路径，默认 /chat/completions；也可以直接填完整 URL */
    public String path = "/chat/completions";
    public String apiKey = "";
    public String model = "";
    /** 该渠道/模型是否支持 function calling */
    public boolean toolsEnabled = true;
    /** 该渠道/模型是否支持图片输入（多模态） */
    public boolean vision;
    public String authStyle = AUTH_BEARER;
    /** 额外请求头，每行 "Key: Value"，可留空 */
    public String extraHeaders = "";

    public String chatUrl() {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String p = path == null ? "" : path.trim();
        if (p.startsWith("http://") || p.startsWith("https://")) return p;
        if (p.isEmpty()) p = "/chat/completions";
        if (!p.startsWith("/")) p = "/" + p;
        return base + p;
    }

    public String modelsUrl() {
        String chat = chatUrl();
        String suffix = "/chat/completions";
        if (chat.endsWith(suffix)) {
            return chat.substring(0, chat.length() - suffix.length()) + "/models";
        }
        int idx = chat.lastIndexOf('/');
        if (idx > 8) return chat.substring(0, idx) + "/models";
        return chat + "/models";
    }

    public boolean needsKey() {
        return !AUTH_NONE.equals(authStyle);
    }

    public boolean hasKey() {
        return !needsKey() || (apiKey != null && !apiKey.trim().isEmpty());
    }

    public boolean isUsable() {
        return baseUrl != null && !baseUrl.trim().isEmpty() && hasKey();
    }

    public String authHeaderName() {
        return AUTH_API_KEY.equals(authStyle) ? "api-key" : "Authorization";
    }

    public String authHeaderValue() {
        String k = apiKey == null ? "" : apiKey.trim();
        return AUTH_API_KEY.equals(authStyle) ? k : "Bearer " + k;
    }

    public String maskedKey() {
        String k = apiKey == null ? "" : apiKey.trim();
        if (k.isEmpty()) return needsKey() ? "未填写" : "无需鉴权";
        if (k.length() <= 8) return "****";
        return k.substring(0, 4) + "…" + k.substring(k.length() - 4);
    }

    public Provider copy() {
        Provider p = new Provider();
        p.id = id;
        p.name = name;
        p.baseUrl = baseUrl;
        p.path = path;
        p.apiKey = apiKey;
        p.model = model;
        p.toolsEnabled = toolsEnabled;
        p.vision = vision;
        p.authStyle = authStyle;
        p.extraHeaders = extraHeaders;
        return p;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("baseUrl", baseUrl);
            o.put("path", path);
            o.put("apiKey", apiKey);
            o.put("model", model);
            o.put("toolsEnabled", toolsEnabled);
            o.put("vision", vision);
            o.put("authStyle", authStyle);
            o.put("extraHeaders", extraHeaders);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Provider fromJson(JSONObject o) {
        Provider p = new Provider();
        p.id = o.optString("id", UUID.randomUUID().toString());
        p.name = o.optString("name", "渠道");
        p.baseUrl = o.optString("baseUrl", "");
        p.path = o.optString("path", "/chat/completions");
        p.apiKey = o.optString("apiKey", "");
        p.model = o.optString("model", "");
        p.toolsEnabled = o.optBoolean("toolsEnabled", true);
        p.vision = o.optBoolean("vision", false);
        p.authStyle = o.optString("authStyle", AUTH_BEARER);
        p.extraHeaders = o.optString("extraHeaders", "");
        return p;
    }
}

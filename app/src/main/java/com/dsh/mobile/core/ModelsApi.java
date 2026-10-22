package com.dsh.mobile.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 从渠道的 /models 拉取可用模型，同时兼任"测试连接"。
 */
public final class ModelsApi {

    public static List<String> fetch(Provider p) throws Exception {
        String url = p.modelsUrl();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "DSH-Mobile/1.1 (Android)");
        if (p.needsKey() && p.apiKey != null && !p.apiKey.trim().isEmpty()) {
            c.setRequestProperty(p.authHeaderName(), p.authHeaderValue());
        }
        String extra = p.extraHeaders == null ? "" : p.extraHeaders.trim();
        if (!extra.isEmpty()) {
            for (String line : extra.split("\n")) {
                int i = line.indexOf(':');
                if (i > 0) {
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim();
                    if (!k.isEmpty()) {
                        try {
                            c.setRequestProperty(k, v);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = readAll(in);
            if (code != 200) {
                throw new Exception("HTTP " + code + "：" + trim(body, 240));
            }
            JSONArray arr;
            try {
                arr = new JSONObject(body).optJSONArray("data");
            } catch (Exception e) {
                throw new Exception("返回的不是模型列表 JSON：" + trim(body, 160));
            }
            List<String> ids = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id", "");
                    if (!id.isEmpty()) ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                throw new Exception("接口返回 200，但没有模型列表。可能不是 OpenAI 兼容接口，或需要特别的路径。");
            }
            Collections.sort(ids);
            return ids;
        } finally {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > (1 << 20)) break;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String one = s.replace("\n", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}

package com.dsh.mobile.core;

import org.json.JSONObject;

/**
 * 一次工具调用。result / error / done 会随执行过程被填上，
 * 因此同一个对象既能描述"模型想调用什么"，也能描述"执行结果如何"。
 */
public class ToolCall {

    public String id = "";
    public String name = "";
    public String arguments = "";

    public boolean done;
    public boolean error;
    public String result = "";
    public long millis;

    public ToolCall() {
    }

    public ToolCall(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("arguments", arguments);
            o.put("done", done);
            o.put("error", error);
            o.put("result", result);
            o.put("millis", millis);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static ToolCall fromJson(JSONObject o) {
        ToolCall t = new ToolCall();
        t.id = o.optString("id", "");
        t.name = o.optString("name", "");
        t.arguments = o.optString("arguments", "");
        t.done = o.optBoolean("done", false);
        t.error = o.optBoolean("error", false);
        t.result = o.optString("result", "");
        t.millis = o.optLong("millis", 0);
        return t;
    }

    /** 给 UI 用的一行摘要，例如 shell: ls -la */
    public String summary() {
        String a = arguments == null ? "" : arguments.trim();
        if (a.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(a);
                if (o.has("command")) return o.optString("command");
                if (o.has("path")) return o.optString("path");
                if (o.has("url")) return o.optString("url");
            } catch (Exception ignored) {
            }
        }
        if (a.length() > 60) a = a.substring(0, 60) + "…";
        return a.replace("\n", " ");
    }
}

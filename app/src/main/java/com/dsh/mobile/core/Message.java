package com.dsh.mobile.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Message {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";
    public static final String ROLE_SYSTEM = "system";

    public String id = UUID.randomUUID().toString();
    public String role = ROLE_USER;
    public String content = "";
    /** deepseek-reasoner 的思考过程，仅用于展示，不回传给接口 */
    public String reasoning = "";
    public List<ToolCall> toolCalls = new ArrayList<>();

    /** role == tool 时使用 */
    public String toolCallId;
    public String toolName;
    public boolean error;

    /** UI 状态，不持久化 */
    public transient boolean streaming;
    public long ts = System.currentTimeMillis();

    public static Message user(String text) {
        Message m = new Message();
        m.role = ROLE_USER;
        m.content = text;
        return m;
    }

    public static Message assistant() {
        Message m = new Message();
        m.role = ROLE_ASSISTANT;
        return m;
    }

    public static Message tool(String callId, String name, String result, boolean error) {
        Message m = new Message();
        m.role = ROLE_TOOL;
        m.toolCallId = callId;
        m.toolName = name;
        m.content = result;
        m.error = error;
        return m;
    }

    public boolean isUser() {
        return ROLE_USER.equals(role);
    }

    public boolean isAssistant() {
        return ROLE_ASSISTANT.equals(role);
    }

    public boolean isEmptyAssistant() {
        return isAssistant()
                && (content == null || content.trim().isEmpty())
                && reasoning.isEmpty()
                && toolCalls.isEmpty();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("role", role);
            o.put("content", content == null ? "" : content);
            if (!reasoning.isEmpty()) o.put("reasoning", reasoning);
            if (!toolCalls.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (ToolCall t : toolCalls) arr.put(t.toJson());
                o.put("toolCalls", arr);
            }
            if (toolCallId != null) o.put("toolCallId", toolCallId);
            if (toolName != null) o.put("toolName", toolName);
            if (error) o.put("error", true);
            o.put("ts", ts);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Message fromJson(JSONObject o) {
        Message m = new Message();
        m.id = o.optString("id", UUID.randomUUID().toString());
        m.role = o.optString("role", ROLE_USER);
        m.content = o.optString("content", "");
        m.reasoning = o.optString("reasoning", "");
        m.toolCallId = o.has("toolCallId") ? o.optString("toolCallId") : null;
        m.toolName = o.has("toolName") ? o.optString("toolName") : null;
        m.error = o.optBoolean("error", false);
        m.ts = o.optLong("ts", System.currentTimeMillis());
        JSONArray arr = o.optJSONArray("toolCalls");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t != null) m.toolCalls.add(ToolCall.fromJson(t));
            }
        }
        return m;
    }
}

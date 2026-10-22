package com.dsh.mobile.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Session {

    public String id = UUID.randomUUID().toString();
    public String title = "";
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = System.currentTimeMillis();
    public List<Message> messages = new ArrayList<>();

    public String displayTitle() {
        if (title == null || title.trim().isEmpty()) return "未命名会话";
        return title;
    }

    public int messageCount() {
        int n = 0;
        for (Message m : messages) if (m.isUser()) n++;
        return n;
    }

    /** 列表里显示的最后一条用户消息 */
    public String snippet() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.isUser() && m.content != null) {
                String s = m.content.replace("\n", " ").trim();
                return s.length() > 40 ? s.substring(0, 40) + "…" : s;
            }
        }
        return "空会话";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("title", title);
            o.put("createdAt", createdAt);
            o.put("updatedAt", updatedAt);
            JSONArray arr = new JSONArray();
            for (Message m : messages) arr.put(m.toJson());
            o.put("messages", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Session fromJson(JSONObject o) {
        Session s = new Session();
        s.id = o.optString("id", UUID.randomUUID().toString());
        s.title = o.optString("title", "");
        s.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        s.updatedAt = o.optLong("updatedAt", s.createdAt);
        JSONArray arr = o.optJSONArray("messages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m != null) s.messages.add(Message.fromJson(m));
            }
        }
        return s;
    }
}

package com.dsh.mobile.core;

import org.json.JSONObject;

import java.util.UUID;

/** 一条消息上的附件元数据（内容按需读取，不塞进会话 JSON）。 */
public class Attachment {

    public static final String KIND_TEXT = "text";
    public static final String KIND_IMAGE = "image";
    public static final String KIND_OTHER = "other";

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String mime = "";
    public String kind = KIND_OTHER;
    public String localPath = "";
    public long size;
    public boolean truncated;

    public boolean isText() {
        return KIND_TEXT.equals(kind);
    }

    public boolean isImage() {
        return KIND_IMAGE.equals(kind);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("mime", mime);
            o.put("kind", kind);
            o.put("localPath", localPath);
            o.put("size", size);
            o.put("truncated", truncated);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Attachment fromJson(JSONObject o) {
        Attachment a = new Attachment();
        a.id = o.optString("id", UUID.randomUUID().toString());
        a.name = o.optString("name", "");
        a.mime = o.optString("mime", "");
        a.kind = o.optString("kind", KIND_OTHER);
        a.localPath = o.optString("localPath", "");
        a.size = o.optLong("size", 0);
        a.truncated = o.optBoolean("truncated", false);
        return a;
    }
}

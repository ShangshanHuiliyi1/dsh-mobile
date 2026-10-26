package com.dsh.mobile.core;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.net.ssl.SSLException;

/**
 * OpenAI 兼容 /chat/completions 客户端。
 * 负责：按渠道拼地址与鉴权、SSE 流式解析、tool_calls 增量合并、
 * 不支持工具调用时自动降级、以及少数渠道"号称流式其实返回整包 JSON"的兜底。
 */
public class ChatApi {

    private static final String TAG = "ChatApi";

    public static class Result {
        public String content = "";
        public String reasoning = "";
        public List<ToolCall> toolCalls = new ArrayList<>();
        public String finishReason;
        public boolean cancelled;
        /** 因为渠道不支持工具调用，本次已去掉 tools 重发 */
        public boolean toolsSuppressed;
        /** 流里出现错误并已上报 */
        public boolean failed;

        public boolean isEmpty() {
            return content.trim().isEmpty() && reasoning.trim().isEmpty() && toolCalls.isEmpty();
        }
    }

    public interface Callback {
        void onDelta(Result partial);

        void onFinish(Result result);

        void onError(String message);
    }

    private volatile HttpURLConnection conn;
    private volatile boolean cancelled;
    private Thread thread;

    public boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    public void cancel() {
        cancelled = true;
        HttpURLConnection c = conn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    public void start(final Provider provider, final String systemPrompt, final List<Message> history,
                      final float temperature, final int maxTokens,
                      final JSONArray tools, final Callback cb) {
        cancelled = false;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    execute(provider, systemPrompt, history, temperature, maxTokens, tools, cb);
                } catch (Throwable t) {
                    if (cancelled) {
                        Result r = new Result();
                        r.cancelled = true;
                        cb.onFinish(r);
                    } else {
                        Log.w(TAG, "request failed", t);
                        cb.onError(describe(t));
                    }
                }
            }
        }, "dsh-chat");
        thread.start();
    }

    private void execute(Provider provider, String systemPrompt, List<Message> history,
                         float temperature, int maxTokens, JSONArray tools, Callback cb)
            throws IOException, JSONException {

        boolean toolsAvailable = provider.toolsEnabled && tools != null && tools.length() > 0;
        JSONArray useTools = toolsAvailable ? tools : null;
        boolean degraded = false;

        for (int attempt = 0; attempt < 2; attempt++) {
            String body = buildBody(provider, systemPrompt, history, temperature, maxTokens, useTools);
            HttpURLConnection c = open(provider);
            conn = c;
            post(c, body);

            int code = c.getResponseCode();
            if (code != 200) {
                String err = readAll(c.getErrorStream(), 64 * 1024);
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
                if (cancelled) {
                    Result r = new Result();
                    r.cancelled = true;
                    cb.onFinish(r);
                    return;
                }
                // 有些渠道/model 不支持 tools，收到 400 时去掉工具再试一次
                if (attempt == 0 && useTools != null && code == 400 && mentionsTools(err)) {
                    Log.w(TAG, "provider rejected tools, retry without them");
                    useTools = null;
                    degraded = true;
                    continue;
                }
                cb.onError(friendly(provider, code, err));
                return;
            }

            Result result = readResponse(c, cb);
            if (result == null || result.failed) return; // 已在内部报错
            result.toolsSuppressed = degraded;
            cb.onFinish(result);
            return;
        }
    }

    // ------------------------------------------------------------ http pieces

    private HttpURLConnection open(Provider p) throws IOException {
        URL url = new URL(p.chatUrl());
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "text/event-stream, application/json");
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
        return c;
    }

    private void post(HttpURLConnection c, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        OutputStream os = c.getOutputStream();
        try {
            os.write(payload);
            os.flush();
        } finally {
            os.close();
        }
    }

    /** 读取响应：SSE 就逐块解析，整包 JSON 就一次性解析 */
    private Result readResponse(HttpURLConnection c, Callback cb) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));

            StringBuilder head = new StringBuilder();
            String first = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                first = line;
                break;
            }
            if (first == null) {
                return new Result();
            }

            if (first.startsWith("data:")) {
                Result result = new Result();
                handleSseLine(first, result, cb);
                while ((line = reader.readLine()) != null) {
                    if (cancelled) break;
                    if (!line.startsWith("data:")) continue;
                    if (handleSseLine(line, result, cb)) break;
                }
                if (cancelled) result.cancelled = true;
                finishToolCalls(result);
                return result;
            }

            // 非流式：把整包读完当 JSON 处理
            head.append(first).append('\n');
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                head.append(buf, 0, n);
                if (head.length() > (8 << 20)) break;
            }
            String text = head.toString().trim();
            Result whole = parseWhole(text);
            if (whole == null) {
                cb.onError("返回内容无法解析，可能不是 OpenAI 兼容接口。\n" + trim(text, 300));
                return null;
            }
            if (!whole.isEmpty()) cb.onDelta(whole);
            finishToolCalls(whole);
            return whole;
        } catch (Exception e) {
            if (cancelled) {
                Result r = new Result();
                r.cancelled = true;
                return r;
            }
            cb.onError(describe(e));
            return null;
        } finally {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    /** @return true 表示流结束 */
    private boolean handleSseLine(String rawLine, Result result, Callback cb) {
        String data = rawLine.substring(5).trim();
        if (data.isEmpty()) return false;
        if ("[DONE]".equals(data)) return true;
        JSONObject obj;
        try {
            obj = new JSONObject(data);
        } catch (JSONException e) {
            Log.w(TAG, "bad chunk: " + data);
            return false;
        }
        if (obj.has("error")) {
            JSONObject e = obj.optJSONObject("error");
            String msg = e != null ? e.optString("message", data) : data;
            result.failed = true;
            cb.onError("接口返回错误：" + msg);
            return true;
        }
        JSONArray choices = obj.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return false;
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) return false;

        boolean changed = false;
        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
            String c = optStringOrNull(delta, "content");
            if (c != null) {
                result.content += c;
                changed = true;
            }
            String r = reasoningOf(delta);
            if (r != null) {
                result.reasoning += r;
                changed = true;
            }
            JSONArray tcs = delta.optJSONArray("tool_calls");
            if (tcs != null && tcs.length() > 0) {
                mergeToolCallDeltas(tcs, result);
                changed = true;
            }
        }
        String fr = optStringOrNull(choice, "finish_reason");
        if (fr != null) result.finishReason = fr;
        if (changed) cb.onDelta(result);
        return false;
    }

    private Result parseWhole(String text) {
        try {
            JSONObject o = new JSONObject(text);
            if (o.has("error")) return null;
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) return null;
            JSONObject msg = choice.optJSONObject("message");
            if (msg == null) msg = choice.optJSONObject("delta");
            Result r = new Result();
            if (msg != null) {
                String c = optStringOrNull(msg, "content");
                if (c != null) r.content = c;
                String reason = reasoningOf(msg);
                if (reason != null) r.reasoning = reason;
                JSONArray tcs = msg.optJSONArray("tool_calls");
                if (tcs != null) {
                    for (int i = 0; i < tcs.length(); i++) {
                        JSONObject t = tcs.optJSONObject(i);
                        if (t == null) continue;
                        ToolCall tc = new ToolCall();
                        tc.id = t.optString("id", "");
                        JSONObject fn = t.optJSONObject("function");
                        if (fn != null) {
                            tc.name = fn.optString("name", "");
                            tc.arguments = fn.optString("arguments", "{}");
                        }
                        r.toolCalls.add(tc);
                    }
                }
            }
            r.finishReason = choice.optString("finish_reason", null);
            return r;
        } catch (JSONException e) {
            return null;
        }
    }


    private String reasoningOf(JSONObject o) {
        String r = optStringOrNull(o, "reasoning_content");
        if (r == null) r = optStringOrNull(o, "reasoning");
        return r;
    }

    private void mergeToolCallDeltas(JSONArray deltas, Result result) {
        for (int i = 0; i < deltas.length(); i++) {
            JSONObject d = deltas.optJSONObject(i);
            if (d == null) continue;
            int idx = d.optInt("index", i);
            while (result.toolCalls.size() <= idx) result.toolCalls.add(new ToolCall());
            ToolCall tc = result.toolCalls.get(idx);

            String id = optStringOrNull(d, "id");
            if (id != null) tc.id = id;

            JSONObject fn = d.optJSONObject("function");
            if (fn == null) continue;
            String name = optStringOrNull(fn, "name");
            if (name != null) {
                if (tc.name == null || tc.name.isEmpty()) tc.name = name;
                else if (!tc.name.equals(name)) tc.name += name;
            }
            String args = optStringOrNull(fn, "arguments");
            if (args != null) {
                if (tc.arguments == null) tc.arguments = args;
                else tc.arguments += args;
            }
        }
    }

    private void finishToolCalls(Result result) {
        for (ToolCall tc : result.toolCalls) {
            if (tc.id == null || tc.id.isEmpty()) {
                tc.id = "call_" + UUID.randomUUID().toString().substring(0, 8);
            }
            if (tc.arguments == null || tc.arguments.isEmpty()) tc.arguments = "{}";
            if (tc.name == null) tc.name = "";
        }
    }

    // --------------------------------------------------------------- request

    /** 渠道支持多模态且消息里带图片时，返回 OpenAI 风格的内容数组；否则返回 null */
    private JSONArray userParts(Message m, boolean vision) {
        if (!vision || m.attachments.isEmpty()) return null;
        boolean hasImage = false;
        for (Attachment a : m.attachments) {
            if (a.isImage()) {
                hasImage = true;
                break;
            }
        }
        if (!hasImage) return null;
        try {
            JSONArray arr = new JSONArray();
            String text = Attachments.composeText(m.content, m.attachments, true);
            if (!text.trim().isEmpty()) {
                arr.put(new JSONObject().put("type", "text").put("text", text));
            }
            for (Attachment a : m.attachments) {
                if (!a.isImage()) continue;
                String b64 = Attachments.base64(a);
                if (b64 == null) continue;
                String mime = a.mime == null || a.mime.isEmpty() ? "image/png" : a.mime;
                arr.put(new JSONObject()
                        .put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", "data:" + mime + ";base64," + b64)));
            }
            return arr.length() > 0 ? arr : null;
        } catch (JSONException e) {
            return null;
        }
    }

    private String buildBody(Provider provider, String systemPrompt, List<Message> history,
                             float temperature, int maxTokens, JSONArray tools) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", provider.model);
        body.put("stream", true);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        JSONArray msgs = new JSONArray();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            msgs.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        }

        Set<String> pending = new HashSet<>();
        for (Message m : history) {
            boolean isTool = Message.ROLE_TOOL.equals(m.role);
            if (!isTool && !pending.isEmpty()) {
                for (String id : pending) {
                    msgs.put(new JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", id)
                            .put("content", "（该工具调用已取消，没有输出）"));
                }
                pending.clear();
            }
            if (isTool) {
                if (m.toolCallId == null || m.toolCallId.isEmpty()) continue;
                msgs.put(new JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", m.toolCallId)
                        .put("content", m.content == null ? "" : m.content));
                pending.remove(m.toolCallId);
                continue;
            }
            if (m.isAssistant()) {
                if (m.isEmptyAssistant()) continue;
                JSONObject o = new JSONObject();
                o.put("role", "assistant");
                o.put("content", m.content == null ? "" : m.content);
                if (!m.toolCalls.isEmpty()) {
                    JSONArray arr = new JSONArray();
                    for (ToolCall tc : m.toolCalls) {
                        arr.put(new JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put("function", new JSONObject()
                                        .put("name", tc.name)
                                        .put("arguments", tc.arguments == null || tc.arguments.isEmpty()
                                                ? "{}" : tc.arguments)));
                        pending.add(tc.id);
                    }
                    o.put("tool_calls", arr);
                }
                msgs.put(o);
            } else {
                JSONObject u = new JSONObject().put("role", "user");
                JSONArray parts = userParts(m, provider.vision);
                if (parts != null) u.put("content", parts);
                else u.put("content", Attachments.composeText(m.content, m.attachments, provider.vision));
                msgs.put(u);
            }
        }
        if (!pending.isEmpty()) {
            for (String id : pending) {
                msgs.put(new JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", "（该工具调用已取消，没有输出）"));
            }
        }

        body.put("messages", msgs);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        return body.toString();
    }

    // ---------------------------------------------------------------- errors

    private boolean mentionsTools(String err) {
        if (err == null) return false;
        String e = err.toLowerCase(Locale.US);
        return e.contains("tool") || e.contains("function") || e.contains("tool_choice")
                || e.contains("不支持") || e.contains("unsupported");
    }

    private String friendly(Provider p, int code, String rawBody) {
        String who = "渠道「" + (p == null ? "当前" : p.name) + "」";
        String head;
        switch (code) {
            case 400:
                head = who + "拒绝了请求（400）：通常是模型名不对，或该模型不支持工具调用。";
                break;
            case 401:
                head = who + "鉴权失败（401）：Key 不对、过期，或没有该模型的权限。";
                break;
            case 403:
                head = who + "无权访问（403）：检查 Key 权限或该模型是否已开通。";
                break;
            case 402:
                head = who + "余额不足（402）。";
                break;
            case 404:
                head = who + "接口路径不对（404）：检查「接口地址」和「请求路径」。";
                break;
            case 422:
                head = who + "参数错误（422）：可能是模型名或参数不被支持。";
                break;
            case 429:
                head = who + "请求过于频繁或额度用尽（429）。";
                break;
            default:
                if (code >= 500) head = who + "服务端异常（" + code + "）：稍后重试。";
                else head = who + "请求失败（HTTP " + code + "）。";
        }
        String detail = "";
        try {
            JSONObject o = new JSONObject(rawBody);
            JSONObject e = o.optJSONObject("error");
            if (e != null) detail = e.optString("message", "");
            else detail = o.optString("message", "");
        } catch (Exception ignored) {
            if (rawBody != null && !rawBody.trim().isEmpty()) {
                detail = trim(rawBody.trim(), 300);
            }
        }
        return detail.isEmpty() ? head : head + "\n" + detail;
    }

    private String describe(Throwable t) {
        if (t instanceof UnknownHostException) {
            return "域名解析失败：检查网络，或「接口地址」是否写错（自建服务需要手机能访问到）。";
        }
        if (t instanceof SocketTimeoutException) {
            return "连接超时：对方没有响应，或网络不稳定。";
        }
        if (t instanceof SSLException) {
            return "TLS 握手失败：可能是证书问题，或被网络中间设备拦截。";
        }
        String m = t.getMessage();
        return "请求失败：" + t.getClass().getSimpleName() + (m == null ? "" : "（" + m + "）");
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String optStringOrNull(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        return o.optString(key, null);
    }

    private static String readAll(InputStream in, int max) {
        if (in == null) return "";
        try {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (sb.length() > max) break;
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
}

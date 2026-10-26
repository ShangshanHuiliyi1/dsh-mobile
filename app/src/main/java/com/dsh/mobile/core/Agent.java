package com.dsh.mobile.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Agent 主循环：发消息 -> 流式接收 -> 执行工具 -> 带着工具结果继续 -> 直到模型不再调用工具。
 * 全局单例，切界面时流式不会中断。
 */
public class Agent {

    private static final String TAG = "Agent";
    private static final int MAX_STEPS = 12;

    public interface Listener {
        void onAppend(Message m);

        void onUpdate(Message m);

        void onRemove(Message m);

        void onBusy(boolean busy);

        void onError(String message);

        /** 非错误的提示（如渠道不支持工具、已自动降级） */
        void onNotice(String message);
    }

    private static Agent instance;

    public static synchronized Agent get(Context c) {
        if (instance == null) instance = new Agent(c);
        return instance;
    }

    private final Config cfg;
    private final SessionStore store;
    private final LocalTools tools;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile Listener listener;
    private ChatApi api;
    private volatile boolean running;
    private Session session;
    private long lastUiPush;

    private Agent(Context c) {
        Context app = c.getApplicationContext();
        cfg = new Config(app);
        store = new SessionStore(app);
        tools = new LocalTools(app, store.workspace());
    }

    public Config config() {
        return cfg;
    }

    public SessionStore store() {
        return store;
    }

    public LocalTools tools() {
        return tools;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isRunning() {
        return running;
    }

    public Session session() {
        return session;
    }

    public Session currentSession() {
        if (session == null) {
            String id = cfg.currentSessionId();
            Session s = id == null ? null : store.load(id);
            if (s == null) s = store.create();
            session = s;
            cfg.setCurrentSessionId(s.id);
        }
        return session;
    }

    public void openSession(String id) {
        if (running) stop();
        Session s = store.load(id);
        if (s == null) return;
        session = s;
        cfg.setCurrentSessionId(s.id);
    }

    public Session newSession() {
        if (running) stop();
        Session s = store.create();
        session = s;
        cfg.setCurrentSessionId(s.id);
        return s;
    }

    public void stop() {
        ChatApi c = api;
        if (c != null) c.cancel();
    }

    public void send(String text) {
        send(text, null);
    }

    public void send(String text, List<Attachment> attachments) {
        if (running) return;
        String body = text == null ? "" : text.trim();
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        if (body.isEmpty() && !hasAttachments) return;

        Provider provider = cfg.providers().active();
        if (provider.model == null || provider.model.trim().isEmpty()) {
            postError("当前渠道「" + provider.name + "」还没填模型名，去「设置 → 渠道」补一下。");
            return;
        }
        if (!provider.hasKey()) {
            postError("当前渠道「" + provider.name + "」缺少 API Key。");
            return;
        }

        Session s = currentSession();

        running = true;
        lastUiPush = 0;
        postBusy(true);

        Message user = Message.user(body);
        if (hasAttachments) user.attachments.addAll(attachments);
        s.messages.add(user);
        emitAppend(user);

        if (s.title == null || s.title.trim().isEmpty() || "新会话".equals(s.title.trim())) {
            s.title = titleOf(body.isEmpty() ? attachments.get(0).name : body);
        }
        store.save(s);

        new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "dsh-agent").start();
    }

    private void loop() {
        final Session s = session;
        final Provider provider = cfg.providers().active();
        int step = 0;
        boolean noticedTools = false;
        try {
            while (running && step < MAX_STEPS) {
                step++;

                Message live = Message.assistant();
                live.streaming = true;
                s.messages.add(live);
                emitAppend(live);

                Turn turn = stream(live, provider);

                if (turn.error != null) {
                    live.streaming = false;
                    live.error = true;
                    live.content = (live.content == null ? "" : live.content)
                            + (live.content == null || live.content.isEmpty() ? "" : "\n\n")
                            + "⚠️ " + turn.error;
                    emitUpdate(live);
                    break;
                }

                ChatApi.Result r = turn.result;
                if (r == null) {
                    live.streaming = false;
                    live.error = true;
                    live.content = "⚠️ 没有收到响应。";
                    emitUpdate(live);
                    break;
                }

                live.content = r.content == null ? "" : r.content;
                live.reasoning = r.reasoning == null ? "" : r.reasoning;
                live.toolCalls = r.toolCalls;
                live.streaming = false;

                if (r.toolsSuppressed && !noticedTools) {
                    noticedTools = true;
                    postNotice("渠道「" + provider.name
                            + "」不接受工具调用，本轮已按普通对话处理。可在渠道设置里关闭工具调用避免重试。");
                }

                if (r.cancelled && live.isEmptyAssistant()) {
                    s.messages.remove(live);
                    emitRemove(live);
                    break;
                }
                if (live.content.trim().isEmpty() && live.reasoning.isEmpty() && live.toolCalls.isEmpty()) {
                    live.content = "（模型没有返回内容，可以重试或换个说法）";
                }
                emitUpdate(live);
                store.save(s);

                if (r.cancelled) break;
                if (live.toolCalls.isEmpty()) break;

                for (ToolCall tc : live.toolCalls) {
                    if (!running) break;
                    tc.done = false;
                    emitUpdate(live);

                    long t0 = System.currentTimeMillis();
                    String out;
                    try {
                        out = tools.execute(tc.name, tc.arguments);
                    } catch (Throwable t) {
                        Log.w(TAG, "tool failed", t);
                        out = "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                    tc.millis = System.currentTimeMillis() - t0;
                    tc.result = out == null ? "" : out;
                    tc.error = tc.result.startsWith("ERROR") || tc.result.startsWith("TIMEOUT");
                    tc.done = true;
                    emitUpdate(live);

                    Message toolMsg = Message.tool(tc.id, tc.name, tc.result, tc.error);
                    s.messages.add(toolMsg);
                    emitAppend(toolMsg);
                    store.save(s);
                }

                if (!running) break;
                if (step >= MAX_STEPS) {
                    Message note = Message.assistant();
                    note.content = "已达到单轮最多 " + MAX_STEPS + " 步工具调用，先停在这里。继续发消息可以接着做。";
                    s.messages.add(note);
                    emitAppend(note);
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "agent loop crashed", t);
            postError("执行出错：" + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            running = false;
            api = null;
            postBusy(false);
            store.save(s);
        }
    }

    private static class Turn {
        ChatApi.Result result;
        String error;
    }

    private Turn stream(final Message live, final Provider provider) {
        final Turn turn = new Turn();
        final ChatApi client = new ChatApi();
        api = client;
        final CountDownLatch latch = new CountDownLatch(1);

        List<Message> history = new ArrayList<>(session.messages);
        history.remove(live);

        client.start(provider, cfg.systemPrompt(),
                history, cfg.temperature(), cfg.maxTokens(), LocalTools.definitions(),
                new ChatApi.Callback() {
                    @Override
                    public void onDelta(ChatApi.Result partial) {
                        live.content = partial.content;
                        live.reasoning = partial.reasoning;
                        live.toolCalls = partial.toolCalls;
                        long now = System.currentTimeMillis();
                        if (now - lastUiPush >= 70) {
                            lastUiPush = now;
                            emitUpdate(live);
                        }
                    }

                    @Override
                    public void onFinish(ChatApi.Result result) {
                        turn.result = result;
                        latch.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        turn.error = message;
                        latch.countDown();
                    }
                });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return turn;
    }

    private static String titleOf(String text) {
        String t = text.replace("\n", " ").trim();
        if (t.isEmpty()) return "新会话";
        return t.length() > 22 ? t.substring(0, 22) + "…" : t;
    }

    // -------------------------------------------------------------- ui glue

    private void emitAppend(final Message m) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onAppend(m);
            }
        });
    }

    private void emitUpdate(final Message m) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onUpdate(m);
            }
        });
    }

    private void emitRemove(final Message m) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onRemove(m);
            }
        });
    }

    private void postBusy(final boolean busy) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onBusy(busy);
            }
        });
    }

    private void postError(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onError(msg);
            }
        });
    }

    private void postNotice(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) l.onNotice(msg);
            }
        });
    }
}

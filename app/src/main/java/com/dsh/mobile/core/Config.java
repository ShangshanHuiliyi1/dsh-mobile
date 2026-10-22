package com.dsh.mobile.core;

import android.content.Context;
import android.content.SharedPreferences;

public final class Config {

    public static final String PREFS_NAME = "dsh_prefs";

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-chat";

    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是 DSH Mobile，一个运行在 Android 手机上的 AI 编程与任务代理。\n"
            + "你可以用工具读写工作区文件、执行 shell 命令、发起网络请求、查看设备信息。\n"
            + "工作原则：\n"
            + "1. 先观察再动手：用 list_dir / read_file 了解现状，不要凭猜测修改文件。\n"
            + "2. 动作要小、可解释：每次调用工具后，用一两句话说明你做了什么、看到了什么。\n"
            + "3. 回答用中文，代码块标注语言；命令与路径用反引号包裹。\n"
            + "4. 不要编造命令输出或文件内容，拿不到就说拿不到，并说明需要什么。\n"
            + "5. 涉及删除、覆盖的操作前，先说清后果。";

    private final SharedPreferences sp;
    private final ProviderStore providers;

    public Config(Context c) {
        Context app = c.getApplicationContext();
        this.sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.providers = ProviderStore.get(app);
    }

    public ProviderStore providers() {
        return providers;
    }

    public Provider activeProvider() {
        return providers.active();
    }

    public boolean hasApiKey() {
        return providers.active().hasKey();
    }

    /** 把 Key 写进当前渠道（首次引导用） */
    public void setApiKey(String v) {
        Provider p = providers.active();
        p.apiKey = v == null ? "" : v.trim();
        providers.save(p);
    }

    public String apiKey() {
        return providers.active().apiKey;
    }

    public String baseUrl() {
        return providers.active().baseUrl;
    }

    public String model() {
        return providers.active().model;
    }

    public float temperature() {
        return sp.getFloat("temperature", 0.7f);
    }

    public void setTemperature(float v) {
        sp.edit().putFloat("temperature", v).apply();
    }

    public int maxTokens() {
        return sp.getInt("max_tokens", 4096);
    }

    public void setMaxTokens(int v) {
        sp.edit().putInt("max_tokens", v).apply();
    }

    public String systemPrompt() {
        String v = sp.getString("system_prompt", null);
        if (v == null) return DEFAULT_SYSTEM_PROMPT;
        return v;
    }

    public void setSystemPrompt(String v) {
        sp.edit().putString("system_prompt", v == null ? "" : v).apply();
    }

    public String currentSessionId() {
        return sp.getString("session_id", null);
    }

    public void setCurrentSessionId(String id) {
        if (id == null) sp.edit().remove("session_id").apply();
        else sp.edit().putString("session_id", id).apply();
    }
}

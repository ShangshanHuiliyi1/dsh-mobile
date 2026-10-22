package com.dsh.mobile.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 常见渠道的预设模板。只是"新建时的初始值"，建好后所有字段都能改。
 * 中转站/自建网关一律选"自定义"。
 */
public final class Presets {

    public static class Preset {
        public final String key;
        public final String name;
        public final String baseUrl;
        public final String path;
        public final String model;
        public final String authStyle;
        public final boolean toolsEnabled;
        public final String note;

        Preset(String key, String name, String baseUrl, String path, String model,
               String authStyle, boolean toolsEnabled, String note) {
            this.key = key;
            this.name = name;
            this.baseUrl = baseUrl;
            this.path = path;
            this.model = model;
            this.authStyle = authStyle;
            this.toolsEnabled = toolsEnabled;
            this.note = note;
        }
    }

    private static List<Preset> cache;

    public static List<Preset> all() {
        if (cache != null) return cache;
        List<Preset> list = new ArrayList<>();
        list.add(new Preset("deepseek", "DeepSeek 官方", "https://api.deepseek.com",
                "/chat/completions", "deepseek-chat", Provider.AUTH_BEARER, true,
                "官方直连。deepseek-chat 支持工具调用，deepseek-reasoner 只思考不调工具。"));
        list.add(new Preset("siliconflow", "硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1",
                "/chat/completions", "deepseek-ai/DeepSeek-V3", Provider.AUTH_BEARER, true,
                "聚合多家模型，新用户有免费额度。模型名带命名空间，如 deepseek-ai/DeepSeek-V3。"));
        list.add(new Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
                "/chat/completions", "deepseek/deepseek-chat", Provider.AUTH_BEARER, true,
                "海外聚合平台，一个 Key 可用上百种模型，按量计费。"));
        list.add(new Preset("moonshot", "Kimi / 月之暗面", "https://api.moonshot.cn/v1",
                "/chat/completions", "moonshot-v1-8k", Provider.AUTH_BEARER, true,
                "国内长文本见长，工具调用支持有限，报错时可关掉工具调用。"));
        list.add(new Preset("dashscope", "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "/chat/completions", "qwen-plus", Provider.AUTH_BEARER, true,
                "通义千问的 OpenAI 兼容模式，qwen-plus / qwen-max / qwen3 系列可选。"));
        list.add(new Preset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
                "/chat/completions", "glm-4-plus", Provider.AUTH_BEARER, true,
                "GLM-4 系列，部分模型支持 function calling。"));
        list.add(new Preset("ark", "火山方舟（豆包）", "https://ark.cn-beijing.volces.com/api/v3",
                "/chat/completions", "", Provider.AUTH_BEARER, true,
                "模型要填「接入点 ID」（ep- 开头），不是模型名。"));
        list.add(new Preset("ollama", "本地 Ollama / LM Studio", "http://127.0.0.1:11434/v1",
                "/chat/completions", "qwen2.5:7b", Provider.AUTH_NONE, false,
                "电脑上跑模型、手机同一局域网时可用；不需要 Key。工具调用看模型本身支持情况。"));
        list.add(new Preset("custom", "自定义 / 中转站", "",
                "/v1/chat/completions", "", Provider.AUTH_BEARER, true,
                "第三方中转、自建网关都选这个：把对方给的接口地址和路径填进来即可。"));
        cache = list;
        return list;
    }

    public static Provider toProvider(Preset p) {
        Provider provider = new Provider();
        provider.name = p.name;
        provider.baseUrl = p.baseUrl;
        provider.path = p.path;
        provider.model = p.model;
        provider.authStyle = p.authStyle;
        provider.toolsEnabled = p.toolsEnabled;
        provider.apiKey = "";
        return provider;
    }

    /** 根据 baseUrl 猜一个预设名，用于旧配置迁移 */
    public static String guessName(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.toLowerCase();
        if (u.contains("deepseek")) return "DeepSeek 官方";
        if (u.contains("siliconflow")) return "硅基流动 SiliconFlow";
        if (u.contains("openrouter")) return "OpenRouter";
        if (u.contains("moonshot")) return "Kimi / 月之暗面";
        if (u.contains("dashscope") || u.contains("aliyun")) return "阿里云百炼";
        if (u.contains("bigmodel")) return "智谱 GLM";
        if (u.contains("volces")) return "火山方舟（豆包）";
        if (u.contains("11434") || u.contains("localhost") || u.contains("192.168.")) return "本地模型";
        return "自定义渠道";
    }
}

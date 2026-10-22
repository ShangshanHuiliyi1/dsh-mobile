package com.dsh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.mobile.core.Agent;
import com.dsh.mobile.core.Config;
import com.dsh.mobile.core.ModelsApi;
import com.dsh.mobile.core.Provider;
import com.dsh.mobile.core.ProviderStore;

import java.util.List;

public class SettingsActivity extends Activity {

    private Agent agent;
    private Config cfg;
    private ProviderStore store;

    private EditText model;
    private EditText systemPrompt;
    private EditText temperature;
    private EditText maxTokens;
    private TextView providerName;
    private TextView providerBase;
    private TextView providerState;
    private TextView workspaceInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        agent = Agent.get(this);
        cfg = agent.config();
        store = cfg.providers();

        model = findViewById(R.id.model);
        systemPrompt = findViewById(R.id.systemPrompt);
        temperature = findViewById(R.id.temperature);
        maxTokens = findViewById(R.id.maxTokens);
        providerName = findViewById(R.id.providerName);
        providerBase = findViewById(R.id.providerBase);
        providerState = findViewById(R.id.providerState);
        workspaceInfo = findViewById(R.id.workspaceInfo);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                toast("已保存");
                finish();
            }
        });
        findViewById(R.id.providerCard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ProvidersActivity.class));
            }
        });
        findViewById(R.id.btnFetchModels).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetchModels(false);
            }
        });
        findViewById(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetchModels(true);
            }
        });
        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });

        fill();
    }

    @Override
    protected void onResume() {
        super.onResume();
        fill();
    }

    private void fill() {
        Provider p = store.active();
        providerName.setText(p.name);
        providerBase.setText(p.baseUrl + p.path);
        providerState.setText("Key " + p.maskedKey()
                + " · 工具调用" + (p.toolsEnabled ? "开" : "关")
                + (p.model == null || p.model.trim().isEmpty() ? " · 未选模型" : ""));

        model.setText(p.model);
        systemPrompt.setText(cfg.systemPrompt());
        temperature.setText(String.valueOf(cfg.temperature()));
        maxTokens.setText(String.valueOf(cfg.maxTokens()));
        workspaceInfo.setText("工作区：" + agent.tools().root().getAbsolutePath()
                + "\nAgent 的所有文件操作都限制在这个目录内。");
    }

    private void save() {
        Provider p = store.active();
        String m = model.getText().toString().trim();
        if (!m.equals(p.model)) {
            p.model = m;
            store.save(p);
        }
        cfg.setSystemPrompt(systemPrompt.getText().toString());
        try {
            float t = Float.parseFloat(temperature.getText().toString().trim());
            if (t < 0f) t = 0f;
            if (t > 2f) t = 2f;
            cfg.setTemperature(t);
        } catch (Exception ignored) {
        }
        try {
            int mt = Integer.parseInt(maxTokens.getText().toString().trim());
            if (mt < 256) mt = 256;
            if (mt > 32768) mt = 32768;
            cfg.setMaxTokens(mt);
        } catch (Exception ignored) {
        }
    }

    private void fetchModels(final boolean asTest) {
        save();
        final Provider p = store.active();
        if (p.baseUrl == null || p.baseUrl.trim().isEmpty()) {
            toast("当前渠道还没有接口地址");
            return;
        }
        toast("正在请求 " + p.modelsUrl());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> models;
                try {
                    models = ModelsApi.fetch(p);
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showFetchError(e);
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (asTest) showTestResult(p, models);
                        else showModelPicker(models);
                    }
                });
            }
        }, "dsh-models").start();
    }

    private void showTestResult(Provider p, List<String> models) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.name).append(" 连接正常，共 ").append(models.size()).append(" 个模型。\n\n");
        for (int i = 0; i < Math.min(6, models.size()); i++) {
            sb.append("· ").append(models.get(i)).append('\n');
        }
        if (models.size() > 6) sb.append("…");
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("测试成功")
                .setMessage(sb.toString())
                .setPositiveButton("好", null)
                .show();
    }

    private void showModelPicker(final List<String> models) {
        String[] items = models.toArray(new String[0]);
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("选择模型（" + models.size() + "）")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        model.setText(models.get(which));
                        save();
                        fill();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showFetchError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) msg = e.getClass().getSimpleName();
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("连接失败")
                .setMessage(msg + "\n\n检查：接口地址、请求路径、Key，以及该渠道是否兼容 OpenAI 的 /models。")
                .setPositiveButton("好", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("清空所有会话")
                .setMessage("本地保存的会话会全部删除，无法恢复。渠道配置不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        agent.store().deleteAll();
                        cfg.setCurrentSessionId(null);
                        agent.newSession();
                        toast("已清空");
                    }
                })
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

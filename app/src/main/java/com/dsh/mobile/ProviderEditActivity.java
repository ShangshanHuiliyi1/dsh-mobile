package com.dsh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.mobile.core.ModelsApi;
import com.dsh.mobile.core.Provider;
import com.dsh.mobile.core.ProviderStore;

import java.util.List;

public class ProviderEditActivity extends Activity {

    public static final String EXTRA_ID = "provider_id";

    private static final String[] AUTH_LABELS = {
            "Bearer 令牌（大多数渠道）", "api-key 请求头（Azure 风格）", "无需鉴权（本地模型）"
    };
    private static final String[] AUTH_VALUES = {
            Provider.AUTH_BEARER, Provider.AUTH_API_KEY, Provider.AUTH_NONE
    };

    private ProviderStore store;
    private Provider provider;

    private EditText name;
    private EditText baseUrl;
    private EditText path;
    private EditText key;
    private EditText model;
    private EditText headers;
    private TextView authStyleView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_provider_edit);

        store = ProviderStore.get(this);
        String id = getIntent().getStringExtra(EXTRA_ID);
        provider = store.byId(id);
        if (provider == null) {
            Toast.makeText(this, "渠道不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        name = findViewById(R.id.name);
        baseUrl = findViewById(R.id.baseUrl);
        path = findViewById(R.id.path);
        key = findViewById(R.id.key);
        model = findViewById(R.id.model);
        headers = findViewById(R.id.headers);
        authStyleView = findViewById(R.id.authStyle);

        name.setText(provider.name);
        baseUrl.setText(provider.baseUrl);
        path.setText(provider.path);
        key.setText(provider.apiKey);
        model.setText(provider.model);
        headers.setText(provider.extraHeaders);
        updateAuthLabel();
        ((android.widget.CheckBox) findViewById(R.id.tools)).setChecked(provider.toolsEnabled);
        ((android.widget.CheckBox) findViewById(R.id.vision)).setChecked(provider.vision);

        authStyleView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int current = indexOfAuth(provider.authStyle);
                new AlertDialog.Builder(ProviderEditActivity.this, R.style.DshDialog)
                        .setTitle("鉴权方式")
                        .setSingleChoiceItems(AUTH_LABELS, current, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                provider.authStyle = AUTH_VALUES[which];
                                updateAuthLabel();
                                dialog.dismiss();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (saveFields()) finish();
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
    }

    private int indexOfAuth(String style) {
        for (int i = 0; i < AUTH_VALUES.length; i++) {
            if (AUTH_VALUES[i].equals(style)) return i;
        }
        return 0;
    }

    private void updateAuthLabel() {
        authStyleView.setText(AUTH_LABELS[Math.max(0, indexOfAuth(provider.authStyle))]);
    }

    /** 把界面上的值收回 provider，返回是否可以保存 */
    private boolean saveFields() {
        String base = baseUrl.getText().toString().trim();
        if (base.isEmpty()) {
            toast("接口地址不能为空");
            return false;
        }
        provider.name = name.getText().toString().trim();
        if (provider.name.isEmpty()) provider.name = "未命名渠道";
        provider.baseUrl = base;
        String p = path.getText().toString().trim();
        provider.path = p.isEmpty() ? "/chat/completions" : p;
        provider.apiKey = key.getText().toString().trim();
        provider.model = model.getText().toString().trim();
        provider.extraHeaders = headers.getText().toString().trim();
        provider.toolsEnabled = ((android.widget.CheckBox) findViewById(R.id.tools)).isChecked();
        provider.vision = ((android.widget.CheckBox) findViewById(R.id.vision)).isChecked();

        store.save(provider);
        return true;
    }

    private void fetchModels(final boolean asTest) {
        if (!saveFields()) return;
        final Provider p = provider.copy();
        toast("正在请求 " + p.modelsUrl());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> models;
                try {
                    models = ModelsApi.fetch(p);
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showError(e);
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (asTest) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("连接正常，共 ").append(models.size()).append(" 个模型。\n\n");
                            for (int i = 0; i < Math.min(6, models.size()); i++) {
                                sb.append("· ").append(models.get(i)).append('\n');
                            }
                            if (models.size() > 6) sb.append("…");
                            new AlertDialog.Builder(ProviderEditActivity.this, R.style.DshDialog)
                                    .setTitle("测试成功")
                                    .setMessage(sb.toString())
                                    .setPositiveButton("好", null)
                                    .show();
                        } else {
                            showModelPicker(models);
                        }
                    }
                });
            }
        }, "dsh-models").start();
    }

    private void showError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) msg = e.getClass().getSimpleName();
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("连接失败")
                .setMessage(msg + "\n\n检查：接口地址、请求路径、Key、以及该渠道是否兼容 OpenAI 的 /models。")
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
                        saveFields();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

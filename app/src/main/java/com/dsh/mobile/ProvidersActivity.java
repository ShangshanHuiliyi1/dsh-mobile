package com.dsh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.mobile.core.Presets;
import com.dsh.mobile.core.Provider;
import com.dsh.mobile.core.ProviderStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 渠道列表：点一行切换为当前渠道，点「编辑」改配置，长按删除。
 */
public class ProvidersActivity extends Activity {

    private ProviderStore store;
    private ListView list;
    private TextView empty;
    private final List<Provider> data = new ArrayList<>();
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_providers);

        store = ProviderStore.get(this);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnAdd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPresetPicker();
            }
        });

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return data.size();
            }

            @Override
            public Object getItem(int position) {
                return data.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView;
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_provider, parent, false);
                final Provider p = data.get(position);

                TextView name = v.findViewById(R.id.name);
                TextView active = v.findViewById(R.id.active);
                TextView model = v.findViewById(R.id.model);
                TextView base = v.findViewById(R.id.base);
                TextView edit = v.findViewById(R.id.edit);

                boolean isActive = p.id.equals(store.activeId());
                name.setText(p.name);
                name.setTextColor(getResources().getColor(isActive ? R.color.accent : R.color.text, null));
                active.setVisibility(isActive ? View.VISIBLE : View.GONE);

                String tools = p.toolsEnabled ? "工具开" : "工具关";
                String modelText = (p.model == null || p.model.trim().isEmpty()) ? "未选模型" : p.model;
                model.setText(modelText + " · " + p.maskedKey() + " · " + tools);
                base.setText(p.baseUrl + p.path);

                edit.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openEdit(p.id);
                    }
                });
                return v;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Provider p = data.get(position);
                store.setActive(p.id);
                adapter.notifyDataSetChanged();
                Toast.makeText(ProvidersActivity.this, "已切换到「" + p.name + "」", Toast.LENGTH_SHORT).show();
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showActions(data.get(position));
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        data.clear();
        data.addAll(store.all());
        adapter.notifyDataSetChanged();
        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openEdit(String id) {
        Intent i = new Intent(this, ProviderEditActivity.class);
        i.putExtra(ProviderEditActivity.EXTRA_ID, id);
        startActivity(i);
    }

    private void showPresetPicker() {
        final List<Presets.Preset> presets = Presets.all();
        String[] names = new String[presets.size()];
        for (int i = 0; i < presets.size(); i++) names[i] = presets.get(i).name;

        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("选择渠道类型")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Provider p = Presets.toProvider(presets.get(which));
                        store.save(p);
                        if (store.size() == 1) store.setActive(p.id);
                        reload();
                        openEdit(p.id);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showActions(final Provider p) {
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle(p.name)
                .setItems(new String[]{"编辑配置", "设为当前渠道", "删除渠道"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            openEdit(p.id);
                        } else if (which == 1) {
                            store.setActive(p.id);
                            reload();
                        } else {
                            confirmDelete(p);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final Provider p) {
        if (store.size() <= 1) {
            Toast.makeText(this, "至少要保留一个渠道", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("删除渠道")
                .setMessage("「" + p.name + "」连同它的 Key 会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        store.delete(p.id);
                        reload();
                    }
                })
                .show();
    }
}

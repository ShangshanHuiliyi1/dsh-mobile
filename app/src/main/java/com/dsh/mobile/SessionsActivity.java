package com.dsh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.mobile.core.Agent;
import com.dsh.mobile.core.Config;
import com.dsh.mobile.core.Session;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SessionsActivity extends Activity {

    private Agent agent;
    private Config cfg;
    private ListView list;
    private View empty;
    private final List<Session> data = new ArrayList<>();
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sessions);

        agent = Agent.get(this);
        cfg = agent.config();

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btnNew).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (agent.isRunning()) {
                    Toast.makeText(SessionsActivity.this, "有任务正在执行，先回主界面停止。",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                agent.newSession();
                finish();
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
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_session, parent, false);
                Session s = data.get(position);
                TextView title = v.findViewById(R.id.title);
                TextView sub = v.findViewById(R.id.sub);
                title.setText(s.displayTitle());
                boolean current = s.id.equals(cfg.currentSessionId());
                title.setTextColor(getResources().getColor(current ? R.color.accent : R.color.text, null));
                String snippet = s.snippet();
                String titleText = s.displayTitle();
                if (snippet.equals(titleText) || titleText.startsWith(snippet) || snippet.startsWith(titleText)) {
                    snippet = "";
                }
                sub.setText(timeOf(s.updatedAt) + " · " + s.messageCount() + " 条"
                        + (snippet.isEmpty() ? "" : " · " + snippet));
                return v;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (agent.isRunning()) {
                    Toast.makeText(SessionsActivity.this, "有任务正在执行，先回主界面停止。",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                agent.openSession(data.get(position).id);
                finish();
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(data.get(position));
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
        data.addAll(agent.store().all());
        adapter.notifyDataSetChanged();
        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void confirmDelete(final Session s) {
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("删除会话")
                .setMessage("「" + s.displayTitle() + "」将被永久删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        agent.store().delete(s.id);
                        if (s.id.equals(cfg.currentSessionId())) {
                            cfg.setCurrentSessionId(null);
                        }
                        reload();
                    }
                })
                .show();
    }

    private String timeOf(long ts) {
        long now = System.currentTimeMillis();
        Date d = new Date(ts);
        SimpleDateFormat f;
        if (now - ts < 24L * 3600 * 1000) f = new SimpleDateFormat("HH:mm", Locale.CHINA);
        else if (now - ts < 6L * 24 * 3600 * 1000) f = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);
        else f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        return f.format(d);
    }
}

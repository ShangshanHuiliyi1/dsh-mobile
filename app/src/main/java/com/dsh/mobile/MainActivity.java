package com.dsh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.mobile.core.Agent;
import com.dsh.mobile.core.Attachment;
import com.dsh.mobile.core.Attachments;
import com.dsh.mobile.core.Config;
import com.dsh.mobile.core.Message;
import com.dsh.mobile.core.Provider;
import com.dsh.mobile.core.Session;
import com.dsh.mobile.core.WorkspaceImporter;
import com.dsh.mobile.ui.MessageRow;

import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

public class MainActivity extends Activity implements Agent.Listener {

    private Agent agent;
    private Config cfg;

    private ScrollView scroll;
    private LinearLayout feed;
    private EditText input;
    private ImageView send;
    private TextView title;
    private TextView status;
    private View emptyState;

    private final Map<Message, MessageRow> rows = new IdentityHashMap<>();
    private boolean follow = true;
    private int maxBubble;
    private String lastVisibleRole;
    private int sendState = -1;

    private static final int REQ_ATTACH = 2001;
    private final java.util.List<Attachment> pending = new java.util.ArrayList<>();
    private LinearLayout attachBar;
    private View attachScroller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        agent = Agent.get(this);
        cfg = agent.config();

        scroll = findViewById(R.id.scroll);
        feed = findViewById(R.id.feed);
        input = findViewById(R.id.input);
        send = findViewById(R.id.send);
        title = findViewById(R.id.title);
        status = findViewById(R.id.status);
        attachBar = findViewById(R.id.attachBar);
        attachScroller = findViewById(R.id.attachScroller);

        maxBubble = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);

        findViewById(R.id.btnAttach).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickAttachments();
            }
        });

        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
                follow = y + v.getHeight() >= feed.getHeight() - dp(90);
            }
        });

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (agent.isRunning()) agent.stop();
                else doSend();
            }
        });

        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateSendButton(agent.isRunning());
            }
        });

        findViewById(R.id.btnNew).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (agent.isRunning()) {
                    toast("先等当前任务结束，或点停止。");
                    return;
                }
                agent.newSession();
                renderAll();
                input.requestFocus();
            }
        });

        findViewById(R.id.btnSessions).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SessionsActivity.class));
            }
        });

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        findViewById(R.id.btnFiles).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, FilesActivity.class));
            }
        });

        buildEmptyState();
        renderAll();

        if (!cfg.hasApiKey()) {
            promptKey(true);
        }

        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /** 从别的应用「分享」文件进来：导入工作区，并预填一句提示 */
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;

        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) return;
        intent.setAction(null); // 避免重复导入
        importShared(uris);
    }

    private void importShared(final ArrayList<Uri> uris) {
        toast("正在导入 " + uris.size() + " 个文件…");
        final File dir = agent.store().workspace();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final WorkspaceImporter.Result r =
                        WorkspaceImporter.importTo(MainActivity.this, dir, uris, 50L * 1024 * 1024);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        StringBuilder sb = new StringBuilder("已导入 ").append(r.ok).append(" 个文件到工作区");
                        if (r.skipped > 0) sb.append("，").append(r.skipped).append(" 个超过 50MB 已跳过");
                        if (!r.failed.isEmpty()) sb.append("，失败：").append(r.failed);
                        toast(sb.toString());
                        if (r.ok > 0 && input.getText().toString().trim().isEmpty()) {
                            input.setText("我刚往工作区放了 " + r.ok + " 个文件，先看看它们是什么");
                            input.setSelection(input.getText().length());
                        }
                    }
                });
            }
        }, "dsh-share-import").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        agent.setListener(this);
        String cur = cfg.currentSessionId();
        Session s = agent.session();
        if (s == null || cur == null || !cur.equals(s.id)) {
            if (cur != null) agent.openSession(cur);
            else agent.currentSession();
        }
        renderAll();
        updateBusy(agent.isRunning());
    }

    @Override
    protected void onPause() {
        super.onPause();
        agent.setListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        agent.setListener(null);
    }

    // ------------------------------------------------------------------ view

    private void renderAll() {
        feed.removeAllViews();
        rows.clear();

        Session s = agent.currentSession();
        title.setText(s.displayTitle());

        if (s.messages.isEmpty()) {
            feed.addView(emptyState);
        } else {
            lastVisibleRole = null;
            for (Message m : s.messages) {
                if (Message.ROLE_TOOL.equals(m.role)) continue;
                addRow(m, m.isAssistant() && Message.ROLE_ASSISTANT.equals(lastVisibleRole));
                lastVisibleRole = m.role;
            }
        }
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void addRow(Message m, boolean compact) {
        MessageRow row = new MessageRow(this, maxBubble, compact);
        row.bind(m);
        rows.put(m, row);
        feed.addView(row);
    }

    private void removeRow(Message m) {
        MessageRow row = rows.remove(m);
        if (row != null) feed.removeView(row);
        if (rows.isEmpty()) {
            feed.removeAllViews();
            feed.addView(emptyState);
        }
    }

    private void updateBusy(boolean busy) {
        updateSendButton(busy);
        title.setText(agent.currentSession().displayTitle());
        if (busy) {
            status.setText("执行中…");
            return;
        }
        Provider p = cfg.providers().active();
        String state;
        if (!p.hasKey()) state = "需要 Key";
        else if (p.model == null || p.model.trim().isEmpty()) state = "需要选模型";
        else state = "就绪";
        status.setText(p.name + " · " + state);
    }

    /** 发送键三态：0 空输入（灰）/ 1 可发送（蓝）/ 2 执行中（停止） */
    private void updateSendButton(boolean busy) {
        if (send == null) return;
        boolean hasText = input != null && input.getText().toString().trim().length() > 0;
        int state = busy ? 2 : ((hasText || !pending.isEmpty()) ? 1 : 0);
        if (sendState == state) return;
        sendState = state;

        if (state == 2) {
            send.setImageResource(R.drawable.ic_stop_24);
            send.setBackgroundResource(R.drawable.bg_stop_button);
            send.setImageTintList(null);
            send.setContentDescription("停止");
        } else if (state == 1) {
            send.setImageResource(R.drawable.ic_send_24);
            send.setBackgroundResource(R.drawable.bg_send_button);
            send.setImageTintList(null);
            send.setContentDescription("发送");
        } else {
            send.setImageResource(R.drawable.ic_send_24);
            send.setBackgroundResource(R.drawable.bg_send_idle_button);
            send.setImageTintList(android.content.res.ColorStateList.valueOf(
                    getResources().getColor(R.color.text_dim, null)));
            send.setContentDescription("发送");
        }
    }

    private void buildEmptyState() {
        LinearLayout box = new LinearLayout(this);
        emptyState = box;
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(72), 0, 0);

        TextView logo = new TextView(this);
        logo.setText("DSH");
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextColor(color(R.color.text));
        logo.setLetterSpacing(0.2f);
        logo.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(logo);

        TextView tag = new TextView(this);
        tag.setText("手机上的 AI 编程代理");
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tag.setTextColor(color(R.color.text_dim));
        tag.setLetterSpacing(0.04f);
        tag.setGravity(Gravity.CENTER_HORIZONTAL);
        tag.setPadding(0, dp(10), 0, 0);
        box.addView(tag);

        TextView hint = new TextView(this);
        hint.setText("读写工作区文件 · 执行 shell · 访问网络\n说个目标，我来动手");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hint.setTextColor(color(R.color.text_faint));
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setLineSpacing(dp(5), 1f);
        hint.setPadding(0, dp(22), 0, dp(30));
        box.addView(hint);

        addSuggestionRow(box, "看看工作区里有什么", "写个快排存成 qsort.py");
        addSuggestionRow(box, "查一下手机型号和存储", "用 shell 看看系统版本");
    }

    /** 两列等宽的建议按钮，比竖排四个胶囊更克制 */
    private void addSuggestionRow(LinearLayout parent, String left, String right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);

        TextView l = makeChip(left);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        llp.rightMargin = dp(4);
        row.addView(l, llp);

        TextView r = makeChip(right);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        rlp.leftMargin = dp(4);
        row.addView(r, rlp);

        parent.addView(row, rowLp);
    }

    private TextView makeChip(final String text) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        chip.setTextColor(color(R.color.text_dim));
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText(text);
                doSend();
            }
        });
        return chip;
    }

    // ----------------------------------------------------------------- logic

    private void doSend() {
        String text = input.getText().toString().trim();
        boolean hasAttachments = !pending.isEmpty();
        if (text.isEmpty() && !hasAttachments) return;
        if (!cfg.hasApiKey()) {
            promptKey(false);
            return;
        }
        if (agent.isRunning()) {
            toast("当前任务还在执行，先停止再发送。");
            return;
        }
        hideKeyboard();
        input.setText("");
        java.util.List<Attachment> atts = new java.util.ArrayList<>(pending);
        pending.clear();
        renderAttachBar();
        updateSendButton(false);
        if (emptyState.getParent() != null) feed.removeView(emptyState);
        follow = true;
        agent.send(text, atts);
    }

    // --------------------------------------------------------------- 对话附件

    private void pickAttachments() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_ATTACH);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ATTACH || resultCode != RESULT_OK || data == null) return;
        final java.util.List<Uri> uris = new java.util.ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        importAttachments(uris);
    }

    private void importAttachments(final java.util.List<Uri> uris) {
        toast("正在处理 " + uris.size() + " 个附件…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<Attachment> ok = new java.util.ArrayList<>();
                final StringBuilder failed = new StringBuilder();
                for (Uri u : uris) {
                    try {
                        ok.add(Attachments.importUri(MainActivity.this, u));
                    } catch (Exception e) {
                        if (failed.length() < 80) {
                            String last = u.getLastPathSegment();
                            failed.append(last == null ? "文件" : last).append(' ');
                        }
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pending.addAll(ok);
                        renderAttachBar();
                        updateSendButton(agent.isRunning());
                        if (!failed.toString().trim().isEmpty()) {
                            toast("部分文件无法添加：" + failed.toString().trim());
                        }
                        boolean hasImage = false;
                        for (Attachment a : ok) if (a.isImage()) hasImage = true;
                        if (hasImage && !cfg.providers().active().vision) {
                            toast("图片需要渠道开启「支持图片输入」；当前只会发送文件名说明。");
                        }
                    }
                });
            }
        }, "dsh-attach").start();
    }

    private void renderAttachBar() {
        if (attachBar == null) return;
        attachBar.removeAllViews();
        boolean empty = pending.isEmpty();
        attachScroller.setVisibility(empty ? View.GONE : View.VISIBLE);
        for (int i = 0; i < pending.size(); i++) {
            final Attachment a = pending.get(i);
            TextView chip = new TextView(this);
            chip.setText(a.name + "  ·  " + Attachments.human(a.size) + "   ×");
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            chip.setTextColor(color(R.color.text_dim));
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            chip.setMaxWidth(dp(220));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pending.remove(a);
                    renderAttachBar();
                    updateSendButton(agent.isRunning());
                }
            });
            attachBar.addView(chip);
        }
    }

    private void promptKey(final boolean firstRun) {
        final Provider provider = cfg.providers().active();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);

        TextView tip = new TextView(this);
        tip.setText("当前渠道：" + provider.name + "\n"
                + (provider.baseUrl == null || provider.baseUrl.isEmpty() ? "还没有填接口地址" : provider.baseUrl)
                + "\n\nKey 只保存在本机。不同渠道的 Key 各自独立，切换不会互相覆盖。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tip.setTextColor(color(R.color.text_dim));
        tip.setLineSpacing(dp(2), 1f);
        box.addView(tip);

        final EditText et = new EditText(this);
        et.setHint("sk-...");
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        et.setTextColor(color(R.color.text));
        et.setBackgroundResource(R.drawable.bg_input);
        et.setPadding(dp(14), dp(11), dp(14), dp(11));
        et.setText(provider.apiKey);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        et.setLayoutParams(lp);
        box.addView(et);

        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("填写 " + provider.name + " 的 Key")
                .setView(box)
                .setPositiveButton("保存", null)
                .setNegativeButton("稍后", null)
                .setNeutralButton("管理渠道", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        startActivity(new Intent(MainActivity.this, ProvidersActivity.class));
                    }
                });
        final AlertDialog dialog = b.create();
        dialog.setCanceledOnTouchOutside(!firstRun);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String key = et.getText().toString().trim();
                if (key.isEmpty()) {
                    toast("Key 不能为空");
                    return;
                }
                cfg.setApiKey(key);
                dialog.dismiss();
                updateBusy(agent.isRunning());
                toast("已保存，可以开始了");
            }
        });
    }

    // --------------------------------------------------------------- listener

    @Override
    public void onAppend(Message m) {
        if (Message.ROLE_TOOL.equals(m.role)) return; // 工具结果折叠进调用卡片
        if (rows.containsKey(m)) return;
        if (emptyState.getParent() != null) feed.removeView(emptyState);
        addRow(m, m.isAssistant() && Message.ROLE_ASSISTANT.equals(lastVisibleRole));
        lastVisibleRole = m.role;
        if (follow) scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    @Override
    public void onUpdate(Message m) {
        MessageRow row = rows.get(m);
        if (row == null) {
            addRow(m, m.isAssistant() && Message.ROLE_ASSISTANT.equals(lastVisibleRole));
            lastVisibleRole = m.role;
            row = rows.get(m);
        }
        if (row != null) row.render();
        if (follow) scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, feed.getHeight());
            }
        });
    }

    @Override
    public void onRemove(Message m) {
        removeRow(m);
    }

    @Override
    public void onBusy(boolean busy) {
        updateBusy(busy);
        if (busy) follow = true;
    }

    @Override
    public void onError(String message) {
        toast(message);
    }

    @Override
    public void onNotice(String message) {
        toast(message);
    }

    // ----------------------------------------------------------------- utils

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int color(int res) {
        return getResources().getColor(res, null);
    }
}

package com.dsh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.mobile.core.SessionStore;
import com.dsh.mobile.core.WorkspaceImporter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 工作区文件管理：浏览 / 从手机导入 / 新建 / 预览 / 重命名 / 删除。
 * Agent 的工具就是对着这个目录工作的，所以这里展示的是相对路径。
 */
public class FilesActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final long MAX_IMPORT = 50L * 1024 * 1024; // 单个文件 50MB 上限
    private static final int PREVIEW_LIMIT = 120 * 1024;
    private static final SimpleDateFormat DAY = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);

    private File root;
    private File current;
    private String relPath = "";

    private ListView list;
    private TextView empty;
    private TextView pathView;
    private final List<File> data = new ArrayList<>();
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_files);

        root = new SessionStore(this).workspace();
        relPath = getIntent().getStringExtra("path");
        if (relPath == null) relPath = "";
        current = resolveDir(relPath);
        if (current == null) {
            current = root;
            relPath = "";
        }

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        pathView = findViewById(R.id.path);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goBack();
            }
        });
        findViewById(R.id.btnAdd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddMenu();
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
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_file, parent, false);
                File f = data.get(position);
                ImageView icon = v.findViewById(R.id.icon);
                TextView name = v.findViewById(R.id.name);
                TextView meta = v.findViewById(R.id.meta);

                boolean dir = f.isDirectory();
                icon.setImageResource(dir ? R.drawable.ic_folder_24 : R.drawable.ic_file_24);
                icon.setImageTintList(ColorStateList.valueOf(
                        getResources().getColor(dir ? R.color.text_dim : R.color.text_faint, null)));
                name.setText(dir ? f.getName() + "/" : f.getName());
                meta.setText(dir ? "文件夹" : humanSize(f.length()) + " · " + DAY.format(new Date(f.lastModified())));
                return v;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                File f = data.get(position);
                if (f.isDirectory()) {
                    relPath = relOf(f);
                    current = f;
                    load();
                } else {
                    preview(f);
                }
            }
        });

        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                showFileActions(data.get(position));
                return true;
            }
        });

        load();
    }

    @Override
    public void onBackPressed() {
        if (!goBack()) super.onBackPressed();
    }

    /** @return true 表示已经回到上一级 */
    private boolean goBack() {
        if (relPath.isEmpty()) return false;
        int idx = relPath.lastIndexOf('/');
        relPath = idx > 0 ? relPath.substring(0, idx) : "";
        current = resolveDir(relPath);
        if (current == null) current = root;
        load();
        return true;
    }

    // ------------------------------------------------------------- 列表 / 状态

    private void load() {
        data.clear();
        File[] files = current.listFiles();
        if (files != null) {
            for (File f : files) data.add(f);
        }
        Collections.sort(data, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        adapter.notifyDataSetChanged();
        boolean isEmpty = data.isEmpty();
        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        pathView.setText(relPath.isEmpty() ? "/" : "/" + relPath);
    }

    private File resolveDir(String rel) {
        try {
            File f = rel.isEmpty() ? root : new File(root, rel);
            String rootPath = root.getCanonicalPath();
            String path = f.getCanonicalPath();
            if (!path.equals(rootPath) && !path.startsWith(rootPath + File.separator)) return null;
            if (!f.exists() || !f.isDirectory()) return null;
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    private String relOf(File f) {
        try {
            String rootPath = root.getCanonicalPath();
            String path = f.getCanonicalPath();
            if (path.equals(rootPath)) return "";
            if (path.startsWith(rootPath + File.separator)) {
                return path.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
            }
            return f.getName();
        } catch (IOException e) {
            return f.getName();
        }
    }

    // ------------------------------------------------------------------ 添加

    private void showAddMenu() {
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("添加到 " + (relPath.isEmpty() ? "工作区根目录" : "/" + relPath))
                .setItems(new String[]{"从手机添加文件", "新建文本文件", "新建文件夹"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) pickFiles();
                        else if (which == 1) newFileDialog();
                        else newFolderDialog();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
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
        importUris(uris, current);
    }

    private void importUris(final List<Uri> uris, final File dir) {
        toast("正在导入 " + uris.size() + " 个文件…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final WorkspaceImporter.Result r =
                        WorkspaceImporter.importTo(FilesActivity.this, dir, uris, MAX_IMPORT);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        load();
                        StringBuilder sb = new StringBuilder();
                        sb.append("已添加 ").append(r.ok).append(" 个文件");
                        if (r.skipped > 0) sb.append("，").append(r.skipped).append(" 个超过 50MB 已跳过");
                        if (!r.failed.isEmpty()) sb.append("，失败：").append(r.failed);
                        toast(sb.toString());
                    }
                });
            }
        }, "dsh-import").start();
    }

    // ------------------------------------------------------------------ 新建

    private void newFileDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);

        final EditText name = field("文件名，如 notes.md");
        box.addView(name);

        TextView label = new TextView(this);
        label.setText("初始内容（可留空）");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setTextColor(getResources().getColor(R.color.text_faint, null));
        label.setPadding(0, dp(12), 0, dp(6));
        box.addView(label);

        final EditText content = field("在这里输入…");
        content.setGravity(Gravity.TOP | Gravity.START);
        content.setMinLines(4);
        content.setSingleLine(false);
        content.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(content);

        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("新建文件")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", null);
        final AlertDialog d = b.create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) {
                    toast("文件名不能为空");
                    return;
                }
                File f = new File(current, n);
                if (f.exists()) {
                    toast("已存在同名文件");
                    return;
                }
                try {
                    if (!f.createNewFile()) throw new IOException("创建失败");
                    String text = content.getText().toString();
                    if (!text.isEmpty()) {
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            out.write(text.getBytes("UTF-8"));
                        }
                    }
                    d.dismiss();
                    load();
                    toast("已创建 " + n);
                } catch (Exception e) {
                    toast("创建失败：" + e.getMessage());
                }
            }
        });
    }

    private void newFolderDialog() {
        final EditText name = field("文件夹名");
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("新建文件夹")
                .setView(wrap(name))
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) return;
                        File f = new File(current, n);
                        if (f.exists()) {
                            toast("已存在同名文件夹");
                        } else if (f.mkdirs()) {
                            load();
                            toast("已创建 " + n);
                        } else {
                            toast("创建失败");
                        }
                    }
                })
                .show();
    }

    private EditText field(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        et.setTextColor(getResources().getColor(R.color.text, null));
        et.setHintTextColor(getResources().getColor(R.color.text_faint, null));
        et.setBackgroundResource(R.drawable.bg_field);
        et.setPadding(dp(16), dp(13), dp(16), dp(13));
        return et;
    }

    private View wrap(View child) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        box.addView(child);
        return box;
    }

    // ------------------------------------------------------------- 文件操作

    private void showFileActions(final File f) {
        String[] items = {f.isDirectory() ? "重命名文件夹" : "重命名", "删除", "复制相对路径"};
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle(f.isDirectory() ? f.getName() + "/" : f.getName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) rename(f);
                        else if (which == 1) confirmDelete(f);
                        else copyPath(f);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void rename(final File f) {
        final EditText input = field("新名称");
        input.setText(f.getName());
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("重命名")
                .setView(wrap(input))
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String n = input.getText().toString().trim();
                        if (n.isEmpty() || n.equals(f.getName())) return;
                        File dst = new File(f.getParentFile(), n);
                        if (dst.exists()) {
                            toast("已存在同名项");
                            return;
                        }
                        if (f.renameTo(dst)) {
                            load();
                            toast("已重命名");
                        } else {
                            toast("重命名失败");
                        }
                    }
                })
                .show();
    }

    private void confirmDelete(final File f) {
        String msg = f.isDirectory()
                ? "「" + f.getName() + "/」及其内部所有文件都会被删除，无法恢复。"
                : "「" + f.getName() + "」将被删除，无法恢复。";
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle("删除")
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        boolean ok = deleteRecursive(f);
                        load();
                        toast(ok ? "已删除" : "部分文件删除失败");
                    }
                })
                .show();
    }

    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        return f.delete();
    }

    private void copyPath(File f) {
        String rel = relOf(f);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("dsh-path", rel));
            toast("已复制路径：" + rel);
        }
    }

    private void preview(final File f) {
        if (f.length() > PREVIEW_LIMIT) {
            showInfo(f, "文件较大（" + humanSize(f.length()) + "），未预览。\n可以让 Agent 用 read_file 分段读。");
            return;
        }
        String text;
        try {
            text = readText(f);
        } catch (Exception e) {
            showInfo(f, "读取失败：" + e.getMessage());
            return;
        }
        if (looksBinary(text)) {
            showInfo(f, "二进制文件（" + humanSize(f.length()) + "），无法以文本预览。");
            return;
        }

        TextView tv = new TextView(this);
        tv.setText(text.isEmpty() ? "（空文件）" : text);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(getResources().getColor(R.color.text, null));
        tv.setLineSpacing(dp(4), 1f);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(18), dp(14), dp(18), dp(14));

        ScrollView sc = new ScrollView(this);
        sc.addView(tv);
        sc.setBackgroundResource(R.drawable.bg_code);

        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        int boxHeight = Math.min(dp(360), dp(30) + lines * dp(19));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(6), dp(16), 0);
        box.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, boxHeight));

        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle(relOf(f) + "  ·  " + humanSize(f.length()))
                .setView(box)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制路径", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyPath(f);
                    }
                })
                .show();
    }

    private void showInfo(File f, String msg) {
        new AlertDialog.Builder(this, R.style.DshDialog)
                .setTitle(relOf(f))
                .setMessage(msg)
                .setPositiveButton("好", null)
                .show();
    }

    private String readText(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), PREVIEW_LIMIT)];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        }
    }

    private boolean looksBinary(String s) {
        if (s.isEmpty()) return false;
        int bad = 0;
        int n = Math.min(s.length(), 4096);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\u0000') bad += 10;
            else if (c < 9 || (c > 13 && c < 32)) bad++;
        }
        return bad * 100 / Math.max(1, n) > 5;
    }

    // ------------------------------------------------------------------ utils

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}

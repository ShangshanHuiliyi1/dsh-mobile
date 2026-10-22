package com.dsh.mobile.core;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 手机端可用的本地工具集。所有文件操作都被限制在应用私有工作区内。
 */
public class LocalTools {

    private static final int MAX_READ = 400 * 1024;
    private static final int MAX_OUT = 120 * 1024;

    private final File root;
    private final Context ctx;
    private static JSONArray cachedDefs;

    public LocalTools(Context c, File root) {
        this.ctx = c.getApplicationContext();
        this.root = root;
    }

    public File root() {
        return root;
    }

    // ---------------------------------------------------------------- schema

    public static JSONArray definitions() {
        if (cachedDefs != null) return cachedDefs;
        JSONArray arr = new JSONArray();
        try {
            arr.put(tool("read_file", "读取工作区内的文本文件内容。",
                    props("path", "string", "文件路径，相对工作区根目录，例如 notes/todo.md"),
                    "path"));

            arr.put(tool("write_file", "写入（覆盖）工作区内的文件，会自动创建父目录。",
                    merge(props("path", "string", "文件路径"),
                            props("content", "string", "要写入的完整文本内容")),
                    "path", "content"));

            arr.put(tool("replace_in_file", "在文件中做精确字符串替换，适合修改代码。",
                    merge(merge(props("path", "string", "文件路径"),
                                    props("find", "string", "要被替换的原文（需完整匹配，含缩进）")),
                            merge(props("replace", "string", "替换后的文本"),
                                    props("all", "boolean", "是否替换所有出现位置，默认只替换第一处"))),
                    "path", "find", "replace"));

            arr.put(tool("list_dir", "列出目录内容（目录在前，带文件大小）。",
                    props("path", "string", "目录路径，默认工作区根目录"),
                    "path"));

            arr.put(tool("delete_file", "删除工作区内的文件或空目录。",
                    props("path", "string", "要删除的路径"), "path"));

            arr.put(tool("run_shell", "在 Android 上用 /system/bin/sh 执行 shell 命令，工作目录为工作区根目录。"
                            + "适合查看系统信息、文件、进程等；无 root 权限，无法访问其他应用私有数据。",
                    merge(props("command", "string", "要执行的 shell 命令"),
                            props("timeout_sec", "integer", "超时秒数，默认 20，最大 120")),
                    "command"));

            arr.put(tool("http_request", "发起 HTTP 请求并返回状态码、响应头和响应正文（截断到 120KB）。",
                    merge(merge(props("url", "string", "完整 http/https 地址"),
                                    props("method", "string", "GET 或 POST，默认 GET")),
                            props("body", "string", "POST 时的请求体")),
                    "url"));

            arr.put(tool("device_info", "查看当前手机型号、Android 版本、屏幕与存储情况。",
                    new JSONObject()));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        cachedDefs = arr;
        return arr;
    }

    private static JSONObject props(String name, String type, String desc) throws JSONException {
        JSONObject p = new JSONObject().put("type", type).put("description", desc);
        if ("integer".equals(type)) p.put("type", "integer");
        return new JSONObject().put(name, p);
    }

    private static JSONObject merge(JSONObject a, JSONObject b) throws JSONException {
        JSONObject out = new JSONObject();
        java.util.Iterator<String> it = a.keys();
        while (it.hasNext()) {
            String k = it.next();
            out.put(k, a.get(k));
        }
        it = b.keys();
        while (it.hasNext()) {
            String k = it.next();
            out.put(k, b.get(k));
        }
        return out;
    }

    private static JSONObject tool(String name, String desc, JSONObject props, String... required)
            throws JSONException {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", desc);
        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", props);
        if (required.length > 0) {
            JSONArray req = new JSONArray();
            for (String r : required) req.put(r);
            params.put("required", req);
        }
        fn.put("parameters", params);
        return new JSONObject().put("type", "function").put("function", fn);
    }

    // -------------------------------------------------------------- dispatch

    public String execute(String name, String argsJson) {
        JSONObject a;
        try {
            a = (argsJson == null || argsJson.trim().isEmpty()) ? new JSONObject() : new JSONObject(argsJson);
        } catch (JSONException e) {
            return "ERROR: 参数不是合法 JSON：" + truncate(String.valueOf(argsJson), 300);
        }
        try {
            if ("read_file".equals(name)) return readFile(a);
            if ("write_file".equals(name)) return writeFile(a);
            if ("replace_in_file".equals(name)) return replaceInFile(a);
            if ("list_dir".equals(name)) return listDir(a);
            if ("delete_file".equals(name)) return delete(a);
            if ("run_shell".equals(name)) return runShell(a);
            if ("http_request".equals(name)) return http(a);
            if ("device_info".equals(name)) return deviceInfo();
            return "ERROR: 未知工具 " + name;
        } catch (Exception e) {
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ----------------------------------------------------------------- tools

    private String readFile(JSONObject a) throws IOException {
        File f = resolve(a.optString("path", ""));
        if (!f.exists()) return "ERROR: 文件不存在: " + rel(f);
        if (f.isDirectory()) return listDir(f);
        if (f.length() > MAX_READ) return "ERROR: 文件过大（" + f.length() + " 字节），请用 run_shell 分段查看。";
        String text = readText(f);
        return "[" + rel(f) + "] " + f.length() + " 字节\n" + text;
    }

    private String writeFile(JSONObject a) throws IOException {
        File f = resolve(a.optString("path", ""));
        if (f.isDirectory()) return "ERROR: 目标是目录: " + rel(f);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return "ERROR: 无法创建目录: " + rel(parent);
        String content = a.optString("content", "");
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
        int lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
        return "OK 已写入 " + rel(f) + "（" + content.getBytes(StandardCharsets.UTF_8).length
                + " 字节，" + lines + " 行）";
    }

    private String replaceInFile(JSONObject a) throws IOException {
        File f = resolve(a.optString("path", ""));
        if (!f.exists()) return "ERROR: 文件不存在: " + rel(f);
        if (f.length() > MAX_READ) return "ERROR: 文件过大，改用 run_shell + sed。";
        String find = a.optString("find", "");
        if (find.isEmpty()) return "ERROR: find 不能为空。";
        String replace = a.optString("replace", "");
        boolean all = a.optBoolean("all", false);
        String text = readText(f);
        int count = 0;
        if (all) {
            int idx = 0;
            StringBuilder sb = new StringBuilder();
            while (true) {
                int at = text.indexOf(find, idx);
                if (at < 0) {
                    sb.append(text, idx, text.length());
                    break;
                }
                sb.append(text, idx, at).append(replace);
                idx = at + find.length();
                count++;
            }
            text = sb.toString();
        } else {
            int at = text.indexOf(find);
            if (at >= 0) {
                text = text.substring(0, at) + replace + text.substring(at + find.length());
                count = 1;
            }
        }
        if (count == 0) return "没有找到要替换的内容，文件未修改。";
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return "OK 已替换 " + count + " 处，文件: " + rel(f);
    }

    private String listDir(JSONObject a) throws IOException {
        return listDir(resolve(a.optString("path", ".")));
    }

    private String listDir(File dir) {
        if (!dir.exists()) return "ERROR: 目录不存在: " + rel(dir);
        if (!dir.isDirectory()) return "ERROR: 不是目录: " + rel(dir);
        File[] fs = dir.listFiles();
        if (fs == null) return "ERROR: 无法读取目录: " + rel(dir);
        List<File> files = new ArrayList<>(Arrays.asList(fs));
        Collections.sort(files, (x, y) -> {
            if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
            return x.getName().compareToIgnoreCase(y.getName());
        });
        if (files.isEmpty()) return "目录 " + rel(dir) + " 是空的。";
        StringBuilder sb = new StringBuilder();
        sb.append("目录 ").append(rel(dir)).append("（").append(files.size()).append(" 项）\n");
        int n = 0;
        for (File f : files) {
            if (n++ >= 300) {
                sb.append("…（超过 300 项，已截断）\n");
                break;
            }
            sb.append(f.isDirectory() ? "d  " : "-  ").append(f.getName());
            if (f.isDirectory()) sb.append("/");
            else sb.append("  ").append(humanSize(f.length()));
            sb.append("\n");
        }
        return sb.toString();
    }

    private String delete(JSONObject a) throws IOException {
        File f = resolve(a.optString("path", ""));
        if (f.equals(root)) return "ERROR: 不能删除工作区根目录。";
        if (!f.exists()) return "ERROR: 路径不存在: " + rel(f);
        if (f.isDirectory()) {
            String[] children = f.list();
            if (children != null && children.length > 0) return "ERROR: 目录非空，先删除里面的文件: " + rel(f);
        }
        boolean ok = f.delete();
        return ok ? "OK 已删除 " + rel(f) : "ERROR: 删除失败: " + rel(f);
    }

    private String runShell(JSONObject a) {
        final String cmd = a.optString("command", "").trim();
        if (cmd.isEmpty()) return "ERROR: command 为空。";
        int timeout = Math.min(Math.max(a.optInt("timeout_sec", 20), 1), 120);
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.directory(root);
            pb.redirectErrorStream(true);
            p = pb.start();
            final StringBuilder sb = new StringBuilder();
            final InputStream in = p.getInputStream();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[4096];
                    try {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            String piece = new String(buf, 0, n, StandardCharsets.UTF_8);
                            synchronized (sb) {
                                if (sb.length() < MAX_OUT) sb.append(piece);
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }, "shell-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                reader.join(400);
                synchronized (sb) {
                    return "TIMEOUT（" + timeout + " 秒未结束，已终止）\n" + truncate(sb.toString(), MAX_OUT);
                }
            }
            reader.join(800);
            int code = p.exitValue();
            String out;
            synchronized (sb) {
                out = sb.toString();
            }
            if (out.isEmpty()) out = "（无输出）";
            else if (out.length() > MAX_OUT) out = out.substring(0, MAX_OUT) + "\n…（输出已截断）";
            return "exit=" + code + "\n" + out;
        } catch (Exception e) {
            return "ERROR: 命令执行失败: " + e;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private String http(JSONObject a) {
        String urlStr = a.optString("url", "").trim();
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            return "ERROR: 只支持 http/https 地址。";
        }
        String method = a.optString("method", "GET").trim().toUpperCase();
        String body = a.optString("body", "");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("GET".equals(method) ? "GET" : "POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "DSH-Mobile/1.0 (Android)");
            c.setRequestProperty("Accept", "*/*");
            if (!"GET".equals(method)) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type",
                        a.optString("content_type", "application/json; charset=utf-8"));
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readStream(in, MAX_OUT);
            String ctype = c.getContentType();
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP ").append(code);
            if (ctype != null) sb.append("  ").append(ctype);
            sb.append("\n\n");
            sb.append(text.isEmpty() ? "（空响应）" : truncate(text, MAX_OUT));
            return sb.toString();
        } catch (Exception e) {
            return "ERROR: 请求失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String deviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("品牌/设备代号: ").append(Build.BRAND).append(" / ").append(Build.DEVICE).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        sb.append("屏幕: ").append(dm.widthPixels).append("×").append(dm.heightPixels)
                .append(" @").append(dm.densityDpi).append("dpi\n");
        try {
            StatFs fs = new StatFs(root.getAbsolutePath());
            long free = fs.getAvailableBytes();
            long total = fs.getTotalBytes();
            sb.append("工作区: ").append(root.getAbsolutePath()).append("\n");
            sb.append("存储: 可用 ").append(humanSize(free)).append(" / 共 ").append(humanSize(total)).append("\n");
        } catch (Exception ignored) {
        }
        sb.append("工作区文件数: ").append(countFiles(root)).append("\n");
        sb.append("当前时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date()));
        return sb.toString();
    }

    private int countFiles(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) n += f.isDirectory() ? countFiles(f) : 1;
        return n;
    }

    // --------------------------------------------------------------- helpers

    private File resolve(String p) throws IOException {
        if (p == null) p = "";
        p = p.trim();
        if (p.isEmpty()) p = ".";
        File f = new File(p);
        if (!f.isAbsolute()) f = new File(root, p);
        String canonRoot = root.getCanonicalPath();
        String canon = f.getCanonicalPath();
        if (!canon.equals(canonRoot) && !canon.startsWith(canonRoot + File.separator)) {
            throw new IOException("路径超出工作区范围：" + p);
        }
        return f;
    }

    private String rel(File f) {
        try {
            String rootPath = root.getCanonicalPath();
            String path = f.getCanonicalPath();
            if (path.equals(rootPath)) return ".";
            if (path.startsWith(rootPath + File.separator)) return path.substring(rootPath.length() + 1);
            return path;
        } catch (IOException e) {
            return f.getName();
        }
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readStream(in, MAX_READ);
        }
    }

    private static String readStream(InputStream in, int max) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
            if (sb.length() > max) break;
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1fK", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1fM", bytes / 1048576.0);
        return String.format(java.util.Locale.US, "%.1fG", bytes / 1073741824.0);
    }
}

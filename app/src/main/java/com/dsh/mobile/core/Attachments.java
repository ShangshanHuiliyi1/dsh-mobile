package com.dsh.mobile.core;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * 对话附件的导入与读取。
 * 文本文件在发请求时把内容注入消息；图片在渠道支持多模态时按 base64 发送。
 */
public final class Attachments {

    public static final int MAX_TEXT_INLINE = 60 * 1024;      // 单个文本注入上限
    public static final int MAX_TEXT_TOTAL = 200 * 1024;      // 一次请求所有文本合计上限
    public static final long MAX_IMAGE = 4L * 1024 * 1024;     // 单张图片上限
    public static final long MAX_ANY = 8L * 1024 * 1024;       // 其它文件上限

    private static final String[] TEXT_EXT = {
            "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "yml", "yaml", "xml",
            "html", "htm", "css", "js", "mjs", "ts", "jsx", "tsx", "vue", "py", "java", "kt",
            "c", "h", "cc", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bash", "bat",
            "ps1", "sql", "ini", "conf", "cfg", "toml", "properties", "gradle", "log", "srt",
            "tex", "gitignore", "env"
    };

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "attachments");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 把选择器的 content:// 复制到应用私有目录，并判定类型 */
    public static Attachment importUri(Context c, Uri uri) throws IOException {
        String name = WorkspaceImporter.displayName(c, uri);
        String mime = "";
        try {
            String t = c.getContentResolver().getType(uri);
            if (t != null) mime = t;
        } catch (Exception ignored) {
        }
        String kind = classify(mime, name);

        File folder = new File(dir(c), java.util.UUID.randomUUID().toString());
        if (!folder.mkdirs()) throw new IOException("无法创建附件目录");
        File dst = WorkspaceImporter.uniqueFile(folder, name);

        long total = 0;
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("无法打开文件");
            out = new FileOutputStream(dst);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_ANY) throw new IOException("文件超过 8MB");
                out.write(buf, 0, n);
            }
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }

        Attachment a = new Attachment();
        a.name = dst.getName();
        a.mime = mime.isEmpty() ? guessMime(name) : mime;
        a.kind = kind;
        a.localPath = dst.getAbsolutePath();
        a.size = total;
        if (a.isImage() && total > MAX_IMAGE) {
            dst.delete();
            throw new IOException("图片超过 4MB");
        }
        return a;
    }

    private static String classify(String mime, String name) {
        if (mime != null && mime.startsWith("image/")) return Attachment.KIND_IMAGE;
        String ext = extOf(name);
        for (String e : TEXT_EXT) {
            if (e.equals(ext)) return Attachment.KIND_TEXT;
        }
        if (mime != null && (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml"))) {
            return Attachment.KIND_TEXT;
        }
        return Attachment.KIND_OTHER;
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.US);
    }

    private static String guessMime(String name) {
        String ext = extOf(name);
        if ("png".equals(ext)) return "image/png";
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        if ("webp".equals(ext)) return "image/webp";
        if ("gif".equals(ext)) return "image/gif";
        return "application/octet-stream";
    }

    /** 读取文本内容，超过上限则截断 */
    public static String readText(Attachment a, int max) {
        if (a == null || a.localPath == null) return "";
        File f = new File(a.localPath);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), max + 1)];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            a.truncated = off > max;
            return new String(buf, 0, Math.min(off, max), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static String base64(Attachment a) {
        if (a == null || a.localPath == null) return null;
        File f = new File(a.localPath);
        if (!f.exists() || f.length() > MAX_IMAGE) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 拼出发给模型的文本：正文 + 文本附件内容 + 二进制/图片的说明。
     * @param vision 当前渠道是否支持图片输入（支持时图片走 image_url，不再写文字说明）
     */
    public static String composeText(String userText, List<Attachment> atts, boolean vision) {
        StringBuilder sb = new StringBuilder(userText == null ? "" : userText);
        if (atts == null || atts.isEmpty()) return sb.toString();

        int budget = MAX_TEXT_TOTAL;
        for (Attachment a : atts) {
            sb.append("\n\n");
            if (a.isText()) {
                sb.append("【附件 ").append(a.name).append(" · ").append(human(a.size)).append("】\n");
                sb.append("<<<附件内容开始>>>\n");
                String t = readText(a, Math.min(MAX_TEXT_INLINE, Math.max(1024, budget)));
                if (t.startsWith("\uFEFF")) t = t.substring(1);
                sb.append(t);
                if (a.truncated) sb.append("\n…（内容过长，已截断）");
                sb.append("\n<<<附件内容结束>>>");
                budget -= Math.min(t.length(), MAX_TEXT_INLINE);
            } else if (a.isImage()) {
                if (!vision) {
                    sb.append("（图片 ").append(a.name).append("，").append(human(a.size))
                            .append("；当前渠道未开启图片输入，只能看到文件名，看不到画面。）");
                }
            } else {
                sb.append("（附件 ").append(a.name).append("，").append(human(a.size))
                        .append("，").append(a.mime).append("；二进制内容未包含。）");
            }
        }
        return sb.toString();
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}

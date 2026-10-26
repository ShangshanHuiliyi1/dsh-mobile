package com.dsh.mobile.core;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把 content:// 文件复制进工作区。
 * 系统分享进来的文件、文件选择器选的文件都走这里。
 */
public final class WorkspaceImporter {

    public static class Result {
        public int ok;
        public int skipped;
        public String failed = "";
    }

    public static Result importTo(Context ctx, File dir, java.util.List<Uri> uris, long maxBytes) {
        Result r = new Result();
        StringBuilder failed = new StringBuilder();
        if (dir == null || uris == null) return r;
        if (!dir.exists()) dir.mkdirs();

        for (Uri u : uris) {
            if (u == null) continue;
            String name = displayName(ctx, u);
            long size = displaySize(ctx, u);
            if (size > maxBytes) {
                r.skipped++;
                continue;
            }
            File dst = uniqueFile(dir, name);
            InputStream in = null;
            OutputStream out = null;
            try {
                in = ctx.getContentResolver().openInputStream(u);
                if (in == null) throw new IOException("无法打开");
                out = new FileOutputStream(dst);
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                r.ok++;
            } catch (Exception e) {
                if (dst.exists()) dst.delete();
                if (failed.length() < 80) failed.append(name).append(' ');
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
        }
        r.failed = failed.toString().trim();
        return r;
    }

    public static String displayName(Context ctx, Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "imported";
        name = name.replace('\\', '_').replace('/', '_').replace("\u0000", "");
        if (name.trim().isEmpty()) name = "imported";
        if (name.length() > 96) name = name.substring(name.length() - 96);
        return name;
    }

    public static long displaySize(Context ctx, Uri uri) {
        if (!"content".equals(uri.getScheme())) return -1;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    public static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            File alt = new File(dir, base + " (" + i + ")" + ext);
            if (!alt.exists()) return alt;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }
}

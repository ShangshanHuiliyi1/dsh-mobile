package com.dsh.mobile.core;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class SessionStore {

    private static final String TAG = "SessionStore";

    private final File dir;
    private final File workspace;

    public SessionStore(Context c) {
        Context app = c.getApplicationContext();
        dir = new File(app.getFilesDir(), "sessions");
        if (!dir.exists()) dir.mkdirs();
        workspace = new File(app.getFilesDir(), "workspace");
        if (!workspace.exists()) workspace.mkdirs();
    }

    public File dir() {
        return dir;
    }

    public File workspace() {
        return workspace;
    }

    public Session create() {
        Session s = new Session();
        s.title = "新会话";
        s.createdAt = System.currentTimeMillis();
        s.updatedAt = s.createdAt;
        save(s);
        return s;
    }

    public void save(Session s) {
        if (s == null) return;
        s.updatedAt = System.currentTimeMillis();
        File tmp = new File(dir, s.id + ".tmp");
        File dst = new File(dir, s.id + ".json");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(s.toJson().toString());
        } catch (Exception e) {
            Log.w(TAG, "save failed: " + e);
            return;
        }
        if (dst.exists() && !dst.delete()) {
            Log.w(TAG, "cannot replace " + dst);
        }
        if (!tmp.renameTo(dst)) {
            Log.w(TAG, "rename failed for " + s.id);
        }
    }

    public Session load(String id) {
        if (id == null) return null;
        File f = new File(dir, id + ".json");
        if (!f.exists()) return null;
        return read(f);
    }

    private Session read(File f) {
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return Session.fromJson(new JSONObject(sb.toString()));
        } catch (Exception e) {
            Log.w(TAG, "read failed " + f.getName() + ": " + e);
            return null;
        }
    }

    public List<Session> all() {
        List<Session> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            Session s = read(f);
            if (s != null) out.add(s);
        }
        Collections.sort(out, new Comparator<Session>() {
            @Override
            public int compare(Session a, Session b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return out;
    }

    public void delete(String id) {
        if (id == null) return;
        File f = new File(dir, id + ".json");
        if (f.exists()) f.delete();
        File tmp = new File(dir, id + ".tmp");
        if (tmp.exists()) tmp.delete();
    }

    public void deleteAll() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
    }
}

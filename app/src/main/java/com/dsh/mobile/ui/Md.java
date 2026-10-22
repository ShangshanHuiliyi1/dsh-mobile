package com.dsh.mobile.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import com.dsh.mobile.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Markdown：围栏代码块、标题、列表、行内代码、粗体、链接。
 * 目标是"看起来像 DSH 的输出"，不是完整 CommonMark 实现。
 */
public final class Md {

    public static class Block {
        public boolean code;
        public String lang = "";
        public String text = "";
    }

    public static List<Block> blocks(String src) {
        List<Block> out = new ArrayList<>();
        if (src == null) return out;
        String[] lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Block cur = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("```")) {
                if (cur != null && cur.code) {
                    out.add(cur);
                    cur = null;
                } else {
                    if (cur != null) out.add(cur);
                    cur = new Block();
                    cur.code = true;
                    cur.lang = t.substring(3).trim();
                }
                continue;
            }
            if (cur == null) {
                cur = new Block();
                cur.code = false;
            }
            if (cur.text.isEmpty()) cur.text = line;
            else cur.text += "\n" + line;
        }
        if (cur != null) out.add(cur);
        return out;
    }

    /** 渲染一段非代码文本，返回带样式的字符序列。 */
    public static CharSequence render(Context ctx, String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (text == null) return sb;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            String line = lines[i];
            int start = sb.length();

            String trimmed = line.trim();
            int heading = 0;
            if (trimmed.startsWith("######")) heading = 6;
            else if (trimmed.startsWith("#####")) heading = 5;
            else if (trimmed.startsWith("####")) heading = 4;
            else if (trimmed.startsWith("###")) heading = 3;
            else if (trimmed.startsWith("##")) heading = 2;
            else if (trimmed.startsWith("#")) heading = 1;

            if (heading > 0) {
                String body = trimmed.substring(heading).trim();
                appendInline(ctx, sb, body);
                int end = sb.length();
                if (end > start) {
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(heading <= 2 ? 1.22f : 1.12f),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                continue;
            }

            String bulleted = line.replaceFirst("^\\s*[-*+]\\s+", "•  ");
            appendInline(ctx, sb, bulleted);
        }
        return sb;
    }

    private static void appendInline(Context ctx, SpannableStringBuilder sb, String s) {
        int n = s.length();
        int i = 0;
        int codeBg = ctx.getResources().getColor(R.color.surface2, null);
        int codeFg = ctx.getResources().getColor(R.color.text, null);
        int link = ctx.getResources().getColor(R.color.accent, null);
        while (i < n) {
            char c = s.charAt(i);
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int st = sb.length();
                    sb.append(s, i + 1, end);
                    int en = sb.length();
                    sb.setSpan(new TypefaceSpan("monospace"), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(codeBg), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(codeFg), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(0.93f), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '*' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("**", i + 2);
                if (end > i + 2) {
                    int st = sb.length();
                    sb.append(s, i + 2, end);
                    int en = sb.length();
                    sb.setSpan(new StyleSpan(Typeface.BOLD), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (c == '[') {
                int close = s.indexOf(']', i + 1);
                if (close > i && close + 1 < n && s.charAt(close + 1) == '(') {
                    int urlEnd = s.indexOf(')', close + 2);
                    if (urlEnd > close) {
                        String label = s.substring(i + 1, close);
                        sb.append(label);
                        sb.setSpan(new ForegroundColorSpan(link), sb.length() - label.length(),
                                sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = urlEnd + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
    }

    /** 给纯文本做一个安全截断，用于卡片预览。 */
    public static String preview(String s, int max) {
        if (s == null) return "";
        String one = s.replace("\n", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}

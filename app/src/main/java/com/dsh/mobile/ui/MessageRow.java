package com.dsh.mobile.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.mobile.R;
import com.dsh.mobile.core.Attachment;
import com.dsh.mobile.core.Attachments;
import com.dsh.mobile.core.Message;
import com.dsh.mobile.core.ToolCall;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 一条消息的渲染。
 * 视觉约定：用户是右侧浅石墨气泡；助手是左侧无气泡正文 + 顶部极小字号的身份行；
 * 代码块与工具卡片都是同一种"深色微卡片"，靠 1px 描边区分层级。
 */
public class MessageRow extends LinearLayout {

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm", Locale.CHINA);

    private final Context ctx;
    private final int maxBubble;
    private final boolean compact;
    private Message msg;

    public MessageRow(Context c, int maxBubbleWidth) {
        this(c, maxBubbleWidth, false);
    }

    public MessageRow(Context c, int maxBubbleWidth, boolean compact) {
        super(c);
        this.ctx = c;
        this.maxBubble = maxBubbleWidth;
        this.compact = compact;
        setOrientation(VERTICAL);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(compact ? 8 : 22);
        setLayoutParams(lp);
    }

    public Message message() {
        return msg;
    }

    public void bind(Message m) {
        this.msg = m;
        render();
    }

    public void render() {
        removeAllViews();
        if (msg == null) return;
        if (msg.isUser()) renderUser();
        else renderAssistant();
    }

    // ------------------------------------------------------------- user side

    private void renderUser() {
        setGravity(Gravity.END);
        LinearLayout bubble = new LinearLayout(ctx);
        bubble.setOrientation(VERTICAL);
        bubble.setBackgroundResource(R.drawable.bg_bubble_user);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));

        if (!msg.content.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText(msg.content);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            tv.setTextColor(color(R.color.text));
            tv.setLineSpacing(dp(4), 1f);
            tv.setTextIsSelectable(true);
            if (maxBubble > 0) tv.setMaxWidth(maxBubble);
            // 竖直 LinearLayout 的默认参数是 MATCH_PARENT 宽，这里必须显式 WRAP_CONTENT，
            // 否则会被同级的附件 chip 挤窄，导致文字换行后被裁切。
            tv.setLayoutParams(new LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            bubble.addView(tv);
        }

        for (int i = 0; i < msg.attachments.size(); i++) {
            Attachment a = msg.attachments.get(i);
            TextView chip = new TextView(ctx);
            chip.setText(a.name + "  ·  " + Attachments.human(a.size));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            chip.setTextColor(color(R.color.text_dim));
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            chip.setMaxWidth(Math.max(dp(160), maxBubble - dp(28)));
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            if (i > 0 || !msg.content.isEmpty()) lp.topMargin = dp(8);
            chip.setLayoutParams(lp);
            bubble.addView(chip);
        }

        addView(bubble, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    // -------------------------------------------------------- assistant side

    private void renderAssistant() {
        setGravity(Gravity.START);

        if (!compact) addView(identityRow());

        if (!msg.reasoning.isEmpty()) {
            addView(reasoningView());
        }

        boolean hasContent = msg.content != null && !msg.content.trim().isEmpty();
        if (hasContent) {
            List<Md.Block> blocks = Md.blocks(msg.content);
            int textBlocks = 0;
            for (Md.Block b : blocks) if (!b.code && !b.text.trim().isEmpty()) textBlocks++;

            int rendered = 0;
            for (Md.Block b : blocks) {
                if (b.code) {
                    addView(codeBlock(b));
                } else if (!b.text.trim().isEmpty()) {
                    rendered++;
                    boolean last = rendered == textBlocks;
                    addView(textBlock(b.text, last && msg.streaming));
                }
            }
        } else if (msg.streaming && msg.toolCalls.isEmpty()) {
            TextView thinking = new TextView(ctx);
            thinking.setText(msg.reasoning.isEmpty() ? "正在思考…" : "正在整理…");
            thinking.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            thinking.setTextColor(color(R.color.text_faint));
            thinking.setPadding(0, dp(6), 0, 0);
            addView(thinking);
        }

        for (ToolCall tc : msg.toolCalls) {
            addView(toolCard(tc));
        }
    }

    /** 身份行：极小的强调色圆点 + DSH + 时间，不抢正文 */
    private View identityRow() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(ctx);
        dot.setBackgroundResource(R.drawable.bg_dot_accent);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(5), dp(5));
        row.addView(dot, dotLp);

        TextView who = new TextView(ctx);
        who.setText("DSH");
        who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        who.setTextColor(color(R.color.text_dim));
        who.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams whoLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        whoLp.leftMargin = dp(7);
        row.addView(who, whoLp);

        TextView when = new TextView(ctx);
        when.setText(TIME.format(new Date(msg.ts)));
        when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        when.setTextColor(color(R.color.text_faint));
        LinearLayout.LayoutParams whenLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        whenLp.leftMargin = dp(8);
        row.addView(when, whenLp);

        return row;
    }

    private View textBlock(String text, boolean cursor) {
        TextView tv = new TextView(ctx);
        tv.setText(Md.render(ctx, text));
        if (cursor) {
            SpannableStringBuilder sb = new SpannableStringBuilder(tv.getText());
            int start = sb.length();
            sb.append("▌");
            sb.setSpan(new ForegroundColorSpan(color(R.color.accent)), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(0.85f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(sb);
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.setTextColor(color(R.color.text));
        tv.setLineSpacing(dp(6), 1f);
        tv.setTextIsSelectable(true);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View codeBlock(final Md.Block b) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        card.setBackgroundResource(R.drawable.bg_code);
        LayoutParams cardLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(12);
        card.setLayoutParams(cardLp);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(9), dp(10), dp(2));

        TextView lang = new TextView(ctx);
        lang.setText(TextUtils.isEmpty(b.lang) ? "code" : b.lang);
        lang.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        lang.setTextColor(color(R.color.text_faint));
        lang.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        head.addView(lang, langLp);

        TextView copy = new TextView(ctx);
        copy.setText("复制");
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        copy.setTextColor(color(R.color.accent));
        copy.setPadding(dp(10), dp(4), dp(4), dp(4));
        copy.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard(b.text);
            }
        });
        head.addView(copy);

        final TextView code = new TextView(ctx);
        code.setText(b.text);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        code.setTextColor(color(R.color.text));
        code.setLineSpacing(dp(5), 1f);
        code.setPadding(dp(14), dp(4), dp(14), dp(14));
        code.setTextIsSelectable(true);
        code.setHorizontallyScrolling(true);

        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setPadding(0, 0, 0, 0);
        hsv.addView(code, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        card.addView(head);
        card.addView(hsv);
        return card;
    }

    private View reasoningView() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.setBackgroundResource(R.drawable.bg_tool);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("思考");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        title.setTextColor(color(R.color.text_faint));
        title.setLetterSpacing(0.1f);
        head.addView(title);

        final TextView body = new TextView(ctx);
        body.setText(msg.reasoning);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        body.setTextColor(color(R.color.text_dim));
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(6), 0, 0);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);

        final TextView toggle = new TextView(ctx);
        toggle.setText("展开");
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        toggle.setTextColor(color(R.color.accent));
        toggle.setPadding(dp(10), 0, 0, 0);
        toggle.setOnClickListener(new OnClickListener() {
            private boolean open;

            @Override
            public void onClick(View v) {
                open = !open;
                body.setMaxLines(open ? 400 : 3);
                body.setEllipsize(open ? null : TextUtils.TruncateAt.END);
                toggle.setText(open ? "收起" : "展开");
            }
        });

        head.addView(toggle);
        box.addView(head);
        box.addView(body);
        return box;
    }

    private View toolCard(final ToolCall tc) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        card.setBackgroundResource(R.drawable.bg_tool);
        card.setPadding(dp(14), dp(11), dp(14), dp(11));
        LayoutParams cardLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(10);
        card.setLayoutParams(cardLp);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(ctx);
        dot.setBackgroundResource(!tc.done ? R.drawable.bg_dot_idle
                : (tc.error ? R.drawable.bg_dot_err : R.drawable.bg_dot_ok));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
        head.addView(dot, dotLp);

        TextView name = new TextView(ctx);
        name.setText(tc.name);
        name.setTypeface(Typeface.MONOSPACE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        name.setTextColor(color(R.color.text));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        nameLp.leftMargin = dp(9);
        head.addView(name, nameLp);

        TextView summary = new TextView(ctx);
        summary.setText(tc.summary());
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        summary.setTextColor(color(R.color.text_faint));
        summary.setTypeface(Typeface.MONOSPACE);
        summary.setSingleLine(true);
        summary.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        sumLp.leftMargin = dp(10);
        head.addView(summary, sumLp);

        TextView status = new TextView(ctx);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        if (!tc.done) {
            status.setText(tc.arguments.isEmpty() ? "准备中" : "运行中");
            status.setTextColor(color(R.color.text_faint));
        } else if (tc.error) {
            status.setText("失败");
            status.setTextColor(color(R.color.err));
        } else {
            status.setText(tc.millis > 0 ? tc.millis + "ms" : "完成");
            status.setTextColor(color(R.color.text_faint));
        }
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        stLp.leftMargin = dp(10);
        head.addView(status, stLp);

        card.addView(head);

        if (tc.done && tc.result != null && !tc.result.isEmpty()) {
            String shown = tc.result.length() > 20000
                    ? tc.result.substring(0, 20000) + "\n…（输出过长，已截断显示）"
                    : tc.result;
            final TextView out = new TextView(ctx);
            out.setText(shown);
            out.setTypeface(Typeface.MONOSPACE);
            out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            out.setTextColor(tc.error ? color(R.color.err) : color(R.color.text_dim));
            out.setLineSpacing(dp(3), 1f);
            out.setPadding(0, dp(9), 0, 0);
            out.setMaxLines(4);
            out.setEllipsize(TextUtils.TruncateAt.END);
            out.setTextIsSelectable(true);

            final TextView more = new TextView(ctx);
            more.setText("展开");
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            more.setTextColor(color(R.color.accent));
            more.setPadding(0, dp(7), 0, 0);
            more.setOnClickListener(new OnClickListener() {
                private boolean open;

                @Override
                public void onClick(View v) {
                    open = !open;
                    out.setMaxLines(open ? 4000 : 4);
                    out.setEllipsize(open ? null : TextUtils.TruncateAt.END);
                    more.setText(open ? "收起" : "展开");
                }
            });

            card.addView(out);
            if (countLines(shown) > 4 || shown.length() > 260) {
                card.addView(more);
            }
        }

        return card;
    }

    private static int countLines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    // ---------------------------------------------------------------- utils

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("dsh-code", text));
            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int color(int res) {
        return getResources().getColor(res, null);
    }
}

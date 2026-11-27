package com.vigil.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import androidx.core.content.ContextCompat;
import com.google.android.material.color.MaterialColors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dependency-free, bounded Markdown parser and Material 3 Spannable renderer for GitHub
 * Release notes shown in the manual update dialog.
 */
final class ReleaseNotesMarkdown {
    private static final int MAX_LIST_DEPTH = 3;
    private static final int MAX_INLINE_DEPTH = 8;

    enum BlockType {
        HEADING,
        PARAGRAPH,
        BULLET_ITEM,
        ORDERED_ITEM,
        CODE_BLOCK,
        BLOCKQUOTE,
        DIVIDER
    }

    enum InlineType {
        TEXT,
        BOLD,
        ITALIC,
        BOLD_ITALIC,
        STRIKETHROUGH,
        CODE,
        LINK
    }

    static final class Block {
        final BlockType type;
        final int level;
        final String marker;
        final String rawText;
        final List<InlineNode> inlines;

        Block(BlockType type, int level, String marker, String rawText, List<InlineNode> inlines) {
            this.type = type;
            this.level = level;
            this.marker = marker;
            this.rawText = rawText;
            this.inlines = inlines == null ? Collections.emptyList() : Collections.unmodifiableList(inlines);
        }
    }

    static final class InlineNode {
        final InlineType type;
        final String text;
        final String url;
        final List<InlineNode> children;

        InlineNode(InlineType type, String text, String url, List<InlineNode> children) {
            this.type = type;
            this.text = text;
            this.url = url;
            this.children = children == null ? Collections.emptyList() : Collections.unmodifiableList(children);
        }

        static InlineNode text(String value) {
            return new InlineNode(InlineType.TEXT, value, null, null);
        }

        static InlineNode code(String value) {
            return new InlineNode(InlineType.CODE, value, null, null);
        }
    }

    private ReleaseNotesMarkdown() { }

    static List<Block> parse(String markdown) {
        if (markdown == null) return Collections.emptyList();
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) return Collections.emptyList();

        String[] lines = normalized.split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                i++;
                continue;
            }

            FenceInfo fence = parseOpeningFence(line);
            if (fence != null) {
                i++;
                StringBuilder code = new StringBuilder();
                while (i < lines.length && !isClosingFence(lines[i], fence)) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[i]);
                    i++;
                }
                if (i < lines.length) i++; // consume closing fence
                blocks.add(new Block(BlockType.CODE_BLOCK, 0, null, code.toString(), null));
                continue;
            }

            if (isHorizontalRule(line)) {
                blocks.add(new Block(BlockType.DIVIDER, 0, null, "", null));
                i++;
                continue;
            }

            Block heading = tryParseHeading(line);
            if (heading != null) {
                blocks.add(heading);
                i++;
                continue;
            }

            if (isBlockquoteLine(line)) {
                StringBuilder quote = new StringBuilder();
                while (i < lines.length && isBlockquoteLine(lines[i])) {
                    if (quote.length() > 0) quote.append('\n');
                    quote.append(stripBlockquotePrefix(lines[i]));
                    i++;
                }
                blocks.add(new Block(BlockType.BLOCKQUOTE, 0, null, null, parseInlines(quote.toString())));
                continue;
            }

            ListItemMatch listItem = tryParseListItem(line);
            if (listItem != null) {
                StringBuilder itemText = new StringBuilder(listItem.content);
                i++;
                while (i < lines.length && !lines[i].trim().isEmpty()
                        && parseOpeningFence(lines[i]) == null
                        && !isHorizontalRule(lines[i])
                        && tryParseHeading(lines[i]) == null
                        && !isBlockquoteLine(lines[i])
                        && tryParseListItem(lines[i]) == null) {
                    itemText.append('\n').append(lines[i].trim());
                    i++;
                }
                blocks.add(new Block(
                        listItem.ordered ? BlockType.ORDERED_ITEM : BlockType.BULLET_ITEM,
                        listItem.depth,
                        listItem.marker,
                        null,
                        parseInlines(itemText.toString())));
                continue;
            }

            StringBuilder paragraph = new StringBuilder(line.trim());
            i++;
            while (i < lines.length && !lines[i].trim().isEmpty()
                    && parseOpeningFence(lines[i]) == null
                    && !isHorizontalRule(lines[i])
                    && tryParseHeading(lines[i]) == null
                    && !isBlockquoteLine(lines[i])
                    && tryParseListItem(lines[i]) == null) {
                paragraph.append('\n').append(lines[i].trim());
                i++;
            }
            blocks.add(new Block(BlockType.PARAGRAPH, 0, null, null, parseInlines(paragraph.toString())));
        }
        return blocks;
    }

    static List<InlineNode> parseInlines(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        return parseInlines(text, 0);
    }

    private static List<InlineNode> parseInlines(String text, int depth) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        if (depth >= MAX_INLINE_DEPTH) {
            return Collections.singletonList(InlineNode.text(text));
        }

        List<InlineNode> nodes = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int len = text.length();
        int i = 0;

        while (i < len) {
            char ch = text.charAt(i);

            // Backslash escape for ASCII Markdown punctuation.
            if (ch == '\\' && i + 1 < len && isEscapable(text.charAt(i + 1))) {
                literal.append(text.charAt(i + 1));
                i += 2;
                continue;
            }

            // Inline code span (`...` or ``...``).
            if (ch == '`') {
                int ticks = countRun(text, i, '`');
                int close = findClosingTicks(text, i + ticks, ticks);
                if (close >= 0) {
                    flushLiteral(nodes, literal);
                    String code = text.substring(i + ticks, close);
                    if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ") && !code.trim().isEmpty()) {
                        code = code.substring(1, code.length() - 1);
                    }
                    nodes.add(InlineNode.code(code));
                    i = close + ticks;
                    continue;
                }
                for (int k = 0; k < ticks; k++) literal.append('`');
                i += ticks;
                continue;
            }

            // Markdown link: [label](url)
            if (ch == '[') {
                LinkMatch link = tryParseLink(text, i);
                if (link != null) {
                    flushLiteral(nodes, literal);
                    List<InlineNode> labelNodes = parseInlines(link.label, depth + 1);
                    String safeUrl = UpdateChecker.isSafeExternalHttpsUrl(link.url) ? link.url : null;
                    if (safeUrl != null) {
                        nodes.add(new InlineNode(InlineType.LINK, null, safeUrl, labelNodes));
                    } else {
                        nodes.addAll(labelNodes);
                    }
                    i = link.nextIndex;
                    continue;
                }
            }

            // Strikethrough: ~~text~~
            if (ch == '~' && i + 1 < len && text.charAt(i + 1) == '~') {
                int close = findDelimiterClose(text, i + 2, "~~", false);
                if (close > i + 2) {
                    flushLiteral(nodes, literal);
                    nodes.add(new InlineNode(InlineType.STRIKETHROUGH, null, null,
                            parseInlines(text.substring(i + 2, close), depth + 1)));
                    i = close + 2;
                    continue;
                }
            }

            // Emphasis / Strong: ***, ___, **, __, *, _
            if (ch == '*' || ch == '_') {
                boolean intrawordRestricted = ch == '_';
                if (!intrawordRestricted || canOpenUnderscore(text, i)) {
                    int run = countRun(text, i, ch);
                    if (run >= 3) {
                        String delim = ch == '*' ? "***" : "___";
                        int close = findDelimiterClose(text, i + 3, delim, intrawordRestricted);
                        if (close > i + 3) {
                            flushLiteral(nodes, literal);
                            nodes.add(new InlineNode(InlineType.BOLD_ITALIC, null, null,
                                    parseInlines(text.substring(i + 3, close), depth + 1)));
                            i = close + 3;
                            continue;
                        }
                    }
                    if (run >= 2) {
                        String delim = ch == '*' ? "**" : "__";
                        int close = findDelimiterClose(text, i + 2, delim, intrawordRestricted);
                        if (close > i + 2) {
                            flushLiteral(nodes, literal);
                            nodes.add(new InlineNode(InlineType.BOLD, null, null,
                                    parseInlines(text.substring(i + 2, close), depth + 1)));
                            i = close + 2;
                            continue;
                        }
                    }
                    if (run == 1) {
                        String delim = String.valueOf(ch);
                        int close = findDelimiterClose(text, i + 1, delim, intrawordRestricted);
                        if (close > i + 1) {
                            flushLiteral(nodes, literal);
                            nodes.add(new InlineNode(InlineType.ITALIC, null, null,
                                    parseInlines(text.substring(i + 1, close), depth + 1)));
                            i = close + 1;
                            continue;
                        }
                    }
                    if (run > 1) {
                        for (int k = 0; k < run; k++) literal.append(ch);
                        i += run;
                        continue;
                    }
                }
            }

            literal.append(ch);
            i++;
        }

        flushLiteral(nodes, literal);
        return nodes;
    }

    static String toPlainText(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (i > 0) out.append('\n');
            if (block.type == BlockType.CODE_BLOCK) {
                out.append(block.rawText);
            } else if (block.type == BlockType.DIVIDER) {
                out.append("---");
            } else {
                if (block.type == BlockType.ORDERED_ITEM && block.marker != null) {
                    out.append(block.marker).append(' ');
                }
                appendPlainInlines(out, block.inlines);
            }
        }
        return out.toString();
    }

    private static void appendPlainInlines(StringBuilder out, List<InlineNode> nodes) {
        for (InlineNode node : nodes) {
            if (node.type == InlineType.TEXT || node.type == InlineType.CODE) {
                if (node.text != null) out.append(node.text);
            } else {
                appendPlainInlines(out, node.children);
            }
        }
    }

    static SpannableStringBuilder render(Context context, String markdown) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        List<Block> blocks = parse(markdown);
        if (blocks.isEmpty()) return out;

        float density = context.getResources().getDisplayMetrics().density;
        int colorOnSurface = MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorOnSurface,
                ContextCompat.getColor(context, R.color.md_on_surface));
        int colorOnSurfaceVariant = MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(context, R.color.md_on_surface_variant));
        int colorPrimary = MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorPrimary,
                ContextCompat.getColor(context, R.color.md_primary));
        int colorCodeBg = MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorSurfaceContainerHigh,
                ContextCompat.getColor(context, R.color.md_surface_container_high));
        int colorOutlineVariant = MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorOutlineVariant,
                ContextCompat.getColor(context, R.color.md_outline_variant));

        RenderPalette palette = new RenderPalette(
                density, colorOnSurface, colorOnSurfaceVariant, colorPrimary, colorCodeBg, colorOutlineVariant);

        Block previous = null;
        for (Block block : blocks) {
            if (previous != null) {
                appendBlockGap(out, previous, block);
            }
            renderBlock(out, block, palette);
            previous = block;
        }
        return out;
    }

    private static void appendBlockGap(SpannableStringBuilder out, Block prev, Block next) {
        out.append('\n');
        boolean bothList = isListBlock(prev) && isListBlock(next);
        if (bothList) {
            if (prev.level > 0 && next.level == 0) {
                appendSpacerLine(out, 0.35f);
            }
            return;
        }
        if (next.type == BlockType.HEADING) {
            appendSpacerLine(out, 0.55f);
        } else if (prev.type == BlockType.HEADING) {
            appendSpacerLine(out, 0.28f);
        } else {
            appendSpacerLine(out, 0.42f);
        }
    }

    private static void appendSpacerLine(SpannableStringBuilder out, float relativeHeight) {
        int start = out.length();
        out.append('\n');
        out.setSpan(new RelativeSizeSpan(relativeHeight), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static boolean isListBlock(Block block) {
        return block.type == BlockType.BULLET_ITEM || block.type == BlockType.ORDERED_ITEM;
    }

    private static void renderBlock(SpannableStringBuilder out, Block block, RenderPalette palette) {
        int start = out.length();
        switch (block.type) {
            case HEADING: {
                renderInlines(out, block.inlines, palette);
                int end = out.length();
                if (end > start) {
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(palette.colorOnSurface), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    float scale = block.level == 1 ? 1.20f : block.level == 2 ? 1.12f
                            : block.level == 3 ? 1.05f : 1.0f;
                    if (scale != 1.0f) {
                        out.setSpan(new RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                break;
            }
            case BULLET_ITEM: {
                renderInlines(out, block.inlines, palette);
                int end = out.length();
                if (end > start) {
                    out.setSpan(new BulletLeadingMarginSpan(
                                    block.level,
                                    palette.dp(14),
                                    palette.dp(8),
                                    Math.max(2, palette.dp(2.5f)),
                                    palette.colorOnSurfaceVariant),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                break;
            }
            case ORDERED_ITEM: {
                String prefix = (block.marker != null ? block.marker : "1.") + " ";
                int markerStart = out.length();
                out.append(prefix);
                out.setSpan(new ForegroundColorSpan(palette.colorOnSurfaceVariant),
                        markerStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                renderInlines(out, block.inlines, palette);
                int end = out.length();
                if (end > start) {
                    int baseIndent = block.level * palette.dp(14);
                    out.setSpan(new LeadingMarginSpan.Standard(baseIndent, baseIndent + palette.dp(18)),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                break;
            }
            case CODE_BLOCK: {
                String code = block.rawText == null ? "" : block.rawText;
                out.append(code);
                int end = out.length();
                if (end > start) {
                    out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.84f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(palette.colorOnSurface), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new LeadingMarginSpan.Standard(palette.dp(10)), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new CodeBlockBackgroundSpan(palette.colorCodeBg, palette.dp(4)),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                break;
            }
            case BLOCKQUOTE: {
                renderInlines(out, block.inlines, palette);
                int end = out.length();
                if (end > start) {
                    out.setSpan(new QuoteLeadingMarginSpan(palette.dp(3), palette.dp(10), palette.colorOutlineVariant),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(palette.colorOnSurfaceVariant), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                break;
            }
            case DIVIDER: {
                out.append('\u00A0');
                int end = out.length();
                out.setSpan(new RelativeSizeSpan(0.5f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new DividerLineSpan(palette.colorOutlineVariant, Math.max(1, palette.dp(1))),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            }
            case PARAGRAPH:
            default: {
                renderInlines(out, block.inlines, palette);
                break;
            }
        }
    }

    private static void renderInlines(SpannableStringBuilder out, List<InlineNode> nodes, RenderPalette palette) {
        for (InlineNode node : nodes) {
            int start = out.length();
            switch (node.type) {
                case TEXT:
                    if (node.text != null) out.append(node.text);
                    break;
                case CODE:
                    if (node.text != null && !node.text.isEmpty()) {
                        out.append(node.text);
                        int end = out.length();
                        out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new RelativeSizeSpan(0.90f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new BackgroundColorSpan(palette.colorCodeBg), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new ForegroundColorSpan(palette.colorOnSurface), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    break;
                case BOLD:
                    renderInlines(out, node.children, palette);
                    if (out.length() > start) {
                        out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    break;
                case ITALIC:
                    renderInlines(out, node.children, palette);
                    if (out.length() > start) {
                        out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    break;
                case BOLD_ITALIC:
                    renderInlines(out, node.children, palette);
                    if (out.length() > start) {
                        out.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    break;
                case STRIKETHROUGH:
                    renderInlines(out, node.children, palette);
                    if (out.length() > start) {
                        out.setSpan(new StrikethroughSpan(), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    break;
                case LINK:
                    renderInlines(out, node.children, palette);
                    if (out.length() > start) {
                        out.setSpan(new ForegroundColorSpan(palette.colorPrimary), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new UnderlineSpan(), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    break;
            }
        }
    }

    private static final class RenderPalette {
        final float density;
        final int colorOnSurface;
        final int colorOnSurfaceVariant;
        final int colorPrimary;
        final int colorCodeBg;
        final int colorOutlineVariant;

        RenderPalette(float density, int colorOnSurface, int colorOnSurfaceVariant,
                int colorPrimary, int colorCodeBg, int colorOutlineVariant) {
            this.density = density;
            this.colorOnSurface = colorOnSurface;
            this.colorOnSurfaceVariant = colorOnSurfaceVariant;
            this.colorPrimary = colorPrimary;
            this.colorCodeBg = colorCodeBg;
            this.colorOutlineVariant = colorOutlineVariant;
        }

        int dp(float value) {
            return Math.round(value * density);
        }
    }

    private static final class FenceInfo {
        final char ch;
        final int length;

        FenceInfo(char ch, int length) {
            this.ch = ch;
            this.length = length;
        }
    }

    private static final class ListItemMatch {
        final boolean ordered;
        final int depth;
        final String marker;
        final String content;

        ListItemMatch(boolean ordered, int depth, String marker, String content) {
            this.ordered = ordered;
            this.depth = depth;
            this.marker = marker;
            this.content = content;
        }
    }

    private static final class LinkMatch {
        final String label;
        final String url;
        final int nextIndex;

        LinkMatch(String label, String url, int nextIndex) {
            this.label = label;
            this.url = url;
            this.nextIndex = nextIndex;
        }
    }

    private static FenceInfo parseOpeningFence(String line) {
        int spaces = countLeadingIndent(line);
        if (spaces > 3) return null;
        int idx = skipLeadingWhitespace(line);
        if (idx >= line.length()) return null;
        char ch = line.charAt(idx);
        if (ch != '`' && ch != '~') return null;
        int count = countRun(line, idx, ch);
        if (count < 3) return null;
        if (ch == '`' && line.indexOf('`', idx + count) >= 0) return null;
        return new FenceInfo(ch, count);
    }

    private static boolean isClosingFence(String line, FenceInfo fence) {
        int spaces = countLeadingIndent(line);
        if (spaces > 3) return false;
        int idx = skipLeadingWhitespace(line);
        if (idx >= line.length() || line.charAt(idx) != fence.ch) return false;
        int count = countRun(line, idx, fence.ch);
        if (count < fence.length) return false;
        return line.substring(idx + count).trim().isEmpty();
    }

    private static boolean isHorizontalRule(String line) {
        int spaces = countLeadingIndent(line);
        if (spaces > 3) return false;
        String trimmed = line.trim();
        if (trimmed.length() < 3) return false;
        char marker = trimmed.charAt(0);
        if (marker != '-' && marker != '*' && marker != '_') return false;
        int count = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == marker) count++;
            else if (c != ' ' && c != '\t') return false;
        }
        return count >= 3;
    }

    private static Block tryParseHeading(String line) {
        int spaces = countLeadingIndent(line);
        if (spaces > 3) return null;
        int idx = skipLeadingWhitespace(line);
        if (idx >= line.length() || line.charAt(idx) != '#') return null;
        int level = countRun(line, idx, '#');
        if (level < 1 || level > 6) return null;
        int after = idx + level;
        if (after < line.length() && line.charAt(after) != ' ' && line.charAt(after) != '\t') {
            return null;
        }
        String text = line.substring(after).trim();
        // Strip optional closing ATX hashes preceded by whitespace.
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '#') end--;
        if (end < text.length() && (end == 0 || text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            text = text.substring(0, end).trim();
        }
        if (text.isEmpty()) return null;
        return new Block(BlockType.HEADING, level, null, null, parseInlines(text));
    }

    private static boolean isBlockquoteLine(String line) {
        int spaces = countLeadingIndent(line);
        if (spaces > 3) return false;
        int idx = skipLeadingWhitespace(line);
        return idx < line.length() && line.charAt(idx) == '>';
    }

    private static String stripBlockquotePrefix(String line) {
        int idx = skipLeadingWhitespace(line);
        if (idx < line.length() && line.charAt(idx) == '>') {
            idx++;
            if (idx < line.length() && line.charAt(idx) == ' ') idx++;
        }
        return line.substring(idx);
    }

    private static ListItemMatch tryParseListItem(String line) {
        int indentSpaces = countLeadingIndent(line);
        int idx = skipLeadingWhitespace(line);
        if (idx >= line.length()) return null;
        int depth = Math.min(MAX_LIST_DEPTH, indentSpaces / 2);

        char ch = line.charAt(idx);
        if ((ch == '-' || ch == '*' || ch == '+')
                && idx + 1 < line.length()
                && (line.charAt(idx + 1) == ' ' || line.charAt(idx + 1) == '\t')) {
            String content = line.substring(idx + 2).trim();
            if (content.isEmpty()) return null;
            return new ListItemMatch(false, depth, String.valueOf(ch), content);
        }

        if (ch >= '0' && ch <= '9') {
            int digitsEnd = idx;
            while (digitsEnd < line.length()
                    && line.charAt(digitsEnd) >= '0'
                    && line.charAt(digitsEnd) <= '9'
                    && digitsEnd - idx < 9) {
                digitsEnd++;
            }
            if (digitsEnd < line.length()
                    && (line.charAt(digitsEnd) == '.' || line.charAt(digitsEnd) == ')')
                    && digitsEnd + 1 < line.length()
                    && (line.charAt(digitsEnd + 1) == ' ' || line.charAt(digitsEnd + 1) == '\t')) {
                String marker = line.substring(idx, digitsEnd) + ".";
                String content = line.substring(digitsEnd + 2).trim();
                if (content.isEmpty()) return null;
                return new ListItemMatch(true, depth, marker, content);
            }
        }
        return null;
    }

    private static int countLeadingIndent(String line) {
        int spaces = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') spaces++;
            else if (c == '\t') spaces += 2;
            else break;
        }
        return spaces;
    }

    private static int skipLeadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i;
    }

    private static int countRun(String text, int start, char ch) {
        int i = start;
        while (i < text.length() && text.charAt(i) == ch) i++;
        return i - start;
    }

    private static int findClosingTicks(String text, int from, int expectedCount) {
        int i = from;
        int len = text.length();
        while (i < len) {
            if (text.charAt(i) == '`') {
                int run = countRun(text, i, '`');
                if (run == expectedCount) return i;
                i += run;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static LinkMatch tryParseLink(String text, int start) {
        int len = text.length();
        int bracketDepth = 0;
        int closeBracket = -1;
        for (int i = start; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < len) {
                i++;
                continue;
            }
            if (c == '[') bracketDepth++;
            else if (c == ']') {
                bracketDepth--;
                if (bracketDepth == 0) {
                    closeBracket = i;
                    break;
                }
            }
        }
        if (closeBracket <= start + 1 || closeBracket + 1 >= len || text.charAt(closeBracket + 1) != '(') {
            return null;
        }
        int parenDepth = 0;
        int closeParen = -1;
        for (int i = closeBracket + 1; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < len) {
                i++;
                continue;
            }
            if (c == '(') parenDepth++;
            else if (c == ')') {
                parenDepth--;
                if (parenDepth == 0) {
                    closeParen = i;
                    break;
                }
            }
        }
        if (closeParen < 0) return null;
        String label = text.substring(start + 1, closeBracket);
        String url = text.substring(closeBracket + 2, closeParen).trim();
        if (url.startsWith("<") && url.endsWith(">") && url.length() >= 2) {
            url = url.substring(1, url.length() - 1).trim();
        }
        return new LinkMatch(label, url, closeParen + 1);
    }

    private static int findDelimiterClose(String text, int from, String delim, boolean intrawordRestricted) {
        int dLen = delim.length();
        int len = text.length();
        if (from >= len || Character.isWhitespace(text.charAt(from))) return -1;

        int i = from;
        while (i <= len - dLen) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < len) {
                i += 2;
                continue;
            }
            if (c == '`') {
                int ticks = countRun(text, i, '`');
                int closeTicks = findClosingTicks(text, i + ticks, ticks);
                if (closeTicks >= 0) {
                    i = closeTicks + ticks;
                    continue;
                }
            }
            if (text.startsWith(delim, i)) {
                char prev = text.charAt(i - 1);
                boolean validPrev = !Character.isWhitespace(prev);
                boolean validNext = !intrawordRestricted || canCloseUnderscore(text, i + dLen);
                // Avoid splitting a longer run of the exact same delimiter char inside single-char emphasis.
                boolean extraSame = dLen == 1 && ((i + 1 < len && text.charAt(i + 1) == delim.charAt(0))
                        || (i > from && text.charAt(i - 1) == delim.charAt(0)));
                if (validPrev && validNext && !extraSame) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static boolean canOpenUnderscore(String text, int idx) {
        if (idx == 0) return true;
        return !isAsciiWordChar(text.charAt(idx - 1));
    }

    private static boolean canCloseUnderscore(String text, int afterIdx) {
        if (afterIdx >= text.length()) return true;
        return !isAsciiWordChar(text.charAt(afterIdx));
    }

    private static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean isEscapable(char c) {
        return c == '\\' || c == '`' || c == '*' || c == '_' || c == '[' || c == ']'
                || c == '(' || c == ')' || c == '#' || c == '+' || c == '-' || c == '.'
                || c == '!' || c == '~' || c == '>';
    }

    private static void flushLiteral(List<InlineNode> nodes, StringBuilder literal) {
        if (literal.length() > 0) {
            nodes.add(InlineNode.text(literal.toString()));
            literal.setLength(0);
        }
    }

    /** API 24-safe nested bullet span with crisp filled/hollow dots and hanging indent. */
    private static final class BulletLeadingMarginSpan implements LeadingMarginSpan {
        private final int depth;
        private final int indentStepPx;
        private final int gapPx;
        private final int radiusPx;
        private final int color;

        BulletLeadingMarginSpan(int depth, int indentStepPx, int gapPx, int radiusPx, int color) {
            this.depth = depth;
            this.indentStepPx = indentStepPx;
            this.gapPx = gapPx;
            this.radiusPx = radiusPx;
            this.color = color;
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return depth * indentStepPx + radiusPx * 2 + gapPx;
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline,
                int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
            if (!first) return;
            Paint.Style oldStyle = p.getStyle();
            int oldColor = p.getColor();
            float oldStroke = p.getStrokeWidth();
            boolean oldAntiAlias = p.isAntiAlias();

            p.setAntiAlias(true);
            p.setColor(color);
            float cx = x + dir * (depth * indentStepPx + radiusPx);
            float cy = baseline + (p.ascent() + p.descent()) * 0.5f;
            if (depth == 0) {
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, radiusPx, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, radiusPx * 0.45f));
                c.drawCircle(cx, cy, Math.max(1.5f, radiusPx * 0.85f), p);
            }

            p.setStyle(oldStyle);
            p.setColor(oldColor);
            p.setStrokeWidth(oldStroke);
            p.setAntiAlias(oldAntiAlias);
        }
    }

    /** API 24-safe left accent bar for Markdown blockquotes. */
    private static final class QuoteLeadingMarginSpan implements LeadingMarginSpan {
        private final int stripeWidthPx;
        private final int gapPx;
        private final int color;

        QuoteLeadingMarginSpan(int stripeWidthPx, int gapPx, int color) {
            this.stripeWidthPx = stripeWidthPx;
            this.gapPx = gapPx;
            this.color = color;
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return stripeWidthPx + gapPx;
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline,
                int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
            Paint.Style oldStyle = p.getStyle();
            int oldColor = p.getColor();
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            float left = Math.min(x, x + dir * stripeWidthPx);
            float right = Math.max(x, x + dir * stripeWidthPx);
            c.drawRect(left, top, right, bottom, p);
            p.setStyle(oldStyle);
            p.setColor(oldColor);
        }
    }

    /** API 24-safe tinted line background for fenced code blocks. */
    private static final class CodeBlockBackgroundSpan implements LineBackgroundSpan {
        private final int bgColor;
        private final int radiusPx;
        private final RectF rect = new RectF();

        CodeBlockBackgroundSpan(int bgColor, int radiusPx) {
            this.bgColor = bgColor;
            this.radiusPx = radiusPx;
        }

        @Override
        public void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline,
                int bottom, CharSequence text, int start, int end, int lineNumber) {
            Paint.Style oldStyle = p.getStyle();
            int oldColor = p.getColor();
            p.setStyle(Paint.Style.FILL);
            p.setColor(bgColor);
            rect.set(left, top, right, bottom);
            c.drawRoundRect(rect, radiusPx, radiusPx, p);
            p.setStyle(oldStyle);
            p.setColor(oldColor);
        }
    }

    /** API 24-safe horizontal divider line for Markdown thematic breaks. */
    private static final class DividerLineSpan implements LineBackgroundSpan {
        private final int color;
        private final int heightPx;

        DividerLineSpan(int color, int heightPx) {
            this.color = color;
            this.heightPx = heightPx;
        }

        @Override
        public void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline,
                int bottom, CharSequence text, int start, int end, int lineNumber) {
            Paint.Style oldStyle = p.getStyle();
            int oldColor = p.getColor();
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            float cy = (top + bottom) * 0.5f;
            float half = heightPx * 0.5f;
            c.drawRect(left, cy - half, right, cy + half, p);
            p.setStyle(oldStyle);
            p.setColor(oldColor);
        }
    }
}

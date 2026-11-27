package com.vigil.app;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReleaseNotesMarkdownTest {
    @Test public void parsesRealVigilReleaseNotesWithNestedListsCodeAndChecksum() {
        String notes = "## 更新内容\r\n"
                + "\r\n"
                + "- **「选择关注应用」弹窗边界与交互优化**\r\n"
                + "  - 修复列表滚动时在上下边缘出现硬裁切的问题。\r\n"
                + "  - 优化弹窗列表高度为自适应测量（上限 `360dp`），确保底部按钮始终可见。\r\n"
                + "- **「应用」页面顶部视觉过渡优化**\r\n"
                + "  - 统一页面底色与顶部 `Vigil` 顶栏。\r\n"
                + "\r\n"
                + "## SHA-256 校验\r\n"
                + "\r\n"
                + "```\r\n"
                + "1222758827b34b4f7bbbe1a8d5502b4a801daaa32c5c6de5680bf6a232bced95  Vigil.apk\r\n"
                + "```";

        List<ReleaseNotesMarkdown.Block> blocks = ReleaseNotesMarkdown.parse(notes);
        assertEquals(8, blocks.size());

        assertEquals(ReleaseNotesMarkdown.BlockType.HEADING, blocks.get(0).type);
        assertEquals(2, blocks.get(0).level);
        assertEquals("更新内容", blocks.get(0).inlines.get(0).text);

        assertEquals(ReleaseNotesMarkdown.BlockType.BULLET_ITEM, blocks.get(1).type);
        assertEquals(0, blocks.get(1).level);
        assertEquals(1, blocks.get(1).inlines.size());
        assertEquals(ReleaseNotesMarkdown.InlineType.BOLD, blocks.get(1).inlines.get(0).type);
        assertEquals("「选择关注应用」弹窗边界与交互优化", blocks.get(1).inlines.get(0).children.get(0).text);

        assertEquals(ReleaseNotesMarkdown.BlockType.BULLET_ITEM, blocks.get(2).type);
        assertEquals(1, blocks.get(2).level);

        assertEquals(ReleaseNotesMarkdown.BlockType.BULLET_ITEM, blocks.get(3).type);
        assertEquals(1, blocks.get(3).level);
        assertEquals(3, blocks.get(3).inlines.size());
        assertEquals(ReleaseNotesMarkdown.InlineType.CODE, blocks.get(3).inlines.get(1).type);
        assertEquals("360dp", blocks.get(3).inlines.get(1).text);

        assertEquals(ReleaseNotesMarkdown.BlockType.HEADING, blocks.get(6).type);
        assertEquals(2, blocks.get(6).level);
        assertEquals("SHA-256 校验", blocks.get(6).inlines.get(0).text);

        assertEquals(ReleaseNotesMarkdown.BlockType.CODE_BLOCK, blocks.get(7).type);
        assertEquals("1222758827b34b4f7bbbe1a8d5502b4a801daaa32c5c6de5680bf6a232bced95  Vigil.apk",
                blocks.get(7).rawText);

        String plain = ReleaseNotesMarkdown.toPlainText(blocks);
        assertFalse(plain.contains("## "));
        assertFalse(plain.contains("**"));
        assertFalse(plain.contains("`"));
        assertTrue(plain.contains("360dp"));
        assertTrue(plain.contains("Vigil.apk"));
    }

    @Test public void parsesHeadingsListsQuotesDividersAndOrderedItems() {
        String md = "# Title #\n"
                + "### Subheading\n"
                + "#NotAHeading\n"
                + "> Quoted **note**\n"
                + "> Second line\n"
                + "---\n"
                + "1. First step\n"
                + "  2) Nested step\n"
                + "~~~sh\n"
                + "echo **raw**\n"
                + "~~~";

        List<ReleaseNotesMarkdown.Block> blocks = ReleaseNotesMarkdown.parse(md);
        assertEquals(8, blocks.size());

        assertEquals(ReleaseNotesMarkdown.BlockType.HEADING, blocks.get(0).type);
        assertEquals(1, blocks.get(0).level);
        assertEquals("Title", blocks.get(0).inlines.get(0).text);

        assertEquals(ReleaseNotesMarkdown.BlockType.HEADING, blocks.get(1).type);
        assertEquals(3, blocks.get(1).level);

        assertEquals(ReleaseNotesMarkdown.BlockType.PARAGRAPH, blocks.get(2).type);
        assertEquals("#NotAHeading", blocks.get(2).inlines.get(0).text);

        assertEquals(ReleaseNotesMarkdown.BlockType.BLOCKQUOTE, blocks.get(3).type);
        assertEquals(ReleaseNotesMarkdown.InlineType.BOLD, blocks.get(3).inlines.get(1).type);

        assertEquals(ReleaseNotesMarkdown.BlockType.DIVIDER, blocks.get(4).type);

        assertEquals(ReleaseNotesMarkdown.BlockType.ORDERED_ITEM, blocks.get(5).type);
        assertEquals("1.", blocks.get(5).marker);
        assertEquals(0, blocks.get(5).level);

        assertEquals(ReleaseNotesMarkdown.BlockType.ORDERED_ITEM, blocks.get(6).type);
        assertEquals("2.", blocks.get(6).marker);
        assertEquals(1, blocks.get(6).level);

        assertEquals(ReleaseNotesMarkdown.BlockType.CODE_BLOCK, blocks.get(7).type);
        assertEquals("echo **raw**", blocks.get(7).rawText);
    }

    @Test public void preservesNestedEmphasisClosingTogetherAndCodeBlockLeadingBlankLine() {
        List<ReleaseNotesMarkdown.InlineNode> nested = ReleaseNotesMarkdown.parseInlines("**foo *bar***");
        assertEquals(1, nested.size());
        assertEquals(ReleaseNotesMarkdown.InlineType.BOLD, nested.get(0).type);
        assertEquals(2, nested.get(0).children.size());
        assertEquals(ReleaseNotesMarkdown.InlineType.TEXT, nested.get(0).children.get(0).type);
        assertEquals("foo ", nested.get(0).children.get(0).text);
        assertEquals(ReleaseNotesMarkdown.InlineType.ITALIC, nested.get(0).children.get(1).type);
        assertEquals("bar", nested.get(0).children.get(1).children.get(0).text);

        List<ReleaseNotesMarkdown.InlineNode> adjacent = ReleaseNotesMarkdown.parseInlines("**foo***bar*");
        assertEquals(2, adjacent.size());
        assertEquals(ReleaseNotesMarkdown.InlineType.BOLD, adjacent.get(0).type);
        assertEquals("foo", adjacent.get(0).children.get(0).text);
        assertEquals(ReleaseNotesMarkdown.InlineType.ITALIC, adjacent.get(1).type);
        assertEquals("bar", adjacent.get(1).children.get(0).text);

        List<ReleaseNotesMarkdown.Block> blankLead = ReleaseNotesMarkdown.parse("```\n\nsecond line\n```");
        assertEquals(1, blankLead.size());
        assertEquals(ReleaseNotesMarkdown.BlockType.CODE_BLOCK, blankLead.get(0).type);
        assertEquals("\nsecond line", blankLead.get(0).rawText);
    }

    @Test public void parsesInlineStylesLinksEscapesAndPreservesIntrawordUnderscores() {
        List<ReleaseNotesMarkdown.InlineNode> nodes = ReleaseNotesMarkdown.parseInlines(
                "Keep PACKAGE_USAGE_STATS and aosp_atd, use *italic*, ***both***, ~~old~~, "
                        + "`` code `tick` ``, \\*lit\\*, [Docs](https://github.com/maixuanfeng1/vigil), "
                        + "and [Unsafe](javascript:alert(1)).");

        StringBuilder rendered = new StringBuilder();
        boolean sawItalic = false;
        boolean sawBoldItalic = false;
        boolean sawStrike = false;
        boolean sawCode = false;
        boolean sawSafeLink = false;
        for (ReleaseNotesMarkdown.InlineNode node : nodes) {
            switch (node.type) {
                case TEXT:
                    rendered.append(node.text);
                    break;
                case CODE:
                    sawCode = true;
                    assertEquals("code `tick`", node.text);
                    rendered.append(node.text);
                    break;
                case ITALIC:
                    sawItalic = true;
                    rendered.append(node.children.get(0).text);
                    break;
                case BOLD_ITALIC:
                    sawBoldItalic = true;
                    rendered.append(node.children.get(0).text);
                    break;
                case STRIKETHROUGH:
                    sawStrike = true;
                    rendered.append(node.children.get(0).text);
                    break;
                case LINK:
                    sawSafeLink = true;
                    assertEquals("https://github.com/maixuanfeng1/vigil", node.url);
                    rendered.append(node.children.get(0).text);
                    break;
                default:
                    break;
            }
        }
        assertTrue(sawItalic);
        assertTrue(sawBoldItalic);
        assertTrue(sawStrike);
        assertTrue(sawCode);
        assertTrue(sawSafeLink);
        String text = rendered.toString();
        assertTrue(text.contains("PACKAGE_USAGE_STATS"));
        assertTrue(text.contains("aosp_atd"));
        assertTrue(text.contains("*lit*"));
        assertTrue(text.contains("Docs"));
        assertTrue(text.contains("Unsafe"));
        assertFalse(text.contains("javascript:"));
    }

    @Test public void handlesEmptyUnclosedAndDeeplyNestedInputSafely() {
        assertTrue(ReleaseNotesMarkdown.parse(null).isEmpty());
        assertTrue(ReleaseNotesMarkdown.parse("   \n\t  ").isEmpty());

        List<ReleaseNotesMarkdown.Block> unclosedFence = ReleaseNotesMarkdown.parse("```\nabc\ndef");
        assertEquals(1, unclosedFence.size());
        assertEquals(ReleaseNotesMarkdown.BlockType.CODE_BLOCK, unclosedFence.get(0).type);
        assertEquals("abc\ndef", unclosedFence.get(0).rawText);

        List<ReleaseNotesMarkdown.InlineNode> unclosedDelims = ReleaseNotesMarkdown.parseInlines("2**3 * 4 `unclosed");
        assertEquals(1, unclosedDelims.size());
        assertEquals("2**3 * 4 `unclosed", unclosedDelims.get(0).text);

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 20; i++) deep.append("**x ");
        deep.append("core");
        for (int i = 0; i < 20; i++) deep.append(" x**");
        List<ReleaseNotesMarkdown.Block> deepBlocks = ReleaseNotesMarkdown.parse(deep.toString());
        assertEquals(1, deepBlocks.size());
        assertEquals(ReleaseNotesMarkdown.BlockType.PARAGRAPH, deepBlocks.get(0).type);
        assertTrue(ReleaseNotesMarkdown.toPlainText(deepBlocks).contains("core"));

        StringBuilder manyBrackets = new StringBuilder(UpdateChecker.MAX_NOTES_LENGTH);
        for (int i = 0; i < UpdateChecker.MAX_NOTES_LENGTH; i++) manyBrackets.append('[');
        List<ReleaseNotesMarkdown.InlineNode> bracketNodes = ReleaseNotesMarkdown.parseInlines(manyBrackets.toString());
        assertEquals(1, bracketNodes.size());
        assertEquals(UpdateChecker.MAX_NOTES_LENGTH, bracketNodes.get(0).text.length());
    }
}

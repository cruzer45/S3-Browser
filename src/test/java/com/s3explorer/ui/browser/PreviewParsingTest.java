package com.s3explorer.ui.browser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewParsingTest {

    @Test
    void parsesQuotedCsv() {
        List<List<String>> rows = PreviewPanel.parseDelimited("name,notes\r\n\"Smith, J\",\"said \"\"hi\"\"\nthen left\"\nx,y", ',');
        assertEquals(3, rows.size());
        assertEquals(List.of("name", "notes"), rows.get(0));
        assertEquals(List.of("Smith, J", "said \"hi\"\nthen left"), rows.get(1));
        assertEquals(List.of("x", "y"), rows.get(2));
    }

    @Test
    void prettyPrintsJson() {
        assertTrue(PreviewPanel.prettyJson("{\"a\":[1,2]}").contains("\n"));
        assertEquals("{not json", PreviewPanel.prettyJson("{not json"));
    }

    @Test
    void xmlPreviewRefusesDoctypes() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>";
        assertEquals(xxe, PreviewPanel.prettyXml(xxe), "documents with DTDs are shown raw, never parsed");
        assertTrue(PreviewPanel.prettyXml("<a><b>1</b></a>").contains("\n"));
    }

    @Test
    void markdownNeverLoadsRemoteImages() {
        String html = PreviewPanel.renderMarkdown("# Title\n\n![logo](https://example.com/track.png)\n\n<script>x</script>");
        assertTrue(html.contains("<h1>Title</h1>"));
        assertFalse(html.contains("<img"));
        assertTrue(html.contains("[image: logo]"));
        assertFalse(html.contains("<script>"));
    }
}

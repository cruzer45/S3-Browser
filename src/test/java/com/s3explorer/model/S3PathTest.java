package com.s3explorer.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3PathTest {

    @Test
    void parsesUrisAndPlainPaths() {
        assertEquals(new S3Path("docs", "contracts/agreement.pdf"), S3Path.parse("s3://docs/contracts/agreement.pdf"));
        assertEquals(new S3Path("docs", "a/"), S3Path.parse("/docs/a/"));
        assertEquals(S3Path.root("docs"), S3Path.parse("docs"));
        assertEquals(S3Path.root("docs"), S3Path.parse("S3://docs/"));
        assertThrows(IllegalArgumentException.class, () -> S3Path.parse("s3://"));
    }

    @Test
    void presentsPrefixesAsFolders() {
        S3Path file = S3Path.object("b", "customers/123/contracts/agreement.pdf");
        assertFalse(file.isDirectory());
        assertEquals("agreement.pdf", file.name());
        assertEquals(S3Path.folder("b", "customers/123/contracts"), file.parent());
        assertEquals("customers/123/contracts/", file.prefix());
        assertEquals(5, file.ancestry().size());
        assertEquals(S3Path.root("b"), file.ancestry().get(0));

        S3Path folder = S3Path.folder("b", "customers/123");
        assertTrue(folder.isDirectory());
        assertEquals("customers/123/", folder.key());
        assertEquals("123", folder.name());
        assertEquals(S3Path.folder("b", "customers/"), folder.parent());
        assertEquals(S3Path.root("b"), S3Path.folder("b", "customers").parent());
        assertNull(S3Path.root("b").parent());
        assertEquals("b", S3Path.root("b").name());
    }

    @Test
    void resolvesChildren() {
        S3Path folder = S3Path.folder("b", "reports/");
        assertEquals("reports/2026/", folder.resolveFolder("2026").key());
        assertEquals("reports/2026/", folder.resolveFolder("2026/").key());
        assertEquals("reports/a.pdf", folder.resolveObject("a.pdf").key());
        assertEquals("s3://b/reports/a.pdf", folder.resolveObject("a.pdf").toUri());
        assertThrows(IllegalStateException.class, () -> S3Path.object("b", "x").resolveObject("y"));
    }

    @Test
    void searchCriteriaFiltersClientSide() {
        S3ObjectItem backup = S3ObjectItem.file(S3Path.object("b", "db/nightly.backup"), 12 * 1024 * 1024,
                LocalDate.of(2026, 6, 15).atStartOfDay(ZoneId.systemDefault()).toInstant(), "\"x\"", "STANDARD_IA");
        S3ObjectItem pdf = S3ObjectItem.file(S3Path.object("b", "docs/Report.PDF"), 1024,
                LocalDate.of(2026, 8, 1).atStartOfDay(ZoneId.systemDefault()).toInstant(), "\"y\"", null);

        assertTrue(criteria("*.backup", null, null, LocalDate.of(2026, 7, 1), null, null, null).compile().matches(backup));
        assertFalse(criteria("*.backup", null, null, LocalDate.of(2026, 6, 1), null, null, null).compile().matches(backup));
        assertTrue(criteria("report", null, null, null, null, null, null).compile().matches(pdf));
        assertTrue(criteria(null, ".pdf", null, null, null, null, null).compile().matches(pdf));
        assertFalse(criteria(null, "pdf", null, null, null, null, null).compile().matches(backup));
        assertTrue(criteria(null, null, null, null, 10L * 1024 * 1024, null, "STANDARD_IA").compile().matches(backup));
        assertFalse(criteria(null, null, null, null, null, 512L, null).compile().matches(pdf));
        assertTrue(criteria(null, null, null, null, null, null, "STANDARD").compile().matches(pdf));
        assertFalse(criteria(null, null, null, null, null, null, null).compile()
                .matches(S3ObjectItem.folder(S3Path.folder("b", "docs"))));
    }

    private static SearchCriteria criteria(String name, String ext, LocalDate after, LocalDate before, Long min,
                                           Long max, String storageClass) {
        return new SearchCriteria(name, ext, after, before, min, max, storageClass, true);
    }
}

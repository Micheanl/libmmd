package com.micheanl.libmmd.client.animation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

final class ActionCatalogTest {
    @Test
    void bundledActionsHaveConsistentMetadataAndOwnedBytes() {
        var catalog = ActionCatalog.bundled();
        assertTrue(catalog.names().contains("combat_sword_heavy_left"));
        assertTrue(catalog.names().contains("fp_use_bow"));
        for (var name : catalog.names()) {
            var definition = catalog.definition(name);
            assertEquals(name, definition.name());
            assertTrue(definition.durationFrames() > 0);
            var bytes = catalog.bytes(name);
            assertTrue(bytes.length > 54);
            assertEquals("Vocaloid Motion Data 0002", new String(bytes, 0, 25, StandardCharsets.US_ASCII));
            bytes[0] = 0;
            assertEquals('V', catalog.bytes(name)[0]);
        }
        assertThrows(IllegalArgumentException.class, () -> catalog.bytes("../idle"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.names().clear());
    }

    @Test
    void rejectsMalformedIndexMissingAssetsAndSettings() throws IOException {
        assertThrows(IOException.class, () -> ActionCatalog.read(null));
        for (var index : new String[] {
            "", "idle\ttrue\t30", "../idle\ttrue\t30\tmove\tthird_person",
            "idle\tyes\t30\tmove\tthird_person", "idle\tfalse\t-1\tmove\tthird_person",
            "idle\ttrue\tNaN\tmove\tthird_person", "idle\ttrue\t30\tmove\tsideways",
            "missing\ttrue\t30\tmove\tthird_person",
            "idle\ttrue\t30\tmove\tthird_person\nidle\ttrue\t30\tmove\tthird_person"
        }) {
            assertThrows(IOException.class, () -> ActionCatalog.read(archive(index, validSettings())), index);
        }
        for (var settings : new String[] {validSettings().replace("combo_reset_ticks=20", "combo_reset_ticks=0.5"), "", "transition_seconds=NaN", "transition_seconds=-1",
            "transition_seconds=0.1\nmovement_threshold=NaN\nturn_threshold_degrees=12",
            "transition_seconds=0.1\nmovement_threshold=0.01\nturn_threshold_degrees=0"}) {
            assertThrows(IOException.class, () -> ActionCatalog.read(
                archive("idle\ttrue\t30\tmove\tthird_person", settings)));
        }
    }

    private static String validSettings() {
        return "transition_seconds=0.1\nmovement_threshold=0.01\nturn_threshold_degrees=12\ncombo_reset_ticks=20\n";
    }

    private static ByteArrayInputStream archive(String index, String settings) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : new String[][] {{"index.tsv", index}, {"idle.vmd", "fixture"}, {"settings.properties", settings}}) {
                zip.putNextEntry(new ZipEntry(entry[0]));
                zip.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}

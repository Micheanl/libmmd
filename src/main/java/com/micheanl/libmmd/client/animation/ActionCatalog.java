package com.micheanl.libmmd.client.animation;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipInputStream;

public final class ActionCatalog {
    private static final String RESOURCE = "/assets/libmmd/actions.zip";
    private final Map<String, Definition> definitions;
    private final Map<String, byte[]> clips;
    private final float transitionSeconds;
    private final float movementThreshold;
    private final float turnThresholdDegrees;

    private ActionCatalog(Map<String, Definition> definitions, Map<String, byte[]> clips, float transitionSeconds,
                          float movementThreshold, float turnThresholdDegrees) {
        this.definitions = Map.copyOf(definitions);
        this.clips = Map.copyOf(clips);
        this.transitionSeconds = transitionSeconds;
        this.movementThreshold = movementThreshold;
        this.turnThresholdDegrees = turnThresholdDegrees;
    }

    public static ActionCatalog bundled() {
        return Bundled.CATALOG;
    }

    public static ActionCatalog read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Missing action archive");
        var entries = new LinkedHashMap<String, byte[]>();
        try (var archive = new ZipInputStream(input)) {
            for (var entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                if (!entry.isDirectory() && entries.putIfAbsent(entry.getName(), archive.readAllBytes()) != null) {
                    throw new IOException("Duplicate action entry: " + entry.getName());
                }
            }
        }
        var index = entries.remove("index.tsv");
        if (index == null) throw new IOException("Missing action index");
        var definitions = new LinkedHashMap<String, Definition>();
        for (var line : new String(index, StandardCharsets.UTF_8).lines().toList()) {
            var fields = line.split("\t", -1);
            if (fields.length != 5 || !fields[0].matches("[a-z][a-z0-9_]*") ||
                !(fields[1].equals("true") || fields[1].equals("false")) ||
                !(fields[4].equals("first_person") || fields[4].equals("third_person"))) {
                throw new IOException("Invalid action index row");
            }
            int frames;
            try {
                frames = Integer.parseInt(fields[2]);
            } catch (NumberFormatException failure) {
                throw new IOException("Invalid action duration: " + fields[0], failure);
            }
            if (frames <= 0 || !entries.containsKey(fields[0] + ".vmd")) {
                throw new IOException("Invalid or missing action: " + fields[0]);
            }
            var definition = new Definition(fields[0], Boolean.parseBoolean(fields[1]), frames, fields[3], fields[4]);
            if (definitions.putIfAbsent(definition.name(), definition) != null) {
                throw new IOException("Duplicate action definition: " + definition.name());
            }
        }
        if (definitions.isEmpty()) throw new IOException("Empty action catalog");
        var settingsBytes = entries.remove("settings.properties");
        if (settingsBytes == null) throw new IOException("Missing action playback settings");
        var settings = new Properties();
        settings.load(new ByteArrayInputStream(settingsBytes));
        float transitionSeconds;
        try {
            transitionSeconds = Float.parseFloat(settings.getProperty("transition_seconds", "NaN"));
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid action transition duration", failure);
        }
        if (!Float.isFinite(transitionSeconds) || transitionSeconds < 0) {
            throw new IOException("Invalid action transition duration");
        }
        return new ActionCatalog(definitions, entries, transitionSeconds,
            positiveSetting(settings, "movement_threshold"), positiveSetting(settings, "turn_threshold_degrees"));
    }

    public List<String> names() {
        return definitions.keySet().stream().sorted().toList();
    }

    public float transitionSeconds() { return transitionSeconds; }
    public float movementThreshold() { return movementThreshold; }
    public float turnThresholdDegrees() { return turnThresholdDegrees; }

    private static float positiveSetting(Properties settings, String name) throws IOException {
        try {
            var value = Float.parseFloat(settings.getProperty(name, "NaN"));
            if (!Float.isFinite(value) || value <= 0) throw new IOException("Invalid action setting: " + name);
            return value;
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid action setting: " + name, failure);
        }
    }

    public Definition definition(String name) {
        var definition = definitions.get(name);
        if (definition == null) throw new IllegalArgumentException("Unknown action: " + name);
        return definition;
    }

    public byte[] bytes(String name) {
        definition(name);
        return clips.get(name + ".vmd").clone();
    }

    public record Definition(String name, boolean looping, int durationFrames, String category, String view) {}

    private static final class Bundled {
        private static final ActionCatalog CATALOG = load();

        private static ActionCatalog load() {
            try {
                return read(ActionCatalog.class.getResourceAsStream(RESOURCE));
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to load bundled actions", failure);
            }
        }
    }
}

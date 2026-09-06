package com.micheanl.libmmd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

final class NativeLibrary {
    private static Path extracted;

    private NativeLibrary() {}

    static synchronized Path resolve() {
        var configured = System.getProperty("libmmd.library");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("LIBMMD_LIBRARY");
        }
        if (configured != null && !configured.isBlank()) {
            var path = Path.of(configured).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Configured native library does not exist: " + path);
            }
            return path;
        }
        if (extracted != null) {
            return extracted;
        }
        var libraryName = System.mapLibraryName("mmd");
        var resource = "/native/" + platform() + "-" + architecture() + "/" + libraryName;
        try (var input = NativeLibrary.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Native library resource is unavailable: " + resource);
            }
            var directory = Files.createTempDirectory("libmmd-native-");
            var library = directory.resolve(libraryName);
            Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING);
            library.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            extracted = library;
            return library;
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to extract " + resource, failure);
        }
    }

    private static String platform() {
        var name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) return "windows";
        if (name.startsWith("mac")) return "macos";
        return "linux";
    }

    private static String architecture() {
        return switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        };
    }
}

package com.micheanl.libmmd.client.model;

import java.io.IOException;
import java.util.zip.ZipInputStream;

final class DefaultAnimationReader {
    private DefaultAnimationReader() {}

    static byte[] read(String name) {
        var resource = DefaultAnimationReader.class.getResourceAsStream("/assets/libmmd/default-animation.zip");
        if (resource == null) throw new IllegalStateException("Missing default animation archive");
        try (var zip = new ZipInputStream(resource)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) return zip.readAllBytes();
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read default animation " + name, failure);
        }
        throw new IllegalStateException("Missing default animation " + name);
    }
}

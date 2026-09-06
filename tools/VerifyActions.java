import com.micheanl.libmmd.client.animation.ActionCatalog;
import com.micheanl.libmmd.client.animation.ActionLibrary;
import com.micheanl.libmmd.runtime.NativeRuntime;

import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

public final class VerifyActions {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: VerifyActions <model.mmdpack> <actions.zip> <report.json>");
        ActionCatalog catalog;
        try (var input = Files.newInputStream(Path.of(args[1]))) {
            catalog = ActionCatalog.read(input);
        }
        var results = new ArrayList<String>();
        long sampleCount = 0;
        try (var runtime = NativeRuntime.open(1);
             var model = runtime.loadPack(Path.of(args[0]));
             var library = new ActionLibrary(model, catalog);
             var pose = model.createPose();
             var scene = runtime.scenes().create();
             var instance = scene.createInstance(model)) {
            for (var name : catalog.names()) {
                var definition = catalog.definition(name);
                var motion = library.motion(name);
                if (motion.info().durationFrames() != definition.durationFrames()) {
                    throw new IllegalStateException("Bound duration differs: " + name);
                }
                var matrices = pose.matrices().data();
                float maximumAbsoluteValue = 0.0f;
                for (int frame = 0; frame <= definition.durationFrames(); frame++) {
                    motion.apply(frame / 30.0f, false, pose);
                    for (long offset = 0; offset < matrices.byteSize(); offset += Float.BYTES) {
                        var value = matrices.get(ValueLayout.JAVA_FLOAT, offset);
                        if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite matrix: " + name + " frame " + frame);
                        maximumAbsoluteValue = Math.max(maximumAbsoluteValue, Math.abs(value));
                    }
                    sampleCount++;
                }
                motion.apply(0.0f, false, pose);
                var first = matrices.toArray(ValueLayout.JAVA_FLOAT);
                if (definition.looping()) {
                    motion.apply(definition.durationFrames() / 30.0f, false, pose);
                    var last = matrices.toArray(ValueLayout.JAVA_FLOAT);
                    for (int index = 0; index < first.length; index++) {
                        if (Math.abs(first[index] - last[index]) > 0.001f) {
                            throw new IllegalStateException("Loop seam mismatch: " + name + " matrix element " + index);
                        }
                    }
                }
                instance.play(motion, definition.looping(), catalog.transitionSeconds());
                scene.update(catalog.transitionSeconds());
                for (var value : instance.matrices().data().toArray(ValueLayout.JAVA_FLOAT)) {
                    if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite transition: " + name);
                }
                results.add(String.format(Locale.ROOT,
                    "{\"name\":\"%s\",\"bound_bones\":%d,\"frames\":%d,\"max_matrix_element\":%.6f}",
                    name, motion.info().boundBoneCount(), definition.durationFrames(), maximumAbsoluteValue));
            }
        }
        var report = "{\"clips\":" + results.size() + ",\"samples\":" + sampleCount +
            ",\"visual_validation\":\"pending\",\"results\":[" + String.join(",", results) + "]}\n";
        Files.writeString(Path.of(args[2]), report);
        System.out.println("Verified " + results.size() + " clips, " + sampleCount + " pose samples");
    }
}

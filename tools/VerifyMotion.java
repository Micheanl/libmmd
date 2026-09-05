import com.micheanl.libmmd.runtime.NativeRuntime;

import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VerifyMotion {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: VerifyMotion <model.mmdpack> <source.vmd> <export.vmd>");
        }
        try (var runtime = NativeRuntime.open(2);
             var model = runtime.loadPack(Path.of(args[0]));
             var original = model.loadMotion(Files.readAllBytes(Path.of(args[1])));
             var exported = model.loadMotion(Files.readAllBytes(Path.of(args[2])));
             var scene = runtime.scenes().create();
             var originalInstance = scene.createInstance(model);
             var exportedInstance = scene.createInstance(model)) {
            if (original.info().boundBoneCount() == 0 || exported.info().boundBoneCount() == 0) {
                throw new IllegalStateException("Motion has no bound bones");
            }
            originalInstance.play(original, true, 0.0f);
            exportedInstance.play(exported, true, 0.0f);
            float maximumError = 0.0f;
            int maximumErrorSample = 0;
            int maximumErrorBone = 0;
            if (original.info().durationFrames() != exported.info().durationFrames()) {
                throw new IllegalStateException("Motion durations differ");
            }
            int samples = original.info().durationFrames() + 1;
            for (int sample = 0; sample < samples; sample++) {
                scene.update(sample == 0 ? 0.0f : 1.0f / 30.0f);
                var sourceMatrices = originalInstance.renderPacket().matrices();
                var exportMatrices = exportedInstance.renderPacket().matrices();
                if (sourceMatrices.byteSize() != exportMatrices.byteSize()) {
                    throw new IllegalStateException("Matrix buffer sizes differ");
                }
                for (long offset = 0; offset < sourceMatrices.byteSize(); offset += Float.BYTES) {
                    float a = sourceMatrices.get(ValueLayout.JAVA_FLOAT, offset);
                    float b = exportMatrices.get(ValueLayout.JAVA_FLOAT, offset);
                    if (!Float.isFinite(a) || !Float.isFinite(b)) {
                        throw new IllegalStateException("Non-finite pose at sample " + sample);
                    }
                    float error = Math.abs(a - b);
                    if (error > maximumError) {
                        maximumError = error;
                        maximumErrorSample = sample;
                        maximumErrorBone = Math.toIntExact(offset / (16L * Float.BYTES));
                    }
                }
            }
            System.out.printf(
                java.util.Locale.ROOT,
                "{\"samples\":%d,\"bones\":%d,\"source_bound_bones\":%d,\"export_bound_bones\":%d,\"maximum_matrix_error\":%.9f,\"maximum_error_sample\":%d,\"maximum_error_bone\":%d}%n",
                samples, model.info().boneCount(), original.info().boundBoneCount(),
                exported.info().boundBoneCount(), maximumError, maximumErrorSample, maximumErrorBone
            );
            if (maximumError > 0.001f) {
                throw new IllegalStateException("Motion roundtrip changed the evaluated pose");
            }
        }
    }
}

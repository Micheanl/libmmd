import com.micheanl.libmmd.runtime.NativeRuntime;
import com.micheanl.libmmd.runtime.SceneRuntime;

import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

public final class VerifyModelPhysics {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                "Usage: VerifyModelPhysics <model.mmdpack> <motion.vmd> <durationSeconds> " +
                "[--realtime] [--maximum-matrix-magnitude <limit>]");
        }
        var realtime = false;
        var magnitudeLimit = Float.POSITIVE_INFINITY;
        for (var argument = 3; argument < args.length; argument++) {
            if (args[argument].equals("--realtime")) {
                realtime = true;
            } else if (args[argument].equals("--maximum-matrix-magnitude") && argument + 1 < args.length) {
                magnitudeLimit = Float.parseFloat(args[++argument]);
                if (!Float.isFinite(magnitudeLimit) || magnitudeLimit <= 0.0f) {
                    throw new IllegalArgumentException("maximum matrix magnitude must be finite and positive");
                }
            } else {
                throw new IllegalArgumentException("Unknown or incomplete option: " + args[argument]);
            }
        }
        var durationSeconds = Double.parseDouble(args[2]);
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0.0) {
            throw new IllegalArgumentException("durationSeconds must be finite and positive");
        }
        var frameSeconds = 1.0f / 60.0f;
        var sampleCount = Math.ceil(durationSeconds / frameSeconds);
        if (sampleCount > Integer.MAX_VALUE) throw new IllegalArgumentException("durationSeconds is too large");
        var samples = (int) sampleCount;
        try (var runtime = NativeRuntime.open(2);
             var model = runtime.loadPack(Path.of(args[0]));
             var motion = model.loadMotion(Files.readAllBytes(Path.of(args[1])));
             var animationScene = runtime.scenes().create();
             var physicsScene = runtime.scenes().create(0.25f, SceneRuntime.PhysicsConfig.defaults());
             var animationInstance = animationScene.createInstance(model);
             var physicsInstance = physicsScene.createInstance(model)) {
            animationInstance.play(motion, true, 0.0f);
            physicsInstance.play(motion, true, 0.0f);
            physicsInstance.resetPhysics();
            var borrowedMatrices = physicsInstance.matrices().data();
            var maximumDifference = 0.0f;
            var maximumMagnitude = 0.0f;
            var maximumMagnitudeBone = -1;
            var maximumMagnitudeSample = -1;
            var maximumMagnitudeComponent = -1;
            long sceneUpdateNanos = 0;
            var runStarted = System.nanoTime();
            for (var sample = 0; sample < samples; sample++) {
                animationScene.update(frameSeconds);
                var started = System.nanoTime();
                var update = physicsScene.update(frameSeconds);
                sceneUpdateNanos += System.nanoTime() - started;
                if (update.droppedTime()) throw new IllegalStateException("Physics dropped time at sample " + sample);
                var animationMatrices = animationInstance.matrices().data();
                var physicsMatrices = physicsInstance.renderPacket().matrices();
                if (physicsMatrices.address() != borrowedMatrices.address()) {
                    throw new IllegalStateException("Physics matrix storage moved");
                }
                for (long offset = 0; offset < physicsMatrices.byteSize(); offset += Float.BYTES) {
                    var animated = animationMatrices.get(ValueLayout.JAVA_FLOAT, offset);
                    var simulated = physicsMatrices.get(ValueLayout.JAVA_FLOAT, offset);
                    if (!Float.isFinite(animated) || !Float.isFinite(simulated)) {
                        throw new IllegalStateException("Non-finite matrix at sample " + sample);
                    }
                    if (Math.abs(simulated) > magnitudeLimit) {
                        throw new IllegalStateException("Matrix magnitude exceeded " + magnitudeLimit +
                            " at sample " + sample + ", bone " + offset / (16 * Float.BYTES));
                    }
                    maximumDifference = Math.max(maximumDifference, Math.abs(simulated - animated));
                    if (Math.abs(simulated) > maximumMagnitude) {
                        maximumMagnitude = Math.abs(simulated);
                        maximumMagnitudeBone = (int) (offset / (16 * Float.BYTES));
                        maximumMagnitudeComponent = (int) (offset / Float.BYTES % 16);
                        maximumMagnitudeSample = sample;
                    }
                }
                if (realtime) {
                    var deadline = runStarted + (long) ((sample + 1.0) * frameSeconds * 1.0e9);
                    for (long remaining; (remaining = deadline - System.nanoTime()) > 0;) {
                        LockSupport.parkNanos(remaining);
                        if (Thread.interrupted()) throw new InterruptedException("Physics verification interrupted");
                    }
                }
            }
            var wallSeconds = (System.nanoTime() - runStarted) / 1.0e9;
            if (maximumDifference == 0.0f) throw new IllegalStateException("Physics did not change any bone matrix");
            physicsInstance.resetPhysics();
            var animationMatrices = animationInstance.matrices().data();
            for (long offset = 0; offset < borrowedMatrices.byteSize(); offset += Float.BYTES) {
                if (borrowedMatrices.get(ValueLayout.JAVA_FLOAT, offset) !=
                    animationMatrices.get(ValueLayout.JAVA_FLOAT, offset)) {
                    throw new IllegalStateException("Physics reset did not restore the animated pose");
                }
            }
            System.out.printf(Locale.ROOT,
                "{\"samples\":%d,\"simulated_seconds\":%.3f,\"bones\":%d,\"rigid_bodies\":%d," +
                "\"joints\":%d,\"maximum_matrix_difference\":%.6f,\"maximum_matrix_magnitude\":%.6f," +
                "\"maximum_magnitude_bone\":%d,\"maximum_magnitude_component\":%d,\"maximum_magnitude_sample\":%d," +
                "\"mean_scene_update_ms\":%.3f,\"wall_seconds\":%.3f,\"realtime\":%b," +
                "\"reset_matches_animation\":true}%n",
                samples, samples * frameSeconds, model.info().boneCount(), model.info().rigidBodyCount(),
                model.info().jointCount(), maximumDifference, maximumMagnitude,
                maximumMagnitudeBone, maximumMagnitudeComponent, maximumMagnitudeSample,
                sceneUpdateNanos / (samples * 1.0e6), wallSeconds, realtime);
        }
    }
}

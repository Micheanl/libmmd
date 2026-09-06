import com.micheanl.libmmd.client.animation.ActionCatalog;
import com.micheanl.libmmd.client.animation.ActionLibrary;
import com.micheanl.libmmd.client.runtime.ClientNativeRuntime;
import com.micheanl.libmmd.client.runtime.ClientPhysicsSettings;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Arrays;

public final class VerifyPlayerPhysics {
    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Usage: VerifyPlayerPhysics <model.mmdpack> <config-directory>");
        var settings = ClientPhysicsSettings.load(Path.of(args[1]));
        if (!settings.enabled()) throw new IllegalArgumentException("Verification requires physics enabled");
        try (var client = ClientNativeRuntime.open(settings);
             var model = client.runtime().loadPack(Path.of(args[0]));
             var actions = new ActionLibrary(model, ActionCatalog.bundled());
             var body = client.scene().createInstance(model);
             var reference = client.firstPersonScene().createInstance(model)) {
            body.play(actions.motion("move_walk"), true, 0);
            reference.play(actions.motion("move_walk"), true, 0);
            body.resetPhysics();
            int differentFrames = 0;
            for (int tick = 0; tick < 300; tick++) {
                if (tick % 60 == 0) {
                    var motion = actions.upperBodyMotion(tick % 120 == 0 ? "use_bow" : "combat_sword_light_1");
                    body.playOverlay(motion, false, 0.12f);
                    reference.playOverlay(motion, false, 0.12f);
                }
                client.update(1.0f / 20.0f);
                var animated = reference.matrices().data().toArray(ValueLayout.JAVA_FLOAT);
                var physical = body.renderPacket().matrices().toArray(ValueLayout.JAVA_FLOAT);
                if (!Arrays.equals(animated, physical)) differentFrames++;
                for (float value : physical) if (!Float.isFinite(value)) throw new AssertionError("Nonfinite physics pose at " + tick);
                if (tick == 149) {
                    body.resetPhysics();
                    var reset = body.matrices().data().toArray(ValueLayout.JAVA_FLOAT);
                    for (int i = 0; i < reset.length; i++) {
                        if (Math.abs(reset[i] - animated[i]) > 0.001f) throw new AssertionError("Physics reset does not restore current animation");
                    }
                }
            }
            if (model.info().rigidBodyCount() == 0 || differentFrames == 0) throw new AssertionError("No physical response");
            System.out.println("Verified client physics scenes: rigid bodies=" + model.info().rigidBodyCount() +
                ", joints=" + model.info().jointCount() + ", frames=300, physical response frames=" + differentFrames +
                "; reset matched animated pose; visual validation pending");
        }
    }
}

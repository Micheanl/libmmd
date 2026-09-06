package com.micheanl.libmmd.client.render;

import com.micheanl.libmmd.client.animation.ActionCatalog;
import com.micheanl.libmmd.client.animation.ActionLibrary;
import com.micheanl.libmmd.runtime.NativeRuntime;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

public final class VerifyPlayerLayers {
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Usage: VerifyPlayerLayers <model.mmdpack>");
        var catalog = ActionCatalog.bundled();
        int checked = 0;
        try (var runtime = NativeRuntime.open(1);
             var model = runtime.loadPack(Path.of(args[0]));
             var actions = new ActionLibrary(model, catalog);
             var scene = runtime.scenes().create();
             var base = scene.createInstance(model);
             var layered = scene.createInstance(model)) {
            var arms = ArmMesh.select(model);
            if (!Float.isFinite(arms.eyeHeight()) || arms.ranges().stream().noneMatch(ArmMesh.Range::left) ||
                arms.ranges().stream().allMatch(ArmMesh.Range::left)) throw new AssertionError("Missing first person arms/head");
            for (String action : catalog.names()) {
                if (!catalog.hasUpperBody(action)) continue;
                layered.stopOverlay(0);
                base.play(actions.motion("move_walk"), true, 0);
                layered.play(actions.motion("move_walk"), true, 0);
                layered.playOverlay(actions.upperBodyMotion(action), catalog.definition(action).looping(), catalog.transitionSeconds());
                if (!layered.overlayState().playing()) throw new AssertionError("Overlay not playing: " + action);
                for (int tick = 0; tick < 5; tick++) {
                    scene.update(0.05f);
                    var expected = base.matrices().data().toArray(ValueLayout.JAVA_FLOAT);
                    var actual = layered.matrices().data().toArray(ValueLayout.JAVA_FLOAT);
                    for (int bone = 0; bone < model.info().boneCount(); bone++) {
                        String name = model.bone(bone).name();
                        if (name.equals("全ての親") || name.equals("センター") || name.equals("下半身") ||
                            name.equals("左足") || name.equals("右足") || name.equals("左ひざ") || name.equals("右ひざ")) {
                            for (int element = bone * 16; element < (bone + 1) * 16; element++) {
                                if (Math.abs(expected[element] - actual[element]) > 0.001f)
                                    throw new AssertionError(action + " overrides locomotion bone " + name);
                            }
                        }
                    }
                    for (float value : actual) if (!Float.isFinite(value)) throw new AssertionError("Nonfinite pose: " + action);
                }
                layered.stopOverlay(catalog.transitionSeconds());
                scene.update(catalog.transitionSeconds());
                if (layered.overlayState().playing()) throw new AssertionError("Overlay did not stop");
                checked++;
            }
            int triangles = arms.ranges().stream().mapToInt(ArmMesh.Range::count).sum() / 3;
            System.out.println("Verified " + checked + " upper body layers through FFM; first person arm triangles=" + triangles +
                "; material ranges=" + arms.ranges().size() + "; GPU/visual validation pending");
        }
    }
}

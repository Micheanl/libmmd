"""Write an explicit input-to-render audit; asset presence never implies live usage."""
import csv
import collections
import io
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WEAPONS = ("sword", "axe", "spear", "trident", "mace", "tool", "unarmed")
USES = ("item", "eat", "drink", "block", "bow", "trident", "crossbow_charge", "spyglass", "toot_horn", "brush", "bundle", "spear")
INTERACTIONS = ("place", "interact", "drop", "throw", "fish_cast", "fish_reel", "bucket", "ignite", "shear", "leash", "inventory", "book")


def routes():
    wired = {}
    for left in ("", "_left"):
        for use in USES:
            wired["use_" + use + left] = "END_CLIENT_TICK -> VanillaActionSampler.useAction"
        for action in INTERACTIONS:
            wired["interact_" + action + left] = "PlayerInteractionMixin -> triggerAction -> pendingActions -> sample"
        wired["interact_mine" + left] = "isDestroying + hand swing -> VanillaActionSampler.sample"
        wired["attack_stab" + left] = "STAB hand swing -> VanillaActionSampler.sample"
        for weapon in WEAPONS:
            for critical in ("", "_critical"):
                wired["attack_" + weapon + critical + left] = "hand swing fallback -> VanillaActionSampler.sample (critical is inferred)"
            for phase, count in (("light", 3), ("air", 2)):
                for index in range(1, count + 1):
                    wired[f"combat_{weapon}_{phase}_{index}{left}"] = "MultiPlayerGameMode.attack RETURN -> ClientBootstrap.attack -> CombatActionController -> pendingActions -> sample (local only)"
    return wired


def main():
    wired = routes()
    rows = []
    with zipfile.ZipFile(ROOT / "src/main/resources/assets/libmmd/actions.zip") as archive:
        index = csv.reader(io.StringIO(archive.read("index.tsv").decode()), delimiter="\t")
        for name, _, _, category, view in index:
            base = name.removeprefix("fp_")
            input_path = wired.get(base)
            if view == "third_person" and (category == "move" or name.startswith("move_") or name in ("react_hurt", "react_death", "react_freeze")):
                input_path = "END_CLIENT_TICK -> VanillaActionSampler.movement / PlayerActionController.transition or hurt edge"
            status = "wired_not_playtested" if input_path else "asset_only"
            render_path = "none"
            if input_path:
                if view == "first_person":
                    render_path = "tick playback -> fp_ clip -> FirstPersonInstance -> submitHandsWithItems -> RenderFeature"
                    if base.startswith("use_spyglass"):
                        status = "vanilla_fallback"
                        render_path = "scope suppresses custom hands; not counted as rendered"
                else:
                    layer = "playOverlay(upper/)" if "upper/" + name + ".vmd" in archive.namelist() else "play(base)"
                    render_path = "PlayerInstance -> " + layer + " -> native scene -> RenderFeature"
            rows.append((name, view, status, input_path or "none", render_path, "pending"))
    output = ROOT / "tools/motion/action_coverage.tsv"
    with output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(("action", "view", "status", "input_path", "render_path", "visual_validation"))
        writer.writerows(sorted(rows))
    print(dict(collections.Counter(row[2] for row in rows)))
    print("Coverage enumerates source wiring, not runtime or visual acceptance.")


if __name__ == "__main__":
    main()

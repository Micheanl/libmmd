import math
from dataclasses import replace

from . import rig
from .clips import (
    Body, Clip, LEFT, RIGHT, cycle, keyed, mirror, move_idle, move_walk,
    move_sneak_idle, move_climb, move_fly, move_ride, move_sleep,
    move_glide, jump_ready, relaxed_arms, grip, swing_frame, stab_frame,
)
from .vmd import BoneKey, LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT


def directional_walk(phase, direction, sneaking=False):
    body = move_sneak_idle(phase) if sneaking else move_walk(phase)
    radians = math.radians(direction)
    reach = 1.5 if sneaking else 2.4
    for side in (LEFT, RIGHT):
        stride = math.cos(math.tau * phase + (0 if side == LEFT else math.pi))
        lift = max(0.0, math.sin(math.tau * phase + (0 if side == LEFT else math.pi)))
        name = rig.bone(side, "足ＩＫ")
        body.transforms[name] = [
            (reach * stride * math.sin(radians), lift * (0.4 if sneaking else 0.7),
             reach * stride * math.cos(radians)), rig.IDENTITY]
    body.upper_body(yaw=direction * 0.06)
    return body


def turn_pose(amount, direction):
    body = jump_ready()
    body.center(y=-0.25 - 0.25 * amount, yaw=direction * 12.0 * amount)
    body.upper_body(yaw=direction * 8.0 * amount)
    body.head(yaw=direction * 14.0 * amount)
    for side in (LEFT, RIGHT):
        body.foot(side, y=0.5 * amount if side == direction else 0.0, yaw=direction * 18.0 * amount)
    return body


def dodge_pose(amount, x, z):
    body = Body()
    body.center(x=x * amount * 1.8, y=-0.3 - 2.0 * amount, z=z * amount * 1.8)
    body.upper_body(pitch=12.0 * amount, roll=-x * 15.0 * amount)
    body.head(pitch=-8.0 * amount, roll=x * 8.0 * amount)
    for side in (LEFT, RIGHT):
        body.arm(side, down=24.0, forward=25.0 - z * 30.0 * amount,
                 elbow=55.0, inward=8.0)
        body.foot(side, x=x * amount * 1.6 + side * 0.5 * amount,
                  z=z * amount * 1.6, y=0.3 * amount)
    return body


def interaction_pose(kind, amount):
    body = jump_ready()
    body.head(pitch=amount * (18.0 if kind in ("pickup", "inventory", "book") else 3.0))
    body.upper_body(pitch=amount * (25.0 if kind == "pickup" else 5.0))
    body.center(y=-0.25 - amount * (1.5 if kind == "pickup" else 0.1))
    if kind == "throw":
        body.arm(RIGHT, down=30, forward=145.0 - 100.0 * amount, elbow=75.0 * (1.0 - amount))
    elif kind in ("book", "inventory", "map"):
        body.arm(LEFT, down=38, forward=35, inward=22, elbow=65)
        body.arm(RIGHT, down=34, forward=30 + 18 * amount, inward=25, elbow=75 - 20 * amount)
    elif kind == "swap":
        for side in (LEFT, RIGHT):
            body.arm(side, down=38, forward=20 + 30 * amount, inward=30 * amount, elbow=55)
    else:
        body.arm(RIGHT, down=35, forward=10 + 65 * amount, inward=5, elbow=55 * (1.0 - amount))
    grip(body, RIGHT, 0.4 if kind in ("place", "pickup") else 0.8)
    return body


def weapon_pose(weapon, stage, combo=0, heavy=False, airborne=False):
    strength = (0.0, 1.0, -0.75, -0.35)[stage]
    if weapon in ("spear", "trident"):
        body = stab_frame(("hold", "pull", "thrust", "follow")[stage])
        body.upper_body(yaw=combo * 6.0 * strength)
        if weapon == "trident":
            body.arm(LEFT, down=35, forward=-10, elbow=20)
    else:
        body = swing_frame(("hold", "windup", "strike", "follow")[stage])
        if weapon in ("axe", "mace"):
            body.arm(RIGHT, down=20, forward=25 * strength, elbow=20 * max(0.0, strength))
            body.arm(LEFT, down=26, forward=35 + 35 * strength, inward=28, elbow=65)
            grip(body, LEFT)
            body.upper_body(pitch=-10 * strength)
        elif weapon == "unarmed":
            body.arm(RIGHT, down=30, forward=15 - 35 * strength, inward=12, elbow=45 + 40 * strength)
            body.arm(LEFT, down=32, forward=40, elbow=90)
        elif weapon == "tool":
            body.upper_body(pitch=-8 * strength, yaw=-6 * strength)
        body.upper_body(yaw=(combo - 1) * 18 * strength, roll=combo * 4 * strength)
    if heavy:
        body.center(y=-0.45 * abs(strength))
        body.upper_body(pitch=-9 * strength, yaw=-8 * strength)
    if airborne:
        for side in (LEFT, RIGHT):
            body.leg(side, forward=18 + side * 10 * strength, knee=42 + 12 * abs(strength), ankle=20)
    return body


def attack_clip(name, weapon, combo=0, heavy=False, airborne=False):
    timings = (0, 12, 17, 23, 34) if heavy else (0, 3, 6, 9, 14)
    return keyed(name, [
        (timings[0], weapon_pose(weapon, 0, combo, heavy, airborne), LINEAR),
        (timings[1], weapon_pose(weapon, 1, combo, heavy, airborne), EASE_IN),
        (timings[2], weapon_pose(weapon, 2, combo, heavy, airborne), EASE_OUT),
        (timings[3], weapon_pose(weapon, 3, combo, heavy, airborne), EASE_IN_OUT),
        (timings[4], weapon_pose(weapon, 0, combo, heavy, airborne), EASE_IN_OUT),
    ], category="combat" if name.startswith("combat") else "attack", hand="right",
        grounded=not airborne, description=f"{weapon} {'空中' if airborne else ''}{'重击' if heavy else '挥击'} {combo + 1}",
        matrix=("武器 " + weapon,))


def guard_pose(amount):
    body = jump_ready()
    body.center(y=-0.6 * amount)
    body.upper_body(pitch=5.0 * amount, yaw=-8.0 * amount)
    for side in (LEFT, RIGHT):
        body.arm(side, down=28, forward=55 + 8 * amount, inward=25, elbow=85)
        grip(body, side)
    return body


def extend_clips():
    clips = []
    for suffix, degrees in (("back", 180), ("left", 90), ("right", -90),
                            ("forward_left", 45), ("forward_right", -45),
                            ("back_left", 135), ("back_right", -135)):
        clips.append(cycle("move_walk_" + suffix, 28, 1,
                           lambda phase, angle=degrees: directional_walk(phase, angle),
                           category="move", description="八方向行走：" + suffix, matrix=("MOVE-01",), cycle_distance=8.0))
    clips += [
        keyed("move_start", [(0, move_idle(0), LINEAR), (4, move_walk(0.8), EASE_IN),
                              (10, move_walk(0), EASE_OUT)], category="move", description="从待机起步", matrix=("MOVE-02",)),
        keyed("move_stop", [(0, move_walk(0.5), LINEAR), (5, dodge_pose(0.25, 0, -1), EASE_OUT),
                             (14, jump_ready(), EASE_IN_OUT)], category="move", description="停止缓冲", matrix=("MOVE-02",)),
        cycle("move_climb_down", 32, 2, lambda phase: move_climb(1.0 - phase),
              category="move", description="向下攀爬", matrix=("MOVE-08",), grounded=False),
        cycle("move_climb_idle", 60, 3, lambda phase: move_climb(0.2 + 0.008 * math.sin(math.tau * phase)),
              category="move", description="攀爬停留与抓握", matrix=("MOVE-08",), grounded=False),
        cycle("move_fall", 48, 2, lambda phase: falling_pose(phase),
              category="move", description="下落张臂平衡", matrix=("MOVE-04",), grounded=False),
        cycle("move_fly_up", 60, 2, lambda phase: flying_pose(phase, 1),
              category="move", description="能力飞行上升", matrix=("MOVE-07",), grounded=False),
        cycle("move_fly_down", 60, 2, lambda phase: flying_pose(phase, -1),
              category="move", description="能力飞行下降", matrix=("MOVE-07",), grounded=False),
        cycle("move_riptide", 18, 1, riptide_pose,
              category="move", description="激流旋转冲刺", matrix=("MOVE-11",), grounded=False),
        cycle("move_boat", 40, 2, boat_pose, category="move", description="乘船划桨",
              matrix=("MOVE-09",), grounded=False),
        cycle("move_minecart", 60, 3, lambda phase: move_ride(phase), category="move", description="矿车乘坐",
              matrix=("MOVE-09",), grounded=False),
        keyed("move_mount", [(0, jump_ready(), LINEAR), (8, move_ride(0), EASE_OUT),
                             (16, move_ride(0.1), EASE_IN_OUT)], category="move", description="上坐骑", matrix=("MOVE-09",), grounded=False),
        keyed("move_dismount", [(0, move_ride(0), LINEAR), (8, dodge_pose(0.5, 1, 0), EASE_OUT),
                                (16, jump_ready(), EASE_IN_OUT)], category="move", description="下坐骑", matrix=("MOVE-09",), grounded=False),
        cycle("react_freeze", 18, 1, freeze_pose, category="react", description="寒冷发抖", matrix=("MOVE-12",)),
        cycle("use_map", 90, 3, lambda phase: interaction_pose("map", 0.7 + 0.1 * math.sin(math.tau * phase)),
              category="use", description="双手查看地图", hand="both", matrix=("NONE",)),
        cycle("use_fishing_hold", 90, 3, lambda phase: interaction_pose("hold", 0.35),
              category="use", description="持竿等待", hand="right", matrix=("瞬时物品",)),
    ]
    for suffix, direction in (("left", LEFT), ("right", RIGHT)):
        clips.append(keyed("move_turn_" + suffix, [(0, turn_pose(0, direction), LINEAR),
            (8, turn_pose(1, direction), EASE_OUT), (18, turn_pose(0, direction), EASE_IN_OUT)],
            category="move", description="原地转向：" + suffix, matrix=("MOVE-01",)))
    for name, kind in (("place", "place"), ("interact", "place"), ("pickup", "pickup"),
                       ("drop", "throw"), ("throw", "throw"), ("fish_cast", "throw"),
                       ("fish_reel", "hold"), ("bucket", "place"), ("ignite", "place"),
                       ("feed", "place"), ("shear", "hold"), ("leash", "place"),
                       ("equip", "swap"), ("swap_hands", "swap"), ("inventory", "inventory"), ("book", "book")):
        clips.append(keyed("interact_" + name, [(0, interaction_pose(kind, 0), LINEAR),
            (6, interaction_pose(kind, 1), EASE_OUT), (16, interaction_pose(kind, 0), EASE_IN_OUT)],
            category="interact", description="瞬时交互：" + name, hand="right", matrix=("第5节交互",)))
    for weapon in ("sword", "axe", "spear", "trident", "mace", "tool", "unarmed"):
        clips.append(attack_clip("attack_" + weapon, weapon))
        clips.append(attack_clip("attack_" + weapon + "_critical", weapon, airborne=True))
        for index in range(3):
            clips.append(attack_clip(f"combat_{weapon}_light_{index + 1}", weapon, index))
        for index in range(2):
            clips.append(attack_clip(f"combat_{weapon}_air_{index + 1}", weapon, index, airborne=True))
        clips.append(attack_clip("combat_" + weapon + "_heavy", weapon, heavy=True))
        clips.append(cycle("combat_" + weapon + "_charge", 60, 3,
            lambda phase, kind=weapon: charge_pose(kind, phase), category="combat", hand="right",
            description="蓄力保持：" + weapon, matrix=("阶段6 蓄力",)))
    clips.append(attack_clip("attack_mace_smash", "mace", heavy=True, airborne=True))
    clips.append(attack_clip("attack_sword_sweep", "sword", combo=2))
    clips.append(attack_clip("interact_mine", "tool"))
    for name, x, z in (("forward", 0, 1), ("back", 0, -1), ("left", 1, 0), ("right", -1, 0)):
        clips.append(keyed("combat_dodge_" + name, [(0, dodge_pose(0, x, z), LINEAR),
            (4, dodge_pose(1, x, z), EASE_OUT), (10, dodge_pose(0.65, x, z), LINEAR),
            (18, dodge_pose(0, x, z), EASE_IN_OUT)], category="combat", description="方向闪避：" + name,
            matrix=("阶段6 闪避",)))
    clips += [
        cycle("combat_guard", 60, 3, lambda phase: guard_pose(0.8 + 0.04 * math.sin(math.tau * phase)),
              category="combat", description="防御保持", matrix=("阶段6 格挡",)),
        keyed("combat_parry", [(0, guard_pose(0.6), LINEAR), (3, guard_pose(1), EASE_OUT),
                               (10, guard_pose(0.6), EASE_IN_OUT)], category="combat", description="格挡反弹", matrix=("阶段6 格挡",)),
        attack_clip("combat_counter", "sword", combo=2),
        cycle("combat_lock_on", 60, 3, lambda phase: guard_pose(0.5 + 0.03 * math.sin(math.tau * phase)),
              category="combat", description="锁定戒备", matrix=("阶段6 锁定",)),
    ]
    return clips


def falling_pose(phase):
    body = move_fly(phase)
    body.upper_body(pitch=-8)
    body.arm(LEFT, down=8, forward=15, elbow=30)
    body.arm(RIGHT, down=8, forward=15, elbow=30)
    return body


def flying_pose(phase, direction):
    body = move_fly(phase)
    body.upper_body(pitch=direction * -8)
    body.arm(LEFT, down=direction * -12, forward=direction * 12, elbow=25)
    body.arm(RIGHT, down=direction * -12, forward=direction * 12, elbow=25)
    return body


def riptide_pose(phase):
    body = move_glide(phase)
    body.arm(RIGHT, down=0, forward=170, elbow=5)
    body.arm(LEFT, down=0, forward=150, elbow=15)
    body.rotate("全ての親", rig.compose(rig.ry(360 * phase), rig.rx(90)))
    body.translate("全ての親", y=3, z=-8)
    return body


def boat_pose(phase):
    body = move_ride(phase)
    stroke = math.sin(math.tau * phase)
    for side in (LEFT, RIGHT):
        body.arm(side, down=30, forward=30 + 30 * stroke, elbow=60 - 25 * stroke, inward=12)
    body.upper_body(pitch=6 * stroke)
    return body


def freeze_pose(phase):
    body = guard_pose(0.6)
    body.upper_body(roll=2 * math.sin(math.tau * phase * 3))
    body.head(yaw=2 * math.sin(math.tau * phase * 3))
    return body


def charge_pose(weapon, phase):
    body = weapon_pose(weapon, 1, heavy=True)
    body.upper_body(pitch=0.5 * math.sin(math.tau * phase * 2))
    return body


def orient_clips(clips):
    orientations = {
        "move_swim": ((0, 3, -8), rig.rx(90)),
        "move_crawl": ((0, 2, -8), rig.rx(90)),
        "move_glide": ((0, 4, -8), rig.rx(85)),
        "move_sleep": ((0, 1.8, 8), rig.rx(-90)),
    }
    for clip in clips:
        if clip.name not in orientations:
            continue
        translation, rotation = orientations[clip.name]
        clip.bone_keys += [BoneKey("全ての親", frame, translation, rotation) for frame in (0, clip.duration)]
        clip.description = clip.description.split("；")[0] + "；全身朝向已烘焙到根骨骼"


def first_person_clips(clips):
    prefixes = ("左肩", "右肩", "左腕", "右腕", "左ひじ", "右ひじ", "左手", "右手", "左親指", "右親指",
                "左人指", "右人指", "左中指", "右中指", "左薬指", "右薬指", "左小指", "右小指")
    result = []
    for clip in clips:
        if clip.category not in ("attack", "use", "interact", "combat") or clip.name.startswith("combat_dodge"):
            continue
        keys = [key for key in clip.bone_keys if key.bone.startswith(prefixes)]
        if keys:
            result.append(replace(clip, name="fp_" + clip.name, bone_keys=keys, morph_keys=[],
                legacy=(), grounded=False, view="first_person", description="第一人称手臂：" + clip.description,
                mirrored_from="fp_" + clip.mirrored_from if clip.mirrored_from else None))
    return result

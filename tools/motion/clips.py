"""Clip definitions for the player animation set.

Conventions (right-handed authoring frame, see ``rig.py``): pitch > 0 leans
forward, yaw > 0 turns toward the character's left, roll > 0 raises the left
side; arm ``down`` lowers the arm toward the body, ``forward`` swings it
forward, ``inward`` sweeps it toward the midline; leg ``forward`` lifts the
thigh, ``knee``/``ankle`` > 0 bend backward/point the toes; foot IK ``pitch``
> 0 points the toes down. Rotations are degrees, translations model units.
"""
import math
from dataclasses import dataclass, field

from . import rig
from .rig import LEFT, RIGHT, bone, rx, ry, rz, compose, quaternion
from .vmd import BoneKey, MorphKey, LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT

FRAME_RATE = 30
TAU = math.tau


@dataclass
class Clip:
    name: str
    duration: int
    loop: bool
    category: str
    description: str
    matrix: tuple = ()
    legacy: tuple = ()
    cycle_distance: float | None = None
    grounded: bool = True
    hand: str = "none"
    bone_keys: list = field(default_factory=list)
    morph_keys: list = field(default_factory=list)
    mirrored_from: str | None = None
    view: str = "third_person"

    def bones(self):
        return sorted({key.bone for key in self.bone_keys})


def smoothstep(value):
    value = min(1.0, max(0.0, value))
    return value * value * (3.0 - 2.0 * value)


def wave(phase, offset=0.0):
    return math.sin(TAU * phase + offset)


def foot_lift_for_pitch(pitch):
    """Ankle rise that keeps the toe (pitch > 0) or heel (pitch < 0) on the ground."""
    radians = math.radians(pitch)
    toe = 1.078 * math.sin(radians) - 1.232 * (1.0 - math.cos(radians))
    heel = 0.6 * math.sin(-radians) - 1.55 * (1.0 - math.cos(radians))
    return max(0.0, toe, heel)


class Body:
    def __init__(self):
        self.transforms = {}
        self._fk_legs = set()

    def _entry(self, name):
        return self.transforms.setdefault(name, [rig.ZERO, rig.IDENTITY])

    def translate(self, name, x=0.0, y=0.0, z=0.0):
        entry = self._entry(name)
        entry[0] = rig.add(entry[0], (x, y, z))

    def rotate(self, name, rotation):
        entry = self._entry(name)
        entry[1] = rig.multiply(rotation, entry[1])

    def turn(self, name, pitch=0.0, yaw=0.0, roll=0.0):
        self.rotate(name, rig.euler(pitch, yaw, roll))

    def center(self, x=0.0, y=0.0, z=0.0, pitch=0.0, yaw=0.0, roll=0.0):
        self.translate("センター", x, y, z)
        self.turn("センター", pitch, yaw, roll)

    def groove(self, y=0.0):
        self.translate("グルーブ", y=y)

    def lower_body(self, pitch=0.0, yaw=0.0, roll=0.0):
        self.turn("下半身", pitch, yaw, roll)

    def upper_body(self, pitch=0.0, yaw=0.0, roll=0.0):
        self.turn("上半身", pitch, yaw, roll)

    def upper_body2(self, pitch=0.0, yaw=0.0, roll=0.0):
        self.turn("上半身2", pitch, yaw, roll)

    def neck(self, pitch=0.0, yaw=0.0, roll=0.0):
        self.turn("首", pitch, yaw, roll)

    def head(self, pitch=0.0, yaw=0.0, roll=0.0):
        self.turn("頭", pitch, yaw, roll)

    def shoulder(self, side, lift=0.0, forward=0.0):
        self.rotate(bone(side, "肩"), compose(rz(side * lift), ry(-side * forward)))

    def arm(self, side, down=0.0, forward=0.0, inward=0.0, twist=0.0, elbow=0.0, wrist_flex=0.0, wrist_bend=0.0):
        upper = bone(side, "腕")
        axis = rig.sub(rig.BIND_POSITIONS[bone(side, "ひじ")], rig.BIND_POSITIONS[upper])
        rotation = compose(
            quaternion(axis, twist) if twist else rig.IDENTITY,
            rz(-side * down),
            rx(-forward),
            ry(-side * inward),
        )
        self.rotate(upper, rotation)
        if elbow:
            self.rotate(bone(side, "ひじ"), quaternion(rig.bend_axis(side, "腕", "ひじ"), elbow))
        if wrist_flex:
            self.rotate(bone(side, "手首"), rz(-side * wrist_flex))
        if wrist_bend:
            self.rotate(bone(side, "手首"), quaternion(rig.bend_axis(side, "ひじ", "手首"), wrist_bend))

    def fingers(self, side, curl=(0.0, 0.0, 0.0), thumb=0.0):
        for finger in rig.FINGER_BONES:
            for joint, amount in zip("１２３", curl):
                if amount:
                    self.rotate(bone(side, finger + joint), rz(-side * amount))
        if thumb:
            self.rotate(bone(side, "親指１"), rz(-side * thumb))
            self.rotate(bone(side, "親指２"), rz(-side * thumb * 0.6))

    def foot(self, side, x=0.0, y=0.0, z=0.0, pitch=0.0, yaw=0.0, roll=0.0):
        name = bone(side, "足ＩＫ")
        self.translate(name, x, y, z)
        self.turn(name, pitch, yaw, roll)

    def leg(self, side, forward=0.0, out=0.0, twist=0.0, knee=0.0, ankle=0.0):
        self.rotate(bone(side, "足"), compose(ry(side * twist), rz(side * out), rx(-forward)))
        if knee:
            self.rotate(bone(side, "ひざ"), rx(knee))
        if ankle:
            self.rotate(bone(side, "足首"), rx(ankle))
        self._fk_legs.add(side)

    def pose(self):
        result = {name: (tuple(translation), rig.normalize_quaternion(rotation))
                  for name, (translation, rotation) in self.transforms.items()}
        for side in self._fk_legs:
            _, ankle, rotation = rig.leg_chain(result, side)
            ik_name = bone(side, "足ＩＫ")
            result[ik_name] = (rig.sub(ankle, rig.BIND_POSITIONS[ik_name]), rotation)
        return result


def cycle(name, duration, step, builder, **meta):
    frames = list(range(0, duration, step)) + [duration]
    poses = [(frame, builder(frame / duration % 1.0).pose()) for frame in frames]
    names = sorted({name for _, pose in poses for name in pose})
    keys = []
    for frame, pose in poses:
        for name_in_pose in names:
            translation, rotation = pose.get(name_in_pose, (rig.ZERO, rig.IDENTITY))
            keys.append(BoneKey(name_in_pose, frame, translation, rotation, LINEAR))
    return Clip(name=name, duration=duration, loop=True, bone_keys=keys, **meta)


def keyed(name, poses, **meta):
    """``poses`` is a list of (frame, Body, curve); the curve eases into that key."""
    resolved = [(frame, body.pose(), curve) for frame, body, curve in poses]
    names = sorted({bone_name for _, pose, _ in resolved for bone_name in pose})
    keys = []
    for frame, pose, curve in resolved:
        for bone_name in names:
            translation, rotation = pose.get(bone_name, (rig.ZERO, rig.IDENTITY))
            keys.append(BoneKey(bone_name, frame, translation, rotation, curve))
    return Clip(name=name, duration=max(frame for frame, _, _ in resolved), loop=False, bone_keys=keys, **meta)


def blink(frames, morph="まばたき"):
    keys = []
    for frame in frames:
        keys += [MorphKey(morph, frame - 3, 0.0), MorphKey(morph, frame, 1.0), MorphKey(morph, frame + 3, 0.0)]
    return keys


def mirror(clip, name):
    def swap(label):
        if label.startswith("左"):
            return "右" + label[1:]
        if label.startswith("右"):
            return "左" + label[1:]
        return label

    hand = {"right": "left", "left": "right"}.get(clip.hand, clip.hand)
    return Clip(
        name=name, duration=clip.duration, loop=clip.loop, category=clip.category,
        description=clip.description + "（左手镜像）", matrix=clip.matrix, legacy=(),
        cycle_distance=clip.cycle_distance, grounded=clip.grounded, hand=hand,
        bone_keys=[BoneKey(swap(key.bone), key.frame,
                           (-key.translation[0], key.translation[1], key.translation[2]),
                           (key.rotation[0], -key.rotation[1], -key.rotation[2], key.rotation[3]),
                           key.curve) for key in clip.bone_keys],
        morph_keys=list(clip.morph_keys), mirrored_from=clip.name,
    )


# ---------------------------------------------------------------- shared layers

def relaxed_arms(body, breath=0.0, swing_left=0.0, swing_right=0.0, down=40.0, elbow=14.0, inward=2.0):
    for side, swing in ((LEFT, swing_left), (RIGHT, swing_right)):
        body.arm(side, down=down + 1.5 * breath, forward=4.0 + swing, inward=inward,
                 elbow=elbow + 3.0 * breath + max(0.0, swing) * 0.4)
        body.fingers(side, curl=(12.0, 18.0, 12.0), thumb=6.0)


def idle_body(body, phase, sway=1.0, breath_scale=1.0):
    breath = wave(phase * 2.0)
    shift = wave(phase) * sway
    tilt = wave(phase, 1.3) * sway
    glance = wave(phase, 2.6) * sway
    body.center(x=0.22 * shift, y=-0.25 + 0.04 * breath * breath_scale)
    body.lower_body(roll=2.5 * shift)
    body.upper_body(pitch=2.0 + 1.2 * breath * breath_scale, roll=-2.0 * shift)
    body.upper_body2(pitch=0.8 * breath * breath_scale)
    body.neck(pitch=-2.0 - 0.5 * breath)
    body.head(pitch=1.0 * breath, yaw=4.0 * glance, roll=3.5 * tilt)
    body.shoulder(LEFT, lift=1.0 * breath)
    body.shoulder(RIGHT, lift=1.0 * breath)
    return breath, shift


def swing_foot(phase, reach, lift, stance, toe_off, heel_strike, shift):
    if phase < stance:
        progress = phase / stance
        z = reach - 2.0 * reach * progress
        pitch = heel_strike * max(0.0, 1.0 - progress / 0.15) + toe_off * smoothstep((progress - 0.75) / 0.25)
        y = foot_lift_for_pitch(pitch)
    else:
        progress = (phase - stance) / (1.0 - stance)
        z = -reach + 2.0 * reach * (0.5 - 0.5 * math.cos(math.pi * progress))
        pitch = toe_off * (1.0 - progress) + heel_strike * progress
        y = lift * math.sin(math.pi * progress) + foot_lift_for_pitch(pitch) * (1.0 - progress)
    return z + shift, y, pitch


# ---------------------------------------------------------------- locomotion

def move_idle(phase):
    body = Body()
    breath, shift = idle_body(body, phase)
    relaxed_arms(body, breath, swing_left=1.5 * shift, swing_right=-1.5 * shift)
    return body


def move_walk(phase):
    body = Body()
    reach, lift = 3.0, 0.8
    body.center(x=0.25 * wave(phase), y=-0.66 - 0.15 * math.cos(2.0 * TAU * phase), z=0.2)
    body.lower_body(pitch=2.0, yaw=-5.0 * math.cos(TAU * phase), roll=3.0 * wave(phase))
    body.upper_body(pitch=4.0 + 1.0 * math.cos(2.0 * TAU * phase), yaw=6.0 * math.cos(TAU * phase),
                    roll=-2.0 * wave(phase))
    body.upper_body2(pitch=1.0)
    body.neck(pitch=-3.0, yaw=-3.0 * math.cos(TAU * phase))
    body.head(pitch=-1.5 * math.cos(2.0 * TAU * phase), yaw=-2.0 * math.cos(TAU * phase), roll=1.5 * wave(phase))
    swing = 22.0 * math.cos(TAU * phase)
    for side, arm_swing in ((LEFT, -swing), (RIGHT, swing)):
        body.arm(side, down=42.0, forward=4.0 + arm_swing, inward=5.0, elbow=16.0 + 14.0 * (0.5 + arm_swing / 44.0))
        body.fingers(side, curl=(14.0, 20.0, 14.0), thumb=6.0)
        z, y, pitch = swing_foot((phase + (0.0 if side == LEFT else 0.5)) % 1.0, reach, lift, 0.6, 25.0, -8.0, 0.3)
        body.foot(side, y=y, z=z, pitch=pitch)
    return body


def move_run(phase):
    body = Body()
    reach, lift = 3.4, 1.8
    body.center(x=0.15 * wave(phase), y=-0.85 - 0.3 * math.cos(2.0 * TAU * (phase - 0.2)), z=0.5)
    body.lower_body(pitch=4.0, yaw=-7.0 * math.cos(TAU * phase), roll=4.0 * wave(phase))
    body.upper_body(pitch=12.0, yaw=8.0 * math.cos(TAU * phase), roll=-3.0 * wave(phase))
    body.upper_body2(pitch=3.0)
    body.neck(pitch=-8.0, yaw=-4.0 * math.cos(TAU * phase))
    body.head(pitch=-4.0 - 1.5 * math.cos(2.0 * TAU * phase), yaw=-3.0 * math.cos(TAU * phase))
    swing = 38.0 * math.cos(TAU * phase)
    for side, arm_swing in ((LEFT, -swing), (RIGHT, swing)):
        body.arm(side, down=34.0, forward=12.0 + arm_swing, inward=10.0, elbow=88.0, wrist_flex=8.0)
        body.fingers(side, curl=(45.0, 60.0, 45.0), thumb=15.0)
        z, y, pitch = swing_foot((phase + (0.0 if side == LEFT else 0.5)) % 1.0, reach, lift, 0.4, 30.0, 4.0, 0.5)
        body.foot(side, y=y, z=z, pitch=pitch)
    return body


def sneak_upper(body, phase, swing=0.0):
    breath = wave(phase * 2.0)
    body.lower_body(pitch=12.0)
    body.upper_body(pitch=20.0 + 1.0 * breath, yaw=swing)
    body.upper_body2(pitch=6.0)
    body.neck(pitch=-14.0)
    body.head(pitch=-10.0, yaw=5.0 * wave(phase, 0.8))
    for side in (LEFT, RIGHT):
        body.arm(side, down=30.0, forward=25.0 + 4.0 * side * swing, inward=6.0, elbow=48.0, wrist_flex=6.0)
        body.fingers(side, curl=(30.0, 40.0, 30.0), thumb=10.0)


def move_sneak_idle(phase):
    body = Body()
    body.center(y=-2.6 + 0.04 * wave(phase * 2.0), z=0.3)
    sneak_upper(body, phase)
    for side in (LEFT, RIGHT):
        body.foot(side, x=side * 0.35, yaw=side * 6.0)
    return body


def move_sneak_walk(phase):
    body = Body()
    reach, lift = 1.8, 0.5
    body.center(x=0.15 * wave(phase), y=-2.6 - 0.05 * math.cos(2.0 * TAU * phase), z=0.3)
    sneak_upper(body, phase, swing=2.0 * math.cos(TAU * phase))
    body.lower_body(yaw=-3.0 * math.cos(TAU * phase))
    for side in (LEFT, RIGHT):
        z, y, pitch = swing_foot((phase + (0.0 if side == LEFT else 0.5)) % 1.0, reach, lift, 0.65, 12.0, -4.0, 0.3)
        body.foot(side, x=side * 0.3, y=y, z=z, pitch=pitch, yaw=side * 5.0)
    return body


def jump_ready():
    body = Body()
    body.center(y=-0.25)
    body.upper_body(pitch=2.0)
    body.neck(pitch=-2.0)
    relaxed_arms(body)
    return body


def jump_crouch():
    body = Body()
    body.center(y=-1.7, z=0.3)
    body.lower_body(pitch=6.0)
    body.upper_body(pitch=18.0)
    body.upper_body2(pitch=4.0)
    body.neck(pitch=-8.0)
    body.head(pitch=-6.0)
    for side in (LEFT, RIGHT):
        body.arm(side, down=48.0, forward=-32.0, inward=2.0, elbow=18.0)
        body.fingers(side, curl=(20.0, 30.0, 20.0))
        body.foot(side, x=side * 0.15)
    return body


def jump_launch():
    body = Body()
    body.center(y=0.55, z=0.2)
    body.upper_body(pitch=4.0)
    body.neck(pitch=-4.0)
    body.head(pitch=-4.0)
    for side in (LEFT, RIGHT):
        body.arm(side, down=24.0, forward=62.0, inward=6.0, elbow=28.0)
        body.fingers(side, curl=(10.0, 15.0, 10.0))
        body.foot(side, x=side * 0.15, y=0.9, z=-0.3, pitch=38.0)
    return body


def move_jump_air(phase):
    body = Body()
    flutter = wave(phase)
    body.center(y=0.1 + 0.08 * flutter, z=0.2)
    body.upper_body(pitch=4.0)
    body.neck(pitch=-3.0)
    body.head(pitch=-3.0 + 1.0 * flutter, roll=2.0 * wave(phase, 1.5))
    for side in (LEFT, RIGHT):
        body.arm(side, down=26.0 + 4.0 * flutter * side, forward=14.0, inward=4.0, elbow=30.0)
        body.fingers(side, curl=(12.0, 18.0, 12.0))
        body.leg(side, forward=16.0 + 2.0 * flutter, out=5.0, knee=48.0, ankle=24.0)
    return body


def jump_absorb():
    body = Body()
    body.center(y=-1.55, z=0.35)
    body.lower_body(pitch=6.0)
    body.upper_body(pitch=16.0)
    body.upper_body2(pitch=4.0)
    body.neck(pitch=-8.0)
    body.head(pitch=-5.0)
    for side in (LEFT, RIGHT):
        body.arm(side, down=34.0, forward=30.0, inward=8.0, elbow=42.0)
        body.fingers(side, curl=(16.0, 24.0, 16.0))
        body.foot(side, x=side * 0.3)
    return body


def move_swim(phase):
    body = Body()
    roll = 6.0 * wave(phase)
    body.center(roll=roll, pitch=2.0 * wave(phase * 2.0))
    body.upper_body(pitch=-3.0, roll=roll * 0.5)
    body.neck(pitch=-12.0)
    body.head(pitch=-10.0, yaw=8.0 * wave(phase))
    for side, offset in ((LEFT, 0.0), (RIGHT, 0.5)):
        stroke = (phase + offset) % 1.0
        forward = 360.0 * stroke
        recovering = 0.5 <= stroke
        elbow = 62.0 * math.sin(math.pi * ((stroke - 0.5) / 0.5)) if recovering else 18.0
        body.arm(side, down=36.0, forward=forward, inward=-6.0 if recovering else 14.0, elbow=elbow)
        body.fingers(side, curl=(8.0, 12.0, 8.0))
        kick = wave(phase * 2.0, 0.0 if side == LEFT else math.pi)
        body.leg(side, forward=8.0 + 12.0 * kick, out=3.0, knee=18.0 + 14.0 * max(0.0, -kick), ankle=30.0)
    return body


def move_crawl(phase):
    body = Body()
    body.center(pitch=3.0, roll=3.0 * wave(phase), y=-0.2)
    body.upper_body(pitch=-4.0, yaw=4.0 * wave(phase))
    body.neck(pitch=-16.0)
    body.head(pitch=-14.0)
    for side, offset in ((LEFT, 0.0), (RIGHT, 0.5)):
        stroke = (phase + offset) % 1.0
        pull = 0.5 - 0.5 * math.cos(TAU * stroke)
        body.arm(side, down=18.0, forward=150.0 - 85.0 * pull, inward=8.0, elbow=25.0 + 30.0 * pull)
        body.fingers(side, curl=(10.0, 16.0, 10.0))
        body.leg(side, forward=12.0 + 30.0 * (1.0 - pull), out=16.0 + 6.0 * (1.0 - pull),
                 knee=25.0 + 55.0 * (1.0 - pull), ankle=25.0)
    return body


def move_glide(phase):
    body = Body()
    wobble = wave(phase)
    body.center(roll=2.0 * wobble, pitch=1.0 * wave(phase * 2.0))
    body.upper_body(pitch=-5.0, roll=1.5 * wobble)
    body.upper_body2(pitch=-3.0)
    body.neck(pitch=-12.0)
    body.head(pitch=-9.0, roll=-2.0 * wobble)
    for side in (LEFT, RIGHT):
        body.arm(side, down=72.0 + 3.0 * wobble * side, forward=-28.0, inward=-4.0, elbow=8.0, wrist_bend=-10.0)
        body.fingers(side, curl=(6.0, 10.0, 6.0))
        body.leg(side, forward=-4.0, out=-2.0, knee=4.0, ankle=32.0)
    return body


def move_fly(phase):
    body = Body()
    drift = wave(phase)
    body.center(y=0.35 * drift, pitch=6.0 + 1.0 * drift)
    body.upper_body(pitch=-2.0, roll=1.5 * wave(phase, 1.0))
    body.neck(pitch=-5.0)
    body.head(pitch=-2.0, yaw=6.0 * wave(phase, 2.0), roll=2.5 * wave(phase, 0.7))
    for side in (LEFT, RIGHT):
        body.arm(side, down=22.0 + 3.0 * wave(phase, 0.0 if side == LEFT else 1.0), forward=12.0, inward=0.0,
                 elbow=26.0, wrist_flex=-6.0)
        body.fingers(side, curl=(8.0, 12.0, 8.0))
        body.leg(side, forward=8.0 + 2.0 * drift, out=4.0, knee=26.0 + 3.0 * drift, ankle=18.0)
    return body


def move_climb(phase):
    body = Body()
    body.center(y=0.25 * wave(phase * 2.0), z=0.2, pitch=2.0)
    body.upper_body(pitch=2.0, yaw=4.0 * wave(phase), roll=-2.0 * wave(phase))
    body.neck(pitch=-14.0)
    body.head(pitch=-12.0)
    for side, offset in ((LEFT, 0.0), (RIGHT, 0.5)):
        step = 0.5 - 0.5 * math.cos(TAU * ((phase + offset) % 1.0))
        body.arm(side, down=6.0, forward=105.0 + 48.0 * step, inward=14.0, elbow=68.0 - 30.0 * step, wrist_flex=20.0)
        body.fingers(side, curl=(45.0, 60.0, 45.0), thumb=15.0)
        body.leg(side, forward=18.0 + 44.0 * (1.0 - step), out=6.0, knee=32.0 + 44.0 * (1.0 - step), ankle=12.0)
    return body


def move_ride(phase):
    body = Body()
    breath = wave(phase * 2.0)
    body.center(y=-0.3 + 0.03 * breath, pitch=2.0)
    body.upper_body(pitch=4.0 + 1.0 * breath)
    body.upper_body2(pitch=0.8 * breath)
    body.neck(pitch=-4.0)
    body.head(pitch=-1.0, yaw=5.0 * wave(phase, 2.0), roll=2.0 * wave(phase, 0.9))
    for side in (LEFT, RIGHT):
        body.arm(side, down=36.0, forward=34.0, inward=12.0, elbow=62.0, wrist_flex=10.0)
        body.fingers(side, curl=(50.0, 65.0, 50.0), thumb=18.0)
        body.leg(side, forward=78.0, out=13.0, knee=76.0, ankle=-6.0)
    return body


def move_sleep(phase):
    body = Body()
    breath = wave(phase)
    body.upper_body(pitch=1.5 * breath)
    body.upper_body2(pitch=1.0 * breath)
    body.neck(pitch=2.0)
    body.head(pitch=1.0, roll=6.0)
    for side in (LEFT, RIGHT):
        body.arm(side, down=74.0 - 1.0 * breath, forward=10.0, inward=-2.0, elbow=28.0, wrist_flex=8.0)
        body.fingers(side, curl=(16.0, 24.0, 16.0))
        body.leg(side, forward=2.0, out=-1.0, knee=4.0, ankle=14.0)
    return body


# ---------------------------------------------------------------- reactions

def react_hurt_key(amount):
    body = Body()
    body.center(y=-0.25 - 0.45 * amount, z=-0.35 * amount)
    body.lower_body(pitch=-3.0 * amount)
    body.upper_body(pitch=2.0 - 12.0 * amount)
    body.upper_body2(pitch=-3.0 * amount)
    body.neck(pitch=-2.0 - 5.0 * amount)
    body.head(pitch=-8.0 * amount, roll=4.0 * amount)
    for side in (LEFT, RIGHT):
        body.arm(side, down=40.0 - 16.0 * amount, forward=4.0 + 26.0 * amount, inward=2.0 + 6.0 * amount,
                 elbow=14.0 + 48.0 * amount, wrist_flex=-10.0 * amount)
        body.fingers(side, curl=(12.0, 18.0, 12.0))
    return body


def react_death_key(amount):
    body = Body()
    body.center(y=-0.25 - 2.0 * amount, z=0.2 * amount)
    body.lower_body(pitch=8.0 * amount)
    body.upper_body(pitch=2.0 + 32.0 * amount)
    body.upper_body2(pitch=6.0 * amount)
    body.neck(pitch=-2.0 + 10.0 * amount)
    body.head(pitch=24.0 * amount, roll=8.0 * amount)
    for side in (LEFT, RIGHT):
        body.arm(side, down=40.0 - 6.0 * amount, forward=4.0 + 26.0 * amount, inward=2.0, elbow=14.0 - 6.0 * amount)
        body.fingers(side, curl=(8.0, 12.0, 8.0))
        body.foot(side, x=side * 0.25 * amount)
    return body


# ---------------------------------------------------------------- items and attacks

def grip(body, side, strength=1.0):
    body.fingers(side, curl=(48.0 * strength, 62.0 * strength, 48.0 * strength), thumb=16.0 * strength)


def hold_item_arm(body, side, lift=0.0):
    body.arm(side, down=38.0 - 6.0 * lift, forward=22.0 + 10.0 * lift, inward=6.0, elbow=56.0 + 8.0 * lift,
             wrist_flex=6.0)
    grip(body, side)


def relaxed_arm(body, side, breath=0.0):
    body.arm(side, down=40.0 + 1.5 * breath, forward=4.0, inward=2.0, elbow=14.0 + 3.0 * breath)
    body.fingers(side, curl=(12.0, 18.0, 12.0), thumb=6.0)


def pose_loop(name, arms, duration=90, description="", matrix=(), hand="right", legacy=()):
    def builder(phase):
        body = Body()
        breath, _ = idle_body(body, phase, sway=0.5)
        arms(body, phase, breath)
        return body
    return cycle(name, duration, 3, builder, category="use", description=description, matrix=matrix, hand=hand,
                 legacy=legacy)


def use_item_arms(body, phase, breath):
    hold_item_arm(body, RIGHT)
    relaxed_arm(body, LEFT, breath)


def use_two_hands_arms(body, phase, breath):
    for side in (LEFT, RIGHT):
        body.arm(side, down=42.0, forward=52.0, inward=24.0, twist=-side * 12.0, elbow=70.0 + 2.0 * breath,
                 wrist_flex=10.0)
        grip(body, side)


def use_block_arms(body, phase, breath):
    body.arm(RIGHT, down=30.0, forward=58.0, inward=38.0, twist=18.0, elbow=102.0, wrist_flex=8.0)
    grip(body, RIGHT)
    hold_item_arm(body, LEFT, lift=0.3)
    body.upper_body(yaw=-8.0)


def use_bow_arms(body, phase, breath):
    body.upper_body(yaw=-22.0)
    body.upper_body2(yaw=-4.0)
    body.head(yaw=16.0, roll=-3.0)
    body.arm(LEFT, down=-34.0, forward=82.0, inward=14.0, twist=-25.0, elbow=6.0, wrist_flex=4.0)
    grip(body, LEFT, 0.9)
    body.arm(RIGHT, down=-28.0, forward=78.0, inward=-8.0, twist=20.0, elbow=138.0, wrist_flex=-6.0)
    body.fingers(RIGHT, curl=(28.0, 36.0, 28.0), thumb=10.0)


def use_crossbow_charge_arms(body, phase, breath):
    pull = 0.5 - 0.5 * math.cos(TAU * phase * 3.0)
    body.upper_body(pitch=6.0 + 2.0 * pull)
    body.head(pitch=6.0)
    body.arm(LEFT, down=22.0, forward=44.0, inward=16.0, twist=-15.0, elbow=44.0, wrist_flex=6.0)
    grip(body, LEFT)
    body.arm(RIGHT, down=30.0, forward=36.0 + 6.0 * pull, inward=22.0, elbow=72.0 + 24.0 * pull, wrist_flex=12.0)
    grip(body, RIGHT)


def use_crossbow_hold_arms(body, phase, breath):
    body.upper_body(yaw=-12.0)
    body.head(yaw=8.0, roll=-4.0)
    body.arm(LEFT, down=-18.0, forward=72.0, inward=18.0, twist=-25.0, elbow=26.0, wrist_flex=4.0)
    grip(body, LEFT)
    body.arm(RIGHT, down=4.0, forward=62.0, inward=6.0, twist=10.0, elbow=112.0, wrist_flex=-4.0)
    grip(body, RIGHT)


def use_spyglass_arms(body, phase, breath):
    body.head(roll=3.0, yaw=2.0)
    body.arm(RIGHT, down=-8.0, forward=74.0, inward=20.0, twist=15.0, elbow=140.0, wrist_flex=-6.0)
    grip(body, RIGHT, 0.85)
    body.arm(LEFT, down=36.0, forward=30.0, inward=18.0, elbow=60.0)
    body.fingers(LEFT, curl=(20.0, 28.0, 20.0), thumb=8.0)


def use_toot_horn_arms(body, phase, breath):
    body.neck(pitch=-3.0)
    body.head(pitch=-4.0)
    body.arm(RIGHT, down=2.0, forward=64.0, inward=24.0, twist=12.0, elbow=130.0, wrist_flex=-4.0)
    grip(body, RIGHT, 0.85)
    relaxed_arm(body, LEFT, breath)


def use_brush_arms(body, phase, breath):
    sweep = wave(phase * 3.0)
    body.upper_body(pitch=8.0)
    body.head(pitch=8.0)
    body.arm(RIGHT, down=30.0, forward=36.0 + 4.0 * sweep, inward=12.0 + 10.0 * sweep, elbow=46.0,
             wrist_bend=8.0 * sweep)
    grip(body, RIGHT, 0.8)
    body.arm(LEFT, down=38.0, forward=14.0, inward=4.0, elbow=30.0)
    body.fingers(LEFT, curl=(16.0, 24.0, 16.0))


def spear_hold(body, pull=0.0, thrust=0.0):
    body.arm(RIGHT, down=42.0, forward=-8.0 - 18.0 * pull + 78.0 * thrust, inward=14.0 + 4.0 * thrust,
             elbow=76.0 + 20.0 * pull - 70.0 * thrust, wrist_flex=8.0)
    grip(body, RIGHT)
    body.arm(LEFT, down=36.0, forward=54.0 - 6.0 * pull + 28.0 * thrust, inward=22.0, twist=-12.0,
             elbow=22.0 + 22.0 * pull - 18.0 * thrust, wrist_flex=6.0)
    grip(body, LEFT)


def use_spear_arms(body, phase, breath):
    spear_hold(body)
    body.upper_body(yaw=-6.0)


def use_eat_arms(body, phase, breath):
    bite = 0.5 - 0.5 * math.cos(TAU * phase)
    body.neck(pitch=2.0 * bite)
    body.head(pitch=4.0 * bite, roll=-3.0 * bite)
    body.arm(RIGHT, down=14.0 + 6.0 * (1.0 - bite), forward=58.0, inward=24.0, twist=12.0,
             elbow=102.0 + 34.0 * bite, wrist_flex=-8.0)
    grip(body, RIGHT, 0.85)
    relaxed_arm(body, LEFT, breath)


def use_drink_arms(body, phase, breath):
    sip = 0.5 - 0.5 * math.cos(TAU * phase)
    body.neck(pitch=-4.0 - 3.0 * sip)
    body.head(pitch=-6.0 - 6.0 * sip)
    body.arm(RIGHT, down=-4.0, forward=70.0, inward=22.0, twist=16.0, elbow=136.0 + 6.0 * sip,
             wrist_flex=-14.0 - 10.0 * sip)
    grip(body, RIGHT, 0.85)
    relaxed_arm(body, LEFT, breath)


def use_trident_arms(body, phase, breath):
    body.center(x=-0.2)
    body.upper_body(yaw=-24.0, pitch=-4.0)
    body.upper_body2(yaw=-4.0)
    body.head(yaw=18.0)
    body.arm(RIGHT, down=-40.0, forward=-20.0, inward=-10.0, twist=30.0, elbow=92.0, wrist_flex=-6.0)
    grip(body, RIGHT)
    body.arm(LEFT, down=-6.0, forward=64.0, inward=18.0, elbow=12.0)
    body.fingers(LEFT, curl=(10.0, 14.0, 10.0))


def use_bundle_arms(body, phase, breath):
    body.head(pitch=10.0)
    body.arm(RIGHT, down=30.0, forward=40.0, inward=22.0, elbow=84.0, wrist_flex=-6.0)
    grip(body, RIGHT, 0.8)
    body.arm(LEFT, down=34.0, forward=36.0, inward=26.0, elbow=76.0, wrist_flex=6.0)
    grip(body, LEFT, 0.8)


def swing_frame(stage):
    """stage: 'hold', 'windup', 'strike' or 'follow'."""
    body = Body()
    if stage == "hold":
        body.center(y=-0.25)
        body.upper_body(pitch=2.0)
        body.neck(pitch=-2.0)
        hold_item_arm(body, RIGHT)
        relaxed_arm(body, LEFT)
    elif stage == "windup":
        body.center(x=-0.15, y=-0.4, z=-0.1)
        body.lower_body(yaw=-6.0)
        body.upper_body(pitch=-3.0, yaw=-14.0, roll=-3.0)
        body.upper_body2(yaw=-4.0)
        body.neck(yaw=8.0)
        body.head(yaw=8.0, pitch=-2.0)
        body.arm(RIGHT, down=8.0, forward=138.0, inward=-18.0, twist=20.0, elbow=74.0, wrist_flex=-8.0)
        grip(body, RIGHT)
        body.arm(LEFT, down=36.0, forward=16.0, inward=10.0, elbow=34.0)
        body.fingers(LEFT, curl=(16.0, 24.0, 16.0))
    elif stage == "strike":
        body.center(x=0.2, y=-0.62, z=0.45)
        body.lower_body(yaw=7.0, pitch=4.0)
        body.upper_body(pitch=11.0, yaw=16.0, roll=2.0)
        body.upper_body2(yaw=4.0, pitch=2.0)
        body.neck(yaw=-6.0)
        body.head(yaw=-6.0, pitch=-4.0)
        body.arm(RIGHT, down=26.0, forward=48.0, inward=28.0, twist=-10.0, elbow=12.0, wrist_flex=12.0)
        grip(body, RIGHT)
        body.arm(LEFT, down=42.0, forward=-14.0, inward=4.0, elbow=26.0)
        body.fingers(LEFT, curl=(16.0, 24.0, 16.0))
    else:
        body.center(x=0.15, y=-0.55, z=0.35)
        body.lower_body(yaw=5.0, pitch=2.0)
        body.upper_body(pitch=8.0, yaw=11.0)
        body.upper_body2(yaw=2.0)
        body.neck(yaw=-4.0)
        body.head(yaw=-4.0, pitch=-2.0)
        body.arm(RIGHT, down=34.0, forward=24.0, inward=32.0, twist=-8.0, elbow=38.0, wrist_flex=8.0)
        grip(body, RIGHT)
        body.arm(LEFT, down=40.0, forward=-6.0, inward=4.0, elbow=22.0)
        body.fingers(LEFT, curl=(16.0, 24.0, 16.0))
    for side in (LEFT, RIGHT):
        body.foot(side)
    return body


def stab_frame(stage):
    body = Body()
    if stage == "hold":
        body.center(y=-0.25)
        body.upper_body(pitch=2.0, yaw=-6.0)
        body.neck(pitch=-2.0)
        spear_hold(body)
    elif stage == "pull":
        body.center(x=-0.1, y=-0.4, z=-0.3)
        body.lower_body(yaw=-4.0)
        body.upper_body(pitch=-2.0, yaw=-14.0)
        body.neck(yaw=6.0)
        body.head(yaw=6.0)
        spear_hold(body, pull=1.0)
    elif stage == "thrust":
        body.center(x=0.1, y=-0.6, z=0.85)
        body.lower_body(yaw=4.0, pitch=4.0)
        body.upper_body(pitch=12.0, yaw=8.0)
        body.upper_body2(pitch=2.0)
        body.neck(yaw=-4.0, pitch=-4.0)
        body.head(yaw=-4.0, pitch=-4.0)
        spear_hold(body, thrust=1.0)
    else:
        body.center(y=-0.45, z=0.4)
        body.lower_body(yaw=2.0)
        body.upper_body(pitch=6.0, yaw=2.0)
        body.neck(pitch=-3.0)
        spear_hold(body, thrust=0.4)
    for side in (LEFT, RIGHT):
        body.foot(side)
    return body


# ---------------------------------------------------------------- registry

def build_clips():
    clips = [
        cycle("move_idle", 120, 3, move_idle, category="move",
              description="站立待机：呼吸、重心左右转移、头部歪头与视线扫视",
              matrix=("MOVE-01",), legacy=("idle.vmd",)),
        cycle("move_walk", 24, 1, move_walk, category="move",
              description="行走循环：IK 足部、骨盆摆动、躯干反向扭转与手臂摆动",
              matrix=("MOVE-01", "MOVE-02"), legacy=("walk.vmd",), cycle_distance=10.0),
        cycle("move_run", 18, 1, move_run, category="move",
              description="疾跑循环：前倾、高抬膝、腾空相与屈肘摆臂",
              matrix=("MOVE-02",), legacy=("sprint.vmd",), cycle_distance=17.0),
        cycle("move_sneak_idle", 90, 3, move_sneak_idle, category="move",
              description="潜行待机：低重心屈膝、前倾、双手前置",
              matrix=("MOVE-03",), legacy=("sneak.vmd",)),
        cycle("move_sneak_walk", 36, 1, move_sneak_walk, category="move",
              description="潜行移动：短步幅、无弹跳、保持低姿态",
              matrix=("MOVE-03",), cycle_distance=5.5),
        keyed("move_jump_start", [(0, jump_ready(), LINEAR), (4, jump_crouch(), EASE_IN), (10, jump_launch(), EASE_OUT)],
              category="move", description="起跳：下蹲蓄力后蹬伸、手臂上摆、脚尖离地", matrix=("MOVE-04",),
              grounded=False),
        cycle("move_jump_air", 24, 2, move_jump_air, category="move",
              description="空中：双腿微收、手臂张开保持平衡", matrix=("MOVE-04",), grounded=False),
        keyed("move_jump_land", [(0, jump_launch(), LINEAR), (3, jump_absorb(), EASE_OUT), (12, jump_ready(), EASE_IN_OUT)],
              category="move", description="落地：屈膝缓冲后恢复站立", matrix=("MOVE-04",), grounded=False),
        cycle("move_swim", 40, 2, move_swim, category="move",
              description="游泳：交替划水与打腿；躯干俯仰由渲染层施加",
              matrix=("MOVE-05",), legacy=("swim.vmd",), grounded=False),
        cycle("move_crawl", 48, 2, move_crawl, category="move",
              description="匍匐：交替伸臂拉动与屈腿蹬伸；躯干俯仰由渲染层施加",
              matrix=("MOVE-05",), legacy=("crawl.vmd", "lieDown.vmd"), grounded=False),
        cycle("move_glide", 30, 2, move_glide, category="move",
              description="鞘翅滑翔：手臂后掠、双腿并拢绷脚、轻微摆动",
              matrix=("MOVE-06",), legacy=("elytraFly.vmd",), grounded=False),
        cycle("move_fly", 72, 3, move_fly, category="move",
              description="创造飞行悬停：上下漂浮、双腿自然下垂", matrix=("MOVE-07",), grounded=False),
        cycle("move_climb", 32, 2, move_climb, category="move",
              description="攀爬：交替伸手抓握与抬腿蹬踏、抬头看向上方",
              matrix=("MOVE-08",), legacy=("onClimbable.vmd", "onClimbableUp.vmd", "onClimbableDown.vmd"),
              grounded=False),
        cycle("move_ride", 60, 3, move_ride, category="move",
              description="乘坐：大腿前伸屈膝、双手持缰",
              matrix=("MOVE-09",), legacy=("ride.vmd", "onHorse.vmd"), grounded=False),
        cycle("move_sleep", 120, 3, move_sleep, category="move",
              description="睡眠：平躺呼吸、双手置于身侧；卧姿旋转由渲染层施加",
              matrix=("MOVE-10",), legacy=("sleep.vmd",), grounded=False),
        keyed("react_hurt", [(0, react_hurt_key(0.0), LINEAR), (3, react_hurt_key(1.0), EASE_OUT),
                             (12, react_hurt_key(0.0), EASE_IN_OUT)],
              category="react", description="受击：后仰缩肩、双手抬起后恢复", matrix=("MOVE-12",)),
        keyed("react_death", [(0, react_death_key(0.0), LINEAR), (8, react_death_key(0.55), EASE_IN),
                              (24, react_death_key(1.0), EASE_OUT)],
              category="react", description="死亡：膝盖发软、身体前倾瘫软；侧倒旋转由渲染层施加",
              matrix=("MOVE-12",), legacy=("die.vmd",)),
        keyed("attack_swing", [(0, swing_frame("hold"), LINEAR), (3, swing_frame("windup"), EASE_IN),
                               (6, swing_frame("strike"), EASE_OUT), (9, swing_frame("follow"), EASE_IN_OUT),
                               (13, swing_frame("hold"), EASE_IN_OUT)],
              category="attack", description="挥击：举臂蓄力、斜劈、随势跟进后收招（剑/斧/工具/空手通用）",
              matrix=("第4节 剑", "第4节 斧", "工具兼容组", "通用组"), hand="right", legacy=("swingRight.vmd",)),
        keyed("attack_stab", [(0, stab_frame("hold"), LINEAR), (3, stab_frame("pull"), EASE_IN),
                              (6, stab_frame("thrust"), EASE_OUT), (9, stab_frame("follow"), EASE_IN_OUT),
                              (13, stab_frame("hold"), EASE_IN_OUT)],
              category="attack", description="刺击：收枪蓄力、弓步前刺后收招（矛 STAB）",
              matrix=("第4节 矛",), hand="right"),
        pose_loop("use_item", use_item_arms, description="一般持物（ArmPose ITEM）", matrix=("NONE",)),
        pose_loop("use_two_hands", use_two_hands_arms, description="双手握持大件物品于胸前",
                  matrix=("阶段2 双手握持",), hand="both"),
        pose_loop("use_block", use_block_arms, description="右手举盾格挡，左手持物", matrix=("BLOCK",)),
        pose_loop("use_bow", use_bow_arms, description="拉弓瞄准（ArmPose BOW_AND_ARROW）", matrix=("BOW",)),
        pose_loop("use_crossbow_charge", use_crossbow_charge_arms, duration=60, description="装填弩（CROSSBOW_CHARGE）",
                  matrix=("CROSSBOW",)),
        pose_loop("use_crossbow_hold", use_crossbow_hold_arms, description="持已装填弩瞄准（CROSSBOW_HOLD）",
                  matrix=("CROSSBOW",)),
        pose_loop("use_spyglass", use_spyglass_arms, description="望远镜贴眼（SPYGLASS）", matrix=("SPYGLASS",)),
        pose_loop("use_toot_horn", use_toot_horn_arms, description="吹号角（TOOT_HORN）", matrix=("TOOT_HORN",)),
        pose_loop("use_brush", use_brush_arms, duration=60, description="刷子来回清扫（BRUSH）", matrix=("BRUSH",)),
        pose_loop("use_spear", use_spear_arms, description="双手持矛（ArmPose SPEAR）", matrix=("SPEAR",)),
        pose_loop("use_eat", use_eat_arms, duration=32, description="进食：反复将手送到嘴边（EAT）", matrix=("EAT",)),
        pose_loop("use_drink", use_drink_arms, duration=40, description="饮用：仰头举瓶（DRINK）", matrix=("DRINK",)),
        pose_loop("use_trident", use_trident_arms, description="三叉戟投掷蓄力（THROW_TRIDENT）", matrix=("TRIDENT",)),
        pose_loop("use_bundle", use_bundle_arms, duration=60, description="低头翻找收纳袋（BUNDLE）", matrix=("BUNDLE",)),
    ]
    by_name = {clip.name: clip for clip in clips}
    by_name["move_idle"].morph_keys = blink((41, 101))
    by_name["move_sleep"].morph_keys = [MorphKey("まばたき", 0, 1.0), MorphKey("まばたき", 120, 1.0)]
    by_name["react_hurt"].morph_keys = [MorphKey("びっくり", 0, 0.0), MorphKey("びっくり", 3, 1.0),
                                        MorphKey("びっくり", 12, 0.0)]
    by_name["react_death"].morph_keys = [MorphKey("まばたき", 8, 0.0), MorphKey("まばたき", 24, 1.0)]
    by_name["use_eat"].morph_keys = [MorphKey("あ", 0, 0.0), MorphKey("あ", 14, 0.45), MorphKey("あ", 20, 0.0),
                                     MorphKey("あ", 32, 0.0)]
    from .extended_clips import extend_clips, first_person_clips, orient_clips
    clips += extend_clips()
    orient_clips(clips)
    mirrored = [mirror(clip, clip.name + "_left") for clip in clips if clip.hand == "right"]
    for clip in mirrored:
        if clip.mirrored_from == "attack_swing":
            clip.legacy = ("swingLeft.vmd",)
    clips += mirrored
    return clips + first_person_clips(clips)

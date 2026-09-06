"""Reference skeleton geometry and quaternion helpers for authoring VMD clips.

Poses are authored in the runtime's right-handed frame: +X is the character's
left, +Y is up, +Z is the character's front. ``vmd.py`` converts to the MMD
frame when writing. Bind positions come from the PMX 2.0 verification model
(right-handed z = -z_pmx); only bones the clips touch are listed.
"""
import math

LEFT = 1
RIGHT = -1
SIDE_PREFIX = {LEFT: "左", RIGHT: "右"}
IDENTITY = (0.0, 0.0, 0.0, 1.0)
ZERO = (0.0, 0.0, 0.0)

BIND_POSITIONS = {
    "全ての親": (0.0, 0.026, 0.002),
    "センター": (0.0, 7.333, 0.002),
    "グルーブ": (0.0, 7.535, 0.002),
    "腰": (0.0, 12.045, -0.248),
    "上半身": (0.0, 12.975, 0.504),
    "上半身2": (0.0, 14.115, 0.457),
    "下半身": (0.0, 12.914, 0.504),
    "首": (0.0, 16.669, 0.110),
    "頭": (0.0, 17.566, 0.118),
    "左肩": (0.235, 16.376, 0.146),
    "右肩": (-0.235, 16.376, 0.146),
    "左腕": (1.092, 16.297, 0.134),
    "右腕": (-1.092, 16.297, 0.134),
    "左ひじ": (3.299, 14.622, 0.102),
    "右ひじ": (-3.295, 14.618, 0.102),
    "左手首": (5.389, 13.114, 0.145),
    "右手首": (-5.383, 13.105, 0.145),
    "左足": (0.895, 11.371, 0.299),
    "右足": (-0.895, 11.371, 0.299),
    "左ひざ": (0.771, 6.151, 0.269),
    "右ひざ": (-0.771, 6.151, 0.269),
    "左足首": (0.804, 1.590, -0.194),
    "右足首": (-0.804, 1.590, -0.194),
    "左つま先": (0.804, 0.358, 0.884),
    "右つま先": (-0.804, 0.358, 0.884),
    "左足IK親": (0.804, 0.045, -0.221),
    "右足IK親": (-0.804, 0.045, -0.221),
    "左足ＩＫ": (0.804, 1.590, -0.194),
    "右足ＩＫ": (-0.804, 1.590, -0.194),
    "左つま先ＩＫ": (0.804, 0.163, 1.054),
    "右つま先ＩＫ": (-0.804, 0.163, 1.054),
}

FINGER_BONES = ("人指", "中指", "薬指", "小指")
IK_BONES = ("左足ＩＫ", "右足ＩＫ", "左つま先ＩＫ", "右つま先ＩＫ")


def add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def scale(v, factor):
    return (v[0] * factor, v[1] * factor, v[2] * factor)


def length(v):
    return math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])


def normalize_vector(v):
    magnitude = length(v)
    if magnitude == 0.0:
        raise ValueError("cannot normalize a zero vector")
    return scale(v, 1.0 / magnitude)


def quaternion(axis, degrees):
    """Rotation about ``axis`` by ``degrees`` following the right-hand rule."""
    x, y, z = normalize_vector(axis)
    half = math.radians(degrees) * 0.5
    s = math.sin(half)
    return (x * s, y * s, z * s, math.cos(half))


def rx(degrees):
    return quaternion((1.0, 0.0, 0.0), degrees) if degrees else IDENTITY


def ry(degrees):
    return quaternion((0.0, 1.0, 0.0), degrees) if degrees else IDENTITY


def rz(degrees):
    return quaternion((0.0, 0.0, 1.0), degrees) if degrees else IDENTITY


def multiply(a, b):
    """Quaternion product a*b: rotating by the result applies b first, then a."""
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return (
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    )


def compose(*rotations):
    """Apply the rotations in the given order (first argument first)."""
    result = IDENTITY
    for rotation in rotations:
        result = multiply(rotation, result)
    return result


def conjugate(q):
    return (-q[0], -q[1], -q[2], q[3])


def normalize_quaternion(q):
    magnitude = math.sqrt(sum(component * component for component in q))
    if magnitude == 0.0:
        raise ValueError("cannot normalize a zero quaternion")
    return tuple(component / magnitude for component in q)


def rotate(q, v):
    qx, qy, qz, qw = q
    x, y, z = v
    tx = 2.0 * (qy * z - qz * y)
    ty = 2.0 * (qz * x - qx * z)
    tz = 2.0 * (qx * y - qy * x)
    return (
        x + qw * tx + (qy * tz - qz * ty),
        y + qw * ty + (qz * tx - qx * tz),
        z + qw * tz + (qx * ty - qy * tx),
    )


def euler(rx_degrees=0.0, ry_degrees=0.0, rz_degrees=0.0):
    """Global-axis rotation applied as X, then Y, then Z."""
    return compose(rx(rx_degrees), ry(ry_degrees), rz(rz_degrees))


def bone(side, name):
    return SIDE_PREFIX[side] + name


def bend_axis(side, root, tip):
    """Axis whose positive rotation swings ``tip`` toward the character's front.

    The axis is the frontal-plane perpendicular of the bind direction, so the
    same helper serves elbows and wrists whatever the shoulder pose is.
    """
    direction = sub(BIND_POSITIONS[bone(side, tip)], BIND_POSITIONS[bone(side, root)])
    ax, ay = direction[0], direction[1]
    magnitude = math.hypot(ax, ay)
    return (ay / magnitude, -ax / magnitude, 0.0)


def leg_length(side=LEFT):
    hip = BIND_POSITIONS[bone(side, "足")]
    knee = BIND_POSITIONS[bone(side, "ひざ")]
    ankle = BIND_POSITIONS[bone(side, "足首")]
    return length(sub(knee, hip)) + length(sub(ankle, knee))


def leg_chain(pose, side):
    """Global hip and ankle transforms for a pose dict of local transforms.

    The chain follows 全ての親 → センター → グルーブ → 腰 → 下半身 → 腰キャンセル
    (which cancels the 腰 rotation for the legs) → 足 → ひざ → 足首.
    """
    def local(name):
        return pose.get(name, (ZERO, IDENTITY))

    position = add(BIND_POSITIONS["センター"], local("センター")[0])
    rotation = local("センター")[1]
    previous = "センター"
    for name in ("グルーブ", "腰", "下半身"):
        translation, local_rotation = local(name)
        position = add(position, rotate(rotation, add(sub(BIND_POSITIONS[name], BIND_POSITIONS[previous]), translation)))
        rotation = multiply(rotation, local_rotation)
        previous = name
    hip_name = bone(side, "足")
    hip = add(position, rotate(rotation, sub(BIND_POSITIONS[hip_name], BIND_POSITIONS["下半身"])))
    rotation = multiply(rotation, conjugate(local("腰")[1]))
    rotation = multiply(rotation, local(hip_name)[1])
    knee_name = bone(side, "ひざ")
    knee = add(hip, rotate(rotation, sub(BIND_POSITIONS[knee_name], BIND_POSITIONS[hip_name])))
    rotation = multiply(rotation, local(knee_name)[1])
    ankle_name = bone(side, "足首")
    ankle = add(knee, rotate(rotation, sub(BIND_POSITIONS[ankle_name], BIND_POSITIONS[knee_name])))
    rotation = multiply(rotation, local(ankle_name)[1])
    return hip, ankle, rotation


def ik_target(pose, side):
    """Global position and rotation of the foot IK bone for a pose dict."""
    ik_name = bone(side, "足ＩＫ")
    translation, rotation = pose.get(ik_name, (ZERO, IDENTITY))
    return add(BIND_POSITIONS[ik_name], translation), rotation

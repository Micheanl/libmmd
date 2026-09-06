"""Writer and reader for the ``Vocaloid Motion Data 0002`` format.

Keys are given in the runtime's right-handed frame (see ``rig.py``); the file
stores MMD's left-handed values: z is negated for translations and the x/y
quaternion components are negated, matching the runtime's motion loader.
Interpolation uses MMD's full 64-byte layout (x1, y1, x2, y2 per channel).
"""
import math
import struct
from dataclasses import dataclass

SIGNATURE = b"Vocaloid Motion Data 0002"
MODEL_NAME = "libmmd motion"
FRAME_STRUCT = struct.Struct("<15sI3f4f64s")
MORPH_STRUCT = struct.Struct("<15sIf")
COUNT = struct.Struct("<I")

LINEAR = (20, 20, 107, 107)
EASE_IN_OUT = (64, 0, 64, 127)
EASE_IN = (64, 0, 127, 64)
EASE_OUT = (0, 64, 64, 127)


@dataclass(frozen=True)
class BoneKey:
    bone: str
    frame: int
    translation: tuple = (0.0, 0.0, 0.0)
    rotation: tuple = (0.0, 0.0, 0.0, 1.0)
    curve: tuple = LINEAR


@dataclass(frozen=True)
class MorphKey:
    morph: str
    frame: int
    weight: float


def encode_name(name, size):
    data = name.encode("cp932")
    if len(data) > size:
        raise ValueError(f"{name!r} needs {len(data)} bytes in Windows-31J, limit is {size}")
    return data.ljust(size, b"\0")


def decode_name(data):
    return data.split(b"\0", 1)[0].decode("cp932")


def interpolation_bytes(curve):
    if len(curve) != 4:
        raise ValueError("interpolation needs four control points")
    for value in curve:
        if not isinstance(value, int) or not 0 <= value <= 127:
            raise ValueError(f"interpolation control points must be integers in 0..127: {curve!r}")
    x1, y1, x2, y2 = curve
    block = bytearray(16)
    block[0], block[4], block[8], block[12] = x1, y1, x2, y2
    return bytes(block) * 4


def curve_from_bytes(data):
    return tuple(data[offset] for offset in (0, 4, 8, 12))


def _finite(values, label):
    for value in values:
        if not math.isfinite(value):
            raise ValueError(f"{label} contains a non-finite value: {values!r}")


def write(bone_keys, morph_keys=(), model_name=MODEL_NAME):
    output = bytearray()
    output += SIGNATURE.ljust(30, b"\0")
    output += encode_name(model_name, 20)
    ordered = sorted(bone_keys, key=lambda key: (key.bone, key.frame))
    output += COUNT.pack(len(ordered))
    for key in ordered:
        if key.frame < 0:
            raise ValueError(f"negative frame for {key.bone}")
        _finite(key.translation, key.bone + " translation")
        _finite(key.rotation, key.bone + " rotation")
        magnitude = math.sqrt(sum(component * component for component in key.rotation))
        if magnitude == 0.0:
            raise ValueError(f"zero quaternion for {key.bone}")
        x, y, z, w = (component / magnitude for component in key.rotation)
        tx, ty, tz = key.translation
        output += FRAME_STRUCT.pack(
            encode_name(key.bone, 15), key.frame,
            tx, ty, -tz,
            -x, -y, z, w,
            interpolation_bytes(key.curve),
        )
    ordered_morphs = sorted(morph_keys, key=lambda key: (key.morph, key.frame))
    output += COUNT.pack(len(ordered_morphs))
    for key in ordered_morphs:
        _finite((key.weight,), key.morph + " weight")
        output += MORPH_STRUCT.pack(encode_name(key.morph, 15), key.frame, key.weight)
    output += COUNT.pack(0) * 4
    return bytes(output)


def read(data):
    """Parse a VMD written by :func:`write` back into right-handed keys."""
    if len(data) < 54 or data[:30] != SIGNATURE.ljust(30, b"\0"):
        raise ValueError("VMD signature is unsupported")
    model_name = decode_name(data[30:50])
    position = 50
    bone_count, = COUNT.unpack_from(data, position)
    position += 4
    bone_keys = []
    for _ in range(bone_count):
        name, frame, tx, ty, tz, x, y, z, w, interpolation = FRAME_STRUCT.unpack_from(data, position)
        position += FRAME_STRUCT.size
        bone_keys.append(BoneKey(decode_name(name), frame, (tx, ty, -tz), (-x, -y, z, w), curve_from_bytes(interpolation)))
    morph_count, = COUNT.unpack_from(data, position)
    position += 4
    morph_keys = []
    for _ in range(morph_count):
        name, frame, weight = MORPH_STRUCT.unpack_from(data, position)
        position += MORPH_STRUCT.size
        morph_keys.append(MorphKey(decode_name(name), frame, weight))
    trailing = data[position:]
    if trailing != COUNT.pack(0) * 4:
        raise ValueError("unexpected trailing sections")
    return model_name, bone_keys, morph_keys

"""POCO X8 Pro — a Blender asset built from reference photos, rendered headless.

Run:  blender -b --factory-startup -noaudio -P scripts/blender/phone.py -- renders 64

The reference is the official product shots: a flat slab in "ice blue", dual stacked
circular camera rings on a raised plateau, punch-hole screen, metallic frame, power
button with a red accent, USB-C and speaker at the bottom. Real size: 163 x 76 x 8 mm.

Everything is procedural so the whole asset is one text file. The render folder holds
PNGs, a GLB, the .blend and report.json — the report is what makes a failed run
diagnosable without the log.
"""

import json
import math
import os
import sys
import traceback

import bmesh
import bpy
from mathutils import Vector

MM = 0.001
W = 76.0 * MM          # width  (X)
T = 8.0 * MM           # thickness (Y)
H = 163.0 * MM         # height (Z)
CORNER = 8.5 * MM      # plan-view corner radius

OUT = "renders"
SAMPLES = 64
ANIM = False
if "--" in sys.argv:
    extra = sys.argv[sys.argv.index("--") + 1:]
    if extra:
        OUT = extra[0]
    if len(extra) > 1:
        SAMPLES = int(extra[1])
    if len(extra) > 2 and extra[2].lower() == "anim":
        ANIM = True

os.makedirs(OUT, exist_ok=True)
report = {"out": OUT, "samples": SAMPLES, "renders": [], "steps": []}


# ---------------------------------------------------------------- helpers

def set_in(node, names, value):
    for name in names:
        if name in node.inputs:
            node.inputs[name].default_value = value
            return True
    return False


def mat(name, base, metallic=0.0, rough=0.35, coat=0.0, ior=1.45,
        transmission=0.0, emission=None, strength=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    set_in(b, ["Base Color"], (*base, 1.0))
    set_in(b, ["Metallic"], metallic)
    set_in(b, ["Roughness"], rough)
    set_in(b, ["IOR"], ior)
    set_in(b, ["Coat Weight", "Clearcoat"], coat)
    set_in(b, ["Transmission Weight", "Transmission"], transmission)
    if emission is not None:
        set_in(b, ["Emission Color", "Emission"], (*emission, 1.0))
        set_in(b, ["Emission Strength"], strength)
    return m


def smooth(obj, angle=32.0):
    for poly in obj.data.polygons:
        poly.use_smooth = True
    if hasattr(obj.data, "use_auto_smooth"):
        obj.data.use_auto_smooth = True
        obj.data.auto_smooth_angle = math.radians(angle)
    else:
        try:
            bpy.context.view_layer.objects.active = obj
            bpy.ops.object.shade_auto_smooth(angle=math.radians(angle))
        except Exception:
            pass


def rounded_slab(name, w, t, h, corner, material, seg=10, rim=0.6 * MM):
    """A rounded-rectangle prism: proper 8.5 mm plan corners and a softened rim.

    A bevel modifier on a cube cannot do both — its width is clamped by the thinnest
    side, so the corners would come out at 4 mm. So the profile is built directly.
    """
    bm = bmesh.new()
    pts = []
    for ccx, ccz, a0 in ((w / 2 - corner, h / 2 - corner, 0.0),
                         (-(w / 2 - corner), h / 2 - corner, 90.0),
                         (-(w / 2 - corner), -(h / 2 - corner), 180.0),
                         (w / 2 - corner, -(h / 2 - corner), 270.0)):
        for i in range(seg + 1):
            a = math.radians(a0 + 90.0 * i / seg)
            pts.append((ccx + corner * math.cos(a), ccz + corner * math.sin(a)))

    cap = [bm.verts.new((x, -t / 2, z)) for x, z in pts]
    face = bm.faces.new(cap)
    res = bmesh.ops.extrude_face_region(bm, geom=[face])
    bmesh.ops.translate(bm, verts=[e for e in res["geom"]
                                   if isinstance(e, bmesh.types.BMVert)],
                        vec=(0.0, t, 0.0))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)

    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    obj = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(obj)
    obj.data.materials.append(material)

    if rim > 0:
        bpy.ops.object.select_all(action="DESELECT")
        obj.select_set(True)
        bpy.context.view_layer.objects.active = obj
        m = obj.modifiers.new("rim", "BEVEL")
        m.width = rim
        m.segments = 3
        m.limit_method = "ANGLE"
        m.angle_limit = math.radians(50)
        bpy.ops.object.modifier_apply(modifier=m.name)
    smooth(obj, 40)
    return obj


def box(name, size, radius, loc=(0, 0, 0), rot=(0, 0, 0), material=None, segments=8):
    bpy.ops.object.select_all(action="DESELECT")
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=loc)
    o = bpy.context.object
    o.name = name
    o.scale = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    o.rotation_euler = rot
    r = min(radius, min(size) * 0.49)
    if r > 0:
        m = o.modifiers.new("bevel", "BEVEL")
        m.width = r
        m.segments = segments
        m.limit_method = "NONE"
        bpy.context.view_layer.objects.active = o
        bpy.ops.object.modifier_apply(modifier=m.name)
    smooth(o)
    if material:
        o.data.materials.append(material)
    return o


def cyl(name, radius, depth, loc, material, axis="Y", verts=64):
    bpy.ops.object.select_all(action="DESELECT")
    bpy.ops.mesh.primitive_cylinder_add(radius=radius, depth=depth,
                                        vertices=verts, location=loc)
    o = bpy.context.object
    o.name = name
    o.rotation_euler = {"Y": (math.radians(90), 0, 0),
                        "Z": (0, 0, 0),
                        "X": (0, math.radians(90), 0)}[axis]
    smooth(o, 40)
    if material:
        o.data.materials.append(material)
    return o


def torus(name, major, minor, loc, material, axis="Y"):
    bpy.ops.object.select_all(action="DESELECT")
    bpy.ops.mesh.primitive_torus_add(major_radius=major, minor_radius=minor,
                                     major_segments=96, minor_segments=20,
                                     location=loc)
    o = bpy.context.object
    o.name = name
    if axis == "Y":
        o.rotation_euler = (math.radians(90), 0, 0)
    smooth(o, 40)
    o.data.materials.append(material)
    return o


def text(name, body, size, loc, rot, material, extrude=0.12 * MM):
    bpy.ops.object.select_all(action="DESELECT")
    bpy.ops.object.text_add(location=loc)
    o = bpy.context.object
    o.name = name
    o.data.body = body
    o.data.size = size
    o.data.extrude = extrude
    o.data.align_x = "CENTER"
    o.data.align_y = "CENTER"
    o.rotation_euler = rot
    bpy.ops.object.convert(target="MESH")
    o = bpy.context.object
    o.data.materials.append(material)
    return o


# ---------------------------------------------------------------- materials
#
# Built inside make_materials(), after the file is reset: datablocks created before
# read_factory_settings() are freed and every later reference raises
# "StructRNA of type Material has been removed".

ice = (0.58, 0.80, 0.78)          # "ice blue" from the product shots
frame_mat = back_mat = plateau_mat = rim_mat = glass_mat = None
flash_mat = bezel_mat = dark_mat = accent_mat = logo_mat = glow_mat = None


def make_materials():
    global frame_mat, back_mat, plateau_mat, rim_mat, glass_mat
    global flash_mat, bezel_mat, dark_mat, accent_mat, logo_mat, glow_mat
    frame_mat = mat("frame-metal", ice, metallic=1.0, rough=0.24)
    back_mat = mat("back-glass", ice, metallic=0.12, rough=0.15, coat=0.55)
    plateau_mat = mat("plateau", ice, metallic=0.30, rough=0.16, coat=0.70)
    rim_mat = mat("lens-rim", (0.08, 0.09, 0.10), metallic=1.0, rough=0.18)
    glass_mat = mat("lens-glass", (0.010, 0.014, 0.022), metallic=0.30, rough=0.05,
                    transmission=0.30, ior=1.55)
    flash_mat = mat("flash", (1.0, 0.95, 0.86), rough=0.12,
                    emission=(1.0, 0.93, 0.80), strength=1.2)
    bezel_mat = mat("bezel", (0.010, 0.011, 0.013), metallic=0.2, rough=0.08, coat=0.6)
    dark_mat = mat("port", (0.02, 0.02, 0.022), metallic=0.4, rough=0.4)
    accent_mat = mat("accent-red", (0.62, 0.05, 0.05), metallic=0.6, rough=0.3)
    logo_mat = mat("logo", (0.16, 0.18, 0.19), metallic=0.7, rough=0.3)
    glow_mat = mat("screen-glow", (0.05, 0.32, 0.42),
                   emission=(0.30, 0.85, 1.0), strength=1.6)


def wallpaper():
    """The dark brushed wallpaper with one bright diagonal band, as on the phone."""
    m = bpy.data.materials.new("wallpaper")
    m.use_nodes = True
    nt = m.node_tree
    nodes, links = nt.nodes, nt.links
    bsdf = nodes["Principled BSDF"]
    set_in(bsdf, ["Roughness"], 0.18)
    set_in(bsdf, ["Metallic"], 0.45)
    set_in(bsdf, ["Emission Strength"], 0.55)

    coord = nodes.new("ShaderNodeTexCoord")
    dot = nodes.new("ShaderNodeVectorMath")
    dot.operation = "DOT_PRODUCT"
    dot.inputs[1].default_value = (0.7071, 0.0, 0.7071)
    # The display quad is ~73 x 157 mm, so the dot product lands in about +/-0.082.
    span = nodes.new("ShaderNodeMapRange")
    span.inputs["From Min"].default_value = -0.09
    span.inputs["From Max"].default_value = 0.09
    span.inputs["To Min"].default_value = 0.0
    span.inputs["To Max"].default_value = 1.0

    ramp = nodes.new("ShaderNodeValToRGB")
    cr = ramp.color_ramp
    cr.elements[0].position = 0.0
    cr.elements[0].color = (0.006, 0.010, 0.020, 1)
    cr.elements[1].position = 1.0
    cr.elements[1].color = (0.008, 0.012, 0.024, 1)
    stops = [(0.38, (0.018, 0.032, 0.052)),
             (0.47, (0.070, 0.135, 0.185)),
             (0.52, (0.330, 0.520, 0.600)),   # the lit edge of the metal band
             (0.57, (0.038, 0.075, 0.115)),
             (0.70, (0.012, 0.022, 0.040))]
    for pos, col in stops:
        e = cr.elements.new(pos)
        e.color = (*col, 1)

    links.new(coord.outputs["Object"], dot.inputs[0])
    links.new(dot.outputs["Value"], span.inputs["Value"])
    links.new(span.outputs["Result"], ramp.inputs["Fac"])
    links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    links.new(ramp.outputs["Color"], bsdf.inputs["Emission Color"])
    return m


# ---------------------------------------------------------------- build

def build():
    rounded_slab("body", W, T, H, CORNER, frame_mat, seg=12, rim=0.7 * MM)

    # Glass back and front, each a hair proud of the frame.
    rounded_slab("back-glass", W - 0.8 * MM, 1.1 * MM, H - 0.8 * MM,
                 CORNER - 0.4 * MM, back_mat, seg=12, rim=0.35 * MM
                 ).location = (0, T / 2 - 0.55 * MM, 0)
    rounded_slab("front-glass", W - 1.0 * MM, 1.0 * MM, H - 1.0 * MM,
                 CORNER - 0.5 * MM, bezel_mat, seg=12, rim=0.3 * MM
                 ).location = (0, -T / 2 + 0.5 * MM, 0)

    # The display is a rounded slab, not a quad: square corners would poke out of the
    # rounded body. Its mesh sits in the X/Z plane, so the wallpaper's diagonal is
    # simply the dot product with (0.7071, 0, 0.7071) in object space.
    display = rounded_slab("display", W - 3.4 * MM, 0.3 * MM, H - 6.6 * MM,
                           CORNER - 1.6 * MM, wallpaper(), seg=12, rim=0.10 * MM)
    display.location = (0, -T / 2 - 0.02 * MM, 0)

    # Punch-hole camera.
    cyl("punch-hole", 1.35 * MM, 0.4 * MM, (0, -T / 2 - 0.06 * MM, H / 2 - 7.0 * MM),
        bezel_mat, axis="Y", verts=48)
    cyl("punch-hole-glass", 1.0 * MM, 0.3 * MM,
        (0, -T / 2 - 0.16 * MM, H / 2 - 7.0 * MM), glass_mat, axis="Y", verts=48)

    # The glowing capsule the wallpaper shows off.
    glow = rounded_slab("screen-glow", 9.0 * MM, 0.25 * MM, 24.0 * MM, 4.5 * MM,
                        glow_mat, seg=16, rim=0.08 * MM)
    glow.location = (-2.0 * MM, -T / 2 - 0.38 * MM, 8.0 * MM)
    glow.rotation_euler = (0, math.radians(-20), 0)

    # Camera plateau, upper part of the back.
    px = -16.5 * MM
    pz = 44.0 * MM
    plateau = rounded_slab("plateau", 28.0 * MM, 1.7 * MM, 57.0 * MM, 12.0 * MM,
                           plateau_mat, seg=14, rim=0.5 * MM)
    plateau.location = (px, T / 2 + 0.85 * MM, pz)

    for i, dz in enumerate((15.0 * MM, -15.0 * MM)):
        z = pz + dz
        y = T / 2 + 1.7 * MM
        torus("lens-ring-%d" % i, 11.2 * MM, 0.9 * MM, (px, y, z), rim_mat)
        cyl("lens-barrel-%d" % i, 10.6 * MM, 1.1 * MM, (px, y + 0.15 * MM, z),
            rim_mat, axis="Y")
        cyl("lens-glass-%d" % i, 8.6 * MM, 0.5 * MM, (px, y + 0.55 * MM, z),
            glass_mat, axis="Y")
        bpy.ops.object.select_all(action="DESELECT")
        bpy.ops.mesh.primitive_uv_sphere_add(radius=8.0 * MM, segments=48,
                                             ring_count=24,
                                             location=(px, y + 0.55 * MM, z))
        dome = bpy.context.object
        dome.name = "lens-dome-%d" % i
        dome.scale = (1.0, 0.16, 1.0)
        dome.data.materials.append(glass_mat)
        smooth(dome, 60)
        cyl("lens-inner-%d" % i, 4.2 * MM, 0.3 * MM, (px, y + 0.9 * MM, z),
            mat("lens-inner-%d" % i, (0.008, 0.008, 0.012), metallic=0.9, rough=0.04),
            axis="Y")

    # Flash and sensor dot, both inside the plateau.
    cyl("flash", 2.8 * MM, 0.8 * MM, (px + 8.5 * MM, T / 2 + 1.55 * MM,
                                      pz + 15.0 * MM), flash_mat, axis="Y", verts=48)
    cyl("sensor-dot", 1.2 * MM, 0.5 * MM, (px + 8.5 * MM, T / 2 + 1.55 * MM,
                                           pz + 3.0 * MM),
        mat("sensor-dot", (0.04, 0.04, 0.05), metallic=0.5, rough=0.25),
        axis="Y", verts=32)

    # POCO wordmark, reading up the back like the reference.
    text("logo", "POCO", 8.5 * MM, (-22.0 * MM, T / 2 + 0.06 * MM, -52.0 * MM),
         (math.radians(-90), math.radians(90), 0), logo_mat)

    # Right-hand side: volume rocker and the power button with its red accent.
    for name, zc, length in (("volume", 30.0 * MM, 24.0 * MM),
                             ("power", 4.0 * MM, 13.0 * MM)):
        box("btn-" + name, (1.5 * MM, 2.6 * MM, length), 0.7 * MM,
            loc=(W / 2 - 0.1 * MM, 0, zc), material=frame_mat, segments=6)
    box("power-accent", (0.5 * MM, 2.9 * MM, 1.4 * MM), 0.2 * MM,
        loc=(W / 2 + 0.15 * MM, 0, 10.6 * MM), material=accent_mat, segments=4)

    # Bottom: USB-C, speaker holes, mic.
    box("usb-c", (7.2 * MM, 2.0 * MM, 2.2 * MM), 1.0 * MM,
        loc=(-4.0 * MM, 0, -H / 2 + 0.2 * MM), material=dark_mat, segments=6)
    for i in range(6):
        cyl("speaker-%d" % i, 0.45 * MM, 1.2 * MM,
            (10.0 * MM + i * 1.6 * MM, 0, -H / 2 + 0.3 * MM), dark_mat,
            axis="Z", verts=16)
    cyl("mic-bottom", 0.5 * MM, 1.2 * MM, (20.0 * MM, 0, -H / 2 + 0.3 * MM),
        dark_mat, axis="Z", verts=16)

    # Top: mic and the IR blaster dot.
    cyl("mic-top", 0.5 * MM, 1.2 * MM, (-2.0 * MM, 0, H / 2 - 0.3 * MM),
        dark_mat, axis="Z", verts=16)
    cyl("ir", 1.1 * MM, 0.6 * MM, (26.0 * MM, 0, H / 2 - 0.25 * MM), dark_mat,
        axis="Z", verts=24)


# ---------------------------------------------------------------- studio

def studio():
    scene = bpy.context.scene

    # Dark floor the phone stands on, so it gets a contact shadow.
    bpy.ops.object.select_all(action="DESELECT")
    bpy.ops.mesh.primitive_plane_add(size=6.0, location=(0, 0.30, -0.0818))
    floor = bpy.context.object
    floor.name = "floor"
    floor.data.materials.append(mat("floor", (0.026, 0.028, 0.032), rough=0.45))

    def area(name, loc, size, power, color=(1, 1, 1), target=(0, 0, 0)):
        light = bpy.data.lights.new(name, type="AREA")
        light.energy = power
        light.size = size
        light.color = color
        obj = bpy.data.objects.new(name, light)
        obj.location = loc
        scene.collection.objects.link(obj)
        d = Vector(target) - Vector(loc)
        obj.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
        return obj

    area("key", (0.50, -0.60, 0.58), 0.50, 105, color=(1.0, 0.98, 0.95))
    area("fill", (-0.70, -0.45, 0.10), 0.85, 38, color=(0.86, 0.93, 1.0))
    area("rim", (0.10, 0.70, 0.52), 0.40, 105, color=(0.86, 0.95, 1.0))
    area("kick", (-0.35, 0.28, -0.05), 0.30, 14, color=(0.90, 1.0, 1.0))

    world = bpy.data.worlds.new("studio")
    world.use_nodes = True
    bg = world.node_tree.nodes["Background"]
    bg.inputs[0].default_value = (0.010, 0.012, 0.016, 1)
    bg.inputs[1].default_value = 1.0
    scene.world = world

    cam_data = bpy.data.cameras.new("camera")
    cam_data.lens = 85
    cam = bpy.data.objects.new("camera", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam

    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = SAMPLES
    scene.cycles.use_denoising = True
    try:
        scene.cycles.denoiser = "OPENIMAGEDENOISE"
    except Exception:
        pass
    scene.cycles.max_bounces = 10
    # Clamp glossy fireflies: without it the coat on the glass back goes speckled.
    scene.cycles.sample_clamp_indirect = 3.0
    report["denoise"] = bool(scene.cycles.use_denoising)
    scene.render.use_persistent_data = True
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGB"
    try:
        scene.view_settings.view_transform = "AgX"
    except Exception:
        pass
    scene.view_settings.exposure = -0.35
    return cam


def aim(cam, loc, target, lens):
    cam.location = loc
    cam.data.lens = lens
    d = Vector(target) - Vector(loc)
    cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()


def turntable(cam, frames=24, res=(720, 900), samples=24):
    """A full spin: the fastest way to show the whole asset in one file."""
    scene = bpy.context.scene
    scene.cycles.samples = samples
    scene.render.resolution_x, scene.render.resolution_y = res
    scene.frame_start, scene.frame_end = 1, frames
    for f in range(1, frames + 1):
        a = 2.0 * math.pi * (f - 1) / frames
        aim(cam, (0.44 * math.sin(a), -0.44 * math.cos(a), 0.075), (0, 0, 0.0), 85)
        cam.keyframe_insert("location", frame=f)
        cam.keyframe_insert("rotation_euler", frame=f)
    scene.render.image_settings.file_format = "FFMPEG"
    scene.render.ffmpeg.format = "MPEG4"
    scene.render.ffmpeg.codec = "H264"
    scene.render.ffmpeg.constant_rate_factor = "HIGH"
    scene.render.filepath = os.path.join(OUT, "turntable.mp4")
    bpy.ops.render.render(animation=True)


def shoot(cam, name, loc, target, lens, res):
    scene = bpy.context.scene
    aim(cam, loc, target, lens)
    scene.render.resolution_x, scene.render.resolution_y = res
    path = os.path.join(OUT, name + ".png")
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    report["renders"].append({
        "name": name,
        "file": os.path.basename(path),
        "bytes": os.path.getsize(path) if os.path.exists(path) else 0,
        "camera": [round(v, 4) for v in loc],
        "lens_mm": lens,
        "resolution": list(res),
    })


# ---------------------------------------------------------------- main

def main():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    make_materials()
    build()
    report["steps"].append("model")
    cam = studio()
    report["steps"].append("studio")

    shoot(cam, "01-front-hero", (0.175, -0.410, 0.065), (0, 0, 0.004), 85, (900, 1200))
    shoot(cam, "02-back-hero", (-0.190, 0.410, 0.070), (0, 0, 0.0), 85, (900, 1200))
    shoot(cam, "03-front-flat", (0, -0.460, 0.0), (0, 0, 0.0), 90, (900, 1200))
    shoot(cam, "04-camera-detail", (-0.085, 0.160, 0.115),
          (-0.0165, T / 2, 0.044), 100, (1000, 1000))
    shoot(cam, "05-edge", (0.200, -0.160, 0.030), (0, 0, 0.0), 120, (1000, 1000))

    if ANIM:
        try:
            turntable(cam)
            report["turntable"] = "turntable.mp4"
        except Exception:
            report["turntable_error"] = traceback.format_exc()

    blend = os.path.join(OUT, "poco-x8-pro.blend")
    bpy.ops.wm.save_as_mainfile(filepath=blend)
    report["blend"] = os.path.basename(blend)

    glb = os.path.join(OUT, "poco-x8-pro.glb")
    try:
        bpy.ops.export_scene.gltf(filepath=glb, export_format="GLB")
        report["glb"] = os.path.basename(glb)
    except Exception:
        report["glb_error"] = traceback.format_exc()
    report["steps"].append("export")
    report["ok"] = True


try:
    main()
except Exception:
    report["ok"] = False
    report["error"] = traceback.format_exc()
    raise
finally:
    with open(os.path.join(OUT, "report.json"), "w") as fh:
        json.dump(report, fh, indent=2)
        fh.write("\n")

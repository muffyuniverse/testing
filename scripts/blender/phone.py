"""POCO X8 Pro — a Blender asset built from reference photos, rendered headless.

Run:  blender -b --factory-startup -noaudio -P scripts/blender/phone.py -- renders 48

The reference is the official product shots: a flat slab in "ice blue", dual stacked
circular camera rings on a raised plateau, punch-hole screen, metallic frame, power
button with a red accent, USB-C + speaker at the bottom. Real dimensions: 163 x 76 x 8 mm.

Everything is built procedurally so the whole asset is one text file: no binaries in the
tree. The render folder holds PNGs, a GLB, the .blend and report.json.
"""

import json
import math
import os
import sys
import traceback

import bpy
from mathutils import Vector

MM = 0.001
W = 76.0 * MM          # width  (X)
T = 8.0 * MM           # thickness (Y)
H = 163.0 * MM         # height (Z)
CORNER = 8.5 * MM

OUT = "renders"
SAMPLES = 48
if "--" in sys.argv:
    extra = sys.argv[sys.argv.index("--") + 1:]
    if extra:
        OUT = extra[0]
    if len(extra) > 1:
        SAMPLES = int(extra[1])

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
        transmission=0.0, emission=None, strength=0.0, alpha=1.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    set_in(b, ["Base Color"], (*base, 1.0))
    set_in(b, ["Metallic"], metallic)
    set_in(b, ["Roughness"], rough)
    set_in(b, ["IOR"], ior)
    set_in(b, ["Coat Weight", "Clearcoat"], coat)
    set_in(b, ["Transmission Weight", "Transmission"], transmission)
    set_in(b, ["Alpha"], alpha)
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


def box(name, size, radius, loc=(0, 0, 0), rot=(0, 0, 0), material=None, segments=8):
    """A slab with every edge rounded — the phone body, the plateau, the buttons."""
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


def cyl(name, radius, depth, loc, material, axis="Y", verts=64, rot=(0, 0, 0)):
    bpy.ops.mesh.primitive_cylinder_add(radius=radius, depth=depth,
                                        vertices=verts, location=loc)
    o = bpy.context.object
    o.name = name
    base = {"Y": (math.radians(90), 0, 0),
            "Z": (0, 0, 0),
            "X": (0, math.radians(90), 0)}[axis]
    o.rotation_euler = tuple(b + r for b, r in zip(base, rot))
    smooth(o, 40)
    if material:
        o.data.materials.append(material)
    return o


def torus(name, major, minor, loc, material, axis="Y"):
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

ice = (0.706, 0.870, 0.851)          # "ice blue" from the product shots
frame_mat = mat("frame-metal", ice, metallic=1.0, rough=0.26)
back_mat = mat("back-glass", ice, metallic=0.15, rough=0.16, coat=1.0)
plateau_mat = mat("plateau", ice, metallic=0.25, rough=0.22, coat=0.8)
rim_mat = mat("lens-rim", (0.10, 0.11, 0.12), metallic=1.0, rough=0.22)
glass_mat = mat("lens-glass", (0.015, 0.02, 0.03), metallic=0.35, rough=0.06,
                transmission=0.35, ior=1.55)
flash_mat = mat("flash", (1.0, 0.95, 0.86), rough=0.15, emission=(1.0, 0.93, 0.80),
                strength=1.6)
bezel_mat = mat("bezel", (0.012, 0.013, 0.015), metallic=0.2, rough=0.10, coat=0.6)
dark_mat = mat("port", (0.02, 0.02, 0.022), metallic=0.4, rough=0.4)
accent_mat = mat("accent-red", (0.62, 0.05, 0.05), metallic=0.6, rough=0.3)
logo_mat = mat("logo", (0.30, 0.33, 0.34), metallic=0.7, rough=0.35)
glow_mat = mat("screen-glow", (0.25, 0.95, 1.0), emission=(0.30, 0.95, 1.0), strength=6.0)


def wallpaper():
    """The dark brushed-metal wallpaper with a diagonal split, as on the phone."""
    m = bpy.data.materials.new("wallpaper")
    m.use_nodes = True
    nt = m.node_tree
    nodes, links = nt.nodes, nt.links
    bsdf = nodes["Principled BSDF"]
    set_in(bsdf, ["Roughness"], 0.25)
    set_in(bsdf, ["Metallic"], 0.6)
    set_in(bsdf, ["Emission Strength"], 1.15)

    coord = nodes.new("ShaderNodeTexCoord")
    dot = nodes.new("ShaderNodeVectorMath")
    dot.operation = "DOT_PRODUCT"
    dot.inputs[1].default_value = (0.7071, 0.0, 0.7071)
    ramp = nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.28
    ramp.color_ramp.elements[0].color = (0.02, 0.03, 0.05, 1)
    ramp.color_ramp.elements[1].position = 0.62
    ramp.color_ramp.elements[1].color = (0.45, 0.55, 0.60, 1)
    mid = ramp.color_ramp.elements.new(0.46)
    mid.color = (0.10, 0.14, 0.18, 1)

    links.new(coord.outputs["Object"], dot.inputs[0])
    links.new(dot.outputs["Value"], ramp.inputs["Fac"])
    links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    links.new(ramp.outputs["Color"], bsdf.inputs["Emission Color"])
    return m


# ---------------------------------------------------------------- build

def build():
    body = box("body", (W, T, H), CORNER, material=frame_mat, segments=10)

    # Back panel sits a hair proud of the frame, like a glass back.
    box("back-glass", (W - 0.7 * MM, 1.1 * MM, H - 0.7 * MM), CORNER - 0.35 * MM,
        loc=(0, T / 2 - 0.55 * MM, 0), material=back_mat)

    # Front: black glass, slightly inset, so the frame shows as a thin border.
    box("front-glass", (W - 1.0 * MM, 1.0 * MM, H - 1.0 * MM), CORNER - 0.5 * MM,
        loc=(0, -T / 2 + 0.5 * MM, 0), material=bezel_mat)

    bpy.ops.mesh.primitive_plane_add(size=1.0, location=(0, -T / 2 - 0.02 * MM, 0))
    screen = bpy.context.object
    screen.name = "display"
    screen.scale = (W - 3.2 * MM, H - 6.4 * MM, 1)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    screen.rotation_euler = (math.radians(90), 0, 0)
    screen.data.materials.append(wallpaper())

    # Punch-hole camera.
    cyl("punch-hole", 1.35 * MM, 0.4 * MM, (0, -T / 2 - 0.06 * MM, H / 2 - 7.0 * MM),
        bezel_mat, axis="Y", verts=48)
    cyl("punch-hole-glass", 1.0 * MM, 0.3 * MM,
        (0, -T / 2 - 0.16 * MM, H / 2 - 7.0 * MM), glass_mat, axis="Y", verts=48)

    # The glowing capsule the wallpaper shows off.
    glow = box("screen-glow", (10.0 * MM, 0.35 * MM, 26.0 * MM), 5.0 * MM,
               loc=(0, -T / 2 - 0.25 * MM, 6.0 * MM), material=glow_mat, segments=12)
    glow.rotation_euler = (0, math.radians(-18), 0)

    # Camera plateau, top-left of the back (mirrored, as seen from the back).
    px = -16.5 * MM
    pz = 44.0 * MM
    box("plateau", (28.0 * MM, 1.7 * MM, 57.0 * MM), 12.0 * MM,
        loc=(px, T / 2 + 0.85 * MM, pz), material=plateau_mat, segments=10)

    for i, dz in enumerate((15.0 * MM, -15.0 * MM)):
        z = pz + dz
        y = T / 2 + 1.7 * MM
        torus("lens-ring-%d" % i, 11.2 * MM, 0.9 * MM, (px, y, z), rim_mat)
        cyl("lens-barrel-%d" % i, 10.6 * MM, 1.1 * MM, (px, y + 0.15 * MM, z),
            rim_mat, axis="Y")
        cyl("lens-glass-%d" % i, 8.6 * MM, 0.5 * MM, (px, y + 0.55 * MM, z),
            glass_mat, axis="Y")
        bpy.ops.mesh.primitive_uv_sphere_add(radius=8.0 * MM, segments=48,
                                             ring_count=24,
                                             location=(px, y + 0.55 * MM, z))
        dome = bpy.context.object
        dome.name = "lens-dome-%d" % i
        dome.scale = (1.0, 0.16, 1.0)
        dome.data.materials.append(glass_mat)
        smooth(dome, 60)
        # a small specular kick so the glass reads as glass
        cyl("lens-inner-%d" % i, 4.2 * MM, 0.3 * MM, (px, y + 0.9 * MM, z),
            mat("lens-inner-%d" % i, (0.01, 0.01, 0.02), metallic=0.9, rough=0.05),
            axis="Y")

    # Flash and the little sensor dot beside the top lens.
    cyl("flash", 3.2 * MM, 0.9 * MM, (px + 16.0 * MM, T / 2 + 1.6 * MM,
                                      pz + 15.0 * MM), flash_mat, axis="Y", verts=48)
    cyl("flash-dot", 1.4 * MM, 0.6 * MM, (px + 16.0 * MM, T / 2 + 1.6 * MM,
                                          pz + 4.0 * MM),
        mat("sensor-dot", (0.05, 0.05, 0.06), metallic=0.5, rough=0.3),
        axis="Y", verts=32)

    # POCO wordmark, reading up the back like the reference.
    text("logo", "POCO", 7.5 * MM, (-20.0 * MM, T / 2 + 0.06 * MM, -50.0 * MM),
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

    return body


# ---------------------------------------------------------------- studio

def studio():
    scene = bpy.context.scene

    # Dark seamless backdrop: a floor that fades away.
    bpy.ops.mesh.primitive_plane_add(size=6.0, location=(0, 0.35, -0.115))
    floor = bpy.context.object
    floor.name = "floor"
    floor.data.materials.append(mat("floor", (0.045, 0.048, 0.052), rough=0.55))

    def area(name, loc, size, power, target=(0, 0, 0), color=(1, 1, 1)):
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

    area("key", (0.55, -0.65, 0.62), 0.55, 160, color=(1.0, 0.98, 0.95))
    area("fill", (-0.75, -0.45, 0.10), 0.90, 70, color=(0.85, 0.93, 1.0))
    area("rim", (0.10, 0.75, 0.55), 0.45, 130, color=(0.85, 0.95, 1.0))
    area("kick", (-0.35, 0.30, -0.05), 0.30, 25, color=(0.9, 1.0, 1.0))

    world = bpy.data.worlds.new("studio")
    world.use_nodes = True
    bg = world.node_tree.nodes["Background"]
    bg.inputs[0].default_value = (0.012, 0.014, 0.018, 1)
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
    scene.cycles.max_bounces = 10
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGB"
    try:
        scene.view_settings.view_transform = "AgX"
    except Exception:
        pass
    return cam


def aim(cam, loc, target, lens):
    cam.location = loc
    cam.data.lens = lens
    d = Vector(target) - Vector(loc)
    cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()


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
    build()
    report["steps"].append("model")
    cam = studio()
    report["steps"].append("studio")

    shoot(cam, "01-front-hero", (0.20, -0.42, 0.09), (0, 0, 0.005), 85, (900, 1200))
    shoot(cam, "02-back-hero", (-0.22, 0.42, 0.07), (0, 0, 0.0), 85, (900, 1200))
    shoot(cam, "03-front-flat", (0, -0.46, 0.0), (0, 0, 0.0), 100, (900, 1200))
    shoot(cam, "04-camera-detail", (-0.10, 0.20, 0.14),
          (-0.0165, T / 2, 0.044), 120, (1000, 1000))
    shoot(cam, "05-edge", (0.26, -0.10, 0.02), (0, 0, 0.0), 100, (1000, 1000))

    blend = os.path.join(OUT, "poco-x8-pro.blend")
    bpy.ops.wm.save_as_mainfile(filepath=blend)
    glb = os.path.join(OUT, "poco-x8-pro.glb")
    bpy.ops.export_scene.gltf(filepath=glb, export_format="GLB")
    report["blend"] = os.path.basename(blend)
    report["glb"] = os.path.basename(glb)
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

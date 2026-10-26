"""Build an animated six-legged verification rig in a disposable live editor via MCP.

This is a functional fixture, not production ant artwork. No project JSON is authored
here: all scene edits, saves and loads go through the live editor's MCP tools.
"""
import asyncio
import base64
import json
import math
import os
from pathlib import Path
import site
import uuid

if os.environ.get("OPENBONE_MCP_SDK_PATH"):
    site.addsitedir(os.environ["OPENBONE_MCP_SDK_PATH"])
import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


def transform(x=0, y=0, rotation=0):
    return {"x": x, "y": y, "rotation": rotation, "scaleX": 1, "scaleY": 1}


async def main():
    if os.environ.get("OPENBONE_MCP_TEST_ALLOW_EDITS") != "disposable-fixture":
        raise RuntimeError("This check requires an explicitly disposable editor")
    output = Path(os.environ["OPENBONE_MCP_TEST_OUTPUT"]).resolve()
    assets = Path(os.environ["OPENBONE_MCP_TEST_ASSETS"]).resolve()
    output.mkdir(parents=True, exist_ok=True)
    headers = {"Authorization": "Bearer " + os.environ["OPENBONE_MCP_TEST_TOKEN"]}
    async with (
        httpx.AsyncClient(headers=headers, timeout=30, trust_env=False) as client,
        streamable_http_client(os.environ["OPENBONE_MCP_TEST_URL"], http_client=client) as (read, write, _),
    ):
        async with ClientSession(read, write) as session:
            await session.initialize()
            assert len((await session.list_tools()).tools) == 9

            async def raw(name, args):
                return await session.call_tool(name, args)

            async def call(name, args):
                result = await raw(name, args)
                assert not result.isError, result.content
                return result.structuredContent["data"], result

            async def context():
                return (await call("openbone_get_context", {}))[0]

            before = await context()
            assert before["counts"]["bones"] == 0 and before["counts"]["slots"] == 0
            assert before["mcpApiVersion"] == 2
            revision = before["revision"]
            clip = before["view"]["animationId"]
            edits, bones, slots = [], {}, []

            def bone(name, parent, setup, length, texture=None, width=None, height=None, offset=None):
                bone_id = str(uuid.uuid4())
                bones[name] = bone_id
                edits.append({"op": "create_bone", "boneId": bone_id, "parentId": bones.get(parent), "name": name, "setup": setup, "length": length})
                if texture:
                    slot_id, attachment_id = str(uuid.uuid4()), str(uuid.uuid4())
                    slots.append(slot_id)
                    edits.append({"op": "create_slot", "slotId": slot_id, "boneId": bone_id, "name": name + "_slot"})
                    edits.append({"op": "create_region", "attachmentId": attachment_id, "slotId": slot_id, "name": name + "_image",
                                  "pngPath": str(assets / texture), "setup": offset or transform(), "width": width, "height": height})

            bone("root", None, transform(), 0)
            bone("thorax", "root", transform(), 0)
            leg_slots = []
            for side in ("L", "R"):
                for index in range(3):
                    upper = f"leg_{side}{index}_upper"
                    lower = f"leg_{side}{index}_lower"
                    angle = (45, 0, -45)[index]
                    if side == "L":
                        angle = 180 - angle
                    bone(upper, "thorax", transform(-17 if side == "L" else 17, 25-index*24, angle), 55,
                         "segment.png", 55, 7, transform(27.5))
                    leg_slots.append(slots[-1])
                    bend = (45, 35, 30)[index] * (1 if side == "L" else -1)
                    bone(lower, upper, transform(55, 0, bend), 62, "segment.png", 62, 5, transform(31))
                    leg_slots.append(slots[-1])
            bone("abdomen", "thorax", transform(0, -65), 0, "body.png", 72, 106)
            abdomen_slot = slots[-1]
            # Attach thorax only after creating the legs so static order puts it in front.
            thorax_slot, thorax_attachment = str(uuid.uuid4()), str(uuid.uuid4())
            slots.append(thorax_slot)
            edits += [{"op": "create_slot", "slotId": thorax_slot, "boneId": bones["thorax"], "name": "thorax_slot"},
                      {"op": "create_region", "attachmentId": thorax_attachment, "slotId": thorax_slot, "name": "thorax_image", "pngPath": str(assets/"body.png"), "width": 49, "height": 76}]
            bone("head", "thorax", transform(0, 68), 0, "head.png", 60, 56)
            head_slot = slots[-1]
            antenna_slots = []
            for side in ("L", "R"):
                bone("antenna_"+side, "head", transform(-18 if side == "L" else 18, 17, 115 if side == "L" else 65), 44,
                     "segment.png", 44, 3, transform(22))
                antenna_slots.append(slots[-1])
            edits.append({"op": "set_slot_order", "slotIds": leg_slots + [abdomen_slot, thorax_slot, head_slot] + antenna_slots})

            def oscillate(name, amplitude, phase=0):
                for i in range(9):
                    time = i / 8
                    angle = 2 * math.pi * time + phase
                    value = amplitude * math.sin(angle)
                    edit = {"op": "put_keyframe", "animationId": clip, "boneId": bones[name], "channel": "ROTATE", "time": time, "values": [value]}
                    if i < 8:
                        delta = amplitude * math.sin(angle+math.pi/4)-value
                        if abs(delta) > 1e-8:
                            slope = amplitude * 2*math.pi * math.cos(angle)
                            next_slope = amplitude * 2*math.pi * math.cos(angle+math.pi/4)
                            edit["curve"] = {"kind": "bezier", "x1": 1/3, "y1": slope/8/(3*delta), "x2": 2/3, "y2": 1-next_slope/8/(3*delta)}
                    edits.append(edit)

            for side in ("L", "R"):
                for index in range(3):
                    phase = 0 if ((side == "L" and index != 1) or (side == "R" and index == 1)) else math.pi
                    sign = 1 if side == "R" else -1
                    oscillate(f"leg_{side}{index}_upper", sign*14, phase)
                    oscillate(f"leg_{side}{index}_lower", sign*20, phase+.7)
            oscillate("head", 5)
            oscillate("antenna_L", 8, .3)
            oscillate("antenna_R", 8, -.3)
            batch = {"expectedRevision": revision, "requestId": str(uuid.uuid4()), "label": "MCP six leg rig verification", "edits": edits}
            built, _ = await call("openbone_apply_edits", batch)
            retry, _ = await call("openbone_apply_edits", batch)
            assert built == retry and built["counts"]["bones"] == 18 and built["counts"]["attachments"] == 17
            assert built["undoCount"] == 1
            revision = built["revision"]
            poses = []
            for time in (0, .25, .5, 1):
                meta, result = await call("openbone_capture_preview", {"expectedRevision": revision, "animationId": clip, "time": time,
                                                                      "fit": time == 0, "showBones": False, "showGrid": False})
                assert meta["time"] == time and not meta["showBones"] and not meta["showGrid"]
                image = next(block for block in result.content if block.type == "image")
                (output/f"t{time}.png").write_bytes(base64.b64decode(image.data))
                poses.append(meta["bones"])
            assert (output/"t0.png").read_bytes() != (output/"t0.25.png").read_bytes()
            for a, b in zip(poses[0], poses[-1]):
                assert abs(a["tip"]["x"]-b["tip"]["x"]) < 1e-6 and abs(a["tip"]["y"]-b["tip"]["y"]) < 1e-6
            _, before_save = await call("openbone_capture_preview", {"expectedRevision": revision, "animationId": clip, "time": .25, "fit": True, "showBones": False, "showGrid": False})
            before_image = next(block.data for block in before_save.content if block.type == "image")
            save = {"expectedRevision": revision, "requestId": str(uuid.uuid4()), "path": str(output/"ant-verification.json"), "portable": True, "overwrite": True}
            saved, _ = await call("openbone_save_project", save)
            assert saved["adopted"] and not saved["dirty"]
            assert (await call("openbone_save_project", save))[0] == saved
            undone, _ = await call("openbone_undo", {"expectedRevision": revision, "requestId": str(uuid.uuid4())})
            assert undone["counts"]["bones"] == 0
            redone, _ = await call("openbone_redo", {"expectedRevision": undone["revision"], "requestId": str(uuid.uuid4())})
            revision = redone["revision"]
            _, _ = await call("openbone_apply_edits", {"expectedRevision": revision, "requestId": str(uuid.uuid4()), "edits": [{"op": "edit_bone", "boneId": bones["head"], "name": "temporarily_renamed"}]})
            revision = (await context())["revision"]
            rejected = await raw("openbone_load_project", {"expectedRevision": revision, "requestId": str(uuid.uuid4()), "path": str(output/"ant-verification.json"), "dirtyPolicy": "reject"})
            assert rejected.isError
            loaded, _ = await call("openbone_load_project", {"expectedRevision": revision, "requestId": str(uuid.uuid4()), "path": str(output/"ant-verification.json"), "dirtyPolicy": "discard"})
            assert not loaded["dirty"] and loaded["counts"]["bones"] == 18
            revision = loaded["revision"]
            clean, image = await call("openbone_capture_preview", {"expectedRevision": revision, "animationId": clip, "time": .25, "fit": True, "showBones": False, "showGrid": False})
            after_image = next(block.data for block in image.content if block.type == "image")
            assert before_image == after_image, "Portable save/load must preserve actual rendered pixels at the same pose and fit"
            await call("openbone_control_view", {"expectedRevision": revision, "requestId": str(uuid.uuid4()), "action": "play", "animationId": clip, "time": 0, "loop": True})
            assert (await context())["view"]["playing"]
            await call("openbone_control_view", {"expectedRevision": revision, "requestId": str(uuid.uuid4()), "action": "pause"})
            assert not (await context())["view"]["playing"]
            await call("openbone_control_view", {"expectedRevision": revision, "requestId": str(uuid.uuid4()), "action": "seek", "animationId": clip, "time": .375})
            assert (await context())["view"]["time"] == .375
            await call("openbone_control_view", {"expectedRevision": revision, "requestId": str(uuid.uuid4()), "action": "frame"})
            final = await context()
            (output/"verification.json").write_text(json.dumps({"version": final["version"], "counts": final["counts"], "edits": len(edits), "checks": ["blank_scene", "atomic_build", "bezier_gait", "retry", "real_gl", "loop_seam", "save", "undo_redo", "dirty_load", "load_roundtrip", "play_pause_seek_frame"]}, indent=2), encoding="utf-8")
    print("Official MCP scene smoke passed: 18 bones, 17 PNG attachments, Bezier gait, GL frames, save/load, undo/redo, playback")


if __name__ == "__main__":
    asyncio.run(main())

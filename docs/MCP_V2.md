# OpenBone MCP API v2

OpenBone 0.1.3 adds the capabilities needed to build and animate a character from a blank live editor. It uses the same application services, model validation and history as desktop editing. No computer-use or external project-JSON authoring is required.

Read `openbone_get_context` first. It now returns `version`, `mcpApiVersion: 2`, and capabilities including `sceneBuilding`, `bezier`, `projectIO`, `playback`, `cleanPreview`, and `atomicEdits`. An old running JAR does not acquire new tools when source files are rebuilt: start the new JAR and reconnect the client to its URL/token.

Connection setup, transport authentication, pagination, local coordinate conventions and retry behavior are described in [the MCP guide](MCP.md). The nine tools are get_context, query_scene, apply_edits, capture_preview, undo, redo, control_view, save_project and load_project, each prefixed `openbone_`.

## Scene operations

`openbone_apply_edits` retains `expectedRevision`, `requestId`, optional `label`, and 1 to 500 sequential `edits`. All operations validate before a single atomic, undoable publication. A failed operation preserves the original model, selection, redo history and dirty state. PNG file validation and candidate computation happen off EDT; publication rechecks the live revision on EDT.

All new IDs are client-created canonical UUID strings. Parents and referenced slots must exist earlier in the batch. Setup transforms use absolute **local** values: required x, y, rotation, scaleX, scaleY; optional shearX/shearY default to zero. X points right, Y up, rotation is counterclockwise degrees. This differs from keyframe values, which remain offsets/factors relative to Setup.

| op | Required fields | Optional fields and defaults |
| --- | --- | --- |
| `create_bone` | `boneId`, `name`, `parentId` (UUID or explicit null), `setup`, `length` | Normal inheritance |
| `edit_bone` | `boneId` | `name`, `setup`, `length`; preserve identity, parent and inheritance |
| `reparent_bone` | `boneId`, `parentId` (UUID or null), `policy` | policy is `keep_local` or `keep_world`; cycles and nonrepresentable world-preserving transforms fail |
| `create_slot` | `slotId`, `name`, `boneId` | integer `index`, default append to setup order |
| `edit_slot` | `slotId` | `name`, `boneId`, `activeAttachmentId` (UUID or null) |
| `create_region` | `attachmentId`, `slotId`, `name`, `pngPath` | `setup` identity, `width`/`height` PNG dimensions, `makeActive` true |
| `edit_region` | `attachmentId` | `name`, `pngPath`, `setup`, `width`, `height`; preserve slot |
| `set_slot_order` | `slotIds` | Exact permutation of all slots, back to front |

`pngPath` must be a readable absolute local PNG path. The actual PNG is decoded and validated before publication. Imports link the canonical path without copying, modifying or creating files, so failed batches leave no imported disk files. Portable save is the explicit operation that copies resources. Use a distinct new path when replacing artwork so existing snapshots retain their image references. The existing restrictions on renaming regions used by skins or attachment animation still apply. Setup slot reordering does not rewrite explicit animation draw-order key permutations.

Region query records now include `source`, `setup`, `width` and `height`, in addition to IDs and name. Other attachment kinds retain summaries.

Example `tools/call` params, replacing all placeholders with UUIDs and a live revision:

```json
{
  "name":"openbone_apply_edits",
  "arguments":{
    "expectedRevision":"<revision>","requestId":"<unique request ID>","label":"Build body",
    "edits":[
      {"op":"create_bone","boneId":"<root UUID>","parentId":null,"name":"root","setup":{"x":0,"y":0,"rotation":0,"scaleX":1,"scaleY":1},"length":0},
      {"op":"create_slot","slotId":"<slot UUID>","name":"body_slot","boneId":"<root UUID>"},
      {"op":"create_region","attachmentId":"<region UUID>","slotId":"<slot UUID>","name":"body","pngPath":"D:/assets/body.png","width":100,"height":160}
    ]
  }
}
```

## Bezier keyframes

`put_keyframe.curve` continues to accept `linear` and `stepped`. It also accepts an object:

```json
{"kind":"bezier","x1":0.3333333333,"y1":0,"x2":0.6666666667,"y2":1}
```

Handles are fractions of the segment's time and value ranges, not absolute seconds or degrees. x1/x2 must be in [0,1]; finite y values may overshoot. The curve applies to the outgoing segment of that key and all its components. Omitting curve preserves existing curves, including Bezier, or uses linear for a new key. The last key has no outgoing segment. Existing `Curve` and `AnimationEdits.curves` implement sampling and validation.

## Playback and preview

`openbone_control_view` requires `expectedRevision`, `requestId`, `action`. Actions are `play`, `pause`, `seek`, `frame`, `configure`. Optional `animationId` chooses an animation, otherwise the current animation is used; `seek` requires `time`, and play may specify a starting time. Optional `loop`, `showBones`, `showGrid` update playback and overlay settings. Exact time is not snapped to frames. These controls do not change the model or create history entries. A frame action fits current visible geometry, falling back to bones when there is no drawable geometry.

`openbone_capture_preview` retains its animationId/time/expectedRevision inputs and adds optional `fit` (default false), `showBones`, `showGrid`. Overlay options persist; omitted options preserve current values, initially true. Capture pauses playback, seeks exactly, prepares the camera and overlay flags, and reads pixels in that same actual GL frame. It returns those flags with the corresponding revision, time, image dimensions and bone world positions. The longest image side remains at most 1024 pixels. Clean preview hides editing overlays; the editor background remains.

```json
{"name":"openbone_capture_preview","arguments":{"expectedRevision":"<revision>","animationId":"<animation UUID>","time":0.25,"fit":true,"showBones":false,"showGrid":false}}
```

```json
{"name":"openbone_control_view","arguments":{"expectedRevision":"<revision>","requestId":"<unique ID>","action":"play","animationId":"<animation UUID>","time":0,"loop":true}}
```

Remote mutation preparation pauses current playback. A subsequent play action restarts it; reading context does not pause it. Camera and playback do not change the model revision.

The desktop View menu also exposes Frame current pose, Show bones and editing handles, and Show grid and axes. Their checkmarks reflect MCP changes, so users can restore editing overlays even after disconnecting the client.

## Saving and loading

`openbone_save_project` requires `expectedRevision`, `requestId`, absolute `.json` `path`. Optional `portable` defaults to true and `overwrite` defaults to false. Existing files require explicit overwrite:true. IO uses a detached captured snapshot and the existing `ProjectDocument` save/portable-save service off EDT. A successful matching save updates the live path/save point on EDT while retaining undo history and selection. If a manual edit or project switch occurs during IO, the disk snapshot can be saved with `adopted:false`; current editor state is preserved and is not marked saved. The response includes `savedPath`, `savedRevision`, `adopted` and current context. Portable resource staging follows existing desktop behavior.

Save is an explicit disk side effect, not an undo entry. Undoing model edits does not delete a saved project. Reusing the identical request ID and arguments returns the original success receipt rather than writing again.

```json
{"name":"openbone_save_project","arguments":{"expectedRevision":"<revision>","requestId":"<unique ID>","path":"D:/assets/ant.json","portable":true,"overwrite":false}}
```

`openbone_load_project` requires `expectedRevision`, `requestId`, absolute `.json` `path`, and explicit `dirtyPolicy` of `reject` or `discard`. Reject returns DIRTY_PROJECT if current edits are unsaved. Discard explicitly authorizes replacing those edits. A detached project is parsed and resolved using the existing project service before the live revision is rechecked and the editor is replaced. Success resets history and animation view state and returns a fresh revision. A path/parse error, busy editor, version conflict or rejected dirty policy leaves the current project intact. As with desktop loading, missing external PNGs can remain missing-resource placeholders in an otherwise valid project.

```json
{"name":"openbone_load_project","arguments":{"expectedRevision":"<revision>","requestId":"<unique ID>","path":"D:/assets/ant.json","dirtyPolicy":"reject"}}
```

Loading cannot be undone after history reset. There is no file deletion, arbitrary code execution or export tool. Do not assume cancellation of a client wait rolls back an already completed save or load; read context and preserve original request IDs when retrying.

## Validation and remaining production work

The need came from the GardenBattle top-down ant task. Generic scene tools were added, with no ant-specific logic in the editor. `scripts/mcp_scene_smoke.py` drives the official Python SDK against a blank visible editor: a 188-operation batch creates 18 bones, 17 PNG attachments and a looping six-leg Bezier gait, then captures 0, 0.25, 0.5 and 1 seconds, checks loop endpoints, saves portable resources, undoes/redoes, exercises dirty loading, reopens, and checks play/pause/exact seek/frame. It never writes a project JSON directly. The save tool authors the project.

The fixture uses deliberately simple diagnostic PNGs. This verifies the tool chain; the final ant artwork, foot contact and production animation remain the original content task and require separate visual acceptance. Tests never change a user's production project. Evidence is in `target/mcp-ant-validation/`, including GL images, the saved project with PNGs, and verification.json. Run:

```shell
mvn -Dtest=EditorMcpServerTest,HistoryTest test
python -m pip install --target target/mcp-client-deps mcp==1.30.0
mvn -Dtest=McpDesktopTest -Dopenbone.verifyGl=true -Dopenbone.mcp.sdkPython=<absolute Python path> test
mvn test
```

Protocol tests additionally cover bad/missing/relative PNG paths, invalid pixels, cyclic parenting, reparent/order changes, atomic rollback, Bezier validation, stale revisions at publication, retry receipts, save history, overwrite policy, dirty and invalid loads, and portable resource round trips. Default tests skip native graphics and optional external SDK runs.

Implementation reuses `ModelEdits`, `AnimationEdits`, `ProjectDocument`, `CommandHistory`, and actual `EditorViewport` rendering. Region semantics were checked against the [official 4.2 API](https://esotericsoftware.com/spine-api-reference-4.2) and [public runtime sources](https://github.com/EsotericSoftware/spine-runtimes/tree/4.2); no runtime source was copied into the product. Local obfuscated editor source searches did not establish an original MCP pathway. The remote workflow is new OpenBone functionality built on its existing edit semantics.

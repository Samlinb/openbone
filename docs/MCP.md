# OpenBone MCP

Version 0.1.3 exposes MCP API v2 with ten tools. See [MCP API v2](MCP_V2.md) for scene construction, Bezier curves, save/load, playback, camera and clean previews. The original six tools below remain supported, with the extensions described there.

MCP is a core feature of the project. An external AI can connect to a running OpenBone instance, read the project, batch-edit bone keyframes, inspect the actual OpenGL preview, and then iterate or undo. Model inference is the client's responsibility; OpenBone does not need a model API key.

`openbone_load_project` also imports Spine 3.8/4.0/4.1/4.2/4.3 JSON and `.skel` / `.skel.bytes` exports through the shared project service, resolving a same-named `.atlas` or `.atlas.txt` and PNG pages. Import creates an unsaved document. Version checks, explicit dirty policy, failure atomicity and identical-request retries use the existing load contract. Binary limitations and graphical verification are documented in [Spine binary import](SPINE_BINARY_IMPORT.md). For example, call `openbone_get_context`, then use its current revision:

```json
{
  "name": "openbone_load_project",
  "arguments": {
    "path": "D:/java_workspace/openbone/res/xiaohei/Ninjahouse_xiaohei.skel.bytes",
    "expectedRevision": "<revision from openbone_get_context>",
    "requestId": "import-xiaohei-1",
    "dirtyPolicy": "reject"
  }
}
```

Use a fresh request ID for a different operation; repeat identical arguments and the same ID only to retry. Choose skin `1` or `3` using the desktop Resources panel to display the full Xiaohei character; remote skin selection is outside the current edit tool contract. Connection settings and ports are unchanged.

Unknown Spine export versions stop before model decoding with `isError: true` and `structuredContent.code: "SPINE_VERSION_RISK"`. The structured content includes `requiresConfirmation`, `sourceVersion`, `format`, `parserId`, `parserVersion` and `versionRiskApproval`. Present the text warning and proposed parser to the human user. Only after their explicit approval, resubmit `openbone_load_project` with a **new** `requestId`, a current `expectedRevision`, the intended `dirtyPolicy`, and the returned approval object unchanged:

```json
"versionRiskApproval": {
  "sourceHash": "<64-character SHA-256 from the risk response>",
  "parserId": "JSON_43"
}
```

Known stable families 3.8, 4.0, 4.1, 4.2 and 4.3 have explicit JSON/binary routes within their feature limits. Beta versions and unknown families require approval; historical 3.8.75 binary is excluded. 4.1.9 now uses its 4.1 adapter normally. Unknown 4.4.9 proposes the 4.3 adapter; 3.9.9 proposes 4.0. Approval is bound to source bytes and parser. Native `.spine` conversion requires a separately configured licensed CLI; without it, existing matching-JSON fallback is explicitly reported as a snapshot. See [version matrix and acceptance limits](SPINE_VERSION_COMPATIBILITY.md).

`openbone_export_spine` accepts an absolute `directory`, explicit `version`, `format` (`JSON`, `BINARY`, `NATIVE`), current `expectedRevision`, and `requestId`. It exports a snapshot, leaving the live model/history/revision unchanged. A concurrent editor change cannot alter the exported snapshot; `sourceRevision` identifies it. An identical request retry returns the original receipt without republishing. A reused ID with different arguments fails. Stale revisions and unrepresentable target versions fail before publication. Files are published through the existing immutable generation/current.json scheme. The result includes `path`, `directory`, `sourceRevision`, `version`, and `format`.

```json
{"name":"openbone_export_spine","arguments":{"directory":"D:/exports/xiaohei","version":"4.1.24","format":"BINARY","expectedRevision":"<current revision>","requestId":"export-xiaohei-41"}}
```

`openbone_get_context.data.spineIO` lists export markers, formats and `nativeCliConfigured`. That flag reports configuration, not license availability. Native conversion executes outside EDT, rejects Trial output, validates output and uses a 120-second process timeout; clients should allow longer request timeouts. `OPENBONE_SPINE_COMMAND` or `-Dopenbone.spine.command=...` selects the separate executable. Native acceptance is pending a licensed installation; the local Trial cannot verify it.

`openbone_query_scene` with `kind: "attachments"` includes `weighted`, `vertexCount`, `deformSize`, `weights`, `vertices`, `closed`, `constantSpeed` and `lengths` for paths. Each influence has `boneId.value` (the UUID), `point` in that bone's local coordinates and a normalized `weight`. A weighted path's `vertices` supplies control count only; its influence points determine geometry. Deform offsets are per-influence XY pairs, applied before skinning and path solving. JSON import, preview and portable save retain this data in project schema 11. Direct weighted control-point edits and runtime export are currently unavailable; bone/keyframe edits and undo keep their existing contracts. See [Stretchyman acceptance and limits](SPINE_VERSION_COMPATIBILITY.md#stretchyman-json-acceptance).

## Starting and connecting

1. Build and start the latest program: `mvn package`, then run `java -jar target/openbone-0.2.1.jar` (the file name depends on the current POM version).
2. Click **MCP** in the menu bar to open the non-modal control window. It contains port, optional token, automatic startup, Start/Stop buttons, live status and the endpoint URL.
3. Set the port (default `39871`). Leave the token empty for local connections without authentication. To enable authentication, enter 32–256 ASCII letters, digits, underscores or hyphens; clients then send `Authorization: Bearer <token>`. The token is masked unless **Show token** is selected.
4. **Save settings** remembers port, token and the **Start MCP automatically when OpenBone opens** checkbox. **Start** also saves before starting. The checkbox is off by default; simply specifying a startup port no longer enables automatic startup. Once saved, it controls future application launches.
5. While running, the window shows **Running**, the actual bound URL and whether authentication is enabled. Port and token stay read-only until **Stop**. Closing this control window keeps MCP running; closing the editor stops it. The control window can stay open during tool calls; modal file dialogs must be closed before tools can edit.

The stable default endpoint is `http://127.0.0.1:39871/mcp`. Port conflicts appear in the control window and never trigger a random fallback or terminate another instance. Multiple editor instances must use different ports.

Port precedence is `-Dopenbone.mcp.port` → `OPENBONE_MCP_PORT` → the saved port → `39871`. Token precedence is `OPENBONE_MCP_TOKEN` → the saved token → no authentication. An empty environment token explicitly disables authentication. Overridden fields are marked read-only in the window; change the startup configuration to change them. For example:

```powershell
java -Dopenbone.mcp.port=39872 -jar target/openbone-0.2.1.jar
```

`openbone.mcp.port=0` (or `OPENBONE_MCP_PORT=0`) explicitly opts into an ephemeral port for isolated tests; it cannot be saved as the user's port. Saved tokens are stable and never automatically rotated.

Settings live in `mcp.json` under the per-user Openbone state directory (normally `%LOCALAPPDATA%/Openbone`), outside installation and projects. On Windows, saved tokens use current-user DPAPI encryption through Windows PowerShell, with private process pipes rather than command-line credentials; no plaintext token is stored in metadata or logs. Saving a nonempty GUI token currently requires Windows; other platforms can still use `OPENBONE_MCP_TOKEN`. The UI performs credential and disk operations in background workers, and starts/stops the editor service on Swing EDT. Decryption failure is reported and never silently falls back to unauthenticated startup.

The service listens only on `127.0.0.1/mcp` and validates Host and Origin, plus the Bearer token only when explicitly configured. This version supports the MCP tool subsets for `2025-11-25`, `2025-06-18`, and `2025-03-26`; other versions negotiate to `2025-11-25` during initialization. Sessionless Streamable HTTP POST returns JSON, while GET and DELETE return 405; SSE, stdio, subscriptions, remote hosting, and model inference are not provided yet.

POST requests must carry `Content-Type: application/json` and `Accept: application/json, text/event-stream`, and after initialization must send the negotiated `MCP-Protocol-Version`. Tool calls against the same service are processed in order.

## Tools and data contracts

Successful results contain `content` text and `structuredContent.data`; previews additionally include an `image/png` image. Execution errors return `isError: true` with a readable reason, while protocol errors return a JSON-RPC error object or an HTTP error.

| Tool | Input | Output |
| --- | --- | --- |
| `openbone_get_context` | Empty object | Version, counts, selection, dirty state, undo information, current animation and time |
| `openbone_query_scene` | `kind`; optional `offset`, `limit`, `expectedRevision`; keyframes require `animationId`, support `boneId`, `trackType`, `targetId`, `channel` | Paginated records, revision, `total`, `hasMore`, `nextOffset` |
| `openbone_apply_edits` | `expectedRevision`, `requestId`, `edits`, optional `label` | Summary after modification, with the whole batch as one undo step |
| `openbone_capture_preview` | `expectedRevision`, `animationId`, `time` | Actual viewport PNG, revision, time, world coordinates of the first 200 bones |
| `openbone_undo` | `expectedRevision`, `requestId` | Summary after undoing one step |
| `openbone_redo` | `expectedRevision`, `requestId` | Summary after redoing one step |

`kind` supports `bones`, `animations`, `keyframes`, `slots`, `attachments`, and `constraints`. Pages default to 50 records and allow at most 200. Existing keyframe calls default to bone TRS/shear tracks and retain their record format. Set `trackType` to `all`, `non_bone`, `bone`, `deform`, `constraint`, `slot_color`, `attachment`, `draw_order`, or `event` to request typed records. Context advertises this additive API-v2 capability as `nonBoneKeyframeQuery`. Refresh `tools/list` (or reconnect clients that cache schemas) after upgrading. Attachment queries return summaries. Bone queries include the parent, setup transform, and inherit mode. All IDs are canonical UUID strings.

Typed keyframe queries filter by exact `targetId` and/or uppercase `channel` before pagination; omitted `trackType` still means `bone`. `boneId` can accompany `bone` or `all` and restricts results to that bone's own tracks, not attachments or constraints that reference it. A valid target with no matching tracks returns an empty page; malformed/unknown IDs, unknown channels, conflicting bone/target filters and filters on another `kind` fail without changing editor state. Use `expectedRevision` on every page; edits, undo, redo and project changes invalidate old pages. Repeating a read with identical arguments at the same revision returns the same records without a `requestId`.

Records follow type order (bone, deform, constraint, slot_color, attachment, draw_order, event), then model track order and chronological key order within each track. This is not a global time sort. Typed pages include `animationKeyCounts` for the entire animation, independent of filters. Animation summaries expose the same breakdown in `keyCounts` plus `totalKeyCount`, including all continuous and discrete keys. Legacy `keyCount` keeps its former meaning: bone + deform + constraint keys. Bone, constraint and deform keys can be written/removed when context advertises `nonBoneKeyframeEdits`; slot-color and discrete tracks are currently read-only through MCP.

| Track type | Target ID | Payload and semantics |
| --- | --- | --- |
| `bone` | Bone UUID | `boneId`, channel, `values`, outgoing per-component `curves`; local Setup offsets (degrees for rotation/shear), scale factors |
| `deform` | Attachment UUID | `attachmentId`, `slotId`, `attachmentType`, full `values`/`curves`; local XY offsets from Setup in project units. `valueBindings` maps each XY pair's `valueOffset` to `vertexIndex` and, when weighted, `influenceIndex`/`boneId`. Weighted offsets apply in each influence bone's local space before skinning; unweighted offsets use slot-bone local space. A zero pair preserves Setup; it does not move the vertex to the origin. |
| `constraint` | Constraint UUID | `constraintId`, absolute parameter `values`, stable `components`, outgoing `curves`, `discreteComponents`; IK order is mix, softness, bendPositive, compress, stretch. Mix is a factor, softness is in project units, and flags are 0/1 with stepped curves. Path position/spacing uses the returned `positionMode`/`spacingMode`; PERCENT uses fractions, FIXED/LENGTH uses project units, PROPORTIONAL is relative. Transform/path mix components are factors; physics parameters keep their model units. |
| `slot_color` | Slot UUID | `slotId`, RGBA/RGB/ALPHA, absolute normalized color `values`, component names and outgoing `curves` |
| `attachment` | Slot UUID | `slotId`, `name` (JSON null clears the attachment); stepped switching, no interpolated `values` or curves |
| `draw_order` | Animation UUID | `slots` contains the complete ordered slot UUID list; stepped switching |
| `event` | Event-definition UUID | `eventId`, `intValue`, `floatValue`, `stringValue`, `volume`, `balance`; instantaneous trigger, no invented interpolation curves |

Every typed row includes `trackType`, `targetId`, `targetName`, `channel`, `interpolation`, key `id` and `time` in seconds. Continuous curves belong to the outgoing segment and use the existing normalized linear/stepped/Bezier representation. Querying does not seek, pause, evaluate a pose, save, or affect dirty state/history.

For example, discover the live `sneak` UUID and revision, then call:

```json
{"name":"openbone_query_scene","arguments":{"kind":"keyframes","animationId":"<sneak UUID>","trackType":"non_bone","expectedRevision":"<current revision>","offset":0,"limit":200}}
```

Use `trackType: "deform"` with an attachment `targetId` to inspect a single path. Keep `expectedRevision` and use `nextOffset` until `hasMore` is false. The Stretchyman regression fixture returns 334 bone keys plus 33 non-bone keys; `all` returns 367.

Constraint queries include disabled constraints and constraints with zero mix throughout Setup and every animation. These remain part of the editable project without claiming solver output. Active writer overlaps remain unsupported; see [inactive constraint import and validation](PROJECT_DRIVEN_DEVELOPMENT.md#inactive-constraint-import-2026-10-26). The query contract and connection settings are unchanged.

Each batch accepts 1 to 500 sequential operations:

- `create_animation`: `animationId`, `name`. The client generates the new UUID, and later operations in the same batch can reference it.
- `put_keyframe`: `animationId`, `boneId`, `channel`, `time`, `values`, optional `curve`. Replaces and keeps the keyframe ID for the same track at exactly the same time. Omitting `curve` preserves the original curve, new keys default to linear, and `linear` and `stepped` are explicitly supported.
- `put_constraint_keyframe`: `animationId`, `constraintId`, `channel`, `time`, `values`, optional `curve`. Reuses desktop `AnimationEdits.putConstraint`. Values are absolute parameters in the typed query's `components` order (1–6 values depending on channel). For `IK`, use `[mix, softness, bendPositive, compress, stretch]`; flags must be 0/1 and their curves remain stepped even when a Bezier curve is supplied. All existing constraint channels are supported; type/parameter validation runs before publication.
- `put_deform_keyframe`: `animationId`, `attachmentId`, `time`, `values`, optional `curve`. Reuses desktop `DeformEdits.put` for existing mesh/path attachments. Send the complete array with exactly `deformSize` components, at most 65536, in the queried `valueBindings` order. Values are local XY offsets from Setup, per influence for weighted attachments; a single outgoing curve applies to every component. Shape correction can preserve endpoint pairs without changing Setup vertices or weights.
- `remove_keyframes`: `animationId`, `keyIds`, for stored bone, constraint and deform keys. Unknown or duplicate IDs fail the entire batch; unsupported discrete/color keys are not removed.

The two non-bone write operations use the existing `linear`/`stepped`/Bezier curve contract, preserve IDs when replacing the same target/channel/time, and preserve prior curves when `curve` is omitted. Mixed bone/constraint/deform batches validate an immutable candidate, publish in one history step, and share the same revision checks, exact-request retry receipts, undo/redo and portable save behavior. They do not create constraints, attachments, meshes, topology or weights. Example IK activation and zero deform (replace placeholders and provide the complete array):

```json
{"expectedRevision":"<revision>","requestId":"author-controls-01","label":"Author controls and path shape","edits":[
  {"op":"put_constraint_keyframe","animationId":"<new animation UUID>","constraintId":"<arm IK UUID>","channel":"IK","time":0,"values":[0,0,1,0,0]},
  {"op":"put_deform_keyframe","animationId":"<new animation UUID>","attachmentId":"<path UUID>","time":0,"values":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],"curve":"linear"}
]}
```

`AnimationMcpEditsTest` covers mixed atomic batches, failed-state/redo preservation, replacement semantics, retries, revision conflicts, heterogeneous removal and portable save/load. The Stretchyman desktop test creates a fresh clip through MCP, writes IK/deform keys, captures the real GL viewport and undoes the complete batch. The independent `sneak_rebuilt` production case uses Setup-based rules rather than copying reference animation tracks; its artifacts, request ledger and actual captures are under `creations/sneak-rebuilt-20261027/`.

Coordinates are local, with X to the right and Y up; time is in seconds; angles are in degrees with counterclockwise positive.

| channel | values | Meaning relative to setup |
| --- | --- | --- |
| `TRANSLATE` | `[x, y]` | Translation offset |
| `ROTATE` | `[angle]` | Rotation offset |
| `SCALE` | `[x, y]` | Scale factor; 1 keeps the original value |
| `SHEAR` | `[x, y]` | Shear angle offset |

Write and preview times are limited to 0 to 600 seconds, and keyframe values must not exceed an absolute value of 1 billion; the model's own reference, geometry, and numeric validation still applies. A project being editable does not mean it can necessarily be exported to every Spine target version.

## Call examples

After completing `initialize` and `notifications/initialized`, call `tools/list`. The following shows only the `params` of `tools/call`; placeholders must be replaced with values read in real time.

```json
{"name":"openbone_get_context","arguments":{}}
```

```json
{"name":"openbone_query_scene","arguments":{"kind":"bones","expectedRevision":"<revision>"}}
```

```json
{"name":"openbone_query_scene","arguments":{"kind":"animations","expectedRevision":"<revision>"}}
```

Add two rotation keyframes to an existing animation:

```json
{
  "name": "openbone_apply_edits",
  "arguments": {
    "expectedRevision": "<revision>",
    "requestId": "<unique request ID for this round>",
    "label": "AI adds an arm raise",
    "edits": [
      {"op":"put_keyframe","animationId":"<animation UUID>","boneId":"<bone UUID>","channel":"ROTATE","time":0,"values":[0]},
      {"op":"put_keyframe","animationId":"<animation UUID>","boneId":"<bone UUID>","channel":"ROTATE","time":1,"values":[45]}
    ]
  }
}
```

Use the new revision returned by the write to inspect the image at several times:

```json
{"name":"openbone_capture_preview","arguments":{"expectedRevision":"<new revision>","animationId":"<animation UUID>","time":0.5}}
```

The preview enters Animate mode, switches the animation, and pauses at the exact time without frame snapping. It uses the current camera, includes the mesh and bone overlay, and keeps the longest edge at no more than 1024 pixels. The window must be visible. Previews add no history and are not auto-saved; times beyond the animation length follow the last-frame sampling and physics-constraint time validation.

```json
{"name":"openbone_undo","arguments":{"expectedRevision":"<new revision>","requestId":"<unique ID for this undo>"}}
```

## Consistency and failure recovery

- The revision is an opaque string. Editing, undoing, redoing, preview dragging, resetting, and switching projects can all change it; undoing back to identical content does not restore the old revision.
- Validate against an immutable snapshot first, then check the revision again before publishing. On `REVISION_CONFLICT`, re-read and generate the request with a new `requestId`.
- Retry with the original tool name, original `requestId`, and exactly the same arguments. Receipts for the last 128 successful write requests are cached, including undo and redo; a receipt reflects the state when the original request completed, so read the current state separately afterwards. Reusing an ID under the same tool with different arguments is rejected.
- Receipts do not survive a service restart. After a timeout, disconnect, receipt eviction, or restart, read the project first to verify the state. A client cancelling its wait is not a rollback of an already-published edit.
- Invalid batches preserve the model, selection, dirty state, and redo history. Preparing an operation may commit valid pending fields in the UI; this counts as the user's own edit and invalidates the old revision.
- While a drag is in progress or a modal dialog is open, the call returns busy and never force-cancels user interaction.
- Undo shares history with manual operations; check `undoLabel` and the revision first. Each batch of AI edits corresponds to one undo step.
- API v2 adds explicit project save/load tools; see the [IO contract](MCP_V2.md). There is no export, arbitrary code execution, deletion or generic filesystem-write tool.

## Implementation basis and verification

MCP is a new OpenBone capability. Edit semantics reuse `AnimationEdits.put`, `AnimationEdits.curves`, `ExtendedEdits.createAnimation`, the immutable `DocumentSnapshot`, and `CommandHistory`; previews use actual `EditorViewport` rendering.

We evaluated the [official Java SDK](https://github.com/modelcontextprotocol/java-sdk) and the [official transport specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports). The first version's sessionless tool subset uses the JDK HTTP Server and the existing Jackson to keep the desktop dependency scope; no SDK source was copied. Re-evaluate the SDK when later adding subscriptions, remote authentication, or complex sessions. This version does not claim full MCP specification conformance certification.

```shell
mvn -Dtest=EditorMcpServerTest,HistoryTest test
mvn -Dtest=McpDesktopTest -Dopenbone.verifyGl=true test
mvn test
```

Protocol and settings tests cover stable ports, explicit overrides, encrypted credential persistence, authentication, origin, negotiation, tool discovery, notifications not performing writes, strict parameters, and request body limits. Business tests cover sampled values, stable IDs, failure atomicity, revision conflicts, duplicate requests, serialization round trips, and history recovery. The actual desktop test edits a temporary project over HTTP, captures GL images at three times, verifies decal pixels and intermediate poses, then undoes and checks that the source project was not rewritten. Images are written to `target/mcp-desktop/`. The default test run skips this graphical test.

The optional official Python SDK integration uses `scripts/mcp_sdk_smoke.py` and is started by the desktop test only against a temporary project. The SDK is a test dependency and does not enter the release JAR. This run used official SDK 1.30.0 to verify initialization, tool discovery, project queries, edit retries, PNG previews, and undo:

```shell
python -m pip install --target target/mcp-client-deps mcp==1.30.0
mvn -Dtest=McpDesktopTest -Dopenbone.verifyGl=true -Dopenbone.mcp.sdkPython=<absolute path to the Python executable> test
```

Use `-Dopenbone.mcp.sdkPath=<dependency directory>` to override the default SDK directory. Do not run the write integration script directly against a user's production project.

The graphical control-window regression is `mvn -Dtest=McpControlDesktopTest -Dopenbone.verifyGl=true test` on Windows. It checks real HTTP authentication, start/stop, stable restart, masked credentials, persisted automatic startup, disabling automatic startup, and editor shutdown; a credential-free UI capture is written to `target/mcp-desktop/control-window.png`.

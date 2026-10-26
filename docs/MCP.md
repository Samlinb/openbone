# OpenBone MCP

Version 0.1.3 exposes MCP API v2 with nine tools. See [MCP API v2](MCP_V2.md) for scene construction, Bezier curves, save/load, playback, camera and clean previews. The original six tools below remain supported, with the extensions described there.

MCP is a core feature of the project. An external AI can connect to a running OpenBone instance, read the project, batch-edit bone keyframes, inspect the actual OpenGL preview, and then iterate or undo. Model inference is the client's responsibility; OpenBone does not need a model API key.

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
| `openbone_query_scene` | `kind`; optional `offset`, `limit`, `expectedRevision`; keyframes require `animationId` and can filter by `boneId` | Paginated records, revision, `total`, `hasMore`, `nextOffset` |
| `openbone_apply_edits` | `expectedRevision`, `requestId`, `edits`, optional `label` | Summary after modification, with the whole batch as one undo step |
| `openbone_capture_preview` | `expectedRevision`, `animationId`, `time` | Actual viewport PNG, revision, time, world coordinates of the first 200 bones |
| `openbone_undo` | `expectedRevision`, `requestId` | Summary after undoing one step |
| `openbone_redo` | `expectedRevision`, `requestId` | Summary after redoing one step |

`kind` supports `bones`, `animations`, `keyframes`, `slots`, `attachments`, and `constraints`. Pages default to 50 records and allow at most 200. Keyframe queries currently return only bone TRS and shear tracks; they do not include deform, constraint, or discrete tracks. Attachment queries return summaries. Bone queries include the parent, setup transform, and inherit mode. All IDs are canonical UUID strings.

Each batch accepts 1 to 500 sequential operations:

- `create_animation`: `animationId`, `name`. The client generates the new UUID, and later operations in the same batch can reference it.
- `put_keyframe`: `animationId`, `boneId`, `channel`, `time`, `values`, optional `curve`. Replaces and keeps the keyframe ID for the same track at exactly the same time. Omitting `curve` preserves the original curve, new keys default to linear, and `linear` and `stepped` are explicitly supported.
- `remove_keyframes`: `animationId`, `keyIds`. Unknown or duplicate IDs fail the entire batch.

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

# OpenBone

MCP is a core project capability. A local AI client can query the live editor, edit bone keyframes atomically, capture actual OpenGL previews, and undo or redo changes. Use the **MCP** menu to start the service and view connection details. See the [MCP guide](docs/MCP.md) (Chinese).

[English](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md)

Openbone is a desktop 2D skeletal animation editor that follows the Spine workflow, built for creating and previewing game characters, props, and skill effects. It uses Java 17, Swing, and libGDX / LWJGL, and covers the workflow from skeleton construction, PNG attachment binding, mesh and weight editing, to keyframe animation and asset-pack export.

The project mirrors Spine's core concepts — bones, slots, skins, attachments, timelines, and constraints — and uses a **supported subset of the Spine 4.2.22 JSON export format** as its interchange format. The current version is `0.1.0-SNAPSHOT` and still under development; the compatibility scope is defined by the current implementation and is not a complete replacement for the full features or versions of Spine.

## Key Features

| Module | Current capabilities |
| --- | --- |
| Bone editing | Create bones, adjust hierarchy, translate / rotate / scale, local and world-space manipulation |
| Slots & attachments | PNG import, region attachments, slot binding, normal and additive blending |
| Meshes & weights | Region-to-mesh conversion, vertex and UV editing, mesh tessellation, bone binding and vertex weight editing |
| Animation timeline | Setup / Animate modes, create and duplicate animations, TRS keyframes, mesh deform, keyframe copy / paste / drag |
| Curves & playback | Linear, stepped and Bézier curves, play, loop, time scrubbing and snapping |
| Asset organization | Multiple skins, attachment-swap keyframes, draw-order keyframes, event definitions and event keyframes |
| Constraints | Single / two-bone IK, transform constraints, path constraints, physics constraints and supported parameter timelines |
| Clipping & paths | Clipping attachments, path attachments and Setup control-point editing |
| Project management | Undo / redo, project saving, portable projects with PNGs, auto-recovery, window layout memory |
| Localization | English, Simplified Chinese, Traditional Chinese |

## Relationship with Spine and format compatibility

Openbone uses its own editable project format and supports importing and exporting Spine JSON data within a limited scope. The project does not include any classes or source code from the official Spine Runtime.

### Supported interchange formats

- **Skeleton JSON**: version field `skeleton.spine`.
- **Textures**: PNG; individual images can be loaded via `skeleton.images` in the JSON and attachment paths.
- **Atlas**: reads a `.atlas` file with the same name and directory as the JSON, e.g. `character.json` pairs with `character.atlas`; rotation and whitespace-stripping information are supported with limitations.
- **Export asset pack**: contains `skeleton.json`, `skeleton.atlas` and PNG images, ready for runtimes that match the format.

### Current compatibility boundaries

| Item | Limitations |
| --- | --- |
| Version & file types | Selecting `.spine` loads a matching exported JSON snapshot, without parsing the native binary; supports bounded 3.8.x and 4.2.22 JSON, not `.skel` |
| Bones | Only `normal` inheritance mode; bone shear fields and shear animation tracks are not supported |
| Attachments | Supports Region, Mesh, Clipping, Path; linked mesh, bounding box, point and other attachment types are not supported |
| Colors & blending | Slots and texture attachments accept white only; blend modes limited to `normal` / `additive`; color animation is not supported |
| Separate X / Y tracks | Keyframe times of grouped separate X / Y tracks must match |
| Deform | Mesh deform supported; deform for path and clipping attachments is not implemented; weighted clipping is not supported |
| Constraints | Subject to solve-order, dependency and numeric-range limits; constraints dependent on skin enabling and global physics timelines are not supported |
| Atlas | Requires PNG, `RGBA8888`, non-premultiplied alpha, `Nearest` filtering and no duplicates; rotation limited to 0° / 90°; region `index` must be `-1`; custom fields are not supported |
| Export | The skeleton must have exactly one root bone; project data must also pass runtime float32 range checks |

When integrating into a game, use a runtime that matches the target format and verify actual playback in the target engine. Openbone's project JSON, the asset-pack entry `current.json`, and the Spine skeleton JSON serve different purposes.

## Build and Run

### Requirements

- JDK 17 or later.
- Maven with `mvn` on the `PATH`; the repository does not currently ship a Maven Wrapper.
- A graphics environment capable of running a Swing desktop window and an LWJGL OpenGL viewport.

The first build downloads Maven dependencies. The project bundles desktop native libraries; availability for a specific OS and driver must be verified on the target environment.

### Run from source

From the project root:

```shell
mvn clean package
java -jar target/openbone-0.1.0-SNAPSHOT.jar
```

The build produces an executable JAR with dependencies included. Do not use `original-openbone-0.1.0-SNAPSHOT.jar` as a standalone distribution.

To launch with a Simplified Chinese UI:

```shell
java -Dopenbone.lang=zh-CN -jar target/openbone-0.1.0-SNAPSHOT.jar
```

Supported language values are `en`, `zh-CN`, `zh-TW`. You can also switch via the language menu in the UI, which takes effect after restart; English is the default on first launch.

### Command-line options

```shell
# Show version
java -jar target/openbone-0.1.0-SNAPSHOT.jar --version

# Show environment info such as Java, OS, screen and user-state directory
java -jar target/openbone-0.1.0-SNAPSHOT.jar --environment

# Launch and open an Openbone project
java -jar target/openbone-0.1.0-SNAPSHOT.jar --open "path/to/character.json"

# Validate an Openbone project; prints schema version, object counts and normalized SHA-256
java -jar target/openbone-0.1.0-SNAPSHOT.jar --validate-project "path/to/character.json"
```

`--open` and `--validate-project` accept Openbone project files. To import JSON exported from Spine, use "Import 4.2.22 JSON" in the editor. When project validation fails, the process exits with code `2`.

## Quick Start

Menu and button names below follow the Simplified Chinese UI.

### 1. Build a skeleton and import images

1. Launch the editor and create an empty project via "File → New", staying in "Setup" mode.
2. Press `N` to switch to the bone-creation tool and drag in the viewport to create the root bone. With an existing bone selected, continuing creates child bones.
3. Select a bone and use `W` / `E` / `R` to adjust position, rotation and scale, or enter values in the property panel.
4. Select the target bone, then import images via "File → Import PNG…" or `Ctrl+I`. The editor uses or creates a slot based on the current selection and adds an image attachment.
5. Adjust the attachment's position and size; for soft deformation, use "Convert to Editable Mesh" in the attachment properties, then edit vertices, bind bones and set weights.

Organizing the whole character under a single root bone is recommended to satisfy runtime export requirements.

### 2. Create keyframe animations

1. Create a new animation in the animation panel and switch to "Animate" mode.
2. Select a bone, move the time to `0` seconds, and use "Record TRS" to capture the initial pose.
3. Move the time to another position, adjust the bone pose, and record keyframes again.
4. Select keyframes on the timeline and use "Curve…" to set linear, stepped or Bézier interpolation; hold `Ctrl` to multi-select keyframes.
5. Click "Play" and enable "Loop" as needed. Manage related assets and discrete keyframes via "Skins / Attachments / Events / Order…".

Setup edits the base structure and initial pose; Animate is for animation. After adjusting bones, explicitly record the keyframes you need.

### 3. Save an editable project

- **Save / Save As**: saves the Openbone project JSON. A regular project references external images, so image paths must be maintained when moving the project.
- **Save Portable Project (with PNGs)**: saves the project and copies the required PNGs into an adjacent `<project filename>.assets/` directory. When sharing or migrating, package the project JSON together with that directory.
- **Recover unsaved edits**: restore an existing snapshot via the File menu, then Save As. Auto-recovery records committed edits every 15 seconds; uncommitted text-field input and drag previews are not included in snapshots.

Auto-recovery and layout data live in `%LOCALAPPDATA%/Openbone` on Windows by default; when the `LOCALAPPDATA` environment variable is absent, `~/.openbone` is used. Use the JVM flag `-Dopenbone.userState=path` to specify another directory.

### 4. Import existing Spine JSON

File → Open also accepts `.spine`, locating `<name>.json` beside it or `export/<name>.json`. This loads the exported snapshot, which may be older than the native project, and reports filtered data; the native binary/editor data is not parsed or preserved losslessly. Missing snapshots require exporting JSON with Spine first. The result is an unsaved Openbone project. `--open res/spineboy/spineboy-pro.spine` uses its existing 3.8.55 JSON and the project's images directory, with automatic framing and normalization of rounded skinning weights. Optional local check: `mvn -Dtest=SpineProjectSourceTest -Dopenbone.spineboy=true test` writes a portable example, report and source license to `target/spineboy-openbone/`.

1. Prepare exported JSON at version `3.8.x` or `4.2.22` that fits the compatibility scope above.
2. Place the same-named `.atlas` and its referenced PNGs in position; when using individual images, keep `skeleton.images` and attachment paths resolvable. Relative resource paths must stay inside the imported resource directory.
3. Choose "File → Import Spine JSON…" and select the skeleton JSON. You may also select `current.json` from an Openbone export asset pack; the editor will parse and validate the pack.
4. A successful import produces a new unsaved project — save it as an Openbone project for further editing.

If an import is rejected, check the version, metadata, attachment types, atlas configuration and constraint limits against the field paths in the error message. Changing only the version number does not convert data from other versions.

`3.8.x` imports translate angles, relative Bezier curves, constraint mixes and deform structures. Invalid entries and unsupported fields are filtered with a path report; valid sibling attachments and timelines are retained. Missing images retain their resource links and dimensions; Linear atlas filtering is adapted to Nearest. Shear and special inheritance modes remain unsupported and are reported and filtered. `4.2.22` imports remain strict. Exporting to `3.8.75` rejects physics, proportional path spacing and incompatible independent axis curves/mixes. Packages require valid PNGs. Later official 3.8 runtimes reject the `3.8.75` version marker; use matching older tools, or export `4.2.22` for its matching runtime.

### 5. Export game assets

The workspace's Images tab and the resource dialog's Images tab show thumbnails, dimensions and shared attachment counts. After extraction, the workspace opens this tab automatically. Double-click or choose Preview original to inspect one image; Locate attachment selects its scene attachment. Replace PNG relinks all attachments sharing the image, preserving IDs, names, sizes, transforms, mesh UVs/weights, deform and all animation tracks. It supports undo/redo and portable saving without overwriting source images. Replacing a packed region detaches only that logical image from its atlas.

After importing JSON, the editor offers to split a same-named atlas whose valid PNG pages are beside the JSON. Accepting writes logical images into a new neighboring `<name>_images-*` folder, restores rotated/trimmed regions and transparent padding, deduplicates shared regions and updates project links. The operation supports undo and preserves all source files. Declining keeps the atlas without writing files; missing or invalid pages do not trigger the offer.

Slot color playback/import/export supports 3.8 `color` and 4.2 `rgba`, `rgb`, `alpha`, setup tint, linear/stepped/Bezier interpolation and independent RGB/alpha channels. Colors multiply texture samples; alpha also affects picking. Projects containing colors use schema 7; earlier projects remain readable. Exporting independent RGB/alpha to 3.8 requires matching key times and a shared curve. All 14 color tracks in `dandao_1` roundtrip. Optional native rendering check: `mvn -Dtest=SlotColorRenderTest -Dopenbone.verifyGl=true test` generates a contact sheet and portable project in `target/` through the production renderer in a windowless Pbuffer.

Choose "File → Export Spine Package…", pick an output directory, then select `4.2.22` or `3.8.75`. Example export result:

```text
export/
├── current.json
└── generation-<uuid>/
    ├── skeleton.json
    ├── skeleton.atlas
    ├── manifest.json
    └── images/
        ├── region_0.png
        └── ...
```

`current.json` points to the latest export batch, and `manifest.json` records asset verification info. When delivering to a game engine, use the `skeleton.json`, `skeleton.atlas` and `images/` under the corresponding `generation-<uuid>/`, preserving their relative directory structure; do not load `current.json` as the runtime skeleton file.

The exporter keeps older batches and publishes new ones by updating the entry file. It currently copies PNGs or original atlas pages per texture attachment and does not automatically repack loose images into a single tight atlas.

## Common Operations

| Operation | Shortcut / mouse |
| --- | --- |
| New / Open / Save | `Ctrl+N` / `Ctrl+O` / `Ctrl+S` |
| Save As / Save portable project | `Ctrl+Shift+S` / `Ctrl+Alt+S` |
| Import PNG | `Ctrl+I` |
| Import JSON / Export asset pack | `Ctrl+Alt+O` / `Ctrl+Alt+E` |
| Undo / Redo | `Ctrl+Z` / `Ctrl+Y` |
| Select / Create bone | `Q` / `N` |
| Move / Rotate / Scale | `W` / `E` / `R` |
| Delete selection | `Delete` |
| Cancel current drag | `Esc` |
| Pan viewport / Zoom viewport | Right- or middle-drag / mouse wheel |

## Project Structure

```text
src/main/java/io/github/samlinb/openbone/
├── EditorApplication.java  # Desktop and command-line entry point
├── application/           # Edit commands, history, project state and playback control
├── core/                  # Data model, animation sampling, math and constraint solvers
├── desktop/               # Swing editor, property panels and timeline
├── viewport/              # OpenGL viewport, texture rendering and picking
├── io/                    # Project I/O, Spine JSON subset, atlas and asset packs
└── i18n/                  # UI language loading
src/main/resources/        # Translations, third-party notices and license texts
packaging/installer/       # Windows installer source
```

During development, run `mvn verify` to execute the tests and integration verification configured in Maven; the tests actually executed depend on the test sources checked out locally.

## FAQ

**`mvn` or `java` not found?**

Install Maven and JDK 17+, configure `PATH`, reopen the terminal, and check with `mvn -version` and `java -version`.

**Images missing or attachments invisible after migrating a project?**

Check the source PNG paths; select the attachment, relocate the image, then use "Reload Image". For cross-machine sharing, prefer portable projects and carry the adjacent `.assets` directory.

**The version is correct, yet import reports unsupported fields?**

The current implementation strictly accepts the supported subset, including checks on extra metadata. Follow the error messages to confirm the data is within the compatibility scope; a matching version does not mean every feature in the file is supported.

**Saving succeeds but export fails?**

Project saving and runtime export use different validation scopes. Check that there is exactly one root bone, that PNGs are readable, and that constraints, animations and numeric values meet the export limits.

## Third-Party Notices

See [pom.xml](pom.xml) for dependency declarations, and [THIRD_PARTY.txt](src/main/resources/THIRD_PARTY.txt) and [licenses](src/main/resources/licenses/) for third-party licenses and notices. The Spine name is used to describe the workflow reference and the data-format compatibility target.

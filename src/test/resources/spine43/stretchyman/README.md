# Stretchyman import regression

Unchanged official export and textures from Esoteric Software's Spine examples:
https://github.com/EsotericSoftware/spine-runtimes/tree/ee2a10936a103ac187d74f7709f855653e923eae/examples/stretchyman

Export: `export/stretchyman-pro.json`, version `4.3.75-beta`.
JSON SHA-256: `ff2670687913945c09d400068adcd35b3392bde6917709d845fbc9045675fdb4`.
The upstream `stretchyman-pro.spine` SHA-256 is
`8120dfbf5698c8b6c13164c3d0bd28da8e8582d7b7e2ba69fc90355ecba0c427`,
identical to the user's Trial example. That identifies the example; importing
this JSON still reads an export snapshot, not the native editor object graph.

See `license.txt`: retain it with redistributed images. Images are for
noncommercial use. The original project is public domain. No Spine runtime
implementation is included in OpenBone.

Coverage: unclamped transform mappings, float-encoded integer vertex components,
reversed triangle winding, four weighted paths, two path deform timelines,
equal-endpoint Bezier overshoot, portable project schema 11, actual OpenGL
capture over HTTP MCP, keyframe edit and undo.

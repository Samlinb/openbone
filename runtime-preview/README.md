# OpenBone runtime preview

Independent Maven application consuming exported skeleton/atlas/PNG packages. Shares OpenBone's runtime code through the `runtime-core` classifier; does not connect to an editor or include an official Spine Runtime.

Build from the parent directory:

```powershell
mvn -DskipTests install
mvn -f runtime-preview/pom.xml package
```

Run:

```powershell
java -jar runtime-preview/target/openbone-runtime-preview.jar --package D:/exports/character/current.json --animation walk
```

For headless MCP integration, request/receipt protocol, limits and validation, see [the workflow guide](../docs/HEADLESS_RUNTIME.md).

"""Opt-in interoperability check against a disposable editor opened by McpDesktopTest.

Requires the official Python MCP SDK. Connection secrets are passed via environment,
never arguments or test output. This script creates an undoable edit then undoes it.
"""
import asyncio
import os
import site
import uuid

if os.environ.get("OPENBONE_MCP_SDK_PATH"):
    site.addsitedir(os.environ["OPENBONE_MCP_SDK_PATH"])

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
import httpx


async def main():
    if os.environ.get("OPENBONE_MCP_TEST_ALLOW_EDITS") != "disposable-fixture":
        raise RuntimeError("Run only through McpDesktopTest with its disposable fixture")
    headers = {"Authorization": "Bearer " + os.environ["OPENBONE_MCP_TEST_TOKEN"]}
    async with (
        httpx.AsyncClient(headers=headers, timeout=20, trust_env=False) as client,
        streamable_http_client(os.environ["OPENBONE_MCP_TEST_URL"], http_client=client) as (read, write, _),
    ):
        async with ClientSession(read, write) as session:
            await session.initialize()
            tools = (await session.list_tools()).tools
            assert len(tools) == 6

            async def call(name, args):
                result = await session.call_tool(name, args)
                assert not result.isError, result.content
                assert result.structuredContent is not None
                return result.structuredContent["data"], result

            context, _ = await call("openbone_get_context", {})
            bones, _ = await call("openbone_query_scene", {"kind": "bones", "limit": 1, "expectedRevision": context["revision"]})
            clip = context["view"]["animationId"]
            before, _ = await call("openbone_query_scene", {"kind": "keyframes", "animationId": clip})
            args = {
                "expectedRevision": context["revision"], "requestId": str(uuid.uuid4()),
                "edits": [{"op": "put_keyframe", "animationId": clip, "boneId": bones["items"][0]["id"],
                           "channel": "ROTATE", "time": 0.375, "values": [15]}],
            }
            edited, _ = await call("openbone_apply_edits", args)
            repeated, _ = await call("openbone_apply_edits", args)
            assert edited == repeated
            preview, image_result = await call("openbone_capture_preview", {
                "expectedRevision": edited["revision"], "animationId": clip, "time": 0.375,
            })
            assert preview["time"] == 0.375 and preview["width"] > 0
            assert any(block.type == "image" and block.mimeType == "image/png" for block in image_result.content)
            restored, _ = await call("openbone_undo", {
                "expectedRevision": edited["revision"], "requestId": str(uuid.uuid4()),
            })
            assert restored["undoCount"] == context["undoCount"]
            after, _ = await call("openbone_query_scene", {"kind": "keyframes", "animationId": clip})
            assert after["items"] == before["items"]
    print("Official Python MCP SDK smoke passed: discovery, live reads, atomic edit, retry, GL image, undo")


if __name__ == "__main__":
    asyncio.run(main())

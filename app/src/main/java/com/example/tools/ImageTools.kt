// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import com.example.llm.LlmContentPart
import com.example.util.ImageLoader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

object ImageDelivery {
    fun deliver(file: File, ctx: ToolContext, crop: List<Int>?): ToolResult {
        if (!ctx.supportsVision) {
            return ToolResult.error(
                "The selected model cannot see images. Tell the user to switch to a model marked 'Vision' in the model picker. " +
                    "Only if they want the TEXT inside the image: apk add tesseract-ocr tesseract-ocr-data-eng, then tesseract <file> stdout."
            )
        }
        val loaded = runCatching { ImageLoader.load(file, crop) }.getOrElse {
            return ToolResult.error("Could not prepare image: ${it.message}")
        }
        val cropNote = if (crop != null) " (cropped region ${crop.joinToString(",")})" else ""
        return ToolResult(
            output = "Image '${file.name}'$cropNote is attached in the next message (${loaded.width}x${loaded.height} sent; original " +
                "${loaded.originalWidth}x${loaded.originalHeight}). Describe only what you actually see. To zoom into a region call view_image " +
                "again with crop=[x0,y0,x1,y1] in ORIGINAL pixel coordinates.",
            isError = false,
            summary = "${loaded.width}x${loaded.height}",
            images = listOf(LlmContentPart.ImageBase64(loaded.mime, loaded.base64, file.name))
        )
    }
}

class ViewImageTool : Tool {
    override val name = "view_image"
    override val description = "Look at an image file (png, jpg, gif, webp). The image is attached so you can actually see it. " +
        "Use crop=[x0,y0,x1,y1] (original pixel coordinates) to zoom into a region. Prefer this over OCR."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "Image path relative to the project or absolute") }
            putJsonObject("crop") {
                put("type", "array")
                putJsonObject("items") { put("type", "integer") }
                put("description", "Optional [x0,y0,x1,y1] region in original pixels")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("path")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = (args["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ToolResult.error("Missing 'path'")
        val file = ctx.env.resolveProjectPath(rawPath, ctx.projectDir)
        if (!file.isFile) return ToolResult.error("File not found: $rawPath")
        if (ImageLoader.sniffMime(file) == null) {
            return ToolResult.error("'$rawPath' is not a png/jpeg/gif/webp image (checked by file header, not extension).")
        }
        val crop = (args["crop"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.takeIf { it.size == 4 }
        return ImageDelivery.deliver(file, ctx, crop)
    }
}

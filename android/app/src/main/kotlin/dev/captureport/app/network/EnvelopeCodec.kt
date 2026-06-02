package dev.captureport.app.network

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object EnvelopeCodec {
    private const val MAGIC = "PHRBIDG1"

    // Model representing the JSON control envelope
    data class Envelope(
        val v: Int = 1,
        val t: String, // "req" | "resp" | "notify"
        val id: String, // ULID correlation
        val method: String? = null,
        val params: JSONObject? = null,
        val result: JSONObject? = null,
        val error: JSONObject? = null,
        val ts: Long = System.currentTimeMillis(),
        val idem: String? = null
    )

    // Decodes JSON string into Envelope structure
    fun decodeEnvelope(jsonStr: String): Envelope {
        val obj = JSONObject(jsonStr)
        return Envelope(
            v = obj.optInt("v", 1),
            t = obj.getString("t"),
            id = obj.getString("id"),
            method = obj.optString("method", null),
            params = obj.optJSONObject("params"),
            result = obj.optJSONObject("result"),
            error = obj.optJSONObject("error"),
            ts = obj.optLong("ts", System.currentTimeMillis()),
            idem = obj.optString("idem", null)
        )
    }

    // Encodes Envelope into plain JSON text
    fun encodeEnvelope(env: Envelope): String {
        val obj = JSONObject()
        obj.put("v", env.v)
        obj.put("t", env.t)
        obj.put("id", env.id)
        if (env.method != null) obj.put("method", env.method)
        if (env.params != null) obj.put("params", env.params)
        if (env.result != null) obj.put("result", env.result)
        if (env.error != null) obj.put("error", env.error)
        obj.put("ts", env.ts)
        if (env.idem != null) obj.put("idem", env.idem)
        return obj.toString()
    }

    // Encodes raw media payload into a little-endian 32-byte headed binary frame
    fun encodeBinaryFrame(
        streamId: Int,
        frameSeq: Int,
        flags: Int,
        totalSize: Long,
        metaJson: String,
        payload: ByteArray
    ): ByteArray {
        val magicBytes = MAGIC.toByteArray(StandardCharsets.US_ASCII)
        val metaBytes = metaJson.toByteArray(StandardCharsets.UTF_8)
        
        val headerSize = 32
        val totalBufferLength = headerSize + metaBytes.size + payload.size
        
        val buffer = ByteBuffer.allocate(totalBufferLength)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // Header assembly (32 bytes)
        buffer.put(magicBytes.take(8).toByteArray())
        buffer.putInt(streamId)
        buffer.putInt(frameSeq)
        buffer.putInt(flags)
        buffer.putLong(totalSize)
        buffer.putInt(metaBytes.size)
        
        // Metadata & Payload assembly
        buffer.put(metaBytes)
        buffer.put(payload)
        
        return buffer.array()
    }
}

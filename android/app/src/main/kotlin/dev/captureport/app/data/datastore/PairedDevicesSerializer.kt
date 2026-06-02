package dev.captureport.app.data.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import dev.captureport.app.data.PairedDevices
import java.io.InputStream
import java.io.OutputStream

object PairedDevicesSerializer : Serializer<PairedDevices> {
    override val defaultValue: PairedDevices = PairedDevices.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): PairedDevices {
        try {
            return PairedDevices.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: PairedDevices, output: OutputStream) {
        t.writeTo(output)
    }
}

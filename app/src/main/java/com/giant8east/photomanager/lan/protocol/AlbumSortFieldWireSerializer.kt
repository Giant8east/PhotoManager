/** 排序字段在 HTTP 上的取值：JSON 里是小写单词（`"custom"`），而不是枚举名 `CUSTOM`。 */
package com.giant8east.photomanager.lan.protocol

import com.giant8east.photomanager.model.AlbumSortField
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 为什么不给枚举加 `@SerialName`：同一个 [AlbumSortField] 还被 DataStore 用作 JSON 值
 * （`album_list_settings.json` 里存的是枚举名 `"MODIFIED_AT"`），加注解会让旧文件解码失败，
 * 触发 `ReplaceFileCorruptionHandler` 把用户的排序设置静默重置。协议层单独用这个序列化器，
 * 磁盘格式与线上格式各自稳定。
 */
object AlbumSortFieldWireSerializer : KSerializer<AlbumSortField> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AlbumSortFieldWire", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AlbumSortField) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): AlbumSortField {
        val raw = decoder.decodeString()
        return AlbumSortField.fromWire(raw)
            ?: throw SerializationException("unknown sort field: $raw")
    }
}

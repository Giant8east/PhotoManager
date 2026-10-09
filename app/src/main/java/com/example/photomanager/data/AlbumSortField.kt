/** 限定相册排序字段，避免将外部输入作为 SQL 列名执行。 */
package com.example.photomanager.data

enum class AlbumSortField(val title: String, internal val sqlExpression: String) {
    CUSTOM("自定义", "sortOrder"),
    ID("ID", "id"),
    NAME("名称", "name COLLATE NOCASE"),
    MODIFIED_AT("修改时间", "julianday(modifiedAt)"),
    PHOTO_COUNT("图片数量", "photoCount")
}
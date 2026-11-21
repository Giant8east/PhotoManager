/** 相册查询的 SQL 构建器：把查询条件翻译成排序与筛选语句，纯函数，不依赖数据库。 */
package com.giant8east.photomanager.data.album

import androidx.sqlite.db.SimpleSQLiteQuery
import com.giant8east.photomanager.model.AlbumQuery
import com.giant8east.photomanager.model.AlbumSortField

/**
 * 列名全部来自 [AlbumSortField] 枚举，用户输入只作为绑定参数进入 LIKE，
 * 因此不存在把外部字符串拼进 SQL 的路径。
 */
object AlbumQueryBuilder {
    fun build(query: AlbumQuery): SimpleSQLiteQuery {
        val direction = if (query.isDescending) "DESC" else "ASC"
        // 未知修改时间始终放在最后，同值相册按路径保持稳定顺序。
        val nullOrder = if (query.sortField == AlbumSortField.MODIFIED_AT) {
            "julianday(modifiedAt) IS NULL ASC, "
        } else ""
        // 统一在查询构建边界归一化关键词，保证本地、远端与 HTTP 接口口径完全一致；
        // 有关键词时做全局搜索：忽略分组与收藏筛选，避免"明明有这个相册却搜不到"。
        val keyword = query.keyword.trim()
        val hasKeyword = keyword.isNotEmpty()
        val whereClause: String
        val whereArgs: Array<Any?>
        when {
            hasKeyword -> {
                whereClause = "name LIKE ? ESCAPE '\\' COLLATE NOCASE"
                whereArgs = arrayOf(searchPattern(keyword))
            }
            // 收藏视图跨分组，不再叠加分组条件。
            query.favoritesOnly -> {
                whereClause = "isFavorite = 1"
                whereArgs = emptyArray()
            }
            query.groupId == null -> {
                whereClause = "groupId IS NULL"
                whereArgs = emptyArray()
            }
            else -> {
                whereClause = "groupId = ?"
                whereArgs = arrayOf(query.groupId)
            }
        }
        // 自定义排序是视图级的：仅收藏视图使用自己的列，全局搜索结果仍按分组序号排序。
        val orderExpression = if (
            query.sortField == AlbumSortField.CUSTOM && query.favoritesOnly && !hasKeyword
        ) {
            "favoriteSortOrder"
        } else {
            query.sortField.orderExpression()
        }
        val sql = """
            SELECT * FROM albums
            WHERE $whereClause
            ORDER BY $nullOrder$orderExpression $direction, albumPath ASC
        """.trimIndent()
        return SimpleSQLiteQuery(sql, whereArgs)
    }

    /**
     * ORDER BY 的字段片段。SQL 列名属于数据层，所以不在共用的 [AlbumSortField] 里，
     * 这里用穷尽 `when` 保证新增排序字段时编译器立刻报错。
     */
    private fun AlbumSortField.orderExpression(): String = when (this) {
        // 自定义顺序就是相册的手动排序序号。
        AlbumSortField.CUSTOM -> "sortOrder"
        AlbumSortField.NAME -> "name COLLATE NOCASE"
        AlbumSortField.MODIFIED_AT -> "julianday(modifiedAt)"
        AlbumSortField.PHOTO_COUNT -> "photoCount"
        AlbumSortField.FILE_SIZE -> "totalSizeBytes"
    }

    /** 转义 SQLite LIKE 模式中的特殊通配符。 */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** 将关键词转换为 LIKE 的包含匹配模式。 */
    private fun searchPattern(keyword: String): String = "%${escapeLike(keyword)}%"
}

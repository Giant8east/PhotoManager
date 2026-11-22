/** 本地文件路径通用工具：路径归一化、子目录前缀与父子包含判断，不依赖任何具体业务模块。 */
package com.giant8east.photomanager.util

import java.io.File
import java.nio.file.Paths

object LocalPaths {
    /** 归一化本地路径，消除冗余分隔符、`.`、`..` 与末尾斜杠。 */
    fun normalize(rawPath: String): String =
        // canonicalPath 声明抛 IOException（如路径含坏符号链接或不可达挂载点），失败时回退纯词法归一化
        runCatching { File(rawPath).canonicalPath }
            .getOrElse { Paths.get(rawPath).toAbsolutePath().normalize().toString() }

    /** 生成指定规范路径的子目录精确前缀（区分大小写）。 */
    fun childPrefix(normalizedPath: String): String =
        if (normalizedPath == "/") "/" else "$normalizedPath/"

    /**
     * 判断 [normalizedChild] 是否是 [normalizedParent] 自己或它的子目录。
     * 纯字符串比较、无磁盘 IO，调用方需保证入参已经过 [normalize]。
     */
    fun isSameOrChild(normalizedParent: String, normalizedChild: String): Boolean =
        normalizedChild == normalizedParent || normalizedChild.startsWith(childPrefix(normalizedParent))

    /** 判断两个原始路径是否为同一目录或父子目录关系（会先分别归一化）。 */
    fun isSameOrSubPath(parent: String, child: String): Boolean =
        isSameOrChild(normalize(parent), normalize(child))
}

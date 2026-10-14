/** 阅读选项的稳定枚举值与中文标题，序列化只保存英文枚举值。 */
package com.example.photomanager.data

import kotlinx.serialization.Serializable

interface ViewerSettingOption {
    val title: String
}

@Serializable
enum class ScreenOrientation(override val title: String) : ViewerSettingOption {
    SYSTEM("系统默认"), PORTRAIT("竖屏"), LANDSCAPE("横屏"), AUTO("自动旋转")
}

@Serializable
enum class ReadingDirection(override val title: String) : ViewerSettingOption {
    LEFT_TO_RIGHT("从左至右"), RIGHT_TO_LEFT("从右至左"), TOP_TO_BOTTOM("从上至下")
}

@Serializable
enum class PageScale(override val title: String) : ViewerSettingOption {
    ORIGINAL("原始尺寸"), FIT_WIDTH("匹配宽度"), FIT_HEIGHT("匹配长度"), FIT_SCREEN("匹配屏幕")
}

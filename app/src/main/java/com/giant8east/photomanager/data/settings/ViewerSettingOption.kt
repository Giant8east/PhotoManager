/** 阅读选项的稳定枚举值与本地化标题资源，序列化只保存英文枚举值。 */
package com.giant8east.photomanager.data.settings

import com.giant8east.photomanager.R
import androidx.annotation.StringRes

import kotlinx.serialization.Serializable

interface ViewerSettingOption {
    @get:StringRes val titleRes: Int
}

@Serializable
enum class ScreenOrientation(
    @get:StringRes
    override val titleRes: Int) : ViewerSettingOption {
    SYSTEM(R.string.orientation_system), PORTRAIT(R.string.orientation_portrait), LANDSCAPE(R.string.orientation_landscape), AUTO(R.string.orientation_auto)
}

@Serializable
enum class ReadingDirection(
    @get:StringRes
    override val titleRes: Int) : ViewerSettingOption {
    LEFT_TO_RIGHT(R.string.reading_ltr), RIGHT_TO_LEFT(R.string.reading_rtl), TOP_TO_BOTTOM(R.string.reading_vertical)
}

@Serializable
enum class PageScale(
    @get:StringRes
    override val titleRes: Int) : ViewerSettingOption {
    ORIGINAL(R.string.scale_original), FIT_WIDTH(R.string.scale_width), FIT_HEIGHT(R.string.scale_height), FIT_SCREEN(R.string.scale_screen)
}

/**
 * 局域网相关的失败文案：把异常按类型翻译成资源串，而不是把库里的英文原文端给用户。
 *
 * 为什么不用通用的 `Throwable.toUiText()`：它优先取 `message`，而这里能拿到的 message
 * 全是库拼出来的英文（`Failed to connect to /192.168.1.5:8080`、`HTTP 400 Bad Request`），
 * 直接显示既看不懂也没法翻译——文案由分享方生成、访问方显示，按分享方的语言翻译方向本身就错。
 */
package com.giant8east.photomanager.pages.lan

import com.giant8east.photomanager.R
import com.giant8east.photomanager.lan.client.LanPairFailure
import com.giant8east.photomanager.ui.state.UiText
import java.io.IOException
import retrofit2.HttpException

/**
 * 访客侧的失败 → 用户能看懂的一句话。
 *
 * 分支顺序有意义：`LanPairFailure` 的三种原因最具体，先判断；`IOException` 覆盖
 * 连接被拒、超时、DNS 失败等传输层问题；`HttpException` 表示对端回了错误状态码。
 */
fun Throwable.toLanUiText(): UiText = when (this) {
    is LanPairFailure.Rejected -> UiText.Resource(R.string.lan_pair_rejected)
    is LanPairFailure.Expired -> UiText.Resource(R.string.lan_pair_expired)
    is LanPairFailure.Unreachable -> UiText.Resource(R.string.lan_unreachable)
    is IOException -> UiText.Resource(R.string.lan_unreachable)
    is HttpException -> UiText.Resource(R.string.lan_server_error)
    else -> UiText.Resource(R.string.lan_unreachable)
}

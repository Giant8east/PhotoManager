package com.giant8east.photomanager

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.giant8east.photomanager.lan.client.LanHttp
import com.giant8east.photomanager.lan.client.MdnsBrowser
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@HiltAndroidApp
class PhotoManagerApplication : Application(), ImageLoaderFactory {
    /** 与 Retrofit 共用同一个 OkHttpClient：连接池复用，token 拦截器也自动对图片请求生效。 */
    @Inject
    lateinit var lanHttp: LanHttp

    /** 局域网设备发现：进程级单例，由上面的前台观察者负责 start/stop。 */
    @Inject
    lateinit var mdnsBrowser: MdnsBrowser

    override fun onCreate() {
        super.onCreate()
        // 设备发现跟着"整个应用在不在前台"走，不跟页面走：
        // 用户在浏览别人相册时也还在听，对端关掉共享能立刻反映到列表。
        // 用 ProcessLifecycleOwner 而不是 Activity 回调：转屏、页面重建不会造成 stop→start 抖动。
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = mdnsBrowser.start()

            override fun onStop(owner: LifecycleOwner) = mdnsBrowser.stop()
        })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(ImageDecoderDecoder.Factory()) }
        // Coil 2.x 默认就用 OkHttp 取网络图；这里换成同一个 client，token 拦截器才对图片生效。
        .okHttpClient(lanHttp.client)
        // 网格与阅读页共用缓存，限制快速翻页时同时进行的像素解码。
        .bitmapFactoryMaxParallelism(2)
        .decoderDispatcher(Dispatchers.IO.limitedParallelism(2))
        .memoryCache {
            MemoryCache.Builder(this).maxSizePercent(0.2).build()
        }
        // 显式保留 250MB 磁盘 LRU 缓存：远端详情网格预览图与原图退出重进、重启均秒开
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(250L * 1024 * 1024)
                .build()
        }
        .build()
}

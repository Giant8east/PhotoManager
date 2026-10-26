package com.example.photomanager

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@HiltAndroidApp
class PhotoManagerApplication : Application(), ImageLoaderFactory {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(ImageDecoderDecoder.Factory()) }
        // 网格与阅读页共用缓存，限制快速翻页时同时进行的像素解码。
        .bitmapFactoryMaxParallelism(2)
        .decoderDispatcher(Dispatchers.IO.limitedParallelism(2))
        .memoryCache {
            MemoryCache.Builder(this).maxSizePercent(0.2).build()
        }
        .build()
}
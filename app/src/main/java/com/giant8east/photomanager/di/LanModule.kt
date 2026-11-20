/** 局域网端口的绑定：协议层只认接口，具体实现在 data 层。 */
package com.giant8east.photomanager.di

import com.giant8east.photomanager.data.lan.AllowedDeviceData
import com.giant8east.photomanager.data.lan.DeviceIdentityData
import com.giant8east.photomanager.data.lan.ShareGateway
import com.giant8east.photomanager.lan.protocol.LanIdentity
import com.giant8east.photomanager.lan.server.ShareDataSource
import com.giant8east.photomanager.lan.server.WhitelistStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 两个端口分别指向 data 层的实现：
 * - [ShareDataSource]：协议层读相册快照，不关心数据来自 Room 还是将来的缓存；
 * - [WhitelistStore]：配对与撤销只关心"记住没记住"，不关心落在哪张表。
 *
 * 用 @Binds 而不是 @Provides：实现类自己带 @Inject 构造，这里只声明接口到实现的映射，
 * 编译器会检查签名是否兼容，也不会多生成一层工厂方法体。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LanModule {
    @Binds
    @Singleton
    abstract fun bindShareDataSource(gateway: ShareGateway): ShareDataSource

    @Binds
    @Singleton
    abstract fun bindWhitelistStore(allowedDevices: AllowedDeviceData): WhitelistStore

    companion object {
        /** 身份是纯数据：由 data 层读出来直接提供，不必再包一层接口。 */
        @Provides
        @Singleton
        fun provideLanIdentity(data: DeviceIdentityData): LanIdentity = data.identity
    }
}

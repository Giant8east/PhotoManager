/** 两端共享的协议常量：改这里主机与访客同时生效。 */
package com.giant8east.photomanager.lan.protocol

/** 鉴权头名字。自定义头，避免与标准 Authorization 混淆；客户端拦截器统一注入。 */
const val TOKEN_HEADER = "token"

/** 协议版本前缀，所有业务接口都挂在它下面。 */
const val API_PREFIX = "/v1"

package com.llucs.samota.core

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object OkHttpProvider {
    val client: OkHttpClient by lazy {
        val uaInterceptor = Interceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", FusConstants.USER_AGENT)
                .build()
            chain.proceed(req)
        }
        OkHttpClient.Builder()
            .addInterceptor(uaInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

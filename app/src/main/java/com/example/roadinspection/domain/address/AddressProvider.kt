package com.example.roadinspection.domain.address

import android.content.Context
import android.location.Location
import android.util.Log
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 地址服务提供者 [cite: 42]
 * 核心功能：提供“智能获取地址”的方法，封装了“优先缓存，网络兜底”的逻辑。
 */
class AddressProvider(private val context: Context) {

    private val geocodeSearch = GeocodeSearch(context)

    /**
     * 核心方法：智能解析地址
     * 逻辑：
     * 1. 优先检查 Location 自带的 extras (零耗时，不需要网络请求)
     * 2. 如果自带地址为空 (Fallback)，则主动发起高德网络请求查询 [cite: 103]
     */
    suspend fun resolveAddress(location: Location): String {
        // 1. 尝试从 extras 获取 (最快)
        val cachedAddress = location.extras?.getString("address")
        if (!cachedAddress.isNullOrEmpty()) {
            Log.d("AddressProvider", "命中缓存地址: $cachedAddress")
            return cachedAddress
        }

        // 2. 缓存未命中，执行网络查询 (兜底)
        Log.w("AddressProvider", "缓存地址为空，发起网络查询...")
        return fetchAddressFromNetwork(location.latitude, location.longitude)
    }

    /**
     * 私有方法：执行高德网络查询 (挂起函数)
     */
    private suspend fun fetchAddressFromNetwork(lat: Double, lon: Double): String =
        withContext(Dispatchers.IO) { // 确保在 IO 线程执行
            suspendCancellableCoroutine { continuation ->
                val query = RegeocodeQuery(
                    LatLonPoint(lat, lon),
                    200f, // 范围 200米
                    GeocodeSearch.AMAP
                )

                geocodeSearch.setOnGeocodeSearchListener(object :
                    GeocodeSearch.OnGeocodeSearchListener {
                    override fun onRegeocodeSearched(result: RegeocodeResult?, rCode: Int) {
                        // 1000 代表成功
                        if (rCode == 1000 && result?.regeocodeAddress != null) {
                            val address = result.regeocodeAddress.formatAddress
                            if (continuation.isActive) continuation.resume(address)
                        } else {
                            Log.e("AddressProvider", "网络查询失败 code: $rCode")
                            // 即使失败也返回一个默认值，防止业务中断
                            if (continuation.isActive) continuation.resume("未知路段 (网络查询失败)")
                        }
                    }

                    override fun onGeocodeSearched(result: GeocodeResult?, rCode: Int) {
                        // 不需要处理
                    }
                })

                // 发起异步调用
                geocodeSearch.getFromLocationAsyn(query)
            }
        }
}
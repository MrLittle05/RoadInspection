package com.example.roadinspection.domain.location

import android.content.Context
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeAddress
import com.amap.api.services.geocoder.RegeocodeQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AddressProvider1(private val context: Context) {

    private val geocodeSearch = GeocodeSearch(context)

    suspend fun getAddressFromLocation(lat: Double, lng: Double): String? {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 坐标转换
                val (gcjLat, gcjLng) = wgs84ToGcj02(lat, lng)
                val point = LatLonPoint(gcjLat, gcjLng)

                // 2. 构建查询
                val query = RegeocodeQuery(point, 200f, GeocodeSearch.AMAP)

                // ================== 修改重点 ==================

                // 3. 同步调用
                // 注意：这里返回的是 RegeocodeAddress，不是 RegeocodeResult
                val address: RegeocodeAddress? = geocodeSearch.getFromLocation(query)

                // 4. 直接获取 formatAddress (不需要再 .regeocodeAddress 了)
                if (address != null && !address.formatAddress.isNullOrEmpty()) {
                    address.formatAddress
                } else {
                    null
                }

                // =============================================

            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    // ... 下面的 wgs84ToGcj02 等函数保持不变 ...
    private fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        // ... 省略 ...
        return Pair(lat, lon) // 占位，请保留你原本的转换逻辑
    }
}
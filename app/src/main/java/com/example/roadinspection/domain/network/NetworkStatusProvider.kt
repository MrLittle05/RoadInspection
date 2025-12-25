package com.example.roadinspection.domain.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.roadinspection.data.model.NetworkStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow



/**
 * 一个可复用的网络状态提供者
 * @param context 应用上下文
 */
class NetworkStatusProvider(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    /**
     * 提供网络状态更新的 Flow
     */
    @SuppressLint("MissingPermission")
    val networkStatusFlow: Flow<NetworkStatus> = callbackFlow {
        // 初始立即发送一次当前状态
        trySend(getCurrentNetworkStatus())

        // 1. 监听网络连接类型的变化 (WIFI/CELLULAR)
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentNetworkStatus())
            }

            override fun onLost(network: Network) {
                trySend(getCurrentNetworkStatus())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(getCurrentNetworkStatus())
            }
        }

        // 2. 监听移动网络信号强度的变化
        val signalStrengthCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrengths: android.telephony.SignalStrength) {
                    trySend(getCurrentNetworkStatus())
                }
            }
        } else {
            null
        }

        // 注册回调
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && signalStrengthCallback != null) {
            telephonyManager.registerTelephonyCallback(ContextCompat.getMainExecutor(context), signalStrengthCallback)
        }

        // 当 Flow 关闭时，取消注册回调以防止内存泄漏
        awaitClose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && signalStrengthCallback != null) {
                telephonyManager.unregisterTelephonyCallback(signalStrengthCallback)
            }
        }
    }

    private fun getCurrentNetworkStatus(): NetworkStatus {
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        var networkType = "N/A"
        var signalLevel = 0

        if (capabilities != null) {
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    networkType = "WIFI"
                    // WiFi 信号强度算法较复杂，这里暂定为满格
                    signalLevel = 4
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    networkType = getCellularNetworkType()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // 对于较新的API，我们可以从SignalStrength中获取更准确的等级
                        telephonyManager.signalStrength?.let { signalLevel = it.level }
                    } else {
                        // 旧版API的估算
                        signalLevel = 2
                    }
                }
            }
        }
        return NetworkStatus(networkType, signalLevel)
    }

    @SuppressLint("MissingPermission")
    private fun getCellularNetworkType(): String {
        // 需要 READ_PHONE_STATE 权限
        return when (telephonyManager.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            else -> "Cellular"
        }
    }
}

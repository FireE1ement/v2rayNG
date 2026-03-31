package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.*
import java.lang.ref.SoftReference
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

@SuppressLint("VpnServicePolicy")
class V2RayVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    
    // === НОВЫЕ ПОЛЯ ДЛЯ МОНИТОРИНГА ===
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var lastSuccessfulConnection: Long = 0
    private var consecutiveFailures: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReconnecting: Boolean = false
    
    companion object {
        private const val CHECK_INTERVAL_MS = 30000L // Проверка каждые 30 секунд
        private const val CONNECTION_TIMEOUT_MS = 15000
        private const val MAX_CONSECUTIVE_FAILURES = 2
        private const val RETRY_DELAY_MS = 3000L
        private const val MIN_SUCCESS_DURATION_MS = 5000L // Минимум 5 сек успешной работы
        
        // Тестовые URL для проверки доступности
        val TEST_URLS = listOf(
            "https://www.google.com/generate_204",
            "https://www.gstatic.com/generate_204",
            "https://www.youtube.com/generate_204"
        )
    }
    // === КОНЕЦ НОВЫХ ПОЛЕЙ ===

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        Log.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")
        stopMonitoring() // Останавливаем мониторинг
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(AppConfig.TAG, "StartCore-VPN: Service command received")
        
        // === НОВАЯ ЛОГИКА: Обработка команд автопереключения ===
        when (intent?.action) {
            "SWITCH_SERVER" -> {
                val newGuid = intent.getStringExtra("server_guid")
                if (newGuid != null && isRunning) {
                    performServerSwitch(newGuid)
                    return START_STICKY
                }
            }
        }
        // === КОНЕЦ НОВОЙ ЛОГИКИ ===
        
        setupVpnService()
        startService()
        return START_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            Log.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!V2RayServiceManager.startCoreLoop(mInterface)) {
            Log.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }
        
        // === НОВАЯ ЛОГИКА: Запускаем мониторинг после успешного старта ===
        if (SettingsManager.isAutoSwitchEnabled()) {
            startMonitoring()
        }
        lastSuccessfulConnection = SystemClock.elapsedRealtime()
        consecutiveFailures = 0
        // === КОНЕЦ НОВОЙ ЛОГИКИ ===
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    // === НОВЫЕ МЕТОДЫ МОНИТОРИНГА ===
    
    /**
     * Запускает периодическую проверку соединения
     */
    private fun startMonitoring() {
        Log.i(AppConfig.TAG, "StartCore-VPN: Starting connection monitoring")
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            delay(CHECK_INTERVAL_MS) // Первая проверка через 30 сек после старта
            
            while (isActive && isRunning) {
                val isHealthy = checkConnectionHealth()
                
                if (isHealthy) {
                    consecutiveFailures = 0
                    lastSuccessfulConnection = SystemClock.elapsedRealtime()
                    Log.d(AppConfig.TAG, "StartCore-VPN: Connection health check passed")
                } else {
                    consecutiveFailures++
                    Log.w(AppConfig.TAG, "StartCore-VPN: Connection health check failed (failure #$consecutiveFailures)")
                    
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        handleConnectionFailure()
                        break // Выходим из цикла, т.к. будет перезапуск
                    }
                }
                
                delay(CHECK_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Останавливает мониторинг
     */
    private fun stopMonitoring() {
        Log.i(AppConfig.TAG, "StartCore-VPN: Stopping connection monitoring")
        monitoringJob?.cancel()
        monitoringJob = null
    }
    
    /**
     * Проверяет здоровье соединения через SOCKS прокси
     */
    private suspend fun checkConnectionHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val proxyPort = SettingsManager.getSocksPort()
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))
            
            // Пробуем несколько URL для надёжности
            for (testUrl in TEST_URLS) {
                try {
                    val url = URL(testUrl)
                    val connection = url.openConnection(proxy) as HttpURLConnection
                    connection.connectTimeout = CONNECTION_TIMEOUT_MS
                    connection.readTimeout = CONNECTION_TIMEOUT_MS
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    
                    val responseCode = connection.responseCode
                    connection.disconnect()
                    
                    if (responseCode == 204 || responseCode == 200) {
                        return@withContext true
                    }
                } catch (e: Exception) {
                    continue // Пробуем следующий URL
                }
            }
            false
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-VPN: Health check error", e)
            false
        }
    }
    
    /**
     * Обрабатывает обнаруженный сбой соединения
     */
    private suspend fun handleConnectionFailure() {
        if (isReconnecting) return // Уже переподключаемся
        
        isReconnecting = true
        Log.w(AppConfig.TAG, "StartCore-VPN: Handling connection failure, requesting server switch")
        
        // Уведомляем UI/ServiceManager о необходимости переключения
        withContext(Dispatchers.Main) {
            val intent = Intent("com.v2ray.ang.ACTION_CONNECTION_FAILED")
            intent.setPackage(packageName)
            sendBroadcast(intent)
            
            // Запрашиваем переключение на лучший сервер через V2RayServiceManager
            V2RayServiceManager.requestServerSwitch()
        }
        
        isReconnecting = false
    }
    
    /**
     * Выполняет переключение на новый сервер без полной остановки VPN
     */
    private fun performServerSwitch(newGuid: String) {
        Log.i(AppConfig.TAG, "StartCore-VPN: Performing server switch to $newGuid")
        
        serviceScope.launch {
            try {
                // Останавливаем только ядро, не трогая VPN интерфейс
                V2RayServiceManager.stopCoreLoop()
                
                delay(RETRY_DELAY_MS)
                
                // Меняем активный сервер
                MmkvManager.setActiveServer(newGuid)
                
                // Перезапускаем ядро с новым сервером
                if (V2RayServiceManager.startCoreLoop(mInterface)) {
                    consecutiveFailures = 0
                    lastSuccessfulConnection = SystemClock.elapsedRealtime()
                    Log.i(AppConfig.TAG, "StartCore-VPN: Server switch successful")
                    
                    // Уведомляем об успехе
                    NotificationManager.showNotification(
                        "Connected to ${V2RayServiceManager.getRunningServerName()}"
                    )
                } else {
                    throw Exception("Failed to restart core")
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-VPN: Server switch failed", e)
                // Если не удалось переключиться - полный рестарт
                stopAllService(true)
            }
        }
    }
    
    // === КОНЕЦ НОВЫХ МЕТОДОВ ===

    private fun setupVpnService() {
        val prepare = prepare(this)
        if (prepare != null) {
            Log.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            stopSelf()
            return
        }

        if (configureVpnService() != true) {
            Log.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            stopSelf()
            return
        }

        runTun2socks()
    }

    private fun configureVpnService(): Boolean {
        val builder = Builder()
        configureNetworkSettings(builder)
        configurePerAppProxy(builder)

        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        configurePlatformFeatures(builder)

        try {
            mInterface = builder.establish()!!
            isRunning = true
            return true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopAllService()
        }
        return false
    }

    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3)
                builder.addRoute("fc00::", 18)
            } else {
                builder.addRoute("::", 0)
            }
        }

        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    private fun configurePlatformFeatures(builder: Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-VPN: Failed to request network", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    builder.addDisallowedApplication(it)
                } else {
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    private fun runTun2socks() {
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
        isRunning = false
        stopMonitoring() // Останавливаем мониторинг
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "StartCore-VPN: Failed to unregister callback", e)
            }
        }

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        V2RayServiceManager.stopCoreLoop()

        if (isForced) {
            stopSelf()

            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
        }
    }
}

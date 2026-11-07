package rongyi.lingbai.wechatpanel

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Method
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal data class HomeSidePanelWalletDisplayState(
    val defaultMaskEnabled: Boolean,
    val isMasked: Boolean = defaultMaskEnabled,
) {
    fun toggleFromCard(): HomeSidePanelWalletDisplayState = if (defaultMaskEnabled) {
        copy(isMasked = !isMasked)
    } else {
        this
    }

    fun reset() = copy(isMasked = defaultMaskEnabled)
}

internal data class HomeSidePanelWalletUiState(
    val balanceFen: Long? = null,
    val displayState: HomeSidePanelWalletDisplayState = HomeSidePanelWalletDisplayState(true),
) {
    fun withBalance(balanceFen: Long?): HomeSidePanelWalletUiState = copy(balanceFen = balanceFen)

    val displayBalance: String
        get() = if (displayState.isMasked) "******" else formatHomeSidePanelWalletBalance(balanceFen)
}

internal fun formatHomeSidePanelWalletBalance(balanceFen: Long?): String {
    if (balanceFen == null) return "¥ --"
    val formatter = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
    return "¥ ${formatter.format(BigDecimal.valueOf(balanceFen, 2))}"
}

internal object HomeSidePanelWalletBalanceSource {
    const val BALANCE_KEY = "USERINFO_NEW_BALANCE_LONG_SYNC"

    private val lock = Any()
    private val _updates = MutableStateFlow<Long?>(null)
    private var readBalance: (() -> Long?)? = null
    private var requestBalanceRefresh: (() -> Boolean)? = null

    val updates: StateFlow<Long?> = _updates.asStateFlow()

    fun install(
        reader: () -> Long?,
        requester: () -> Boolean = { false },
    ) {
        synchronized(lock) {
            readBalance = reader
            requestBalanceRefresh = requester
            _updates.value = null
        }
    }

    fun clear() {
        synchronized(lock) {
            readBalance = null
            requestBalanceRefresh = null
            _updates.value = null
        }
    }

    fun read(): Long? = synchronized(lock) { readBalance }?.invoke()

    fun refresh() {
        val balance = read() ?: return
        synchronized(lock) {
            _updates.value = balance
        }
    }

    /** Ask WeChat for fresh wallet data, then reread its cache while the async response arrives. */
    suspend fun refreshFromHost() {
        var requested = requestHostRefresh()
        refresh()
        for (waitMillis in HOST_REFRESH_POLL_DELAYS_MS) {
            delay(waitMillis)
            if (!requested) requested = requestHostRefresh()
            refresh()
        }
    }

    fun onCacheWrite(key: Any?, value: Any?) {
        synchronized(lock) {
            if ((key as? Enum<*>)?.name != BALANCE_KEY) return
            if (value !is Long) return
            _updates.value = value
        }
    }

    private fun requestHostRefresh(): Boolean =
        synchronized(lock) { requestBalanceRefresh }?.invoke() == true

    private val HOST_REFRESH_POLL_DELAYS_MS = longArrayOf(250L, 500L, 900L, 1_600L, 2_400L)
}

internal fun readHomeSidePanelWalletBalance(
    walletCacheReadMethod: Method,
    walletPayPluginClass: Class<*>,
): Long? {
    val walletPayService = WeServiceApi.getServiceByClass(walletPayPluginClass.interfaces[0])
    val walletCacheMethod = walletPayService.javaClass.methods.firstOrNull {
        it.parameterTypes.isEmpty() &&
            walletCacheReadMethod.declaringClass.isAssignableFrom(it.returnType)
    } ?: walletPayService.javaClass.declaredMethods.first {
        it.parameterTypes.isEmpty() &&
            walletCacheReadMethod.declaringClass.isAssignableFrom(it.returnType)
    }
    walletCacheMethod.isAccessible = true
    val walletCache = walletCacheMethod.invoke(walletPayService) ?: return null
    val balanceKey = walletCacheReadMethod.parameterTypes[0].enumConstants!!
        .single { (it as Enum<*>).name == HomeSidePanelWalletBalanceSource.BALANCE_KEY }
    return walletCacheReadMethod.invoke(walletCache, balanceKey, null) as? Long
}

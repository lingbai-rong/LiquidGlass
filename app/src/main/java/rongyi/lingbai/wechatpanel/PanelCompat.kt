// SPDX-License-Identifier: GPL-3.0-or-later
package rongyi.lingbai.wechatpanel

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.os.Process
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.luckypray.dexkit.DexKitBridge
import rongyi.lingbai.XposedBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Small host bridge used by the unmodified WeKit panel model and UI.
 *
 * WeKit normally supplies these services through its feature framework, MMKV, DexKit APIs and
 * injected-resource locale provider. LiquidGlass owns a different Xposed lifecycle, so the bridge
 * keeps those host concerns outside the copied panel sources.
 */
internal object PanelHostContext {
    private const val PANEL_PREFS = "liquidglass_wekit_home_side_panel"

    @Volatile
    private var hostContext: Context? = null

    @Volatile
    private var resourceContext: Context? = null

    fun initialize(host: Context, module: Context) {
        hostContext = host.applicationContext ?: host
        resourceContext = module
    }

    val host: Context
        get() = checkNotNull(hostContext) { "HomeSidePanel host context is not initialized" }

    val resources: Context
        get() = resourceContext ?: host

    val preferences: SharedPreferences
        get() = host.getSharedPreferences(PANEL_PREFS, Context.MODE_PRIVATE)
}

internal val DefaultJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

internal object WeLogger {
    fun d(tag: String, message: String) = XposedBridge.log("[LiquidGlass][$tag] $message")
    fun w(tag: String, message: String, error: Throwable? = null) {
        XposedBridge.log("[LiquidGlass][$tag] $message")
        if (error != null) XposedBridge.log(error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        XposedBridge.log("[LiquidGlass][$tag] $message")
        if (error != null) XposedBridge.log(error)
    }
}

internal fun showToast(context: Context, message: CharSequence) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

internal fun localizedBeautifyString(@StringRes id: Int, vararg formatArgs: Any): String =
    PanelHostContext.resources.getString(id, *formatArgs)

internal fun Context.localizedBeautifyString(
    @StringRes id: Int,
    vararg formatArgs: Any,
): String = PanelHostContext.resources.getString(id, *formatArgs)

internal sealed interface BeautifyText {
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : BeautifyText

    data class Raw(val value: String) : BeautifyText
}

internal fun beautifyText(@StringRes id: Int, vararg args: Any): BeautifyText =
    BeautifyText.Resource(id, args.toList())

internal fun Context.resolveBeautifyText(text: BeautifyText): String = when (text) {
    is BeautifyText.Resource -> localizedBeautifyString(text.id, *text.args.toTypedArray())
    is BeautifyText.Raw -> text.value
}

internal val LocalWeKitLocalizedContext = staticCompositionLocalOf { PanelHostContext.resources }

internal enum class SelfProfileField(val code: Int) {
    WXID(2),
    NAME(4),
    EMAIL(5),
    PHONE_NUMBER(6),
    CUSTOM_WXID(42),
    GENDER(12290),
    SIGNATURE(12291),
    CITY(12292),
    PROVINCE(12293),
    COUNTRY_CODE(12324),
    PROVINCE_CODE(12325),
    CITY_CODE(12326),
}

internal data class TextStatus(
    val statusId: String,
    val description: String,
    val userText: String,
    val iconId: String,
    val attachedEmoji: TextStatusEmoji?,
)

internal data class TextStatusEmoji(
    val md5: String?,
    val url: String?,
    val thumbUrl: String?,
    val attachedText: String?,
)

internal sealed interface TextStatusResult {
    data class Ready(val status: TextStatus) : TextStatusResult
    data object NoStatus : TextStatusResult
    data class Error(val cause: Throwable) : TextStatusResult
}

internal object WeApi {
    val selfWxId: String
        get() = PanelHostContext.host
            .getSharedPreferences("com.tencent.mm_preferences", Context.MODE_PRIVATE)
            .let { preferences ->
                sequenceOf(
                    "login_weixin_username",
                    "last_login_wxid",
                    "last_login_username",
                ).mapNotNull { preferences.getString(it, null) }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }
}

/** Minimal extraction of the same DexKit lookups used by WeKit's database and service APIs. */
internal object PanelWeChatResolver {
    private const val TAG = "PanelWeChatResolver"
    private val lock = Any()

    @Volatile
    private var resolutionRunning = false

    @Volatile
    private var configAccess: ConfigAccess? = null

    @Volatile
    private var serviceGetter: Method? = null

    @Volatile
    private var databaseAccess: DatabaseAccess? = null

    @Volatile
    private var conversationAccess: ConversationAccess? = null

    @Volatile
    private var walletRefreshMethod: Method? = null

    fun warmUpAsync(scope: CoroutineScope) {
        if (resolutionRunning) return
        synchronized(lock) {
            if (resolutionRunning) return
            resolutionRunning = true
        }
        scope.launch(Dispatchers.IO) {
            try {
                resolve()
                HomeSidePanelWalletBalanceSource.refresh()
            } finally {
                resolutionRunning = false
            }
        }
    }

    fun readProfileField(field: SelfProfileField, defaultValue: Any): Any? {
        if (configAccess == null) ensureResolved()
        val access = configAccess ?: return null
        return runCatching {
            val coreStorage = access.coreStorage.invoke(null) ?: return@runCatching null
            val configStorage = access.configStorage.invoke(coreStorage) ?: return@runCatching null
            access.readField.invoke(configStorage, field.code, defaultValue)
        }.onFailure {
            WeLogger.w(TAG, "failed to read WeChat profile field ${field.name}", it)
        }.getOrNull()
    }

    fun getService(type: Class<*>): Any? {
        if (serviceGetter == null) ensureResolved()
        return runCatching { serviceGetter?.invoke(null, type) }.getOrNull()
    }

    fun readAvatarUrl(wxId: String): String? {
        if (wxId.isBlank()) return null
        if (databaseAccess == null) ensureResolved()
        val access = databaseAccess ?: return null
        return runCatching {
            val coreStorage = access.coreStorage.invoke(null) ?: return@runCatching null
            val wrapperField = coreStorage.javaClass.allFields().firstOrNull {
                it.type == access.sqliteWrapper ||
                    access.sqliteWrapper.interfaces.any { interfaceType -> interfaceType == it.type }
            } ?: return@runCatching null
            wrapperField.isAccessible = true
            val wrapper = wrapperField.get(coreStorage) ?: return@runCatching null
            val databaseAccessor = wrapper.javaClass.allMethods().firstOrNull {
                it.parameterTypes.isEmpty() &&
                    it.returnType.name == "com.tencent.wcdb.database.SQLiteDatabase"
            } ?: return@runCatching null
            databaseAccessor.isAccessible = true
            val database = databaseAccessor.invoke(wrapper) ?: return@runCatching null
            val rawQuery = database.javaClass.allMethods().firstOrNull {
                it.name == "rawQuery" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1].isArray
            } ?: return@runCatching null
            rawQuery.isAccessible = true
            val queryArgs = java.lang.reflect.Array.newInstance(
                rawQuery.parameterTypes[1].componentType,
                1,
            ).also { java.lang.reflect.Array.set(it, 0, wxId) }
            val cursor = rawQuery.invoke(
                database,
                "SELECT reserved2 FROM img_flag WHERE username = ?",
                queryArgs,
            ) as? Cursor ?: return@runCatching null
            cursor.use { if (it.moveToFirst()) it.getString(0) else null }
        }.onFailure {
            WeLogger.w(TAG, "failed to read WeChat avatar", it)
        }.getOrNull()
    }

    fun markAllAsRead() {
        if (conversationAccess == null || databaseAccess == null || serviceGetter == null) {
            ensureResolved()
        }
        val access = conversationAccess
            ?: throw IllegalStateException("WeChat conversation API is unavailable")
        val featureService = getService(access.storageFeatureService)
            ?: throw IllegalStateException("WeChat storage feature service is unavailable")
        val conversationStorageAccessor = featureService.javaClass.allMethods().firstOrNull {
            it.parameterTypes.isEmpty() &&
                access.conversationStorage.isAssignableFrom(it.returnType)
        } ?: throw IllegalStateException("WeChat conversation storage is unavailable")
        conversationStorageAccessor.isAccessible = true
        val conversationStorage = conversationStorageAccessor.invoke(featureService)
            ?: throw IllegalStateException("WeChat conversation storage is null")
        val cursor = rawQueryDatabase(
            "SELECT username FROM rconversation " +
                "WHERE unReadCount>0 OR unReadMuteCount>0 OR atCount>0",
            null,
        ) ?: throw IllegalStateException("WeChat database is unavailable")
        cursor.use {
            while (it.moveToNext()) {
                val talker = it.getString(0)
                runCatching { access.updateUnreadByTalker.invoke(conversationStorage, talker) }
                    .onFailure { error ->
                        WeLogger.w(TAG, "failed to mark $talker as read", error)
                    }
            }
        }
    }

    fun readWalletBalance(): Long? {
        if (serviceGetter == null) return null
        return runCatching {
            val classLoader = PanelHostContext.host.classLoader
            val walletCacheClass = Class.forName(
                "com.tencent.mm.wallet_core.model.m1",
                false,
                classLoader,
            )
            val walletPayPluginClass = Class.forName(
                "com.tencent.mm.plugin.wxpay.g",
                false,
                classLoader,
            )
            val serviceType = walletPayPluginClass.interfaces.firstOrNull() ?: return@runCatching null
            val walletService = getService(serviceType) ?: return@runCatching null
            val cacheAccessor = walletService.javaClass.allMethods().firstOrNull {
                it.parameterTypes.isEmpty() && walletCacheClass.isAssignableFrom(it.returnType)
            } ?: return@runCatching null
            cacheAccessor.isAccessible = true
            val cache = cacheAccessor.invoke(walletService) ?: return@runCatching null
            val readMethod = walletCacheClass.allMethods().firstOrNull {
                it.name == "i" && it.parameterTypes.size == 2 && it.returnType == Any::class.java
            } ?: return@runCatching null
            readMethod.isAccessible = true
            val balanceKey = readMethod.parameterTypes[0].enumConstants
                ?.firstOrNull {
                    (it as? Enum<*>)?.name == HomeSidePanelWalletBalanceSource.BALANCE_KEY
                } ?: return@runCatching null
            readMethod.invoke(cache, balanceKey, null) as? Long
        }.onFailure {
            WeLogger.w(TAG, "failed to read WeChat wallet balance", it)
        }.getOrNull()
    }

    /**
     * Starts WeChat's own QueryWeChatWalletManager request.
     *
     * WeChat 8.0.72 calls this same static entry from MallIndexUI with
     * `(callback = null, false, false, 0)`. Its response updates
     * USERINFO_NEW_BALANCE_LONG_SYNC, which is already observed by the Xposed cache-write hook.
     */
    fun requestWalletBalanceRefresh(): Boolean {
        val method = walletRefreshMethod ?: return false
        return runCatching {
            method.invoke(null, null, false, false, 0)
            true
        }.onFailure {
            WeLogger.w(TAG, "failed to request a fresh WeChat wallet balance", it)
        }.getOrDefault(false)
    }

    private fun ensureResolved() {
        synchronized(lock) {
            resolveLocked()
        }
    }

    private fun resolve() = synchronized(lock) { resolveLocked() }

    private fun resolveLocked() {
        val classLoader = PanelHostContext.host.classLoader
        if (!PanelNativeLoader.ensureDexKitLoaded()) return
        val bridge = runCatching { DexKitBridge.create(classLoader, true) }
            .onFailure { WeLogger.w(TAG, "failed to open DexKit", it) }
            .getOrNull() ?: return
        try {
            if (serviceGetter == null) {
                serviceGetter = bridge.findMethod {
                    matcher {
                        modifiers(Modifier.STATIC)
                        paramTypes(Class::class.java)
                        usingEqStrings("calling getService(...)")
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)?.apply { isAccessible = true }
            }
            if (walletRefreshMethod == null) {
                walletRefreshMethod = bridge.findMethod {
                    matcher {
                        modifiers(Modifier.STATIC)
                        usingEqStrings(
                            "MicroMsg.QueryWeChatWalletManager",
                            "do query wechat wallet: %s",
                        )
                    }
                }.singleOrNull()
                    ?.getMethodInstance(classLoader)
                    ?.takeIf { method ->
                        method.returnType == Void.TYPE &&
                            method.parameterTypes.size == 4 &&
                            method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                            method.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                            method.parameterTypes[3] == Int::class.javaPrimitiveType
                    }
                    ?.apply { isAccessible = true }
            }
            if (configAccess == null) {
                val mmKernelClass = bridge.findClass {
                    matcher {
                        usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
                    }
                }.singleOrNull()?.getInstance(classLoader)
                val coreStorageClass = bridge.findClass {
                    matcher {
                        usingEqStrings(
                            "MMKernel.CoreStorage",
                            "CheckData path[%s] blocksize:%s blockcount:%s availcount:%s",
                        )
                    }
                }.singleOrNull()?.getInstance(classLoader)
                val configStorageClass = bridge.findClass {
                    searchPackages("com.tencent.mm.storage")
                    matcher {
                        usingEqStrings(
                            "MicroMsg.ConfigStorage",
                            "shouldProcessEvent db is close :%s",
                        )
                    }
                }.singleOrNull()?.getInstance(classLoader)
                val sqliteWrapperClass = bridge.findClass {
                    matcher {
                        usingEqStrings("MicroMsg.SqliteDB", "sql is null ")
                    }
                }.singleOrNull()?.getInstance(classLoader)
                if (mmKernelClass != null && coreStorageClass != null && configStorageClass != null) {
                    val coreStorage = mmKernelClass.allMethods().firstOrNull {
                        Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                            coreStorageClass.isAssignableFrom(it.returnType)
                    }
                    val configStorage = coreStorageClass.allMethods().firstOrNull {
                        it.parameterTypes.isEmpty() &&
                            configStorageClass.isAssignableFrom(it.returnType)
                    }
                    val readField = configStorageClass.allMethods().firstOrNull {
                        it.parameterTypes.size == 2 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            it.parameterTypes[1] == Any::class.java &&
                            it.returnType == Any::class.java
                    }
                    if (coreStorage != null && configStorage != null && readField != null) {
                        coreStorage.isAccessible = true
                        configStorage.isAccessible = true
                        readField.isAccessible = true
                        configAccess = ConfigAccess(coreStorage, configStorage, readField)
                        if (sqliteWrapperClass != null) {
                            databaseAccess = DatabaseAccess(coreStorage, sqliteWrapperClass)
                        }
                    }
                }
            }
            if (conversationAccess == null) {
                val storageFeatureServiceData = bridge.findClass {
                    searchPackages("com.tencent.mm.plugin.messenger.foundation")
                    matcher {
                        addMethod {
                            returnType {
                                usingEqStrings("PRAGMA table_info( contact_ext )")
                            }
                        }
                        addMethod {
                            returnType {
                                usingEqStrings(
                                    "MicroMsg.MsgInfoStorage",
                                    "deleted dirty msg ,count is %d",
                                )
                            }
                        }
                        addMethod {
                            returnType {
                                usingEqStrings("PRAGMA table_info( rconversation)")
                            }
                        }
                    }
                }.singleOrNull()
                val conversationStorageData = bridge.findClass {
                    searchPackages("com.tencent.mm.storage")
                    matcher {
                        usingEqStrings("rconversation", "PRAGMA table_info( rconversation)")
                    }
                }.singleOrNull()
                if (storageFeatureServiceData != null && conversationStorageData != null) {
                    val updateUnread = bridge.findMethod {
                        matcher {
                            declaredClass = conversationStorageData.name
                            usingEqStrings(
                                "MicroMsg.ConversationStorage",
                                "updateUnreadByTalker %s",
                            )
                        }
                    }.singleOrNull()?.getMethodInstance(classLoader)
                    if (updateUnread != null) {
                        updateUnread.isAccessible = true
                        conversationAccess = ConversationAccess(
                            storageFeatureServiceData.getInstance(classLoader),
                            conversationStorageData.getInstance(classLoader),
                            updateUnread,
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            WeLogger.w(TAG, "DexKit resolution failed", error)
        } finally {
            runCatching(bridge::close)
        }
    }

    private fun Class<*>.allMethods(): Sequence<Method> = sequence {
        var current: Class<*>? = this@allMethods
        while (current != null) {
            yieldAll(current.declaredMethods.asSequence())
            current = current.superclass
        }
    }

    private fun Class<*>.allFields() = sequence {
        var current: Class<*>? = this@allFields
        while (current != null) {
            yieldAll(current.declaredFields.asSequence())
            current = current.superclass
        }
    }

    private fun rawQueryDatabase(sql: String, args: Any?): Cursor? {
        val access = databaseAccess ?: return null
        val coreStorage = access.coreStorage.invoke(null) ?: return null
        val wrapperField = coreStorage.javaClass.allFields().firstOrNull {
            it.type == access.sqliteWrapper ||
                access.sqliteWrapper.interfaces.any { interfaceType -> interfaceType == it.type }
        } ?: return null
        wrapperField.isAccessible = true
        val wrapper = wrapperField.get(coreStorage) ?: return null
        val databaseAccessor = wrapper.javaClass.allMethods().firstOrNull {
            it.parameterTypes.isEmpty() &&
                it.returnType.name == "com.tencent.wcdb.database.SQLiteDatabase"
        } ?: return null
        databaseAccessor.isAccessible = true
        val database = databaseAccessor.invoke(wrapper) ?: return null
        val rawQuery = database.javaClass.allMethods().firstOrNull {
            it.name == "rawQuery" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1].isArray
        } ?: return null
        rawQuery.isAccessible = true
        return rawQuery.invoke(database, sql, args) as? Cursor
    }

    private data class ConfigAccess(
        val coreStorage: Method,
        val configStorage: Method,
        val readField: Method,
    )

    private data class DatabaseAccess(
        val coreStorage: Method,
        val sqliteWrapper: Class<*>,
    )

    private data class ConversationAccess(
        val storageFeatureService: Class<*>,
        val conversationStorage: Class<*>,
        val updateUnreadByTalker: Method,
    )
}

/** The libdexkit.so part of WeKit's NativeLoader, isolated from LiquidGlass's global packaging. */
internal object PanelNativeLoader {
    private const val TAG = "PanelNativeLoader"
    private val lock = Any()

    @Volatile
    private var attempted = false

    @Volatile
    private var loaded = false

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun ensureDexKitLoaded(): Boolean {
        if (loaded) return true
        synchronized(lock) {
            if (loaded) return true
            if (attempted) return false
            attempted = true

            val result = runCatching {
                // With LiquidGlass's original useLegacyPackaging=false, LSPosed exposes the
                // module APK's lib/<abi> directory through LspModuleClassLoader. Calling this
                // from the module class (rather than the host class loader) lets Android map the
                // in-APK library without changing packaging for the rest of LiquidGlass.
                System.loadLibrary("dexkit")
                "module class loader"
            }.recoverCatching { classLoaderError ->
                // Match WeKit's installed-APK fallback for ROMs/frameworks that still extract
                // native libraries. API 102 supplies the module paths before any panel Context
                // exists, so optional avatar hooks also work with the side panel disabled.
                val moduleInfo = XposedBridge.getModuleApplicationInfo()
                val instructionSet = if (Process.is64Bit()) "arm64" else "arm"
                val installedModuleDir = requireNotNull(
                    File(moduleInfo.sourceDir).parentFile,
                )
                val candidates = buildList {
                    moduleInfo.nativeLibraryDir
                        ?.takeIf { it.isNotBlank() }
                        ?.let { add(File(it, "libdexkit.so")) }
                    add(File(installedModuleDir, "lib/$instructionSet/libdexkit.so"))
                }
                val library = candidates.firstOrNull { it.isFile && it.canRead() }
                    ?: throw IllegalStateException(
                        "libdexkit.so is unavailable through both the module class loader and " +
                            "the installed module directory",
                        classLoaderError,
                    )
                System.load(library.absolutePath)
                library.absolutePath
            }.map { source ->
                loaded = true
                WeLogger.d(TAG, "loaded libdexkit.so from $source")
                true
            }.onFailure { error ->
                WeLogger.w(TAG, "failed to load libdexkit.so", error)
            }.getOrDefault(false)
            // Allow a later attempt after a temporary load failure rather than permanently
            // disabling the shared DexKit loader.
            if (!result) attempted = false
            return result
        }
    }
}

internal object WeDatabaseApi {
    private val accountPreferences: SharedPreferences
        get() = PanelHostContext.host.getSharedPreferences(
            "com.tencent.mm_preferences",
            Context.MODE_PRIVATE,
        )

    fun getSelfProfileField(field: SelfProfileField, defaultValue: Any): Any =
        PanelWeChatResolver.readProfileField(field, defaultValue) ?: when (field) {
        SelfProfileField.WXID -> WeApi.selfWxId.ifBlank { defaultValue.toString() }
        SelfProfileField.NAME -> firstString(
            "last_login_nick_name",
            "login_user_name",
            "last_login_name",
        ) ?: defaultValue

        SelfProfileField.COUNTRY_CODE -> firstString("last_login_country") ?: defaultValue
        SelfProfileField.PROVINCE,
        SelfProfileField.PROVINCE_CODE,
        -> firstString("last_login_province") ?: defaultValue

        SelfProfileField.CITY,
        SelfProfileField.CITY_CODE,
        -> firstString("last_login_city") ?: defaultValue

            else -> defaultValue
        }

    fun getAvatarUrl(wxId: String): String =
        PanelWeChatResolver.readAvatarUrl(wxId) ?: firstString(
            "last_login_avatar_url",
            "login_avatar_url",
            "last_avatar_url",
        ).orEmpty()

    private fun firstString(vararg keys: String): String? = keys.asSequence()
        .mapNotNull { accountPreferences.getString(it, null) }
        .firstOrNull { it.isNotBlank() }
}

internal object WeServiceApi {
    fun getServiceByClass(type: Class<*>): Any {
        return PanelWeChatResolver.getService(type)
            ?: throw IllegalStateException("WeChat service ${type.name} is unavailable")
    }
}

internal object WeConversationApi {
    suspend fun markAllAsRead() {
        PanelWeChatResolver.markAllAsRead()
    }
}

internal fun restartHost() {
    val context = PanelHostContext.host
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
    if (launchIntent != null) context.startActivity(launchIntent)
    Process.killProcess(Process.myPid())
}

internal fun killHost() {
    Process.killProcess(Process.myPid())
}

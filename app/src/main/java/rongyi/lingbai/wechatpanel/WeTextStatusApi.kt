// SPDX-License-Identifier: GPL-3.0-or-later
package rongyi.lingbai.wechatpanel

import android.app.Activity
import android.content.Context
import android.widget.ImageView
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * WeChat TextStatus bridge ported from WeKit's WeTextStatusApi.
 *
 * None of the obfuscated WeChat class or method names are hard-coded. The same semantic DexKit
 * anchors as WeKit are resolved once per process, then the current non-expired status is read from
 * WeChat's own TextStatus storage.
 */
internal object WeTextStatusApi {
    private const val TAG = "WeTextStatusApi"
    private val lock = Any()

    @Volatile
    private var access: TextStatusAccess? = null

    fun read(wxId: String): TextStatusResult = runCatching {
        if (wxId.isBlank()) return TextStatusResult.NoStatus
        val resolved = ensureResolved()
        val current = readCurrentStatus(resolved, wxId) ?: return TextStatusResult.NoStatus
        val topicInfo = resolved.topicInfo.invoke(
            null,
            current.hostObject,
            false,
            1,
            null,
        ) ?: return TextStatusResult.NoStatus
        TextStatusResult.Ready(
            TextStatus(
                statusId = current.statusId,
                description = resolved.description.invoke(resolved.iconHelper, topicInfo)
                    ?.toString()
                    .orEmpty(),
                userText = (current.record.readFieldValue("field_Description") as? String).orEmpty(),
                iconId = (current.record.readFieldValue("field_IconID") as? String).orEmpty(),
                attachedEmoji = parseEmojiInfo(
                    current.record.readFieldValue("field_EmojiInfo") as? ByteArray,
                ),
            ),
        )
    }.getOrElse { throwable ->
        WeLogger.e(TAG, "failed to read current TextStatus", throwable)
        TextStatusResult.Error(throwable)
    }

    fun renderIcon(imageView: ImageView, iconId: String) {
        runCatching {
            val resolved = ensureResolved()
            resolved.setIcon.invoke(
                null,
                resolved.iconHelper,
                imageView,
                iconId,
                null,
                null,
                null,
                false,
                false,
                124,
                null,
            )
        }.onFailure { throwable ->
            imageView.setImageDrawable(null)
            WeLogger.w(TAG, "failed to render TextStatus icon", throwable)
        }
    }

    fun openCurrentStatusActions(activity: Activity, wxId: String): Boolean = runCatching {
        if (wxId.isBlank()) return false
        val resolved = ensureResolved()
        val current = readCurrentStatus(resolved, wxId) ?: return false
        val actionMethod = resolved.currentStatusActions
        val logicConstructor = actionMethod.declaringClass.declaredConstructors.single().apply {
            isAccessible = true
        }
        val showParameterType = logicConstructor.parameterTypes.single()
        val showParameter = showParameterType.declaredConstructors
            .single { it.parameterCount == 0 }
            .apply { isAccessible = true }
            .newInstance()
        val logic = logicConstructor.newInstance(showParameter)
        logic.javaClass.allFields().first { it.type == Context::class.java }.apply {
            isAccessible = true
            set(logic, activity)
        }
        logic.javaClass.allFields().first { it.type == actionMethod.parameterTypes[1] }.apply {
            isAccessible = true
            set(logic, current.hostObject)
        }
        actionMethod.invoke(logic, activity, current.hostObject)
        true
    }.getOrElse { throwable ->
        WeLogger.e(TAG, "failed to open current TextStatus actions", throwable)
        false
    }

    private fun ensureResolved(): TextStatusAccess = access ?: synchronized(lock) {
        access ?: resolve().also { access = it }
    }

    private fun resolve(): TextStatusAccess {
        check(PanelNativeLoader.ensureDexKitLoaded()) { "DexKit native library is unavailable" }
        val classLoader = PanelHostContext.host.classLoader
        val bridge = DexKitBridge.create(classLoader, true)
        try {
            val recordData = bridge.findClass {
                matcher {
                    addFieldForName("field_UserName")
                    addFieldForName("field_StatusID")
                    addFieldForName("field_IconID")
                    addFieldForName("field_Description")
                    addFieldForName("field_ExpireTime")
                    addFieldForName("field_EmojiInfo")
                }
            }.single()
            val latestStatusData = bridge.findMethod {
                matcher {
                    paramTypes(String::class.java)
                    usingEqStrings(
                        "MicroMsg.TextStatus.StatusInfoAffStorage",
                        "getLatestStatusByUserName: failed",
                    )
                }
            }.single()
            val storageInterface = latestStatusData.declaredClass!!.interfaces.single { candidate ->
                candidate.methods.any { method ->
                    method.methodName == latestStatusData.methodName &&
                        method.paramTypeNames == latestStatusData.paramTypeNames &&
                        method.returnTypeName == latestStatusData.returnTypeName
                }
            }
            val storageAccessorData = bridge.findMethod {
                matcher {
                    paramTypes()
                    returnType(storageInterface.name)
                }
            }.single { candidate ->
                candidate.declaredClass!!.fields.any { field ->
                    field.typeName == candidate.declaredClassName &&
                        Modifier.isStatic(field.modifiers)
                }
            }
            val topicInfoData = bridge.findMethod {
                matcher {
                    usingEqStrings(
                        "Super calls with default arguments not supported in this target, function: getTopicInfo",
                    )
                }
            }.single()
            val setIconData = bridge.findMethod {
                matcher {
                    usingEqStrings(
                        "Super calls with default arguments not supported in this target, function: setIcon",
                    )
                }
            }.single()
            val iconHelperType = setIconData.paramTypeNames.first()
            val iconHelperAccessorData = bridge.findMethod {
                matcher {
                    paramTypes()
                    returnType(iconHelperType)
                    modifiers(Modifier.STATIC)
                }
            }.single()
            val descriptionData = bridge.findMethod {
                matcher {
                    declaredClass(iconHelperType)
                    paramTypes(topicInfoData.returnTypeName)
                    returnType(String::class.java)
                }
            }.single()
            val statusType = latestStatusData.returnTypeName
            val currentStatusActionsData = bridge.findMethod {
                matcher {
                    paramTypes(Context::class.java.name, statusType)
                    returnType(Void.TYPE)
                    usingEqStrings(
                        "MicroMsg.MMSubMenuHelper",
                        "show, menu empty",
                    )
                }
            }.single { candidate ->
                candidate.usesFieldThroughDirectInvoke("field_UserName")
            }

            val serviceClass = storageAccessorData.declaredClass!!.getInstance(classLoader)
            val serviceInstance = serviceClass.allFields().first {
                Modifier.isStatic(it.modifiers) && it.type == serviceClass
            }.apply { isAccessible = true }.get(null)
                ?: error("TextStatus service singleton is null")
            val iconHelperAccessor = iconHelperAccessorData.getMethodInstance(classLoader).accessible()
            val iconHelper = iconHelperAccessor.invoke(null)
                ?: error("TextStatus icon helper is null")

            return TextStatusAccess(
                recordClass = recordData.getInstance(classLoader),
                serviceInstance = serviceInstance,
                storageAccessor = storageAccessorData.getMethodInstance(classLoader).accessible(),
                latestStatus = latestStatusData.getMethodInstance(classLoader).accessible(),
                topicInfo = topicInfoData.getMethodInstance(classLoader).accessible(),
                setIcon = setIconData.getMethodInstance(classLoader).accessible(),
                iconHelper = iconHelper,
                description = descriptionData.getMethodInstance(classLoader).accessible(),
                currentStatusActions = currentStatusActionsData.getMethodInstance(classLoader).accessible(),
            )
        } finally {
            runCatching(bridge::close)
        }
    }

    private fun readCurrentStatus(resolved: TextStatusAccess, wxId: String): CurrentStatus? {
        val storage = resolved.storageAccessor.invoke(resolved.serviceInstance) ?: return null
        val hostObject = resolved.latestStatus.invoke(storage, wxId) ?: return null
        val record = unwrapStatusRecord(resolved.recordClass, hostObject)
        val statusId = record.readFieldValue("field_StatusID") as? String
        val expireTime = (record.readFieldValue("field_ExpireTime") as? Number)?.toLong() ?: 0L
        if (statusId.isNullOrBlank() || expireTime <= System.currentTimeMillis() / 1_000L) {
            return null
        }
        return CurrentStatus(hostObject, record, statusId)
    }

    private fun unwrapStatusRecord(recordClass: Class<*>, value: Any): Any {
        if (recordClass.isInstance(value)) return value
        val field = value.javaClass.allFields().first { recordClass.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
        return field.get(value) ?: error("TextStatus record wrapper is null")
    }

    private data class TextStatusAccess(
        val recordClass: Class<*>,
        val serviceInstance: Any,
        val storageAccessor: Method,
        val latestStatus: Method,
        val topicInfo: Method,
        val setIcon: Method,
        val iconHelper: Any,
        val description: Method,
        val currentStatusActions: Method,
    )

    private data class CurrentStatus(
        val hostObject: Any,
        val record: Any,
        val statusId: String,
    )
}

private fun Method.accessible(): Method = apply { isAccessible = true }

private fun Class<*>.allFields(): Sequence<Field> = sequence {
    var current: Class<*>? = this@allFields
    while (current != null) {
        yieldAll(current.declaredFields.asSequence())
        current = current.superclass
    }
}

private fun Any.readFieldValue(name: String): Any? = javaClass.allFields()
    .first { it.name == name }
    .apply { isAccessible = true }
    .get(this)

private fun MethodData.usesFieldThroughDirectInvoke(fieldName: String): Boolean =
    usingFields.any { it.field.fieldName == fieldName } ||
        invokes.any { invoked ->
            invoked.usingFields.any { it.field.fieldName == fieldName }
        }

@Serializable
@OptIn(ExperimentalSerializationApi::class)
private data class TextStatusEmojiProto(
    @ProtoNumber(1) val md5: String = "",
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val thumbUrl: String = "",
    @ProtoNumber(11) val attachedText: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
private fun parseEmojiInfo(bytes: ByteArray?): TextStatusEmoji? {
    val payload = bytes ?: return null
    if (payload.isEmpty()) return null
    return runCatching {
        ProtoBuf.decodeFromByteArray<TextStatusEmojiProto>(payload)
    }.onFailure {
        WeLogger.w("WeTextStatusApi", "failed to decode TextStatus EmojiInfo", it)
    }.getOrNull()?.let { proto ->
        if (proto.md5.isBlank() && proto.url.isBlank() &&
            proto.thumbUrl.isBlank() && proto.attachedText.isBlank()
        ) {
            null
        } else {
            TextStatusEmoji(
                md5 = proto.md5.ifBlank { null },
                url = proto.url.ifBlank { null },
                thumbUrl = proto.thumbUrl.ifBlank { null },
                attachedText = proto.attachedText.ifBlank { null },
            )
        }
    }
}

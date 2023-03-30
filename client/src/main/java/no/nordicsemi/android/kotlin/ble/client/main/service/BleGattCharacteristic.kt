/*
 * Copyright (c) 2022, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.android.kotlin.ble.client.main.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import no.nordicsemi.android.kotlin.ble.client.api.BleGatt
import no.nordicsemi.android.kotlin.ble.client.api.CharacteristicEvent
import no.nordicsemi.android.kotlin.ble.client.api.DescriptorEvent
import no.nordicsemi.android.kotlin.ble.client.api.OnCharacteristicChanged
import no.nordicsemi.android.kotlin.ble.client.api.OnCharacteristicRead
import no.nordicsemi.android.kotlin.ble.client.api.OnCharacteristicWrite
import no.nordicsemi.android.kotlin.ble.client.api.OnReliableWriteCompleted
import no.nordicsemi.android.kotlin.ble.client.api.ServiceEvent
import no.nordicsemi.android.kotlin.ble.client.main.MtuProvider
import no.nordicsemi.android.kotlin.ble.client.main.errors.GattOperationException
import no.nordicsemi.android.kotlin.ble.client.main.errors.MissingPropertyException
import no.nordicsemi.android.kotlin.ble.client.main.errors.NotificationDescriptorNotFoundException
import no.nordicsemi.android.kotlin.ble.core.data.BleErrorResult
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConsts
import no.nordicsemi.android.kotlin.ble.core.data.BleGattPermission
import no.nordicsemi.android.kotlin.ble.core.data.BleGattProperty
import no.nordicsemi.android.kotlin.ble.core.data.BleOperationResult
import no.nordicsemi.android.kotlin.ble.core.data.BleSuccessResult
import no.nordicsemi.android.kotlin.ble.core.data.BleWriteType
import no.nordicsemi.android.kotlin.ble.core.data.toLogLevel
import no.nordicsemi.android.kotlin.ble.core.ext.toDisplayString
import no.nordicsemi.android.kotlin.ble.core.logger.BlekLogger
import no.nordicsemi.android.kotlin.ble.core.splitter.split
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class BleGattCharacteristic internal constructor(
    private val gatt: BleGatt,
    private val characteristic: BluetoothGattCharacteristic,
    private val logger: BlekLogger
) {

    val uuid = characteristic.uuid

    val instanceId = characteristic.instanceId

    val permissions = BleGattPermission.createPermissions(characteristic.permissions)

    val properties = BleGattProperty.createProperties(characteristic.properties)

    private val _notification = MutableSharedFlow<ByteArray>(extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @SuppressLint("MissingPermission")
    suspend fun getNotifications(): Flow<ByteArray> {
        enableIndicationsOrNotifications()

        return suspendCoroutine {
            it.resume(_notification.onCompletion { disableNotifications() })
        }
    }

    private val descriptors = characteristic.descriptors.map { BleGattDescriptor(gatt, instanceId, it, logger) }

    private var pendingReadEvent: ((OnCharacteristicRead) -> Unit)? = null
    private var pendingWriteEvent: ((OnCharacteristicWrite) -> Unit)? = null

    fun findDescriptor(uuid: UUID): BleGattDescriptor? {
        return descriptors.firstOrNull { it.uuid == uuid }
    }

    internal fun onEvent(event: ServiceEvent) {
        when (event) {
            is CharacteristicEvent -> onEvent(event)
            is DescriptorEvent -> descriptors.forEach { it.onEvent(event) }
            is OnReliableWriteCompleted -> TODO()
        }
    }

    private fun onEvent(event: CharacteristicEvent) {
        when (event) {
            is OnCharacteristicChanged -> onLocalEvent(event.characteristic) { _notification.tryEmit(event.value) }
            is OnCharacteristicRead -> onLocalEvent(event.characteristic) { pendingReadEvent?.invoke(event) }
            is OnCharacteristicWrite -> onLocalEvent(event.characteristic) { pendingWriteEvent?.invoke(event) }
        }
    }

    private fun onLocalEvent(eventCharacteristic: BluetoothGattCharacteristic, block: () -> Unit) {
        if (eventCharacteristic.uuid == characteristic.uuid && eventCharacteristic.instanceId == characteristic.instanceId) {
            block()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun write(value: ByteArray, writeType: BleWriteType = BleWriteType.DEFAULT) = suspendCoroutine { continuation ->
        logger.log(Log.DEBUG, "Write to characteristic - start, uuid: $uuid, value: ${value.toDisplayString()}, type: $writeType")
        validateWriteProperties(writeType)
        pendingWriteEvent = {
            if (it.status.isSuccess) {
                logger.log(Log.DEBUG, "Write to characteristic - end, uuid: $uuid, result: ${it.status}")
                continuation.resume(Unit)
            } else {
                logger.log(Log.ERROR, "Write to characteristic - end, uuid: $uuid, result: ${it.status}")
                continuation.resumeWithException(GattOperationException(it.status))
            }
        }
        gatt.writeCharacteristic(characteristic, value, writeType)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun splitWrite(value: ByteArray, writeType: BleWriteType = BleWriteType.DEFAULT) {
        logger.log(Log.DEBUG, "Split write to characteristic - start, uuid: $uuid, value: ${value.toDisplayString()}, type: $writeType")
        value.split(MtuProvider.availableMtu(writeType)).forEachIndexed { i, it ->
            write(it, writeType)
        }
        logger.log(Log.DEBUG, "Split write to characteristic - end, uuid: $uuid")
    }

    private fun validateWriteProperties(writeType: BleWriteType) {
        when (writeType) {
            BleWriteType.DEFAULT -> if (!properties.contains(BleGattProperty.PROPERTY_WRITE)) {
                logger.log(Log.ERROR, "Write to characteristic - missing property error, uuid: $uuid")
                throw MissingPropertyException(BleGattProperty.PROPERTY_WRITE)
            }
            BleWriteType.NO_RESPONSE -> if (!properties.contains(BleGattProperty.PROPERTY_WRITE_NO_RESPONSE)) {
                logger.log(Log.ERROR, "Write to characteristic - missing property error, uuid: $uuid")
                throw MissingPropertyException(BleGattProperty.PROPERTY_WRITE_NO_RESPONSE)
            }
            BleWriteType.SIGNED -> if (!properties.contains(BleGattProperty.PROPERTY_SIGNED_WRITE)) {
                logger.log(Log.ERROR, "Write to characteristic - missing property error, uuid: $uuid")
                throw MissingPropertyException(BleGattProperty.PROPERTY_SIGNED_WRITE)
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun read() = suspendCoroutine { continuation ->
        logger.log(Log.DEBUG, "Read from characteristic - start, uuid: $uuid")
        if (!properties.contains(BleGattProperty.PROPERTY_READ)) {
            logger.log(Log.ERROR, "Read from characteristic - missing property error, uuid: $uuid")
            throw MissingPropertyException(BleGattProperty.PROPERTY_READ)
        }
        pendingReadEvent = {
            if (it.status.isSuccess) {
                logger.log(Log.DEBUG, "Read from characteristic - end, uuid: $uuid, value: ${it.value}")
                continuation.resume(it.value)
            } else {
                logger.log(Log.ERROR, "Read from characteristic - end, uuid: $uuid, result: ${it.status}")
                continuation.resumeWithException(GattOperationException(it.status))
            }
        }
        gatt.readCharacteristic(characteristic)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun enableIndicationsOrNotifications(): Unit {
        return if (properties.contains(BleGattProperty.PROPERTY_NOTIFY)) {
            enableNotifications()
        } else if (properties.contains(BleGattProperty.PROPERTY_INDICATE)) {
            enableIndications()
        } else {
            throw MissingPropertyException(BleGattProperty.PROPERTY_NOTIFY)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun enableIndications() {
        logger.log(Log.DEBUG, "Enable indications on characteristic - start, uuid: $uuid")
        return findDescriptor(BleGattConsts.NOTIFICATION_DESCRIPTOR)?.let { descriptor ->
            gatt.enableCharacteristicNotification(characteristic)
            descriptor.write(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE).also {
                logger.log(Log.DEBUG, "Enable indications on characteristic - end, uuid: $uuid")
            }
        } ?: run {
            logger.log(Log.ERROR, "Enable indications on characteristic - missing descriptor error, uuid: $uuid")
            throw NotificationDescriptorNotFoundException()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun enableNotifications() {
        logger.log(Log.DEBUG, "Enable notifications on characteristic - start, uuid: $uuid")
        return findDescriptor(BleGattConsts.NOTIFICATION_DESCRIPTOR)?.let { descriptor ->
            gatt.enableCharacteristicNotification(characteristic)
            descriptor.write(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE).also {
                logger.log(Log.DEBUG, "Enable notifications on characteristic - end, uuid: $uuid")
            }
        } ?: run {
            logger.log(Log.ERROR, "Enable notifications on characteristic - missing descriptor error, uuid: $uuid")
            throw NotificationDescriptorNotFoundException()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun disableNotifications() {
        logger.log(Log.DEBUG, "Disable notifications on characteristic - start, uuid: $uuid")
        return findDescriptor(BleGattConsts.NOTIFICATION_DESCRIPTOR)?.let { descriptor ->
            gatt.disableCharacteristicNotification(characteristic)
            descriptor.write(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE).also {
                logger.log(Log.DEBUG, "Disable notifications on characteristic - end, uuid: $uuid")
            }
        } ?: run {
            logger.log(Log.ERROR, "Disable notifications on characteristic - missing descriptor error, uuid: $uuid")
            throw NotificationDescriptorNotFoundException()
        }
    }
}
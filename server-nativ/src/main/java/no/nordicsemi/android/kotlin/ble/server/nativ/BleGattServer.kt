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

package no.nordicsemi.android.kotlin.ble.server.nativ

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.kotlin.ble.core.data.BleGattOperationStatus
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import no.nordicsemi.android.kotlin.ble.server.nativ.callback.BleGattServerCallback
import no.nordicsemi.android.kotlin.ble.server.nativ.service.BleGattServerService
import no.nordicsemi.android.kotlin.ble.server.nativ.service.BleGattServerServices
import no.nordicsemi.android.kotlin.ble.server.nativ.service.BleServerGattServiceConfig
import no.nordicsemi.android.kotlin.ble.server.nativ.service.BluetoothGattServerConnection
import no.nordicsemi.android.kotlin.ble.server.nativ.service.BluetoothGattServiceFactory
import no.nordicsemi.android.kotlin.ble.core.server.BleServer
import no.nordicsemi.android.kotlin.ble.core.server.OnConnectionStateChanged
import no.nordicsemi.android.kotlin.ble.core.server.OnPhyRead
import no.nordicsemi.android.kotlin.ble.core.server.OnPhyUpdate
import no.nordicsemi.android.kotlin.ble.core.server.OnServiceAdded
import no.nordicsemi.android.kotlin.ble.core.server.ServiceEvent

class BleGattServer internal constructor(
    private val server: BleServer
) {

    companion object {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun create(context: Context, vararg config: BleServerGattServiceConfig) : BleGattServer {
            val bluetoothManager: BluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

            val callback = BleGattServerCallback()
            val bluetoothGattServer = bluetoothManager.openGattServer(context, callback)
            val server = BluetoothGattServerWrapper(bluetoothGattServer, callback)

            config.forEach {
                bluetoothGattServer.addService(BluetoothGattServiceFactory.create(it))
            }

            return BleGattServer(server)
        }
    }

    private val _connections =
        MutableStateFlow(mapOf<BluetoothDevice, BluetoothGattServerConnection>())
    val connections = _connections.asStateFlow()

    private var services: List<BluetoothGattService> = emptyList()

    init {
        server.event.onEach { event ->
            Log.d("AAATESTAAA", "On server event: $event")
            when (event) {
                is OnConnectionStateChanged -> onConnectionStateChanged(
                    event.device,
                    event.status,
                    event.newState
                )
                is OnServiceAdded -> onServiceAdded(event.service, event.status)
                is ServiceEvent -> connections.value.values.forEach { it.services.onEvent(event) }
                is OnPhyRead -> onPhyRead(event)
                is OnPhyUpdate -> onPhyUpdate(event)
            }
        }.launchIn(ServerScope)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stopServer() {
        server.close()
    }

    private fun onConnectionStateChanged(
        device: BluetoothDevice,
        status: BleGattOperationStatus,
        newState: Int
    ) {
        val connectionState = GattConnectionState.create(newState)

        Log.d("AAATESTAAA", "On connection state change: $status, $newState")

        when (connectionState) {
            GattConnectionState.STATE_CONNECTED -> connectDevice(device)
            GattConnectionState.STATE_DISCONNECTED,
            GattConnectionState.STATE_CONNECTING,
            GattConnectionState.STATE_DISCONNECTING -> removeDevice(device)
        }
    }

    private fun removeDevice(device: BluetoothDevice) {
        val mutableMap = connections.value.toMutableMap()
        mutableMap.remove(device)
        _connections.value = mutableMap.toMap()
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        val copiedServices = services.map {
            BleGattServerService(
                server,
                device,
                BluetoothGattServiceFactory.copy(it)
            )
        }
        val mutableMap = connections.value.toMutableMap()
        mutableMap[device] = BluetoothGattServerConnection(
            device,
            server,
            BleGattServerServices(server, device, copiedServices)
        )
        _connections.value = mutableMap.toMap()
        Log.d("AAATESTAAA", "Connect device $server")
        server.connect(device, true)
    }

    private fun onServiceAdded(service: BluetoothGattService, status: BleGattOperationStatus) {
        server.let { _ ->
            if (status == BleGattOperationStatus.GATT_SUCCESS) {
                services = services + service
            }
        }
    }

    private fun onPhyRead(event: OnPhyRead) {
        _connections.value = _connections.value.toMutableMap().also {
            val connection = it.getValue(event.device).copy(
                txPhy = event.txPhy,
                rxPhy = event.rxPhy
            )
            it[event.device] = connection
        }.toMap()
    }

    private fun onPhyUpdate(event: OnPhyUpdate) {
        _connections.value = _connections.value.toMutableMap().also {
            val connection = it.getValue(event.device).copy(
                txPhy = event.txPhy,
                rxPhy = event.rxPhy
            )
            it[event.device] = connection
        }.toMap()
    }
}
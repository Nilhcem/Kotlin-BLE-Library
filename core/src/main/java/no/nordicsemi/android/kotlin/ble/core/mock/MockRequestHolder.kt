package no.nordicsemi.android.kotlin.ble.core.mock

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

internal class MockRequestHolder {
    private var requestId = 0
    private val requests = mutableMapOf<Int, MockRequest>()

    fun newWriteRequest(characteristic: BluetoothGattCharacteristic): MockRequest {
        return MockCharacteristicWrite(requestId++, characteristic).also {
            requests[it.requestId] = it
        }
    }

    fun newReadRequest(characteristic: BluetoothGattCharacteristic): MockRequest {
        return MockCharacteristicRead(requestId++, characteristic).also {
            requests[it.requestId] = it
        }
    }

    fun newWriteRequest(descriptor: BluetoothGattDescriptor): MockRequest {
        return MockDescriptorWrite(requestId++, descriptor).also {
            requests[it.requestId] = it
        }
    }

    fun newReadRequest(descriptor: BluetoothGattDescriptor): MockRequest {
        return MockDescriptorRead(requestId++, descriptor).also {
            requests[it.requestId] = it
        }
    }

    fun getRequest(requestId: Int): MockRequest {
        return requests.remove(requestId)!!
    }
}
package com.rodcarvalho.kobopageturner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.delay

enum class HidKeyCode(val code: Int) {
    // USB HID Usage Tables, Keyboard/Keypad page (0x07).
    LEFT_ARROW(0x50),
    RIGHT_ARROW(0x4F),
}

// Makes the phone advertise itself as a BLE HID keyboard (HID over GATT
// Profile) so Kobo's native "Bluetooth accessories" page-turn support
// recognizes it, the same way it recognizes any Bluetooth keyboard.
// Unlike Windows/UWP, Android does not reserve the HID/Device Information
// GATT services for system use, so a third-party app can publish them —
// confirmed via the kshoji/BLE-HID-Peripheral-for-Android reference project
// and the io.appground.blek Play Store app before building this.
@SuppressLint("MissingPermission")
class BlePeripheralService(private val context: Context) {

    var onConnectionStateChanged: ((hasSubscriber: Boolean) -> Unit)? = null

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var reportCharacteristic: BluetoothGattCharacteristic? = null
    private val subscribedDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private var pendingStartResult: ((Boolean, String?) -> Unit)? = null

    fun isSupported(): Boolean {
        return adapter != null && adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
    }

    fun start(onResult: (success: Boolean, error: String?) -> Unit) {
        try {
            val server = bluetoothManager.openGattServer(context, gattServerCallback)
            if (server == null) {
                onResult(false, "Could not open GATT server")
                return
            }
            gattServer = server

            server.addService(buildDeviceInformationService())
            server.addService(buildBatteryService())
            server.addService(buildHidService())

            startAdvertising(onResult)
        } catch (e: Exception) {
            onResult(false, e.message)
        }
    }

    fun stop() {
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping", e)
        }
        gattServer = null
        subscribedDevices.clear()
    }

    suspend fun sendKey(key: HidKeyCode) {
        val server = gattServer ?: return
        val characteristic = reportCharacteristic ?: return

        val pressed = byteArrayOf(0, 0, key.code.toByte(), 0, 0, 0, 0, 0)
        notifyAll(server, characteristic, pressed)
        delay(30)
        notifyAll(server, characteristic, ByteArray(8))
    }

    private fun notifyAll(
        server: BluetoothGattServer,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        characteristic.value = value
        for (device in subscribedDevices) {
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun startAdvertising(onResult: (Boolean, String?) -> Unit) {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            onResult(false, "This phone's Bluetooth doesn't support peripheral/advertising mode")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_HID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        pendingStartResult = onResult
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            pendingStartResult?.invoke(true, null)
            pendingStartResult = null
        }

        override fun onStartFailure(errorCode: Int) {
            pendingStartResult?.invoke(false, "advertise error code $errorCode")
            pendingStartResult = null
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.d(TAG, "Service added: ${service.uuid} status=$status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState != BluetoothProfile.STATE_CONNECTED) {
                subscribedDevices.remove(device)
                onConnectionStateChanged?.invoke(subscribedDevices.isNotEmpty())
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: ByteArray(0)
            val response = if (offset > value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val value = descriptor.value ?: ByteArray(0)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedDevices.add(device)
                } else {
                    subscribedDevices.remove(device)
                }
                onConnectionStateChanged?.invoke(subscribedDevices.isNotEmpty())
            }
            descriptor.value = value
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private fun buildDeviceInformationService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_DEVICE_INFORMATION, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val manufacturer = BluetoothGattCharacteristic(
            CHAR_MANUFACTURER_NAME,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        manufacturer.value = "Kobo Page Turner".toByteArray(Charsets.UTF_8)
        service.addCharacteristic(manufacturer)

        val pnp = BluetoothGattCharacteristic(
            CHAR_PNP_ID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        // Vendor ID Source (USB-IF placeholder), Vendor ID, Product ID, Product Version — little-endian.
        pnp.value = byteArrayOf(0x02, 0xFF.toByte(), 0xFF.toByte(), 0x01, 0x00, 0x00, 0x01)
        service.addCharacteristic(pnp)

        return service
    }

    private fun buildBatteryService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_BATTERY, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val level = BluetoothGattCharacteristic(
            CHAR_BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        level.value = byteArrayOf(100)
        service.addCharacteristic(level)
        return service
    }

    private fun buildHidService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_HID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val hidInformation = BluetoothGattCharacteristic(
            CHAR_HID_INFORMATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        hidInformation.value = byteArrayOf(0x11, 0x01, 0x00, 0x02) // bcdHID 1.11 LE, country 0, flags RemoteWake
        service.addCharacteristic(hidInformation)

        val reportMap = BluetoothGattCharacteristic(
            CHAR_REPORT_MAP,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        reportMap.value = REPORT_MAP
        service.addCharacteristic(reportMap)

        val protocolMode = BluetoothGattCharacteristic(
            CHAR_PROTOCOL_MODE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        protocolMode.value = byteArrayOf(0x01) // Report Protocol Mode
        service.addCharacteristic(protocolMode)

        val controlPoint = BluetoothGattCharacteristic(
            CHAR_HID_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(controlPoint)

        val report = BluetoothGattCharacteristic(
            CHAR_REPORT,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        report.value = ByteArray(8)

        val reportReference = BluetoothGattDescriptor(
            DESCRIPTOR_REPORT_REFERENCE,
            BluetoothGattDescriptor.PERMISSION_READ,
        )
        reportReference.value = byteArrayOf(0x01, 0x01) // Report ID 1, Type Input
        report.addDescriptor(reportReference)

        val cccd = BluetoothGattDescriptor(
            DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        report.addDescriptor(cccd)

        service.addCharacteristic(report)
        reportCharacteristic = report

        return service
    }

    companion object {
        private const val TAG = "BlePeripheralService"

        private fun uuid16(assignedNumber: Long): UUID =
            UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", assignedNumber))

        private val SERVICE_HID = uuid16(0x1812)
        private val SERVICE_DEVICE_INFORMATION = uuid16(0x180A)
        private val SERVICE_BATTERY = uuid16(0x180F)

        private val CHAR_HID_INFORMATION = uuid16(0x2A4A)
        private val CHAR_REPORT_MAP = uuid16(0x2A4B)
        private val CHAR_HID_CONTROL_POINT = uuid16(0x2A4C)
        private val CHAR_REPORT = uuid16(0x2A4D)
        private val CHAR_PROTOCOL_MODE = uuid16(0x2A4E)
        private val CHAR_MANUFACTURER_NAME = uuid16(0x2A29)
        private val CHAR_PNP_ID = uuid16(0x2A50)
        private val CHAR_BATTERY_LEVEL = uuid16(0x2A19)

        private val DESCRIPTOR_REPORT_REFERENCE = uuid16(0x2908)
        private val DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION = uuid16(0x2902)

        // Standard USB HID "Boot Keyboard" report descriptor, Report ID 1 —
        // same bytes as the UWP implementation (Services/BleKeyboardService.cs).
        private val REPORT_MAP: ByteArray = intArrayOf(
            0x05, 0x01,
            0x09, 0x06,
            0xA1, 0x01,
            0x85, 0x01,
            0x05, 0x07,
            0x19, 0xE0,
            0x29, 0xE7,
            0x15, 0x00,
            0x25, 0x01,
            0x75, 0x01,
            0x95, 0x08,
            0x81, 0x02,
            0x95, 0x01,
            0x75, 0x08,
            0x81, 0x01,
            0x95, 0x05,
            0x75, 0x01,
            0x05, 0x08,
            0x19, 0x01,
            0x29, 0x05,
            0x91, 0x02,
            0x95, 0x01,
            0x75, 0x03,
            0x91, 0x01,
            0x95, 0x06,
            0x75, 0x08,
            0x15, 0x00,
            0x25, 0x65,
            0x05, 0x07,
            0x19, 0x00,
            0x29, 0x65,
            0x81, 0x00,
            0xC0,
        ).map { it.toByte() }.toByteArray()
    }
}

package com.ble

import android.bluetooth.*
import android.content.Context
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.util.Log
import com.ble.exceptions.ConnectionClosingException
import com.ble.typealiases.Callback
import com.ble.typealiases.EmptyCallback
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.atomic.AtomicInteger


@Suppress("unused")
class BluetoothConnection(private val device: BluetoothDevice) {


    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    /* Bluetooth */
    private var genericAttributeProfile: BluetoothGatt? = null
    private var closingConnection: Boolean = false
    private var connectionActive: Boolean = false

    /* Callbacks */
    private var connectionCallback: Callback<Boolean>? = null

    /* Misc */
    private var operationsInQueue: AtomicInteger = AtomicInteger(0)

    /***
     * Indicates whether additional information should be logged
     ***/
    var verbose: Boolean = false

    /***
     * Called whenever a successful connection is established
     ***/
    var onConnect: EmptyCallback? = null

    /***
     * Called whenever a connection is lost
     ***/
    var onDisconnect: EmptyCallback? = null

    /***
     * Indicates whether the connection is active
     ***/
    val isActive get() = this.connectionActive

    /***
     * Indicates the connection signal strength (dBm)
     ***/
    var rsii: Int = 0
        private set

    private fun log(message: String) {
        if (this.verbose) Log.d("BluetoothConnection", message)
    }
val notificationData= MutableStateFlow<String?>(null)
    private fun setupGattCallback(): BluetoothGattCallback {
        return object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("Device ${device.address} connected!")

                    // Notifies that the connection has been established
                    if (!connectionActive) {
                        onConnect?.invoke()
                        connectionActive = true
                    }

                    // Starts the services discovery
                    genericAttributeProfile?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status == 133) {// HACK: Multiple reconnections handler
                        log("Found 133 connection failure! Reconnecting GATT...")
                    } else if (closingConnection) {// HACK: Workaround for Lollipop 21 and 22
                        endDisconnection()
                    } else {
                        // Notifies that the connection has been lost
                        if (connectionActive) {
                            log("Lost connection with ${device.address}")
                            onDisconnect?.invoke()
                            connectionActive = false
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                super.onServicesDiscovered(gatt, status)
                // detectCharacteristic(gatt!!)
                GlobalScope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        connectionCallback?.invoke(true)
                    } else {
                        Log.e(
                            "BluetoothConnection",
                            "Error while discovering services at ${device.address}! Status: $status"
                        )
                        close()
                        connectionCallback?.invoke(false)
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                super.onCharacteristicChanged(gatt, characteristic)
                Log.e(TAG, characteristic.descriptors., )
//notificationData.value=characteristic.
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
                super.onReadRemoteRssi(gatt, rssi, status)

                // Update the internal rsii variable
                if (status == BluetoothGatt.GATT_SUCCESS) this@BluetoothConnection.rsii = rsii
            }
        }
    }

    private fun getCharacteristic(
        gatt: BluetoothGatt,
        characteristic: String
    ): BluetoothGattCharacteristic? {
        // Converts the specified string into an UUID
        val characteristicUuid = UUID.fromString(characteristic)

        // Iterates trough every service on the gatt
        gatt.services?.forEach { service ->
            // Iterate trough every characteristic on the service
            service.characteristics.forEach { characteristic ->
                // If matches the uuid, then return it
                if (characteristic.uuid == characteristicUuid) return characteristic
            }
        }

        return null
    }


    private fun beginOperation() {
        // Don't allow operations while closing an active connection
        if (closingConnection) throw ConnectionClosingException()

        // Increment the amount of operations
        this.operationsInQueue.incrementAndGet()
    }

    private fun finishOperation() {
        // Decrement the amount of operations, because the current operation has finished
        this.operationsInQueue.decrementAndGet()
    }


    /***
     * Performs a write operation on a specific characteristic
     *
     * @see [write] For a variant that receives a [String] value
     *
     * @param characteristic The uuid of the target characteristic
     *
     * @return True when successfully written the specified value
     ***/
    fun write(characteristic: String, message: ByteArray): Boolean {
        // TODO: Add reliable writing implementation
        this.log("Writing to device ${device.address} (${message.size} bytes)")
        this.beginOperation()

        val characteristicUuid = UUID.fromString(characteristic)

        genericAttributeProfile?.let { gatt ->
            gatt.services?.forEach { service ->
                service.characteristics.forEach { characteristic ->
                    if (characteristic.uuid == characteristicUuid) {
                        characteristic.value = message
                        return gatt.writeCharacteristic(characteristic).also {
                            if (!it) log("Could not write to device ${device.address}")
                            this.finishOperation()
                        }
                    }
                }
            }
        }

        Log.e(
            "BluetoothConnection",
            "Characteristic $characteristic not found on device ${device.address}!"
        )
        this.finishOperation()
        return false
    }

    /***
     * Performs a write operation on a specific characteristic
     *
     * @see [write] For a variant that receives a [ByteArray] value
     *
     * @param characteristic The uuid of the target characteristic
     *
     * @return True when successfully written the specified value
     ***/
    fun write(characteristic: String, message: String, charset: Charset = Charsets.UTF_8): Boolean =
        this.write(characteristic, message.toByteArray(charset))

    fun readByNotification(uuid: String) {
        genericAttributeProfile?.let { gatt ->
            val characteristic = getCharacteristic(gatt, uuid)
            characteristic ?: return

            gatt.setCharacteristicNotification(characteristic, true)

            val desc: BluetoothGattDescriptor = characteristic.getDescriptor(characteristic.uuid)
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(desc)
        }

    }

    /***
     * Performs a read operation on a specific characteristic
     *
     * @see [read] For a variant that returns a [String] value
     *
     * @param uuid The uuid of the target characteristic
     *
     * @return A nullable [ByteArray], null when failed to read
     ***/
    fun read(uuid: String): ByteArray? {
        this.beginOperation()

        // Null safe let of the generic attribute profile
        genericAttributeProfile?.let { gatt ->
            // Searches for the characteristic
            val characteristic = getCharacteristic(gatt, uuid)

            if (characteristic != null) {
                // Tries to read its value, if successful return it
                if (gatt.readCharacteristic(characteristic)) {
                    return characteristic.value.also {
                        this.finishOperation()
                    }
                } else {
                    Log.e(
                        "BluetoothConnection",
                        "Failed to read characteristic $characteristic on device ${device.address}"
                    )
                }
            } else {
                Log.e(
                    "BluetoothConnection",
                    "Characteristic $characteristic not found on device ${device.address}!"
                )
            }
        }

        this.finishOperation()
        return null
    }

    fun startReadings(characteristic: String, pollingRate: Long = 1000L) = flow {
        var lastValue: String? = null
        while (true) {
            // Reads the characteristic from the device
            val currentValue = read(characteristic, Charsets.UTF_8)
            // Check if it has changed
            if (lastValue != currentValue) {
                currentValue?.let { emit(it) }

                // Update the lastValue to reflect the changes
                lastValue = currentValue
            }

            // Waits until the next reading
            delay(pollingRate)
        }

    }

    /***
     * Performs a read operation on a specific characteristic
     *
     * @see [read] For a variant that returns a [ByteArray] value
     *
     * @param uuid The uuid of the target characteristic
     *
     * @return A nullable [String], null when failed to read
     ***/
    fun read(uuid: String, charset: Charset = Charsets.UTF_8): String? =
        this.read(uuid)?.let { String(it, charset) }

    // region Workaround for lollipop
    private fun isLollipop() =
        VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP && VERSION.SDK_INT <= VERSION_CODES.M

    private fun startDisconnection() {
        try {
            this.closingConnection = true
            this.genericAttributeProfile?.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun endDisconnection() {
        log("Disconnected successfully from ${device.address}!\nClosing connection...")

        try {
            connectionActive = false
            connectionCallback?.invoke(false)
            onDisconnect?.invoke()
            genericAttributeProfile?.close()
            genericAttributeProfile = null
            closingConnection = false
        } catch (e: Exception) {
            log("Ignoring closing connection with ${device.address} exception -> ${e.message}")
        }
    }
    // endregion

    internal fun establish(context: Context, callback: Callback<Boolean>) {
        this.connectionCallback = {
            // Clear the operations queue
            closingConnection = false
            operationsInQueue.set(0)

            // Calls the external connection callback
            callback(it)
            connectionCallback = null
        }

        genericAttributeProfile = if (VERSION.SDK_INT < VERSION_CODES.M)
            device.connectGatt(context, false, setupGattCallback())
        else
            device.connectGatt(context, false, setupGattCallback(), BluetoothDevice.TRANSPORT_LE)

    }

    /***
     * Closes the connection
     ***/
    suspend fun close() {
        // Wait for ongoing operations to finish before closing the connection
        // Has a counter of 20 times 500ms each
        // Being 10s in total of timeout
        var counter = 0
        while (operationsInQueue.get() > 0 && counter < 20) {
            log("${operationsInQueue.get()} operations in queue! Waiting for 500ms (${counter * 500}ms elapsed)")
            delay(500)
            counter++
        }

        // HACK: Workaround for Lollipop 21 and 22
        startDisconnection()
    }

}
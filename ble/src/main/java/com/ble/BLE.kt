package com.ble

import android.Manifest
import android.Manifest.permission
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ble.exceptions.*
import com.ble.utils.PermissionUtils
import com.ble.utils.doubleButtonAlertDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.security.Provider
import java.util.*
import kotlin.coroutines.resume

internal const val DEFAULT_TIMEOUT = 10000L


class BLE(private var context: Context) {

    /* Bluetooth related variables */
    private var manager: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null


    var isScanRunning = false
        private set

    /***
     * Indicates whether additional information should be logged
     ***/
    var verbose = false


    /***
     * Instantiates a new Bluetooth scanner instance
     *
     * @throws HardwareNotPresentException If no hardware is present on the running device
     ***/
    fun setup() {
        verifyBluetoothHardwareFeature()
        manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager?.adapter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scanner = adapter?.bluetoothLeScanner
        }
    }


    private fun verifyBluetoothHardwareFeature() {
        log("Checking bluetooth hardware on device...")

        context.packageManager.let {
            if (!PermissionUtils.isBluetoothLowEnergyPresentOnDevice(it) || !PermissionUtils.isBluetoothPresentOnDevice(
                    it
                )
            ) {
                log("No bluetooth hardware detected on this device!")
                throw HardwareNotPresentException()
            } else {
                log("Detected bluetooth hardware on this device!")
            }
        }
    }


    /**
     * Checks if the bluetooth adapter is active
     *
     * If not, automatically requests it's activation to the user
     *
     * @see verifyBluetoothAdapterState For a variation using callbacks
     *
     * @return True when the bluetooth adapter is active
     ***/
    @RequiresPermission(permission.BLUETOOTH_ADMIN)
    suspend fun verifyBluetoothAdapterState(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            if (adapter == null || adapter?.isEnabled == false) {
                log("Bluetooth adapter turned off!")
                continuation.cancel(DisabledAdapterException())
            } else continuation.resume(true)

        }
    }


    /***
     * Starts a scan for bluetooth devices
     * Only runs with a [duration] defined
     *
     * If only one device is required consider using [scanFor]
     *
     * @see underLScan For a variation using callbacks
     *
     * @param filters Used to specify attributes of the devices on the scan
     * @param settings Native object to specify the scan settings (The default setting is only recommended for really fast scans)
     * @param duration Scan time limit, when exceeded stops the scan <b>(Ignored when less then 0)</b>
     *
     * @throws IllegalArgumentException When a duration is not defined
     * @throws ScanFailureException When an error occurs
     *
     * @return An Array of Bluetooth devices found
     ***/

    private var scanCallback: ScanCallback? = null
    private var leScanCallBack: BluetoothAdapter.LeScanCallback? = null

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresPermission(permission.BLUETOOTH_ADMIN)
    fun scan(
        filters: List<ScanFilter>? = null,
        settings: ScanSettings? = null,
        duration: Long = DEFAULT_TIMEOUT
    ) = callbackFlow<BluetoothDevice> {

        // Validates the duration
        if (duration <= 0)
            close(IllegalArgumentException("In order to run a synchronous scan you'll need to specify a duration greater than 0ms!"))

        if (scanner == null)
            close(Exception("should call setup() first!"))

        log("Starting scan...")

        scanCallback =
            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            object : ScanCallback() {

                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    super.onScanResult(callbackType, result)
                    // Gets the device from the result
                    result?.device?.let { device ->
                        log("Scan result! ${device.name} (${device.address}) ${result.rssi}dBm")
                        if (trySend(result.device).isFailure)
                            log("callback flow can't send data")
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    super.onScanFailed(errorCode)
                    log("Scan failed! $errorCode")
                    // Calls the error callback
                    close(ScanFailureException(errorCode))
                }

            }

        val defaultScanSettings by lazy {
            val builder =
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                builder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)

            builder.build()
        }

        // Starts the scan
        isScanRunning = true

        scanner?.startScan(filters, settings ?: defaultScanSettings, scanCallback)


        // Automatically stops the scan if a duration is specified
        if (duration > 0) {
            log("Scan timeout reached!")
            // Waits for the specified timeout
            delay(duration)
            close()
        } else {
            log("Skipped timeout definition on scan!")
        }
        awaitClose { stopScan() }
    }

    /**
     * just use in under LOLLIPOP
     * */
    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresPermission(permission.BLUETOOTH_ADMIN)
    fun underLScan(
        uuids: Array<UUID>? = null,
        duration: Long = DEFAULT_TIMEOUT
    ) = callbackFlow {

        // Validates the duration
        if (duration <= 0)
            close(IllegalArgumentException("In order to run a synchronous scan you'll need to specify a duration greater than 0ms!"))

        if (scanner == null)
            close(Exception("should call setup() first!"))

        log("Starting scan...")

        //under 21
        leScanCallBack =
            BluetoothAdapter.LeScanCallback { device, rssi, scanRecord ->
                log("Scan result! ${device.name} (${device.address}) ${rssi}dBm")
                device?.let {
                    if (trySend(it).isFailure)
                        log("callback flow can't send data")
                }
            }

        // Starts the scan
        isScanRunning = true

        @Suppress("DEPRECATION")
        adapter?.startLeScan(uuids, leScanCallBack)


        // Automatically stops the scan if a duration is specified
        if (duration > 0) {
            log("Scan timeout reached!")
            // Waits for the specified timeout
            delay(duration)
            close()
        } else {
            log("Skipped timeout definition on scan!")
        }
        awaitClose { stopScan() }
    }


    /***
     * Stops the scan started by [underLScan]
     ***/
    fun stopScan() {
        log("Stopping scan...")

        if (!isScanRunning) return

        isScanRunning = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            scanCallback?.let { scanner?.stopScan(it) }
        else
            leScanCallBack?.let {
                @Suppress("DEPRECATION")
                adapter?.stopLeScan(it)
            }

    }

    // region Utility methods
    private fun log(message: String) {
        if (verbose) Log.d("BluetoothMadeEasy", message)
    }


    /***
     * Establishes a connection with the specified bluetooth device
     *
     * @param device The device to be connected with
     *
     * @return A nullable [BluetoothConnection], null when not successful
     ***/
    suspend fun connect(device: BluetoothDevice): BluetoothConnection? {
        return suspendCancellableCoroutine { continuation ->
            log("Trying to establish a conecttion with device ${device.address}...")

            // Establishes a bluetooth connection to the specified device
            val connection = BluetoothConnection(device)
            connection.verbose = verbose
            connection.establish(context) { successful ->
                if (successful) {
                    log("Connected successfully with ${device.address}!")
                    continuation.resume(connection)
                } else {
                    log("Could not connect with ${device.address}")
                    continuation.resume(null)
                }
            }
        }
    }


    suspend fun checkPermissions(activityCompat: AppCompatActivity ): Boolean {
        return suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
                continuation.resume(true)

            if (ContextCompat.checkSelfPermission(context, permission.ACCESS_FINE_LOCATION) != PERMISSION_GRANTED)
                activityCompat.registerForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted: Boolean ->
                    if (isGranted)
                        continuation.resume(true)
                    else
                        continuation.resume(false)

                }.launch(permission.ACCESS_FINE_LOCATION)
        }
    }

    suspend fun checkLocationService(appCompatActivity: AppCompatActivity): Boolean {
        return suspendCancellableCoroutine { continuation ->

            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                continuation.resume(true)
            else {
                val activityResultLauncher =
                    appCompatActivity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                            continuation.resume(true)
                        else
                            continuation.resume(false)
                    }
                context.doubleButtonAlertDialog(
                    "Enable location",
                    "Please enable location service to find device",
                    "OK",
                    "Cancel",
                    {
                        activityResultLauncher.launch(Intent(ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    {
                        continuation.resume(false)
                    })

            }
        }
    }
}
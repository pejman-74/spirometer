package com.spirometer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.coroutineScope
import com.ble.BLE
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion



const val SPIROMETER_CHARACTERISTIC="0000FFE1-0000-1000-8000-00805F9B34FB"
const val TAG="MyTag"
class MainActivity : AppCompatActivity() {
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val ble = BLE(this)
        try {
            ble.setup()
        } catch (e: Exception) {
            Log.d("BluetoothMadeEasy", e.message.toString())
        }

        ble.verbose = true
        lifecycle.coroutineScope.launchWhenCreated {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {


            }
        }

        val devList = ArrayList<BluetoothDevice>()
        lifecycle.coroutineScope.launchWhenStarted {

            ble.scan(duration = 5000).catch { e ->

            }.onCompletion { e ->
                devList.find { it.address.contains("D0:B5:C2:AF") }?.let {
                    ble.connect(it)?.startReadings(SPIROMETER_CHARACTERISTIC)?.collect {
                        Log.e(TAG, it)
                    }
                }
            }.collect {
                Log.e(TAG, "onCreate: ")
                devList.add(it)
            }
        }
    }
}
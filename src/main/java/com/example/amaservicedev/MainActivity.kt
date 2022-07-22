package com.example.amaservicedev

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.amazon.alexa.accessory.protocol.Accessories
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MainActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var btnConnect: Button
    private lateinit var btnScan: Button
    private lateinit var infoText: EditText
    private lateinit var uuidText: EditText
    private lateinit var btManager: BluetoothManager
    private lateinit var btAdapter: BluetoothAdapter
    private lateinit var devSpinner: Spinner
    private lateinit var devList: List<BluetoothDevice>
    private lateinit var transferThread: TransferThread
    private lateinit var mmSocket: BluetoothSocket
    private val TAG: String = "AMAServiceDev"

    private inner class TransferThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream
        private var running: Boolean = true

        public override fun run() {
            var numBytes: Int // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs.
            while (running) {
                // Read from the InputStream.
                numBytes = try {
                    mmInStream.read(mmBuffer)
                } catch (e: IOException) {
                    Log.d(TAG, "Input stream was disconnected", e)
                    break
                }

                Log.d(TAG, "Read $numBytes...")
            }

            Log.d(TAG, "loop break")
        }

        // Call this from the main activity to send data to the remote device.
        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
                mmOutStream.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)
                return
            }
        }

        fun cancel() {
            Log.d(TAG, "transferThread cancel")
            running = false
            mmSocket.close()
        }
    }

    private fun checkPermission() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            infoText.getText().append("No permission (ACCESS_FINE_LOCATION)\n")
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDevDiscovery() {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(btBroadcastReceiver, filter)

        if (btAdapter.isDiscovering) btAdapter.cancelDiscovery()
        btAdapter.startDiscovery()
        btnScan.isEnabled = false
        btnScan.text = getString(R.string.btn_discovering)
    }

    private fun showToastInfo(text: String) {
        Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        btnConnect.text = getString(R.string.btn_connecting)

        mmSocket = device.let {
            val uuid: UUID = UUID.fromString(uuidText.text.toString())
            Log.d(TAG, "Service UUID: " + uuid.toString())
            it.createRfcommSocketToServiceRecord(uuid)
        }

        try {
            mmSocket.connect()
        } catch (e: IOException) {
            Log.d(TAG, "Could not connect to remote device.", e)
        }
        Log.d(TAG, "Connect to remote device successfully.")
        btnConnect.text = getString(R.string.btn_disconnect)
        btnConnect.isEnabled = true
        btnScan.isEnabled = false

        transferThread = TransferThread(mmSocket)
        transferThread.start()
    }

    private fun disconnectDevice() {
        if (this@MainActivity::transferThread.isInitialized) {
            if (transferThread.isAlive) {
                transferThread.cancel()
//                        while (mmSocket.isConnected) {
//                            Log.d(TAG,"Wait for finishing socket close.")
//                            sleep(100)
//                        }
            }
        }
        if (this::mmSocket.isInitialized && mmSocket.isConnected) {
            mmSocket.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.buttonConnect)
        btnScan = findViewById(R.id.buttonScan)
        infoText = findViewById(R.id.editTextMultiLineInfo)
        uuidText = findViewById(R.id.editTextSvcUuid)
        btManager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        btAdapter = btManager.adapter
        devSpinner = findViewById(R.id.spinnerPairedDev)
        devList = listOf<BluetoothDevice>()

        btnConnect.setOnClickListener {
            btnConnect.isEnabled = false

            if (btnConnect.text == getString(R.string.btn_disconnect)) {
                disconnectDevice()
                btnConnect.text = getString(R.string.btn_connect)
                btnConnect.isEnabled = true
                btnScan.isEnabled = true
                return@setOnClickListener
            }

            if (!btAdapter.isEnabled) {
                showToastInfo("Please turn on BT first.")
                return@setOnClickListener
            }

            val uuid = uuidText.getText()
            val newContent = infoText.getText().append(uuid)

            val selectedDevice = devSpinner.selectedItemId.toInt()
            infoText.setText("$newContent\n$selectedDevice\n")

            // Cancel discovery because it otherwise slows down the connection.
            if (btAdapter.isDiscovering) {
                btAdapter.cancelDiscovery()
                btnScan.isEnabled = false
                btnScan.text = getString(R.string.btn_scan)
            }

            when (devList.get(selectedDevice).bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    infoText.setText(infoText.text.append("Bonded\n"))
                    connectDevice(devList.get(selectedDevice))
                }
                BluetoothDevice.BOND_NONE -> {
                    infoText.setText(infoText.text.append("None\n"))
                    devList.get(selectedDevice).createBond()
                }
            }
        }

        btnScan.setOnClickListener {
            devList.toMutableList().clear()
            val devNameList = arrayListOf<String>()
            devSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                devNameList
            )
            devSpinner.isEnabled = false
            btnConnect.isEnabled = false
            btnScan.isEnabled = false
            btnScan.text = getString(R.string.btn_discovering)
            startDevDiscovery()
        }

        checkPermission()
        devSpinner.isEnabled = false
        btnConnect.isEnabled = false

        if (btAdapter.isEnabled) {
            showToastInfo("BT is on.")
            startDevDiscovery()
        } else {
            val resultLauncher =
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
//                        val data: Intent? = result.data
                        showToastInfo("BT is on.")
                        startDevDiscovery()
                    } else {
                        showToastInfo("BT is off.")
                    }
                }

            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            resultLauncher.launch(intent)
        }
    }

    private val btBroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device?.name != null) {
                        devList += device
                        val devNameList = arrayListOf<String>()
                        for (dev in devList) {
                            devNameList += dev.name
                        }
                        devSpinner.adapter = ArrayAdapter(
                            context,
                            android.R.layout.simple_spinner_dropdown_item,
                            devNameList
                        )

                        devSpinner.isEnabled = true
                        btnConnect.isEnabled = true
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val devName = device?.name
                    val result = device?.let {
                        var state = 0
                        when (bondState) {
                            BluetoothDevice.BOND_BONDED -> {
                                connectDevice(device)
                            }
                            BluetoothDevice.BOND_BONDING -> {
                                disconnectDevice()
                                btnConnect.text = getString(R.string.btn_pairing)
                            }
                            BluetoothDevice.BOND_NONE -> {
                                showToastInfo("$devName is not bonded")
                                btnConnect.text = getString(R.string.btn_connect)
                                btnConnect.isEnabled = true
                            }
                        }
                        state
                    } ?: -1

                    if (result == -1) {
                        showToastInfo("Couldn't get boding device information.")
                    }
                }
            }
        }
    }

    override fun onClick(p0: View?) {
        TODO("Not yet implemented")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(btBroadcastReceiver)
        if (this::transferThread.isInitialized && transferThread.isAlive) {
            transferThread.interrupt()
        }
        if (this::mmSocket.isInitialized && mmSocket.isConnected) {
            mmSocket.close()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            100 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                    btAdapter.startDiscovery()
                    showToastInfo(permissions[0] + " is granted.")
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        infoText.getText().append("No permission (BLUETOOTH_CONNECT)\n")
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
                    }
                }
                return
            }
            101 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showToastInfo(permissions[0] + " is granted.")
                }
                return
            }
        }
    }
}
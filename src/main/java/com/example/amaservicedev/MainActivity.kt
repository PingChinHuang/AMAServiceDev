package com.example.amaservicedev

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
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
import com.amazon.alexa.accessory.protocol.Common
import com.amazon.alexa.accessory.protocol.Device
import com.amazon.alexa.accessory.protocol.Device.CompleteSetup
import com.amazon.alexa.accessory.protocol.Device.StartSetup
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.NullPointerException
import java.util.*

@Suppress("BlockingMethodInNonBlockingContext")
class MainActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var btnConnect: Button
    private lateinit var btnScan: Button
    private lateinit var btnSend: Button
    private lateinit var infoText: EditText
    private lateinit var uuidText: EditText
    private lateinit var btManager: BluetoothManager
    private lateinit var btAdapter: BluetoothAdapter
    private lateinit var devSpinner: Spinner
    private lateinit var protocolSpinner: Spinner
    private lateinit var devList: MutableList<BluetoothDevice>
    private lateinit var transferThread: TransferThread
    private lateinit var mmSocket: BluetoothSocket
    private val mmTAG: String = "AMAServiceDev"

    private val hexArray = "0123456789ABCDEF".toCharArray()
    private fun dumpByteArray(array: ByteArray, size: Int): String {
        val hexChars = CharArray(size * 3)
        for (j in array.indices) {
            if (j >= size)
                break

            val v = array[j].toInt() and 0xFF

            hexChars[j * 3] = hexArray[v ushr 4]
            hexChars[j * 3 + 1] = hexArray[v and 0x0F]
            if (j % 16 == 15 )
                hexChars[j * 3 + 2] = '\n'
            else
                hexChars[j * 3 + 2] = ' '
        }
        return String(hexChars)
    }

    private inner class TransferThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream
        private var running: Boolean = true

        override fun run() {
            var numBytes: Int// bytes returned from read()

            // Keep listening to the InputStream until an exception occurs.
            while (running) {
                // Read from the InputStream.
                numBytes = try {
                    mmInStream.read(mmBuffer)
                } catch (e: IOException) {
                    Log.d(mmTAG, e.message.toString())
                    showMsg(e.message.toString())
                    break
                }

                val buffer = mmBuffer.copyOfRange(0, numBytes)
                Log.d(mmTAG, dumpByteArray(buffer, buffer.size))
                try {
                    val response: Accessories.Response =
                        Accessories.Response.parseFrom(buffer)
                    Log.d(mmTAG, "Response error code: ${response.errorCode}")
                } catch (e: InvalidProtocolBufferException) {
                    Log.e(mmTAG, e.stackTraceToString())
                }
                Log.d(mmTAG, "Read $numBytes...")
            }

            Log.d(mmTAG, "loop break")
        }

        // Call this from the main activity to send data to the remote device.
        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
                mmOutStream.flush()
            } catch (e: IOException) {
                Log.e(mmTAG, "Error occurred when sending data", e)
                return
            }
        }

        fun cancel() {
            Log.d(mmTAG, "transferThread cancel")
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
            infoText.text.append("No permission (ACCESS_FINE_LOCATION)\n")
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

    @SuppressLint("MissingPermission")
    private fun cancelDevDiscovery() {
        btAdapter.cancelDiscovery()
        btnScan.isEnabled = false
        btnScan.text = getString(R.string.btn_scan)
    }

    private fun showToastInfo(text: String) {
        Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
    }

    private fun showMsg(text: String) {
        infoText.text.append("$text\n")
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        btnConnect.text = getString(R.string.btn_connecting)

        mmSocket = device.let {
            val uuid: UUID = UUID.fromString(uuidText.text.toString())
            Log.d(mmTAG, "Service UUID: $uuid")
            showMsg("Trying to create connection to $uuid")
            it.createRfcommSocketToServiceRecord(uuid)
        }

        try {
            mmSocket.connect()
        } catch (e: IOException) {
            Log.d(mmTAG, e.message.toString())
            showMsg(e.message.toString())
            btnConnect.text = getString(R.string.btn_connect)
            btnConnect.isEnabled = true
            btnScan.isEnabled = true
            btnSend.isEnabled = false
            return
        }
        Log.d(mmTAG, "Create RFCOMM connection successfully.")
        showMsg("Create RFCOMM connection successfully.")
        btnConnect.text = getString(R.string.btn_disconnect)
        btnConnect.isEnabled = true
        btnScan.isEnabled = false
        btnSend.isEnabled = true

//        transferThread = TransferThread(mmSocket)
//        transferThread.start()
    }

    private fun disconnectDevice() {
        if (this@MainActivity::transferThread.isInitialized) {
            if (transferThread.isAlive) {
                transferThread.cancel()
            }
        }
        if (this::mmSocket.isInitialized && mmSocket.isConnected) {
            mmSocket.close()
        }
    }

    private fun recvProtocolMessage(): Accessories.Response? {
        val mmBuffer = ByteArray(512)
        val numBytes = try {
            mmSocket.inputStream.read(mmBuffer)
        } catch (e: IOException) {
            Log.d(mmTAG, e.message.toString())
//            showMsg(e.message.toString() + '\n')
        }
        Log.d(mmTAG, "Read $numBytes...")

        val buffer = mmBuffer.copyOfRange(0, numBytes)
        Log.d(mmTAG, dumpByteArray(buffer, buffer.size))
        val response: Accessories.Response? = try {
            Accessories.Response.parseFrom(buffer)
        } catch (e: InvalidProtocolBufferException) {
            Log.e(mmTAG, e.message.toString())
            null
        }

        return response
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.buttonConnect)
        btnScan = findViewById(R.id.buttonScan)
        btnSend = findViewById(R.id.buttonSend)
        infoText = findViewById(R.id.editTextMultiLineInfo)
        uuidText = findViewById(R.id.editTextSvcUuid)
        btManager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        btAdapter = btManager.adapter
        devSpinner = findViewById(R.id.spinnerPairedDev)
        protocolSpinner = findViewById(R.id.spinnerProtocol)
        devList = mutableListOf()

        btnConnect.setOnClickListener {
            btnConnect.isEnabled = false

            if (btnConnect.text == getString(R.string.btn_disconnect)) {
                disconnectDevice()
                btnConnect.text = getString(R.string.btn_connect)
                btnConnect.isEnabled = true
                btnScan.isEnabled = true
                btnSend.isEnabled = false
                return@setOnClickListener
            }

            if (!btAdapter.isEnabled) {
                showToastInfo("Please turn on BT first.")
                return@setOnClickListener
            }

            val selectedDevice = devSpinner.selectedItemId.toInt()
//            showMsg("${uuidText.text}\n")

            // Cancel discovery because it otherwise slows down the connection.
            if (btAdapter.isDiscovering) {
                cancelDevDiscovery()
            }

            when (devList[selectedDevice].bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    showMsg("Paired to ${devList[selectedDevice].name} successfully.")
                    connectDevice(devList[selectedDevice])
                }
                BluetoothDevice.BOND_NONE -> {
                    showMsg("Paired to ${devList[selectedDevice].name} failed.")
                    devList[selectedDevice].createBond()
                }
            }
        }

        btnScan.setOnClickListener {
            devList.clear()
            val devNameList = arrayListOf<String>()
            devSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                devNameList
            )
            devSpinner.isEnabled = false
            btnConnect.isEnabled = false
            startDevDiscovery()
        }

        btnSend.setOnClickListener {
            val selectedProtocol = protocolSpinner.selectedItemId.toInt()
            showMsg("Protocol: ${protocolSpinner.selectedItem}")
            when (selectedProtocol) {
                0 -> {
                    runBlocking {
                        launch {
                            val startSetup: StartSetup = StartSetup.newBuilder().build()
                            val controlEnvelope: Accessories.ControlEnvelope =
                                Accessories.ControlEnvelope.newBuilder()
                                    .setCommand(Accessories.Command.START_SETUP)
                                    .setStartSetup(startSetup)
                                    .build()
                            controlEnvelope.writeTo(mmSocket.outputStream)

                            val response = recvProtocolMessage()
                            showMsg("Response:\nerror code: ${response?.errorCode.toString()}")
                        }
                    }
                }
                1 -> {
                    runBlocking {
                        launch {
                            val completeSetup: CompleteSetup =
                                CompleteSetup.newBuilder().setErrorCode(Common.ErrorCode.NOT_FOUND).build()
                            val controlEnvelope: Accessories.ControlEnvelope =
                                Accessories.ControlEnvelope.newBuilder()
                                    .setCommand(Accessories.Command.COMPLETE_SETUP)
                                    .setCompleteSetup(completeSetup)
                                    .build()
                            controlEnvelope.writeTo(mmSocket.outputStream)

                            val response = recvProtocolMessage()
                            showMsg("Response\nError code: ${response?.errorCode.toString()}")
                        }
                    }
                }
                2 -> {
                    runBlocking {
                        launch {
                            val getDevInfo: Device.GetDeviceInformation =
                                Device.GetDeviceInformation.newBuilder()
                                    .setDeviceId(0).build()
                            val controlEnvelope: Accessories.ControlEnvelope =
                                Accessories.ControlEnvelope.newBuilder()
                                    .setCommand(Accessories.Command.GET_DEVICE_INFORMATION)
                                    .setGetDeviceInformation(getDevInfo)
                                    .build()
                            controlEnvelope.writeTo(mmSocket.outputStream)

                            val response = recvProtocolMessage()
                            showMsg("Response:\nerror code: ${response?.errorCode.toString()}")
                            try {
                                if (response?.hasDeviceInformation()!!) {
                                    Log.d(
                                        mmTAG,
                                        "serial number: ${response.deviceInformation.serialNumber}"
                                    )
                                    Log.d(mmTAG, "serial number: ${response.deviceInformation.name}")
                                    Log.d(
                                        mmTAG,
                                        "serial number: ${response.deviceInformation.deviceType}"
                                    )
                                }
                            } catch (e: NullPointerException) {
                                Log.e(mmTAG, e.message.toString())
                                showMsg(e.message.toString())
                            }
                        }
                    }
                }
                3 -> {
                    runBlocking {
                        launch {
                            val getDevConfig: Device.GetDeviceConfiguration =
                                Device.GetDeviceConfiguration.newBuilder().build()
                            val controlEnvelope: Accessories.ControlEnvelope =
                                Accessories.ControlEnvelope.newBuilder()
                                    .setCommand(Accessories.Command.GET_DEVICE_CONFIGURATION)
                                    .setGetDeviceConfiguration(getDevConfig)
                                    .build()
                            controlEnvelope.writeTo(mmSocket.outputStream)

                            val response = recvProtocolMessage()
                            showMsg("Response:\nerror code: ${response?.errorCode.toString()}")
                        }
                    }
                }
            }
        }

        checkPermission()
        devSpinner.isEnabled = false
        btnConnect.isEnabled = false
        btnSend.isEnabled = false

        if (btAdapter.isEnabled) {
            showToastInfo("BT is enabled.")
            startDevDiscovery()
        } else {
            val resultLauncher =
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
//                        val data: Intent? = result.data
                        showToastInfo("BT is enabled.")
                        startDevDiscovery()
                    } else {
                        showToastInfo("BT is disabled.")
                    }
                }

            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            resultLauncher.launch(intent)
        }
    }

    private val btBroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val pattern = "^GOODIX_.*".toRegex()
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device?.name != null && pattern.matches(device.name)) {
                        devList.add(device)
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
                    val result = device?.let {
                        val state = 0
                        when (bondState) {
                            BluetoothDevice.BOND_BONDED -> {
                                showMsg("Paired to ${device.name} successfully.")
                                connectDevice(device)
                            }
                            BluetoothDevice.BOND_BONDING -> {
                                showMsg("Trying to pair to ${device.name}.")
                                disconnectDevice()
                                btnScan.isEnabled = false
                                btnScan.text = getString(R.string.btn_scan)
                                btnConnect.text = getString(R.string.btn_pairing)
                            }
                            BluetoothDevice.BOND_NONE -> {
                                showMsg("Paired to ${device.name} failed.")
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
                    showToastInfo("${permissions[0]} is granted.")
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        showMsg("No permission (BLUETOOTH_CONNECT)\n")
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
                    }
                }
                return
            }
            101 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showToastInfo("${permissions[0]} is granted.")
                }
                return
            }
        }
    }
}

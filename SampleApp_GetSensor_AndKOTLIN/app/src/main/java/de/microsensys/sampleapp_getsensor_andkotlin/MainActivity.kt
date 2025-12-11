package de.microsensys.sampleapp_getsensor_andkotlin

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.microsensys.LibraryVersion
import de.microsensys.TELID.TELIDSensorInfo
import de.microsensys.exceptions.MssException
import de.microsensys.functions.RFIDFunctions_3000
import de.microsensys.utils.BluetoothDeviceScan
import de.microsensys.utils.PermissionFunctions
import de.microsensys.utils.PortTypeEnum
import de.microsensys.utils.ReaderIDInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var spDeviceToConnect: Spinner
    private lateinit var btConnect: Button
    private lateinit var btDisconnect: Button
    private lateinit var rgScanType: RadioGroup
    private lateinit var rbAutoScan: RadioButton
    private lateinit var clManualSelect: ConstraintLayout
    private lateinit var etManualPhSize: EditText
    private lateinit var tvLibInfo: TextView
    private lateinit var tvReaderStatus: TextView
    private lateinit var tvReaderInfo: TextView
    private lateinit var btStart: Button
    private lateinit var btStop: Button
    private lateinit var tvLastResult: TextView
    private lateinit var tvLastSerNo: TextView
    private lateinit var tvLastType: TextView
    private lateinit var tvLastTimestamp: TextView
    private lateinit var etLogging: EditText

    private var mRFIDFunctions: RFIDFunctions_3000? = null
    private var mCheckThread: CheckConnectingReaderThread? = null
    private var mScanThread: ScanThread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        spDeviceToConnect = findViewById(R.id.spinner_device)
        btConnect = findViewById(R.id.button_connect)
        btConnect.setOnClickListener {
            connect()
        }
        btDisconnect = findViewById(R.id.button_disconnect)
        btDisconnect.isEnabled = false
        btDisconnect.setOnClickListener {
            disconnect()
        }
        rgScanType = findViewById(R.id.radiogroupScanType)
        clManualSelect = findViewById(R.id.layoutManualSelect)
        rbAutoScan = findViewById(R.id.radio_Auto)
        rbAutoScan.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            etManualPhSize.isEnabled = !isChecked
        }
        etManualPhSize = findViewById(R.id.et_PhSize)
        tvLibInfo = findViewById(R.id.tv_LibInfo)
        tvLibInfo.text = String.format("V %s", LibraryVersion.getVersionNumber())
        tvReaderStatus = findViewById(R.id.tv_ReaderStatus)
        tvReaderInfo = findViewById(R.id.tv_ReaderInfo)
        btStart = findViewById(R.id.button_start)
        btStart.setOnClickListener {
            startScan()
            btStart.isEnabled = false
            btStop.isEnabled = true
        }
        btStop = findViewById(R.id.button_stop)
        btStop.setOnClickListener {
            stopScan()
            btStart.isEnabled = true
            btStop.isEnabled = false
        }
        tvLastResult = findViewById(R.id.tv_LastResult)
        tvLastSerNo = findViewById(R.id.tv_SerNo)
        tvLastType = findViewById(R.id.tv_TelidType)
        tvLastTimestamp = findViewById(R.id.tv_LastTimestamp)
        etLogging = findViewById(R.id.edit_logging)
        rbAutoScan.isChecked = true
        setUiConnected(false)
    }

    override fun onPostResume() {
        super.onPostResume()

        //Check if there are permissions that need to be requested (USB permission is requested first when "initialize" is called)
        val neededPermissions = PermissionFunctions.getNeededPermissions(applicationContext, PortTypeEnum.Bluetooth)
        if (neededPermissions.isNotEmpty()) {
            etLogging.append("Allow permissions and try again.\n")
            requestPermissions(neededPermissions, 0)
            return
        }
        etLogging.append("Permissions granted.\n")
        //Fill spinner with list of paired POCKETwork devices
        val deviceNames: MutableList<String> = ArrayList()
        val bluetoothManager = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val mAdapter = bluetoothManager.adapter
        if (mAdapter != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Already requested in snippet above, but Android Studio throws an error because not explicitly checked for the exception in code
                return
            }
            //List of connected devices
            val pairedDevices = BluetoothDeviceScan.getPairedDevices(mAdapter)
            if (pairedDevices.size > 0) {
                for (device in pairedDevices) {
                    if (device.name.startsWith("iID ")) deviceNames.add(device.name)
                }
            }
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item, deviceNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spDeviceToConnect.adapter = adapter
    }

    private fun connect() {
        etLogging.setText("")

        //Before opening a new communication port, make sure that previous instance is disposed
        disposeRfidFunctions()
        if (spDeviceToConnect.selectedItemPosition == -1) {
            //TODO notify user to select a device to connect to!
            return
        }

        //Check if there are permissions that need to be requested (USB permission is requested first when "initialize" is called)
        val neededPermissions =
            PermissionFunctions.getNeededPermissions(applicationContext, PortTypeEnum.Bluetooth)
        if (neededPermissions.isNotEmpty()) {
            etLogging.append("Allow permissions and try again.")
            requestPermissions(neededPermissions, 0)
            return
        }
        spDeviceToConnect.isEnabled = false

        //Initialize SpcInterfaceControl instance.
        //  PortType = PortTypeEnum.Bluetooth --> Bluteooth
        //  PortName = selected device in Spinner --> Device name as shown in Settings
        mRFIDFunctions = RFIDFunctions_3000(
            applicationContext,  //Context
            PortTypeEnum.Bluetooth
        )
        mRFIDFunctions!!.portName = spDeviceToConnect.selectedItem.toString()

        //Try to open communication port. This call does not block!!
        try {
            mRFIDFunctions!!.initialize()
            //No exception --> Check for process in a separate thread
            etLogging.append("Connecting...")
            startCheckConnectingThread()
            btConnect.isEnabled = false
            btDisconnect.isEnabled = true
        } catch (e: MssException) {
            e.printStackTrace()
            etLogging.append("Error opening port.")
            spDeviceToConnect.isEnabled = true
        }
    }

    private fun connectProcedureFinished(_connected: Boolean, _readerID: Int) {
        //Open process finished
        if (_connected) {
            // Communication port is open
            runOnUiThread {
                etLogging.append("\nCONNECTED\n")
                if (_readerID > 0) {
                    tvReaderInfo.text =
                        String.format(Locale.getDefault(), "ReaderID: %d", _readerID)
                    tvReaderStatus.setBackgroundColor(Color.GREEN)
                } else tvReaderStatus.setBackgroundColor(Color.YELLOW)
                setUiConnected(true)
            }
        } else {
            // Communication port is open
            runOnUiThread {
                etLogging.append("\n Reader NOT connected \n")
                setUiConnected(false)
            }
        }
    }

    private fun sensorFound(sensorInfo: TELIDSensorInfo?) {
        runOnUiThread {
            if (sensorInfo != null) {
                tvLastResult.setBackgroundColor(Color.GREEN)
                tvLastSerNo.text = sensorInfo.serialNumber
                tvLastType.text = sensorInfo.telidDescription
                tvLastTimestamp.text =
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                etLogging.setText("")
                for (sensorValue in sensorInfo.sensorValueStrings) {
                    etLogging.append(sensorValue.toString())
                }
            } else {
                tvLastResult.setBackgroundColor(Color.RED)
            }
        }
    }

    private fun setUiConnected(isConnected: Boolean) {
        if (isConnected) {
            btStart.isEnabled = true
            rgScanType.isEnabled = true
            clManualSelect.isEnabled = true
        } else {
            spDeviceToConnect.isEnabled = true
            btConnect.isEnabled = true
            btDisconnect.isEnabled = false
            btStart.isEnabled = false
            tvReaderInfo.text = ""
            tvReaderStatus.setBackgroundColor(Color.TRANSPARENT)
            rgScanType.isEnabled = false
            clManualSelect.isEnabled = false
        }
    }

    private fun disconnect() {
        disposeCheckConnectingThread()
        disposeScanThread()
        disposeRfidFunctions()
        setUiConnected(false)
        etLogging.append("\n DISCONNECTED")
    }

    private fun disposeRfidFunctions() {
        if (mRFIDFunctions != null) mRFIDFunctions!!.terminate()
        mRFIDFunctions = null
    }

    private fun disposeCheckConnectingThread() {
        if (mCheckThread != null) {
            mCheckThread!!.cancel()
        }
        mCheckThread = null
    }

    private fun disposeScanThread() {
        if (mScanThread != null) {
            mScanThread!!.cancel()
        }
        mScanThread = null
    }

    private fun startScan() {
        disposeScanThread()
        mScanThread = if (rbAutoScan.isChecked) ScanThread() else ScanThread(
            etManualPhSize.text.toString().toByte()
        )
        mScanThread!!.start()
    }

    private fun stopScan() {
        disposeScanThread()
    }

    private fun startCheckConnectingThread() {
        disposeCheckConnectingThread()
        mCheckThread = CheckConnectingReaderThread()
        mCheckThread!!.start()
    }
    private inner class CheckConnectingReaderThread : Thread() {
        private var crtLoop = true
        override fun run() {
            while (crtLoop) {
                if (mRFIDFunctions!!.isConnecting) {
                    //Still trying to connect -> Wait and continue
                    try {
                        sleep(200)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                    runOnUiThread { etLogging.append(".") }
                    continue
                }
                //Connecting finished! Check if connected or not connected
                var readerId: ReaderIDInfo? = null
                if (mRFIDFunctions!!.isConnected) {
                    try {
                        readerId = mRFIDFunctions!!.readReaderID()
                        if (readerId != null) {
                            connectProcedureFinished(true, readerId.readerID)
                        }
                    } catch (ignore: Exception) {
                    }
                }
                if (readerId == null) connectProcedureFinished(false, 0)

                //Stop thread
                cancel()
            }
        }

        fun cancel() {
            crtLoop = false
        }
    }

    private inner class ScanThread : Thread {
        private var st_loop: Boolean
        private var st_Auto = true
        private var st_phSize: Byte = 0

        constructor() {
            st_loop = true
        }

        constructor(_phSize: Byte) {
            st_loop = true
            st_Auto = false
            st_phSize = _phSize
        }

        override fun run() {
            var sensorInfo: TELIDSensorInfo?
            while (st_loop) {
                sensorInfo = null
                if (st_Auto) {
                    try {
                        sensorInfo = mRFIDFunctions!!.getSensorData(0xFC)
                    } catch (e: MssException) {
                        e.printStackTrace()
                    }
                } else {
                    try {
                        sensorInfo = mRFIDFunctions!!.getSensorData(st_phSize.toInt())
                    } catch (e: MssException) {
                        e.printStackTrace()
                    }
                }
                if (st_loop) {
                    sensorFound(sensorInfo)
                }
                try {
                    sleep(1000)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
        }

        fun cancel() {
            st_loop = false
        }
    }
}
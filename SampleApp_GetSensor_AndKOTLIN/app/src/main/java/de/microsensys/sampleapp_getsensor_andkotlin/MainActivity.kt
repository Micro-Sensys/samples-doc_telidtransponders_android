package de.microsensys.sampleapp_getsensor_andkotlin

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
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

    private lateinit var sp_DeviceToConnect: Spinner
    private lateinit var bt_Connect: Button
    private lateinit var bt_Disconnect: Button
    private lateinit var rg_ScanType: RadioGroup
    private lateinit var rb_AutoScan: RadioButton
    private lateinit var cl_ManualSelect: ConstraintLayout
    private lateinit var et_ManualPhSize: EditText
    private lateinit var tv_LibInfo: TextView
    private lateinit var tv_ReaderStatus: TextView
    private lateinit var tv_ReaderInfo: TextView
    private lateinit var bt_Start: Button
    private lateinit var bt_Stop: Button
    private lateinit var tv_LastResult: TextView
    private lateinit var tv_LastSerNo: TextView
    private lateinit var tv_LastType: TextView
    private lateinit var tv_LastTimestamp: TextView
    private lateinit var et_Logging: EditText

    private var mRFIDFunctions: RFIDFunctions_3000? = null
    private var mCheckThread: CheckConnectingReaderThread? = null
    private var mScanThread: ScanThread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sp_DeviceToConnect = findViewById(R.id.spinner_device)
        bt_Connect = findViewById(R.id.button_connect)
        bt_Connect.setOnClickListener {
            connect()
        }
        bt_Disconnect = findViewById(R.id.button_disconnect)
        bt_Disconnect.isEnabled = false
        bt_Disconnect.setOnClickListener {
                disconnect()
        }
        rg_ScanType = findViewById(R.id.radiogroupScanType)
        cl_ManualSelect = findViewById(R.id.layoutManualSelect)
        rb_AutoScan = findViewById(R.id.radio_Auto)
        rb_AutoScan.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            et_ManualPhSize.isEnabled = !isChecked
        }
        et_ManualPhSize = findViewById(R.id.et_PhSize)
        tv_LibInfo = findViewById(R.id.tv_LibInfo)
        tv_LibInfo.text = String.format("V %s", LibraryVersion.getVersionNumber())
        tv_ReaderStatus = findViewById(R.id.tv_ReaderStatus)
        tv_ReaderInfo = findViewById(R.id.tv_ReaderInfo)
        bt_Start = findViewById(R.id.button_start)
        bt_Start.setOnClickListener {
            startScan()
            bt_Start.isEnabled = false
            bt_Stop.isEnabled = true
        }
        bt_Stop = findViewById(R.id.button_stop)
        bt_Stop.setOnClickListener {
            stopScan()
            bt_Start.isEnabled = true
            bt_Stop.isEnabled = false
        }
        tv_LastResult = findViewById(R.id.tv_LastResult)
        tv_LastSerNo = findViewById(R.id.tv_SerNo)
        tv_LastType = findViewById(R.id.tv_TelidType)
        tv_LastTimestamp = findViewById(R.id.tv_LastTimestamp)
        et_Logging = findViewById(R.id.edit_logging)
        rb_AutoScan.isChecked = true
        setUiConnected(false)
    }

    override fun onPostResume() {
        super.onPostResume()

        //Check if there are permissions that need to be requested (USB permission is requested first when "initialize" is called)
        val neededPermissions = PermissionFunctions.getNeededPermissions(applicationContext, PortTypeEnum.Bluetooth)
        if (neededPermissions.isNotEmpty()) {
            et_Logging.append("Allow permissions and try again.\n")
            requestPermissions(neededPermissions, 0)
            return
        }
        et_Logging.append("Permissions granted.\n")
        //Fill spinner with list of paired POCKETwork devices
        val deviceNames: MutableList<String> = ArrayList()
        val mAdapter = BluetoothAdapter.getDefaultAdapter()
        if (mAdapter != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Already requested in snippet above, but Android Studio throws an error because not explicitly checked for the exception in code
                    return
                }
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
        sp_DeviceToConnect.adapter = adapter
    }

    private fun connect() {
        et_Logging.setText("")

        //Before opening a new communication port, make sure that previous instance is disposed
        disposeRfidFunctions()
        if (sp_DeviceToConnect.selectedItemPosition == -1) {
            //TODO notify user to select a device to connect to!
            return
        }

        //Check if there are permissions that need to be requested (USB permission is requested first when "initialize" is called)
        val neededPermissions =
            PermissionFunctions.getNeededPermissions(applicationContext, PortTypeEnum.Bluetooth)
        if (neededPermissions.isNotEmpty()) {
            et_Logging.append("Allow permissions and try again.")
            requestPermissions(neededPermissions, 0)
            return
        }
        sp_DeviceToConnect.isEnabled = false

        //Initialize SpcInterfaceControl instance.
        //  PortType = PortTypeEnum.Bluetooth --> Bluteooth
        //  PortName = selected device in Spinner --> Device name as shown in Settings
        mRFIDFunctions = RFIDFunctions_3000(
            applicationContext,  //Context
            PortTypeEnum.Bluetooth
        )
        mRFIDFunctions!!.portName = sp_DeviceToConnect.selectedItem.toString()

        //Try to open communication port. This call does not block!!
        try {
            mRFIDFunctions!!.initialize()
            //No exception --> Check for process in a separate thread
            et_Logging.append("Connecting...")
            startCheckConnectingThread()
            bt_Connect.isEnabled = false
            bt_Disconnect.isEnabled = true
        } catch (e: MssException) {
            e.printStackTrace()
            et_Logging.append("Error opening port.")
            sp_DeviceToConnect.isEnabled = true
        }
    }

    private fun connectProcedureFinished(_connected: Boolean, _readerID: Int) {
        //Open process finished
        if (_connected) {
            // Communication port is open
            runOnUiThread {
                et_Logging.append("\nCONNECTED\n")
                if (_readerID > 0) {
                    tv_ReaderInfo.text =
                        String.format(Locale.getDefault(), "ReaderID: %d", _readerID)
                    tv_ReaderStatus.setBackgroundColor(Color.GREEN)
                } else tv_ReaderStatus.setBackgroundColor(Color.YELLOW)
                setUiConnected(true)
            }
        } else {
            // Communication port is open
            runOnUiThread {
                et_Logging.append("\n Reader NOT connected \n")
                setUiConnected(false)
            }
        }
    }

    private fun sensorFound(_sensorInfo: TELIDSensorInfo?) {
        runOnUiThread {
            if (_sensorInfo != null) {
                tv_LastResult.setBackgroundColor(Color.GREEN)
                tv_LastSerNo.text = _sensorInfo.serialNumber
                tv_LastType.text = _sensorInfo.telidDescription
                tv_LastTimestamp.text =
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                et_Logging.setText("")
                for (sensorValue in _sensorInfo.sensorValueStrings) {
                    et_Logging.append(sensorValue.toString())
                }
            } else {
                tv_LastResult.setBackgroundColor(Color.RED)
            }
        }
    }

    private fun setUiConnected(_isConnected: Boolean) {
        if (_isConnected) {
            bt_Start.isEnabled = true
            rg_ScanType.isEnabled = true
            cl_ManualSelect.isEnabled = true
        } else {
            sp_DeviceToConnect.isEnabled = true
            bt_Connect.isEnabled = true
            bt_Disconnect.isEnabled = false
            bt_Start.isEnabled = false
            tv_ReaderInfo.text = ""
            tv_ReaderStatus.setBackgroundColor(Color.TRANSPARENT)
            rg_ScanType.isEnabled = false
            cl_ManualSelect.isEnabled = false
        }
    }

    private fun disconnect() {
        disposeCheckConnectingThread()
        disposeScanThread()
        disposeRfidFunctions()
        setUiConnected(false)
        et_Logging.append("\n DISCONNECTED")
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
        mScanThread = if (rb_AutoScan.isChecked) ScanThread() else ScanThread(
            et_ManualPhSize.text.toString().toByte()
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

    private inner class CheckConnectingReaderThread() : Thread() {
        private var crt_loop = true
        override fun run() {
            while (crt_loop) {
                if (mRFIDFunctions!!.isConnecting) {
                    //Still trying to connect -> Wait and continue
                    try {
                        sleep(200)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                    runOnUiThread { et_Logging.append(".") }
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
            crt_loop = false
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
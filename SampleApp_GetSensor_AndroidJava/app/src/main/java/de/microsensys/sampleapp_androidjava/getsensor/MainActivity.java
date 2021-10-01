package de.microsensys.sampleapp_androidjava.getsensor;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.microsensys.TELID.TELIDSensorInfo;
import de.microsensys.exceptions.MssException;
import de.microsensys.functions.RFIDFunctions_3000;
import de.microsensys.utils.BluetoothDeviceScan;
import de.microsensys.utils.ReaderIDInfo;

public class MainActivity extends AppCompatActivity {

    Spinner sp_DeviceToConnect;
    Button bt_Connect;
    Button bt_Disconnect;
    RadioGroup rg_ScanType;
    RadioButton rb_AutoScan;
    ConstraintLayout cl_ManualSelect;
    EditText et_ManualPhSize;
    TextView tv_LibInfo;
    TextView tv_ReaderStatus;
    TextView tv_ReaderInfo;
    Button bt_Start;
    Button bt_Stop;
    TextView tv_LastResult;
    TextView tv_LastSerNo;
    TextView tv_LastType;
    TextView tv_LastTimestamp;
    EditText et_Logging;

    RFIDFunctions_3000 mRFIDFunctions;
    private CheckConnectingReaderThread mCheckThread;
    private ScanThread mScanThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sp_DeviceToConnect = findViewById(R.id.spinner_device);
        bt_Connect = findViewById(R.id.button_connect);
        bt_Connect.setOnClickListener(v -> connect());
        bt_Disconnect = findViewById(R.id.button_disconnect);
        bt_Disconnect.setEnabled(false);
        bt_Disconnect.setOnClickListener(v -> disconnect());
        rg_ScanType = findViewById(R.id.radiogroupScanType);
        cl_ManualSelect = findViewById(R.id.layoutManualSelect);
        rb_AutoScan = findViewById(R.id.radio_Auto);
        rb_AutoScan.setOnCheckedChangeListener((buttonView, isChecked) -> et_ManualPhSize.setEnabled(!isChecked));
        et_ManualPhSize = findViewById(R.id.et_PhSize);
        tv_LibInfo = findViewById(R.id.tv_LibInfo);
        tv_LibInfo.setText(String.format("V %s", de.microsensys.LibraryVersion.getVersionNumber()));
        tv_ReaderStatus = findViewById(R.id.tv_ReaderStatus);
        tv_ReaderInfo = findViewById(R.id.tv_ReaderInfo);
        bt_Start = findViewById(R.id.button_start);
        bt_Start.setOnClickListener(v -> {
            startScan();
            bt_Start.setEnabled(false);
            bt_Stop.setEnabled(true);
        });
        bt_Stop = findViewById(R.id.button_stop);
        bt_Stop.setOnClickListener(v -> {
            stopScan();
            bt_Start.setEnabled(true);
            bt_Stop.setEnabled(false);
        });
        tv_LastResult = findViewById(R.id.tv_LastResult);
        tv_LastSerNo = findViewById(R.id.tv_SerNo);
        tv_LastType = findViewById(R.id.tv_TelidType);
        tv_LastTimestamp = findViewById(R.id.tv_LastTimestamp);
        et_Logging = findViewById(R.id.edit_logging);

        //Fill spinner with list of paired POCKETwork devices
        List<String> deviceNames = new ArrayList<>();
        BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter!=null){
            //List of connected devices
            List<BluetoothDevice> pairedDevices = BluetoothDeviceScan.getPairedDevices(mAdapter);
            if (pairedDevices.size()>0){
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getName().startsWith("iID "))
                        deviceNames.add(device.getName());
                }
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item, deviceNames
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp_DeviceToConnect.setAdapter(adapter);
        rb_AutoScan.setChecked(true);
        setUiConnected(false);
    }

    private void appendResultText(String _toAppend){
        appendResultText(_toAppend, true);
    }
    private void appendResultText(final String _toAppend, final boolean _autoAppendNewLine){
        new Handler(Looper.getMainLooper()).post(() -> {
            if (_autoAppendNewLine)
                et_Logging.append(_toAppend + "\n");
            else
                et_Logging.append(_toAppend);
        });
    }

    private void connect() {
        et_Logging.setText("");

        //Before opening a new communication port, make sure that previous instance is disposed
        disposeRfidFunctions();
        if (sp_DeviceToConnect.getSelectedItemPosition() == -1){
            //TODO notify user to select a device to connect to!
            return;
        }
        sp_DeviceToConnect.setEnabled(false);

        //Initialize SpcInterfaceControl instance.
        //  PortType = 2 --> Bluteooth
        //  PortName = selected device in Spinner --> Device name as shown in Settings
        mRFIDFunctions = new RFIDFunctions_3000(
                getApplicationContext(),    //Context
                2);
        mRFIDFunctions.setPortName(sp_DeviceToConnect.getSelectedItem().toString());

        //Try to open communication port. This call does not block!!
        try {
            mRFIDFunctions.initialize();
            //No exception --> Check for process in a separate thread
            et_Logging.append("Connecting...");
            startCheckConnectingThread();
            bt_Connect.setEnabled(false);
            bt_Disconnect.setEnabled(true);
        } catch (MssException e) {
            e.printStackTrace();

            et_Logging.append("Error opening port.");
            sp_DeviceToConnect.setEnabled(true);
        }
    }
    private void connectProcedureFinished(boolean _connected, final int _readerID) {
        //Open process finished
        if (_connected) {
            // Communication port is open
            new Handler(Looper.getMainLooper()).post(() -> {
                et_Logging.append("\nCONNECTED\n");
                if (_readerID > 0) {
                    tv_ReaderInfo.setText(String.format(Locale.getDefault(), "ReaderID: %d", _readerID));
                    tv_ReaderStatus.setBackgroundColor(Color.GREEN);
                }
                else
                    tv_ReaderStatus.setBackgroundColor(Color.YELLOW);
                setUiConnected(true);
            });
        } else {
            // Communication port is open
            new Handler(Looper.getMainLooper()).post(() -> {
                et_Logging.append("\n Reader NOT connected \n");
                setUiConnected(false);
            });
        }
    }
    private void sensorFound(final TELIDSensorInfo _sensorInfo) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (_sensorInfo != null) {
                tv_LastResult.setBackgroundColor(Color.GREEN);
                tv_LastSerNo.setText(_sensorInfo.getSerialNumber());
                tv_LastType.setText(_sensorInfo.getTELIDDescription());
                tv_LastTimestamp.setText(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
                et_Logging.setText("");
                for(String sensorValue : _sensorInfo.getSensorValueStrings()){
                    et_Logging.append(sensorValue + "\n");
                }
            }
            else{
                tv_LastResult.setBackgroundColor(Color.RED);
            }
        });
    }

    private void setUiConnected(boolean _isConnected) {
        if (_isConnected){
            bt_Start.setEnabled(true);
            rg_ScanType.setEnabled(true);
            cl_ManualSelect.setEnabled(true);
        }
        else{
            sp_DeviceToConnect.setEnabled(true);
            bt_Connect.setEnabled(true);

            bt_Disconnect.setEnabled(false);
            bt_Start.setEnabled(false);
            tv_ReaderInfo.setText("");
            tv_ReaderStatus.setBackgroundColor(Color.TRANSPARENT);
            rg_ScanType.setEnabled(false);
            cl_ManualSelect.setEnabled(false);
        }
    }

    private void disconnect() {
        disposeCheckConnectingThread();
        disposeScanThread();
        disposeRfidFunctions();
        setUiConnected(false);
        et_Logging.append("\n DISCONNECTED");
    }
    private void disposeRfidFunctions(){
        if (mRFIDFunctions != null)
            mRFIDFunctions.terminate();
        mRFIDFunctions = null;
    }
    private void disposeCheckConnectingThread(){
        if (mCheckThread != null){
            mCheckThread.cancel();
        }
        mCheckThread = null;
    }
    private void disposeScanThread(){
        if (mScanThread != null){
            mScanThread.cancel();
        }
        mScanThread = null;
    }

    private void startScan() {
        disposeScanThread();
        if (rb_AutoScan.isChecked())
            mScanThread = new ScanThread();
        else
            mScanThread = new ScanThread(Byte.parseByte(et_ManualPhSize.getText().toString()));
        mScanThread.start();
    }
    private void stopScan() {
        disposeScanThread();
    }

    private void startCheckConnectingThread() {
        disposeCheckConnectingThread();
        mCheckThread = new CheckConnectingReaderThread();
        mCheckThread.start();
    }
    private class CheckConnectingReaderThread extends Thread {
        private boolean crt_loop;

        CheckConnectingReaderThread(){
            crt_loop = true;
        }

        @Override
        public void run() {
            while (crt_loop){
                if (mRFIDFunctions.isConnecting()){
                    //Still trying to connect -> Wait and continue
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    appendResultText(".", false);
                    continue;
                }
                //Connecting finished! Check if connected or not connected
                ReaderIDInfo readerId = null;
                if (mRFIDFunctions.isConnected()){
                    try {
                        readerId = mRFIDFunctions.readReaderID();
                        if (readerId != null) {
                            connectProcedureFinished(true, readerId.getReaderID());
                        }
                    }
                    catch (Exception ignore){}
                }
                if (readerId == null)
                    connectProcedureFinished(false, 0);

                //Stop thread
                cancel();
            }
        }

        void cancel(){
            crt_loop = false;
        }
    }
    private class ScanThread extends Thread {
        private boolean st_loop;
        private boolean st_Auto = true;
        private byte st_phSize;

        ScanThread(){
            st_loop = true;
        }
        ScanThread(byte _phSize){
            st_loop = true;
            st_Auto = false;
            st_phSize = _phSize;
        }

        @Override
        public void run() {
            TELIDSensorInfo sensorInfo;

            while (st_loop){
                sensorInfo = null;
                if (st_Auto){
                    try {
                        sensorInfo = mRFIDFunctions.getSensorData(0xFC);
                    } catch (MssException e) {
                        e.printStackTrace();
                    }
                }
                else{
                    try {
                        sensorInfo = mRFIDFunctions.getSensorData(st_phSize);
                    } catch (MssException e) {
                        e.printStackTrace();
                    }
                }
                if (st_loop){
                    sensorFound(sensorInfo);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        void cancel(){
            st_loop = false;
        }
    }
}
package dev.bessems.usbserial;

import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.BinaryMessenger;

public class UsbSerialPortAdapter implements MethodCallHandler, EventChannel.StreamHandler {

    private final String TAG = UsbSerialPortAdapter.class.getSimpleName();

    private int m_InterfaceId;
    private UsbDeviceConnection m_Connection;
    private UsbSerialDevice m_SerialDevice;
    private BinaryMessenger m_Messenger;
    private String m_MethodChannelName;
    private EventChannel.EventSink m_EventSink;
    private Handler m_handler;

    UsbSerialPortAdapter(BinaryMessenger messenger, int interfaceId, UsbDeviceConnection connection, UsbSerialDevice serialDevice) {
        m_Messenger = messenger;
        m_InterfaceId = interfaceId;
        m_Connection = connection;
        m_SerialDevice = serialDevice;
        m_MethodChannelName = "usb_serial/UsbSerialPortAdapter/" + String.valueOf(interfaceId);
        m_handler = new Handler(Looper.getMainLooper());
        final MethodChannel channel = new MethodChannel(m_Messenger, m_MethodChannelName);
        channel.setMethodCallHandler(this);
        final EventChannel eventChannel = new EventChannel(m_Messenger, m_MethodChannelName + "/stream");
        eventChannel.setStreamHandler(this);
    }

    String getMethodChannelName() {
        return m_MethodChannelName;
    }

    private void setPortParameters(int baudRate, int dataBits, int stopBits, int parity) {
        m_SerialDevice.setBaudRate(baudRate);
        m_SerialDevice.setDataBits(dataBits);
        m_SerialDevice.setStopBits(stopBits);
        m_SerialDevice.setParity(parity);
    }

    private void setFlowControl( int flowControl ) {
        m_SerialDevice.setFlowControl(flowControl);
    }

    private UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {

        @Override
        public void onReceivedData(byte[] arg0)
        {
            if ( m_EventSink != null ) {
                m_handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if ( m_EventSink != null ) {
                            m_EventSink.success(arg0);
                        }
                    }
                });
            }
        }

    };

    private Boolean open() {
        if ( m_SerialDevice.open() ) {
            m_SerialDevice.read(mCallback);
            return true;
        } else {
            return false;
        }
    }

    private Boolean close() {
        m_SerialDevice.close();
        return true;
    }

    private void write( byte[] data ) {
        m_SerialDevice.write(data);
    }

    // return true if the object is to be kept, false if it is to be destroyed.
    public void onMethodCall(MethodCall call, Result result) {
        try {
            switch (call.method) {
                case "close":
                    result.success(close());
                    break;
                case "open":
                    result.success(open());
                    break;
                case "write": {
                    byte[] data = call.argument("data");
                    if (data == null) {
                        result.error(UsbErrorCode.INVALID_ARGUMENT, "Missing or invalid 'data' argument for write.", null);
                        return;
                    }
                    write(data);
                    result.success(true);
                    break;
                }

                case "setPortParameters": {
                    Integer baudRate = call.argument("baudRate");
                    Integer dataBits = call.argument("dataBits");
                    Integer stopBits = call.argument("stopBits");
                    Integer parity = call.argument("parity");
                    if (baudRate == null || dataBits == null || stopBits == null || parity == null) {
                        result.error(UsbErrorCode.INVALID_ARGUMENT, "Missing argument for setPortParameters.", null);
                        return;
                    }
                    setPortParameters(baudRate, dataBits, stopBits, parity);
                    result.success(null);
                    break;
                }

                case "setFlowControl": {
                    Integer flowControl = call.argument("flowControl");
                    if (flowControl == null) {
                        result.error(UsbErrorCode.INVALID_ARGUMENT, "Missing 'flowControl' argument.", null);
                        return;
                    }
                    setFlowControl(flowControl);
                    result.success(null);
                    break;
                }

                case "setDTR": {
                    Boolean v = call.argument("value");
                    if (v == null) {
                        result.error(UsbErrorCode.INVALID_ARGUMENT, "Missing 'value' argument for setDTR.", null);
                        return;
                    }
                    m_SerialDevice.setDTR(v);
                    Log.d(TAG, "set DTR to " + v);
                    result.success(null);
                    break;
                }
                case "setRTS": {
                    Boolean v = call.argument("value");
                    if (v == null) {
                        result.error(UsbErrorCode.INVALID_ARGUMENT, "Missing 'value' argument for setRTS.", null);
                        return;
                    }
                    m_SerialDevice.setRTS(v);
                    result.success(null);
                    break;
                }

                default:
                    result.notImplemented();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied in '" + call.method + "': " + e.getMessage(), e);
            result.error(UsbErrorCode.PERMISSION_DENIED, "Permission denied during " + call.method + ": " + e.getMessage(), null);
        } catch (Exception e) {
            Log.e(TAG, "Error in '" + call.method + "': " + e.getMessage(), e);
            result.error(UsbErrorCode.DEVICE_DISCONNECTED, "Device communication failed during " + call.method + ": " + e.getMessage(), null);
        }
    }

    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
        m_EventSink = eventSink;

    }

    @Override
    public void onCancel(Object o) {
        m_EventSink = null;

    }
}

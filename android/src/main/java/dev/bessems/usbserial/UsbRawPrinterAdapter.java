package dev.bessems.usbserial;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * Raw USB bulk transfer adapter for printers that don't use serial protocols.
 * Communicates directly via USB bulk OUT endpoint.
 */
public class UsbRawPrinterAdapter implements MethodCallHandler, EventChannel.StreamHandler {

    private static final String TAG = "UsbRawPrinterAdapter";
    private static final int TRANSFER_TIMEOUT_MS = 5000;

    private final UsbDeviceConnection m_Connection;
    private final UsbDevice m_Device;
    private final String m_MethodChannelName;
    private UsbInterface m_Interface;
    private UsbEndpoint m_EndpointOut;
    private boolean m_IsOpen = false;

    UsbRawPrinterAdapter(BinaryMessenger messenger, int interfaceId, UsbDeviceConnection connection, UsbDevice device) {
        m_Connection = connection;
        m_Device = device;
        m_MethodChannelName = "usb_serial/UsbSerialPortAdapter/" + interfaceId;

        MethodChannel channel = new MethodChannel(messenger, m_MethodChannelName);
        channel.setMethodCallHandler(this);

        EventChannel eventChannel = new EventChannel(messenger, m_MethodChannelName + "/stream");
        eventChannel.setStreamHandler(this);
    }

    String getMethodChannelName() {
        return m_MethodChannelName;
    }

    private void open(Result result) {
        try {
            // Find bulk OUT endpoint
            for (int i = 0; i < m_Device.getInterfaceCount(); i++) {
                UsbInterface usbInterface = m_Device.getInterface(i);
                Log.d(TAG, "Interface " + i + ": class=" + usbInterface.getInterfaceClass()
                        + " subclass=" + usbInterface.getInterfaceSubclass()
                        + " protocol=" + usbInterface.getInterfaceProtocol()
                        + " endpoints=" + usbInterface.getEndpointCount());

                for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                    UsbEndpoint endpoint = usbInterface.getEndpoint(j);
                    Log.d(TAG, "  Endpoint " + j + ": type=" + endpoint.getType()
                            + " direction=" + endpoint.getDirection());

                    if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                            && endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                        m_Interface = usbInterface;
                        m_EndpointOut = endpoint;
                        break;
                    }
                }
                if (m_EndpointOut != null)
                    break;
            }

            if (m_EndpointOut == null) {
                Log.e(TAG, "No bulk OUT endpoint found");
                result.error(UsbErrorCode.NO_ENDPOINT_FOUND, "No bulk OUT endpoint found on the device.", null);
                return;
            }

            boolean claimed = m_Connection.claimInterface(m_Interface, true);
            Log.d(TAG, "Interface claimed: " + claimed);
            if (!claimed) {
                result.error(UsbErrorCode.INTERFACE_CLAIM_FAILED, "Failed to claim USB interface.", null);
                return;
            }

            m_IsOpen = true;
            Log.d(TAG, "Raw USB printer opened successfully");
            result.success(true);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied during open: " + e.getMessage(), e);
            result.error(UsbErrorCode.PERMISSION_DENIED, "Permission denied: " + e.getMessage(), null);
        } catch (Exception e) {
            Log.e(TAG, "Error during open: " + e.getMessage(), e);
            result.error(UsbErrorCode.DEVICE_DISCONNECTED, "Device communication failed during open: " + e.getMessage(), null);
        }
    }

    private void close(Result result) {
        try {
            if (m_Interface != null) {
                m_Connection.releaseInterface(m_Interface);
            }
            m_Connection.close();
            m_IsOpen = false;
            result.success(true);
        } catch (Exception e) {
            Log.e(TAG, "Error during close: " + e.getMessage(), e);
            m_IsOpen = false;
            result.error(UsbErrorCode.DEVICE_DISCONNECTED, "Device communication failed during close: " + e.getMessage(), null);
        }
    }

    private void write(byte[] data, Result result) {
        if (!m_IsOpen || m_EndpointOut == null) {
            Log.e(TAG, "Cannot write: device not open");
            result.error(UsbErrorCode.DEVICE_NOT_OPEN, "Cannot write: device is not open.", null);
            return;
        }

        try {
            int offset = 0;
            int maxPacketSize = m_EndpointOut.getMaxPacketSize();
            while (offset < data.length) {
                int length = Math.min(data.length - offset, maxPacketSize);
                byte[] chunk = new byte[length];
                System.arraycopy(data, offset, chunk, 0, length);
                int transferred = m_Connection.bulkTransfer(m_EndpointOut, chunk, length, TRANSFER_TIMEOUT_MS);
                if (transferred < 0) {
                    Log.e(TAG, "Bulk transfer failed at offset " + offset);
                    result.error(UsbErrorCode.TRANSFER_FAILED, "Bulk transfer failed at offset " + offset + ". Device may be disconnected.", null);
                    return;
                }
                offset += transferred;
            }
            result.success(true);
        } catch (Exception e) {
            Log.e(TAG, "Error during write: " + e.getMessage(), e);
            result.error(UsbErrorCode.DEVICE_DISCONNECTED, "Device communication failed during write: " + e.getMessage(), null);
        }
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink eventSink) {
        // Raw printers don't receive data, so nothing to stream
    }

    @Override
    public void onCancel(Object arguments) {
        // No-op
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        switch (call.method) {
            case "open":
                open(result);
                break;
            case "close":
                close(result);
                break;
            case "write": {
                byte[] data = call.argument("data");
                if (data == null) {
                    result.error(UsbErrorCode.INVALID_ARGUMENT, "Missing or invalid 'data' argument for write.", null);
                    return;
                }
                write(data, result);
                break;
            }
            case "setPortParameters":
            case "setFlowControl":
            case "setDTR":
            case "setRTS":
                // No-op for raw USB printers, but don't fail
                result.success(null);
                break;
            default:
                result.notImplemented();
                break;
        }
    }
}

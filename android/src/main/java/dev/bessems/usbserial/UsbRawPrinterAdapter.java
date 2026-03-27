package dev.bessems.usbserial;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * Raw USB bulk transfer adapter for printers that don't use serial protocols.
 * Communicates directly via USB bulk OUT endpoint.
 */
public class UsbRawPrinterAdapter implements MethodCallHandler {

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
    }

    String getMethodChannelName() {
        return m_MethodChannelName;
    }

    private boolean open() {
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
            return false;
        }

        boolean claimed = m_Connection.claimInterface(m_Interface, true);
        Log.d(TAG, "Interface claimed: " + claimed);
        if (!claimed) {
            return false;
        }

        m_IsOpen = true;
        Log.d(TAG, "Raw USB printer opened successfully");
        return true;
    }

    private boolean close() {
        if (m_Interface != null) {
            m_Connection.releaseInterface(m_Interface);
        }
        m_Connection.close();
        m_IsOpen = false;
        return true;
    }

    private void write(byte[] data) {
        if (!m_IsOpen || m_EndpointOut == null) {
            Log.e(TAG, "Cannot write: device not open");
            return;
        }

        int offset = 0;
        int maxPacketSize = m_EndpointOut.getMaxPacketSize();
        while (offset < data.length) {
            int length = Math.min(data.length - offset, maxPacketSize);
            byte[] chunk = new byte[length];
            System.arraycopy(data, offset, chunk, 0, length);
            int transferred = m_Connection.bulkTransfer(m_EndpointOut, chunk, length, TRANSFER_TIMEOUT_MS);
            if (transferred < 0) {
                Log.e(TAG, "Bulk transfer failed at offset " + offset);
                return;
            }
            offset += transferred;
        }
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        switch (call.method) {
            case "open":
                result.success(open());
                break;
            case "close":
                result.success(close());
                break;
            case "write":
                write((byte[]) call.argument("data"));
                result.success(true);
                break;
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

package dev.bessems.usbserial;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.Handler;
import android.os.Looper;
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
    private final Handler m_MainHandler = new Handler(Looper.getMainLooper());
    private UsbInterface m_Interface;
    private UsbEndpoint m_EndpointOut;
    private UsbEndpoint m_EndpointIn;
    private boolean m_IsOpen = false;
    private volatile EventChannel.EventSink m_EventSink;
    private volatile boolean m_Reading = false;
    private Thread m_ReaderThread;

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
            // Find an interface that has a bulk OUT endpoint, then look for
            // a bulk IN endpoint on the same interface (USB Printer Class
            // bidirectional devices report both — protocol=2/3 — and the IN
            // endpoint carries ASB status pushes from the printer).
            for (int i = 0; i < m_Device.getInterfaceCount(); i++) {
                UsbInterface usbInterface = m_Device.getInterface(i);
                Log.d(TAG, "Interface " + i + ": class=" + usbInterface.getInterfaceClass()
                        + " subclass=" + usbInterface.getInterfaceSubclass()
                        + " protocol=" + usbInterface.getInterfaceProtocol()
                        + " endpoints=" + usbInterface.getEndpointCount());

                UsbEndpoint candidateOut = null;
                UsbEndpoint candidateIn = null;
                for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                    UsbEndpoint endpoint = usbInterface.getEndpoint(j);
                    Log.d(TAG, "  Endpoint " + j + ": type=" + endpoint.getType()
                            + " direction=" + endpoint.getDirection());

                    if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;

                    if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT && candidateOut == null) {
                        candidateOut = endpoint;
                    } else if (endpoint.getDirection() == UsbConstants.USB_DIR_IN && candidateIn == null) {
                        candidateIn = endpoint;
                    }
                }

                if (candidateOut != null) {
                    m_Interface = usbInterface;
                    m_EndpointOut = candidateOut;
                    m_EndpointIn = candidateIn;
                    if (candidateIn != null) {
                        Log.d(TAG, "Bulk IN endpoint found: maxPacketSize=" + candidateIn.getMaxPacketSize());
                    } else {
                        Log.d(TAG, "No bulk IN endpoint on this interface — reads disabled");
                    }
                    break;
                }
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

            // If a bulk IN endpoint is present, start the reader thread so
            // ASB / status-query responses are forwarded to the Dart side
            // via the existing EventChannel.
            if (m_EndpointIn != null) {
                startReader();
            }

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
            stopReader();
            if (m_Interface != null) {
                m_Connection.releaseInterface(m_Interface);
            }
            m_Connection.close();
            m_IsOpen = false;
            // Reset endpoint references so a subsequent reopen on a
            // re-attached device starts from a clean state instead of
            // pointing at stale UsbEndpoint/UsbInterface objects.
            m_Interface = null;
            m_EndpointOut = null;
            m_EndpointIn = null;
            result.success(true);
        } catch (Exception e) {
            Log.e(TAG, "Error during close: " + e.getMessage(), e);
            m_IsOpen = false;
            m_Interface = null;
            m_EndpointOut = null;
            m_EndpointIn = null;
            result.error(UsbErrorCode.DEVICE_DISCONNECTED, "Device communication failed during close: " + e.getMessage(), null);
        }
    }

    private void startReader() {
        if (m_EndpointIn == null || m_Reading || !m_IsOpen) return;
        final UsbEndpoint endpoint = m_EndpointIn;
        // Cap buffer at a reasonable size — ASB packets are 4 bytes, but the
        // endpoint may have larger maxPacketSize. We just want enough to
        // grab a full packet in one transfer.
        final int bufferSize = Math.max(64, endpoint.getMaxPacketSize());
        m_Reading = true;
        m_ReaderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[bufferSize];
                while (m_Reading) {
                    int transferred;
                    try {
                        // Short timeout so the thread can exit promptly on stopReader().
                        transferred = m_Connection.bulkTransfer(endpoint, buffer, bufferSize, 1000);
                    } catch (Exception e) {
                        Log.e(TAG, "Reader thread bulkTransfer failed: " + e.getMessage());
                        break;
                    }
                    if (transferred > 0) {
                        final byte[] payload = new byte[transferred];
                        System.arraycopy(buffer, 0, payload, 0, transferred);
                        m_MainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                EventChannel.EventSink sink = m_EventSink;
                                if (sink != null) sink.success(payload);
                            }
                        });
                    }
                    // transferred < 0 → timeout / no data — loop again.
                }
                Log.d(TAG, "Reader thread exiting");
            }
        }, "UsbRawPrinterAdapter-Reader");
        m_ReaderThread.setDaemon(true);
        m_ReaderThread.start();
        Log.d(TAG, "Reader thread started (bufferSize=" + bufferSize + ")");
    }

    private void stopReader() {
        if (!m_Reading) return;
        m_Reading = false;
        if (m_ReaderThread != null) {
            try {
                m_ReaderThread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            m_ReaderThread = null;
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
        // Bidirectional USB Printer Class devices (protocol=2/3) expose a
        // bulk IN endpoint — when the Dart side subscribes, hand it the
        // sink so the already-running reader thread can forward incoming
        // bytes. Devices without a bulk IN endpoint will simply never
        // emit any events.
        m_EventSink = eventSink;
    }

    @Override
    public void onCancel(Object arguments) {
        m_EventSink = null;
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

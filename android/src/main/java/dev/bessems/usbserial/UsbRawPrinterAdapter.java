package dev.bessems.usbserial;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Raw USB bulk transfer adapter for printers that don't use serial protocols.
 * Communicates directly via USB bulk OUT endpoint.
 *
 * Transfers go through {@link UsbRequest} with direct buffers (API 26+), not
 * {@link UsbDeviceConnection#bulkTransfer}. bulkTransfer pins its Java array
 * as a JNI critical section for the whole transfer, which holds off the
 * garbage collector — and every thread that touches a pinned array meanwhile,
 * the main thread included — for as long as the printer takes to answer: up
 * to the 1 s read timeout on every idle read, up to the 5 s write timeout on a
 * printer that stopped accepting data. Older systems keep bulkTransfer.
 */
public class UsbRawPrinterAdapter implements MethodCallHandler, EventChannel.StreamHandler {

    private static final String TAG = "UsbRawPrinterAdapter";
    private static final int TRANSFER_TIMEOUT_MS = 5000;

    // Some printers answer every IN poll with their status byte, thousands of
    // times a second. Reads are paced and the bytes reach Dart in batches, so
    // neither the reader nor the main thread — every event to Dart passes
    // through it — can be flooded. Both delays stay far below the timeouts of
    // the status queries the Dart side makes (hundreds of ms).
    private static final long READ_PAUSE_MS = 20;
    private static final long DELIVERY_INTERVAL_MS = 50;

    // Such a printer repeats the same status byte even when nobody asked.
    // Bytes reach Dart for this long after each write — the answer to a status
    // query, the first ASB packet after enabling it — and after a read that
    // differs from the previous one, so a change of state still gets through.
    // Endless repeats of an unchanged state do not. Longer than the longest
    // status query timeout on the Dart side.
    private static final long ANSWER_WINDOW_MS = 3000;

    // How long the reader blocks in requestWait before it rechecks whether it
    // should stop. Bounds how long close() waits for the reader.
    private static final long WAIT_SLICE_MS = 250;

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

    // Bytes read but not handed to Dart yet, and when the last batch went.
    // Touched by the reader thread only.
    private final ByteArrayOutputStream m_Pending = new ByteArrayOutputStream();
    private long m_LastDeliveryAt = 0;
    private byte[] m_LastRead = null;

    // Uptime until which everything read goes to Dart; set by every write.
    private volatile long m_AnswerWindowUntil = 0;

    // While the reader runs it is the only thread that reaps requests on the
    // connection (requestWait returns whichever request finished, so two
    // reapers would steal each other's results). The writes it reaps for are
    // tracked here, so the reader can fail them when it stops.
    private volatile boolean m_ReaderReaps = false;
    private final Set<OutTransfer> m_PendingWrites = new HashSet<>();

    // Single worker thread: keeps writes in order while keeping the blocking
    // bulk transfers off the main thread. A full receipt is hundreds of
    // 64-byte transfers, each with a 5 s timeout.
    private final ExecutorService m_Executor = Executors.newSingleThreadExecutor();

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

    private static boolean usesRequests() {
        // requestWait(timeout) and queue(ByteBuffer) without a length are API 26.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
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
        m_ReaderReaps = usesRequests();
        m_ReaderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (usesRequests()) {
                        readWithRequests(endpoint, bufferSize);
                    } else {
                        readWithBulkTransfer(endpoint, bufferSize);
                    }
                } finally {
                    m_ReaderReaps = false;
                    failPendingWrites();
                    Log.d(TAG, "Reader thread exiting");
                }
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

    // Runs on the reader thread: keeps one IN request queued (at most one
    // every READ_PAUSE_MS) and reaps every request that finishes on the
    // connection, the writes' included.
    private void readWithRequests(UsbEndpoint endpoint, int bufferSize) {
        UsbRequest in = new UsbRequest();
        if (!in.initialize(m_Connection, endpoint)) {
            Log.e(TAG, "Reader: IN request could not be initialized");
            return;
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
        boolean inQueued = false;
        long nextReadAt = 0;

        try {
            while (m_Reading) {
                long now = SystemClock.uptimeMillis();

                if (!inQueued && now >= nextReadAt) {
                    buffer.clear();
                    if (!in.queue(buffer)) {
                        Log.e(TAG, "Reader: IN request could not be queued");
                        return;
                    }
                    inQueued = true;
                }

                long wait = inQueued ? WAIT_SLICE_MS : Math.max(1, nextReadAt - now);
                if (m_Pending.size() > 0) {
                    wait = Math.min(wait, Math.max(1, m_LastDeliveryAt + DELIVERY_INTERVAL_MS - now));
                }

                UsbRequest done;
                try {
                    done = m_Connection.requestWait(wait);
                } catch (TimeoutException e) {
                    deliverIfDue();
                    continue;
                }

                if (done == null) {
                    // The connection failed — the device left the bus or was closed.
                    Log.e(TAG, "Reader: requestWait failed, stopping");
                    return;
                }

                if (done == in) {
                    inQueued = false;
                    int received = buffer.position();
                    if (received > 0) {
                        byte[] data = new byte[received];
                        buffer.flip();
                        buffer.get(data);
                        collect(data);
                    }
                    // Also after an empty answer, or a device sending zero-length
                    // packets would spin the reader.
                    nextReadAt = SystemClock.uptimeMillis() + READ_PAUSE_MS;
                } else {
                    Object clientData = done.getClientData();
                    if (clientData instanceof OutTransfer) {
                        ((OutTransfer) clientData).complete();
                    }
                }

                deliverIfDue();
            }
        } finally {
            if (inQueued) {
                in.cancel();
                // The cancelled request must be reaped before it is freed, or
                // the kernel would still hold a pointer into it.
                inQueued = !reapCancelled(in);
            }
            if (!inQueued) {
                in.close();
            }
        }
    }

    // Runs on the reader thread on systems without requestWait(timeout).
    private void readWithBulkTransfer(UsbEndpoint endpoint, int bufferSize) {
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
                byte[] data = new byte[transferred];
                System.arraycopy(buffer, 0, data, 0, transferred);
                collect(data);
                deliverIfDue();
                SystemClock.sleep(READ_PAUSE_MS);
            } else {
                // transferred < 0 → timeout / no data — loop again.
                deliverIfDue();
            }
        }
    }

    // Reaps [cancelled] after cancel(), finishing any write reaped meanwhile.
    private boolean reapCancelled(UsbRequest cancelled) {
        long deadline = SystemClock.uptimeMillis() + 1000;
        while (SystemClock.uptimeMillis() < deadline) {
            UsbRequest done;
            try {
                done = m_Connection.requestWait(WAIT_SLICE_MS);
            } catch (TimeoutException e) {
                continue;
            }
            if (done == null) return false;
            if (done == cancelled) return true;
            Object clientData = done.getClientData();
            if (clientData instanceof OutTransfer) {
                ((OutTransfer) clientData).complete();
            }
        }
        return false;
    }

    private void collect(byte[] data) {
        long now = SystemClock.uptimeMillis();
        boolean repeated = Arrays.equals(data, m_LastRead);
        m_LastRead = data;

        // A change opens the window as well: a reader that assembles packets
        // from the stream (the ASB monitor takes 4 bytes) gets whole ones of
        // the new state, not a single byte it throws away.
        if (!repeated) {
            m_AnswerWindowUntil = Math.max(m_AnswerWindowUntil, now + ANSWER_WINDOW_MS);
        }

        // Nobody listens on the Dart side: there is no one to deliver to.
        if (m_EventSink == null) {
            m_Pending.reset();
            return;
        }

        if (now > m_AnswerWindowUntil) {
            return;
        }

        m_Pending.write(data, 0, data.length);
    }

    // Hands the collected bytes to Dart as one event, at most once per
    // DELIVERY_INTERVAL_MS. The first bytes after a quiet spell go at once, so
    // the answer to a status query is not held back.
    private void deliverIfDue() {
        if (m_Pending.size() == 0) return;
        long now = SystemClock.uptimeMillis();
        if (now - m_LastDeliveryAt < DELIVERY_INTERVAL_MS) return;

        final byte[] payload = m_Pending.toByteArray();
        m_Pending.reset();
        m_LastDeliveryAt = now;
        m_MainHandler.post(new Runnable() {
            @Override
            public void run() {
                EventChannel.EventSink sink = m_EventSink;
                if (sink != null) sink.success(payload);
            }
        });
    }

    private void write(byte[] data, Result result) {
        if (!m_IsOpen || m_EndpointOut == null) {
            Log.e(TAG, "Cannot write: device not open");
            result.error(UsbErrorCode.DEVICE_NOT_OPEN, "Cannot write: device is not open.", null);
            return;
        }

        // Opened before the bytes go out, so an answer that comes back fast is not missed.
        m_AnswerWindowUntil = SystemClock.uptimeMillis() + ANSWER_WINDOW_MS;

        try {
            int failedAt = usesRequests() ? writeWithRequests(data) : writeWithBulkTransfer(data);
            if (failedAt >= 0) {
                Log.e(TAG, "Bulk transfer failed at offset " + failedAt);
                result.error(UsbErrorCode.TRANSFER_FAILED, "Bulk transfer failed at offset " + failedAt + ". Device may be disconnected.", null);
                return;
            }
            result.success(true);
        } catch (Exception e) {
            Log.e(TAG, "Error during write: " + e.getMessage(), e);
            result.error(UsbErrorCode.DEVICE_DISCONNECTED, "Device communication failed during write: " + e.getMessage(), null);
        }
    }

    // Returns the offset the write failed at, or -1 when all of [data] went out.
    private int writeWithRequests(byte[] data) throws InterruptedException {
        int maxPacketSize = m_EndpointOut.getMaxPacketSize();
        UsbRequest out = new UsbRequest();
        if (!out.initialize(m_Connection, m_EndpointOut)) {
            return 0;
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(maxPacketSize);
        boolean queued = false;
        int offset = 0;

        try {
            while (offset < data.length) {
                int length = Math.min(data.length - offset, maxPacketSize);
                buffer.clear();
                buffer.put(data, offset, length);
                buffer.flip();

                OutTransfer transfer = new OutTransfer();
                out.setClientData(transfer);
                boolean reaper = registerWrite(transfer);

                if (!out.queue(buffer)) {
                    unregisterWrite(transfer);
                    return offset;
                }
                queued = true;

                boolean finished = reaper
                        ? transfer.await(TRANSFER_TIMEOUT_MS)
                        : reapOwn(out, TRANSFER_TIMEOUT_MS);

                // A request may be freed only once it is reaped, or the
                // kernel would still hold a pointer into it.
                if (!finished) {
                    out.cancel();
                    // The reader reaps the cancelled request, or fails it when it stops.
                    boolean reaped = reaper
                            ? transfer.await(1000) && !transfer.failed
                            : reapOwn(out, 1000);
                    unregisterWrite(transfer);
                    queued = !reaped;
                    return offset;
                }
                unregisterWrite(transfer);

                if (transfer.failed) {
                    // The reader stopped before it reaped this one; nobody else will.
                    out.cancel();
                    queued = !reapOwn(out, 1000);
                    return offset;
                }
                queued = false;

                int transferred = buffer.position();
                if (transferred <= 0) {
                    return offset;
                }
                offset += transferred;
            }
            return -1;
        } finally {
            if (!queued) {
                out.close();
            }
        }
    }

    private int writeWithBulkTransfer(byte[] data) {
        int offset = 0;
        int maxPacketSize = m_EndpointOut.getMaxPacketSize();
        while (offset < data.length) {
            int length = Math.min(data.length - offset, maxPacketSize);
            byte[] chunk = new byte[length];
            System.arraycopy(data, offset, chunk, 0, length);
            int transferred = m_Connection.bulkTransfer(m_EndpointOut, chunk, length, TRANSFER_TIMEOUT_MS);
            if (transferred < 0) {
                return offset;
            }
            offset += transferred;
        }
        return -1;
    }

    // Reaps [request] on this thread; only valid while the reader is not reaping.
    private boolean reapOwn(UsbRequest request, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            long left = deadline - SystemClock.uptimeMillis();
            if (left <= 0) return false;
            UsbRequest done;
            try {
                done = m_Connection.requestWait(left);
            } catch (TimeoutException e) {
                return false;
            }
            if (done == null) return false;
            if (done == request) return true;
        }
    }

    // Whether the reader reaps [transfer]; if so it is tracked until done.
    private boolean registerWrite(OutTransfer transfer) {
        synchronized (m_PendingWrites) {
            if (!m_ReaderReaps) return false;
            m_PendingWrites.add(transfer);
            return true;
        }
    }

    private void unregisterWrite(OutTransfer transfer) {
        synchronized (m_PendingWrites) {
            m_PendingWrites.remove(transfer);
        }
    }

    // The reader stops reaping: every write still waiting on it fails.
    private void failPendingWrites() {
        synchronized (m_PendingWrites) {
            for (OutTransfer transfer : m_PendingWrites) {
                transfer.fail();
            }
            m_PendingWrites.clear();
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
    public void onMethodCall(final MethodCall call, final Result result) {
        final Result mainThreadResult = new MainThreadResult(result);
        m_Executor.execute(new Runnable() {
            @Override
            public void run() {
                handleMethodCall(call, mainThreadResult);
            }
        });
    }

    private void handleMethodCall(MethodCall call, Result result) {
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

    /** One queued OUT request the reader reaps for a write. */
    private static final class OutTransfer {
        private final CountDownLatch m_Done = new CountDownLatch(1);
        volatile boolean failed = false;

        void complete() {
            m_Done.countDown();
        }

        void fail() {
            failed = true;
            m_Done.countDown();
        }

        boolean await(long timeoutMs) throws InterruptedException {
            return m_Done.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }
}

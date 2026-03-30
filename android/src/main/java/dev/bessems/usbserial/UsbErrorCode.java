package dev.bessems.usbserial;

/**
 * Error codes sent via MethodChannel result.error() to the Dart side.
 * These must match the values in UsbErrorCode enum in Dart.
 */
public final class UsbErrorCode {
    private UsbErrorCode() {}

    /** The USB device was disconnected during an operation. */
    public static final String DEVICE_DISCONNECTED = "deviceDisconnected";

    /** The device is not open — call open() first. */
    public static final String DEVICE_NOT_OPEN = "deviceNotOpen";

    /** USB bulk transfer failed (device may still be connected). */
    public static final String TRANSFER_FAILED = "transferFailed";

    /** No suitable USB endpoint was found on the device. */
    public static final String NO_ENDPOINT_FOUND = "noEndpointFound";

    /** Could not claim the USB interface. */
    public static final String INTERFACE_CLAIM_FAILED = "interfaceClaimFailed";

    /** A required method argument is missing or has the wrong type. */
    public static final String INVALID_ARGUMENT = "invalidArgument";

    /** Permission to access the USB device was denied. */
    public static final String PERMISSION_DENIED = "permissionDenied";

    /** The USB device could not be found. */
    public static final String DEVICE_NOT_FOUND = "deviceNotFound";

    /** The USB device could not be opened. */
    public static final String DEVICE_OPEN_FAILED = "deviceOpenFailed";

    /** An unexpected error occurred. */
    public static final String UNKNOWN = "unknown";
}

import 'package:flutter/services.dart';

/// Error codes returned from the native USB layer.
/// These match the values in UsbErrorCode.java.
class UsbErrorCode {
  /// The USB device was disconnected during an operation.
  static const String deviceDisconnected = 'deviceDisconnected';

  /// The device is not open — call open() first.
  static const String deviceNotOpen = 'deviceNotOpen';

  /// USB bulk transfer failed (device may still be connected).
  static const String transferFailed = 'transferFailed';

  /// No suitable USB endpoint was found on the device.
  static const String noEndpointFound = 'noEndpointFound';

  /// Could not claim the USB interface.
  static const String interfaceClaimFailed = 'interfaceClaimFailed';

  /// A required method argument is missing or has the wrong type.
  static const String invalidArgument = 'invalidArgument';

  /// Permission to access the USB device was denied.
  static const String permissionDenied = 'permissionDenied';

  /// The USB device could not be found.
  static const String deviceNotFound = 'deviceNotFound';

  /// The USB device could not be opened.
  static const String deviceOpenFailed = 'deviceOpenFailed';

  /// An unexpected error occurred.
  static const String unknown = 'unknown';

  UsbErrorCode._();
}

/// Exception thrown when a USB operation fails.
///
/// Wraps [PlatformException] and provides a typed [code] for easy matching.
///
/// Example:
/// ```dart
/// try {
///   await port.write(data);
/// } on PlatformException catch (e) {
///   final UsbException usbError = UsbException.fromPlatformException(e);
///   switch (usbError.code) {
///     case UsbErrorCode.deviceDisconnected:
///       print('Device was disconnected');
///       break;
///     case UsbErrorCode.deviceNotOpen:
///       print('Device is not open');
///       break;
///     case UsbErrorCode.transferFailed:
///       print('Transfer failed');
///       break;
///     default:
///       print('USB error: ${usbError.message}');
///   }
/// }
/// ```
class UsbException implements Exception {
  /// The error code string — compare with [UsbErrorCode] constants.
  final String code;

  /// Human-readable error message from the native layer.
  final String message;

  /// Optional details from the native layer.
  final dynamic details;

  UsbException({
    required this.code,
    required this.message,
    this.details,
  });

  /// Creates a [UsbException] from a [PlatformException].
  factory UsbException.fromPlatformException(PlatformException e) {
    return UsbException(
      code: e.code,
      message: e.message ?? 'Unknown USB error',
      details: e.details,
    );
  }

  @override
  String toString() => 'UsbException($code): $message';
}

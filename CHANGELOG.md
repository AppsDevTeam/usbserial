## 0.9.1
* The raw USB printer reader no longer floods the host app's main thread.
  Some printers (seen on VID:PID 1155:22339) answer every bulk IN poll with
  their status byte, thousands of times a second. The reader forwarded each
  read as its own event, posted through the main thread to Dart, which kept the
  main thread 75–90 % busy for as long as the printer was connected — every
  platform channel answer, touch and keyboard event waited behind it. Reads are
  now paced (at most one every 20 ms), forwarded bytes go to Dart in batches
  (at most one event every 50 ms), and nothing is forwarded while nobody
  listens. Repeats of the same bytes are dropped unless they come within 3 s of
  a write (the answer to a status query) or of a change, so a change of state
  still reaches Dart in full. The main thread with such a printer connected now
  costs about the same as without one.
* Raw USB transfers no longer hold off the garbage collector.
  `UsbDeviceConnection.bulkTransfer` pins its Java array as a JNI critical
  section for the whole transfer, which blocks the garbage collector and every
  thread that touches a pinned array meanwhile — up to the 1 s read timeout on
  each idle read, up to the 5 s write timeout on a printer that stopped
  accepting data (measured: 4.3 s). Reads and writes now go through
  `UsbRequest` with direct buffers on API 26+; the reader reaps the writes'
  requests too, since `requestWait` returns whichever request finished. Older
  systems keep `bulkTransfer`.
* The reader stops when the device leaves the bus instead of spinning on the
  failed transfer.

## 0.9.0
* `listDevices()` now reports the USB descriptor data needed to classify a
  device without opening it: `deviceClass`, `deviceSubclass`, `deviceProtocol`
  and a per-interface list carrying interface class/subclass/protocol plus
  whether the interface exposes bulk IN / bulk OUT endpoints. On the Dart side
  these arrive as `UsbDevice.deviceClass`, `UsbDevice.interfaces`
  (`UsbInterfaceInfo`) and the derived `isHub`, `isPrinterClass` and
  `hasBulkOut` helpers. Nothing is filtered — `listDevices()` still returns
  every device the platform enumerates.
* Blocking USB work no longer runs on the main thread. `open()`, `close()`,
  `write()` and the port parameter setters are dispatched to a per-adapter
  single worker thread — one thread, so write ordering is preserved — and
  their results are posted back to the main thread via the new
  `MainThreadResult`. A serial driver handed a device that speaks a different
  protocol blocks on control transfers for seconds, which previously froze the
  host app's UI thread long enough to ANR.
* Concurrent permission requests no longer collide. `PendingIntent` is now
  keyed by device id instead of request code `0` for every device, and the
  receiver checks `EXTRA_DEVICE` before resolving, staying registered while
  the answer belongs to someone else. Previously a single answer resolved
  every pending request, so with two devices in flight the second one silently
  failed to open.

## 0.8.0
* Raw USB printer adapter now claims the bulk IN endpoint when the USB
  Printer Class device exposes one (interface protocol 2/3, bidirectional).
  A background reader thread forwards incoming bytes to the existing
  EventChannel, so the Dart side `port.inputStream` finally produces
  data on these devices — enabling DLE EOT real-time status queries and
  Automatic Status Back (ASB) reception over USB.
* `close()` resets the cached `UsbInterface` / `UsbEndpoint` references so
  a subsequent `open()` on a re-attached device starts from a clean state.

## 0.5.1
* Improve Java handling of null pointers.
* Update gradle version on sample.

## 0.5.0
* Support newer Android version.
* Linter fixes.
* Add device name mapping.
* Add access to maxLen parameter for long serial messages.

## 0.4.0
* Use deviceName (unique and not null) for equal operator and hashCode.

## 0.3.0

* Implemented v2 Android embedding
* Updated UsbSerial to 6.1.0
* Transaction constructor signature changed to fix issue #39 (see test testTwoTransformersInSequence)

## 0.2.991

* Addressed issue #46
* Fixed @immutable error

## 0.2.99

* Merged null safety pull request

## 0.2.4

* Added interfaceCount
* Added CH340 VID/PID to example xml.
* Added exception catch for Device Create

## 0.2.3

* Fixed threading issue ( PR10 )

## 0.2.2

* Fixed 3 dart lint warnings.

## 0.2.1

* Updated the underlying UsbSerial library to 6.0.6
* Updated the examples

## 0.2.0

* Added Transformers and Transactions
* Added unit tests
* Changed Java naming, if your compile fails delete the 'build' directory and try again.

## 0.1.1

* Merged pull request 1: Fix for NoSuchMethodException thrown on api level less than 21
  during device enumeration.

## 0.1.0

* Modified the USB Attached/Detach stream from Stream<String> to Stream<UsbEvent> to
  carry additional information about the device being added or removed.
* Added ACTION_USB_ATTACHED and ACTION_USB_DETACHED constants to dart interface.

## 0.0.2

* Fixed crasher when connected or disconnecting a USB device without having
  a sink attached.

## 0.0.1

* Initial release, providing easy UART access to Android Flutter apps
package dev.bessems.usbserial;

import android.os.Handler;
import android.os.Looper;

import io.flutter.plugin.common.MethodChannel.Result;

/**
 * Wraps a {@link Result} so it can be completed from a worker thread.
 *
 * Flutter requires method channel replies to be delivered on the main thread,
 * while the USB work behind them (interface claim, control transfers, bulk
 * transfers) blocks for seconds and must not run there — a serial driver
 * probing a device that speaks a different protocol can block long enough to
 * trigger an ANR.
 */
class MainThreadResult implements Result {

    private final Result m_Delegate;
    private final Handler m_MainHandler = new Handler(Looper.getMainLooper());

    MainThreadResult(Result delegate) {
        m_Delegate = delegate;
    }

    @Override
    public void success(final Object result) {
        m_MainHandler.post(new Runnable() {
            @Override
            public void run() {
                m_Delegate.success(result);
            }
        });
    }

    @Override
    public void error(final String errorCode, final String errorMessage, final Object errorDetails) {
        m_MainHandler.post(new Runnable() {
            @Override
            public void run() {
                m_Delegate.error(errorCode, errorMessage, errorDetails);
            }
        });
    }

    @Override
    public void notImplemented() {
        m_MainHandler.post(new Runnable() {
            @Override
            public void run() {
                m_Delegate.notImplemented();
            }
        });
    }
}

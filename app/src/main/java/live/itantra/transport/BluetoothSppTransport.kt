package live.itantra.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothSppTransport(context: Context) : Transport {

    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "itantra-bt-write").apply { isDaemon = true }
    }

    private val reader = FrameReader()
    private val running = AtomicBoolean(false)

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var current = LinkStatus()

    private var frameHandler: ((ByteArray) -> Unit)? = null
    private var statusListener: ((LinkStatus) -> Unit)? = null

    override val name: String = "bluetooth-spp"
    override fun status(): LinkStatus = current

    override fun observe(listener: (LinkStatus) -> Unit) {
        statusListener = listener
        listener(current)
    }

    override fun onFrame(handler: (ByteArray) -> Unit) {
        frameHandler = handler
    }

    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter

    val isSupported: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedDevice> = runCatching {
        adapter?.bondedDevices.orEmpty().map { PairedDevice(it.name ?: it.address, it.address) }
    }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    fun listen() {
        val adapter = this.adapter ?: return publish(LinkState.FAILED, "no Bluetooth adapter")
        if (!adapter.isEnabled) return publish(LinkState.FAILED, "Bluetooth is off")
        closeSockets()
        running.set(true)
        publish(LinkState.LISTENING, "waiting for a device to connect")

        Thread({
            try {
                val server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                serverSocket = server
                val accepted = server.accept()
                runCatching { server.close() }
                serverSocket = null
                if (!running.get()) {
                    runCatching { accepted.close() }
                    return@Thread
                }
                attach(accepted)
            } catch (e: IOException) {
                if (running.get()) publish(LinkState.FAILED, e.message ?: "listen failed")
            } catch (e: SecurityException) {
                if (running.get()) publish(LinkState.FAILED, "Bluetooth permission not granted")
            }
        }, "itantra-bt-accept").apply { isDaemon = true }.start()
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val adapter = this.adapter ?: return publish(LinkState.FAILED, "no Bluetooth adapter")
        if (!adapter.isEnabled) return publish(LinkState.FAILED, "Bluetooth is off")
        closeSockets()
        running.set(true)
        publish(LinkState.CONNECTING, address)

        Thread({
            try {
                val device: BluetoothDevice = adapter.getRemoteDevice(address)

                runCatching { adapter.cancelDiscovery() }
                val s = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                s.connect()
                if (!running.get()) {
                    runCatching { s.close() }
                    return@Thread
                }
                attach(s)
            } catch (e: IOException) {
                if (running.get()) publish(LinkState.FAILED, e.message ?: "connect failed")
            } catch (e: SecurityException) {
                if (running.get()) publish(LinkState.FAILED, "Bluetooth permission not granted")
            }
        }, "itantra-bt-connect").apply { isDaemon = true }.start()
    }

    @SuppressLint("MissingPermission")
    private fun attach(s: BluetoothSocket) {
        socket = s
        output = s.outputStream
        reader.reset()
        val peer = runCatching { s.remoteDevice?.name ?: s.remoteDevice?.address }
            .getOrNull() ?: "peer"
        publish(LinkState.CONNECTED, peer)
        pump(s.inputStream)
    }

    private fun pump(input: InputStream) {
        Thread({
            val chunk = ByteArray(READ_BUFFER)
            try {
                while (running.get()) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    reader.append(chunk, read).forEach { frame ->
                        runCatching { frameHandler?.invoke(frame) }
                            .onFailure { Log.e(TAG, "frame handler threw", it) }
                    }
                }
                if (running.get()) publish(LinkState.FAILED, "peer disconnected")
            } catch (e: IOException) {
                if (running.get()) publish(LinkState.FAILED, e.message ?: "link dropped")
            }
        }, "itantra-bt-read").apply { isDaemon = true }.start()
    }

    override fun send(frame: ByteArray) {
        val stream = output ?: return
        io.execute {
            try {
                stream.write(frame)
                stream.flush()
            } catch (e: IOException) {
                publish(LinkState.FAILED, e.message ?: "write failed")
            }
        }
    }

    override fun close() {
        running.set(false)
        closeSockets()
        publish(LinkState.OFFLINE, "")
    }

    private fun closeSockets() {
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        socket = null
        serverSocket = null
        output = null
    }

    private fun publish(state: LinkState, detail: String) {
        current = LinkStatus(state, detail)
        statusListener?.invoke(current)
    }

    data class PairedDevice(val name: String, val address: String)

    companion object {
        private const val TAG = "iTantraLink"
        private const val READ_BUFFER = 512
        private const val SERVICE_NAME = "iTantra"

        val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-1734-4682-9a4e-17a1f0d51701")
    }
}

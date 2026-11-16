package com.example.stylushost

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * Server WebSocket:
 *  - Host -> client : frame JPEG (binary message) + pesan "hello" (text JSON)
 *  - Client -> host : JSON stylus / clear / undo (text message)
 */
class StreamServer(
    port: Int,
    private val listener: Listener
) : WebSocketServer(InetSocketAddress(port)) {

    interface Listener {
        fun onClientCountChanged(count: Int)
        fun onCommand(cmd: StylusCommand)
        fun onServerError(e: Exception)
    }

    init {
        isReuseAddr = true
        isTcpNoDelay = true // latensi rendah
    }

    override fun onStart() {
        connectionLostTimeout = 10
        Log.i(TAG, "Server berjalan di port $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        conn.send("""{"type":"hello","format":"jpeg","coords":"normalized"}""")
        listener.onClientCountChanged(connections.size)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        listener.onClientCountChanged(connections.size)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        StylusParser.parse(message)?.let { listener.onCommand(it) }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
        if (conn == null) listener.onServerError(ex) // error level server (mis. port terpakai)
    }

    /** Kirim frame ke semua client. Frame di-skip untuk client yang lambat (anti-lag). */
    fun broadcastFrame(jpeg: ByteArray) {
        for (c in connections) {
            try {
                if (c.isOpen && !c.hasBufferedData()) c.send(jpeg)
            } catch (e: Exception) {
                Log.w(TAG, "Gagal kirim frame", e)
            }
        }
    }

    companion object { private const val TAG = "StreamServer" }
}

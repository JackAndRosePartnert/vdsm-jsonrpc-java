package org.ovirt.vdsm.jsonrpc.client.reactors;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.ovirt.vdsm.jsonrpc.client.ClientConnectionException;
import org.ovirt.vdsm.jsonrpc.client.utils.OneTimeCallback;

/**
 * Helper object responsible for low level ssl communication.
 *
 */
public class SSLEngineNioHelper {

    private final static int MAX_ATTEMPTS = 10;
    private final SocketChannel channel;
    private final SSLEngine engine;
    private final ByteBuffer appBuffer;
    private final ByteBuffer packetBuffer;
    private final ByteBuffer appPeerBuffer;
    private final ByteBuffer packatPeerBuffer;
    private final SSLClient client;
    private OneTimeCallback callback;

    public SSLEngineNioHelper(SocketChannel channel, SSLEngine engine, OneTimeCallback callback, SSLClient client) {
        this.channel = channel;
        this.engine = engine;
        this.callback = callback;
        this.client = client;
        SSLSession session = engine.getSession();
        this.appBuffer = ByteBuffer.allocate(session.getApplicationBufferSize());
        this.packetBuffer = ByteBuffer.allocate(session.getPacketBufferSize());
        this.appPeerBuffer = ByteBuffer.allocate(session.getApplicationBufferSize());
        this.packatPeerBuffer = ByteBuffer.allocate(session.getPacketBufferSize());
    }

    public void beginHandshake() throws SSLException {
        this.engine.beginHandshake();
    }

    public int read(ByteBuffer buff) throws IOException {
        int read = 0;
        if (this.appPeerBuffer.position() == 0) {
            this.channel.read(this.packatPeerBuffer);
            if (this.packatPeerBuffer.position() == 0) {
                return read;
            }
            this.packatPeerBuffer.flip();
            boolean retry = true;

            while (retry) {
                SSLEngineResult result = this.engine.unwrap(this.packatPeerBuffer, this.appPeerBuffer);
                switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    putBuffer(buff);
                    read += result.bytesProduced();
                    break;
                default:
                    retry = false;
                    read += result.bytesProduced();
                }
                ;
            }
            this.packatPeerBuffer.compact();
        }
        putBuffer(buff);
        return read;
    }

    private void putBuffer(ByteBuffer buff) {
        this.appPeerBuffer.flip();
        final ByteBuffer slice = this.appPeerBuffer.slice();
        if (slice.limit() > buff.remaining()) {
            slice.limit(buff.remaining());
        }

        buff.put(slice);
        this.appPeerBuffer.position(this.appPeerBuffer.position() + slice.limit());
        this.appPeerBuffer.compact();
    }

    public void write(ByteBuffer buff) throws IOException {
        if (buff != this.appBuffer) {
            int attempts = 0;
            while (buff.hasRemaining()) {
                SSLEngineResult result = this.engine.wrap(buff, this.packetBuffer);
                if (SSLEngineResult.Status.CLOSED == result.getStatus()) {
                    return;
                }
                this.packetBuffer.flip();
                while (this.packetBuffer.hasRemaining()) {
                    int written = this.channel.write(this.packetBuffer);
                    if (result.bytesConsumed() == 0 && written == 0) {
                        attempts++;
                        if (attempts > MAX_ATTEMPTS) {
                            // looks like network issue we let higher logic handle timeout
                            this.packetBuffer.clear();
                        }
                    }
                }
                this.packetBuffer.compact();
            }
            return;
        }
        this.appBuffer.flip();
        this.engine.wrap(this.appBuffer, this.packetBuffer);
        this.appBuffer.compact();

        this.packetBuffer.flip();
        this.channel.write(this.packetBuffer);
        this.packetBuffer.compact();

    }

    @SuppressWarnings("incomplete-switch")
    public Runnable process() throws IOException, ClientConnectionException {
        if (!handshakeInProgress()) {
            if (this.callback != null) {
                this.callback.checkAndExecute();
            }

            if (this.getSSLEngine().getUseClientMode()) {
                try {
                    client.getPeerCertificates();
                } catch (Exception e) {
                    // ignore exception if the session is invalid
                }
            }
            return null;
        }

        final SSLEngineResult.HandshakeStatus hs = this.engine.getHandshakeStatus();
        switch (hs) {
        case NEED_UNWRAP:
            this.read(appPeerBuffer);
            this.client.updateLastIncomingHeartbeat();
            return null;
        case NEED_WRAP:
            this.write(appBuffer);
            this.client.updateLastOutgoingHeartbeat();
            return null;
        case NEED_TASK:
            return engine.getDelegatedTask();
        }
        return null;
    }

    public boolean handshakeInProgress() {
        return !SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING.equals(this.engine.getHandshakeStatus());
    }

    public void clearBuff() {
        this.packetBuffer.clear();
    }

    public SSLEngine getSSLEngine() {
        return this.engine;
    }
}

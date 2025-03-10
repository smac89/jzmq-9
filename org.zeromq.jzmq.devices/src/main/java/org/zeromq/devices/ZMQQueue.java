package org.zeromq.devices;

import java.io.Closeable;
import java.io.IOException;

import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;
import org.zeromq.ZMQ.Poller;
import org.zeromq.ZMQ.Socket;
import org.zeromq.ZMQException;

/**
 * ZeroMQ Queue Device implementation.
 *
 * @author Alois Belaska &lt;alois.belaska@gmail.com&gt;
 */
public class ZMQQueue implements Runnable, Closeable {

    private final Poller poller;
    private final Socket inSocket;
    private final Socket outSocket;

    /**
     * Class constructor.
     *
     * @param context a 0MQ context previously created.
     * @param inSocket input socket
     * @param outSocket output socket
     */
    public ZMQQueue(Context context, Socket inSocket, Socket outSocket) {
        this.inSocket = inSocket;
        this.outSocket = outSocket;

        this.poller = new Poller(2);
        this.poller.register(inSocket, ZMQ.Poller.POLLIN);
        this.poller.register(outSocket, ZMQ.Poller.POLLIN);
    }

    /**
     * Queuing of requests and replies.
     */
    @Override
    public void run() {
        byte[] msg = null;
        boolean more = true;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // wait while there are either requests or replies to process
                if (poller.poll(-1) < 0) {
                    break;
                }

                // process a request
                if (poller.pollin(0)) {
                    more = true;
                    while (more) {
                        msg = inSocket.recv(0);

                        more = inSocket.hasReceiveMore();

                        if (msg != null) {
                            outSocket.send(msg, more ? ZMQ.SNDMORE : 0);
                        }
                    }
                }

                // process a reply
                if (poller.pollin(1)) {
                    more = true;
                    while (more) {
                        msg = outSocket.recv(0);

                        more = outSocket.hasReceiveMore();

                        if (msg != null) {
                            inSocket.send(msg, more ? ZMQ.SNDMORE : 0);
                        }
                    }
                }
            } catch (ZMQException e) {
                // context destroyed, exit
                if (ZMQ.Error.ETERM.getCode() == e.getErrorCode()) {
                    break;
                }
                throw e;
            }
        }
    }

    /**
     * Unregisters input and output sockets.
     */
    @Override
    public void close() {
        poller.unregister(this.inSocket);
        poller.unregister(this.outSocket);
    }
}

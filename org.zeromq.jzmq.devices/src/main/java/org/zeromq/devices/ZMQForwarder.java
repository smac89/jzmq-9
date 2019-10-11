package org.zeromq.devices;

import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;
import org.zeromq.ZMQ.Poller;
import org.zeromq.ZMQ.Socket;
import org.zeromq.ZMQException;

/**
 * ZeroMQ Forwarder Device implementation.
 *
 * @author Alois Belaska &lt;alois.belaska@gmail.com&gt;
 */
public class ZMQForwarder implements Runnable {

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
    public ZMQForwarder(Context context, Socket inSocket, Socket outSocket) {
        this.inSocket = inSocket;
        this.outSocket = outSocket;

        this.poller = context.poller(1);
        this.poller.register(inSocket, ZMQ.Poller.POLLIN);
    }

    /**
     * Forwarding messages.
     */
    @Override
    public void run() {
        byte[] msg = null;
        boolean more = true;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // wait while there are requests to process
                if (poller.poll(250000) < 1) {
                    continue;
                }

                msg = inSocket.recv(0);

                more = inSocket.hasReceiveMore();

                if (msg != null) {
                    outSocket.send(msg, more ? ZMQ.SNDMORE : 0);
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
}

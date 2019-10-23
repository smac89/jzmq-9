package org.zeromq.core;

import org.junit.Assert;
import org.junit.Test;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;

public class ZThreadTest {

    @Test
    public void testDetached() {
        ZThread.IDetachedRunnable detached = new ZThread.IDetachedRunnable() {

            @Override
            public void run(Object[] args) {
                ZContext ctx = new ZContext();
                assert (ctx != null);

                Socket push = ctx.createSocket(ZMQ.PUSH);
                assert (push != null);
                ctx.destroy();
            }
        };

        ZThread.start(detached);
    }

    @Test
    public void testFork() {
        ZContext ctx = new ZContext();

        ZThread.IAttachedRunnable attached = new ZThread.IAttachedRunnable() {

            @Override
            public void run(Object[] args, ZContext ctx, Socket pipe) {
                // Create a socket to check it'll be automatically deleted
                ctx.createSocket(ZMQ.PUSH);
                pipe.recvStr(ZMQ.CHARSET);
                pipe.send("pong");
            }
        };

        Socket pipe = ZThread.fork(ctx, attached);
        assert (pipe != null);

        pipe.send("ping");
        String pong = pipe.recvStr(ZMQ.CHARSET);

        Assert.assertEquals(pong, "pong");

        // Everything should be cleanly closed now
        ctx.destroy();
    }
}

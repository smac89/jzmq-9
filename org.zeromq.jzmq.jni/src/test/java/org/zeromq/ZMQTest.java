package org.zeromq;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import javax.xml.bind.DatatypeConverter;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.zeromq.ZMQ.Context;
import org.zeromq.ZMQ.Event;
import org.zeromq.ZMQ.Poller;
import org.zeromq.ZMQ.Socket;

/**
 * @author Cliff Evans
 */
public class ZMQTest {
    private ZMQ.Context context;

    @Rule
    public final ExternalResource contextRule = new ExternalResource() {
        @Override
        protected void before() {
            context = ZMQ.context(1);
        }

        @Override
        protected void after() {
            try {
                context.close();
            } catch (Exception ignore) {
            }
        }
    };

    /**
     * Test method for {@link org.zeromq.ZMQ#makeVersion(int, int, int)}.
     */
    @Test
    public void testMakeVersion() {
        assertEquals(ZMQ.getFullVersion(),
                ZMQ.makeVersion(ZMQ.getMajorVersion(), ZMQ.getMinorVersion(), ZMQ.getPatchVersion()));
    }

    /**
     * Test method for {@link org.zeromq.ZMQ#getVersionString()}.
     */
    @Test
    public void testGetVersion() {
        assertEquals(ZMQ.getMajorVersion() + "." + ZMQ.getMinorVersion() + "." + ZMQ.getPatchVersion(),
                ZMQ.getVersionString());
    }

    /**
     * Test method for {@link org.zeromq.ZMQ.Socket#bindToRandomPort(String)}.
     */
    @Test
    public void testBindToRandomPort() {
        ZMQ.Socket sock = context.socket(ZMQ.DEALER);

        // Check that bindToRandomport generate valid port number
        for (int i = 0; i < 100; i++) {
            sock.bindToRandomPort("tcp://127.0.0.1");
        }

        sock.close();
        sock = context.socket(ZMQ.DEALER);

        // Check that exception different of EADDRINUSE is not catched

        // Invalid protocol
        try {
            sock.bindToRandomPort("noprotocol://127.0.0.1");
        } catch (ZMQException e) {
            assertEquals(e.getErrorCode(), ZMQ.EPROTONOSUPPORT());
        }

        sock.close();
    }

    /**
     * Test method for {@link org.zeromq.ZMQ.Socket#bindToSystemRandomPort(String)}.
     */
    @Test
    public void testBindToSystemRandomPort() {
        ZMQ.Socket sock = context.socket(ZMQ.DEALER);

        sock.bindToSystemRandomPort("tcp://127.0.0.1");
        sock.close();
    }

    @Test
    public void testReqRep() {
        try (ZMQ.Socket in = context.socket(ZMQ.REQ); ZMQ.Socket out = context.socket(ZMQ.REP)) {
            in.bind("inproc://reqrep");
            out.connect("inproc://reqrep");

            for (int i = 0; i < 10; i++) {
                byte[] req = ("request" + i).getBytes();
                byte[] rep = ("reply" + i).getBytes();

                assertTrue(in.send(req, 0));
                byte[] reqTmp = out.recv(0);
                assertArrayEquals(req, reqTmp);

                assertTrue(out.send(rep, 0));
                byte[] repTmp = in.recv(0);
                assertArrayEquals(rep, repTmp);
            }
        }
    }

    @Test
    public void testXPUBSUB() {
        if (ZMQ.getFullVersion() < ZMQ.make_version(3, 0, 0)) {
            // Can only test XPUB on ZMQ >= of 3.0
            return;
        }

        try (ZMQ.Socket pub = context.socket(ZMQ.XPUB);
          ZMQ.Socket sub = context.socket(ZMQ.SUB);
          ZMQ.Socket xsub = context.socket(ZMQ.XSUB)) {

            pub.bind("inproc://xpub");
            sub.connect("inproc://xpub");
            xsub.connect("inproc://xpub");

            sub.subscribe("".getBytes());
            byte[] subcr = pub.recv(0);
            assertArrayEquals(new byte[] { 1 }, subcr);

            sub.unsubscribe("".getBytes());
            subcr = pub.recv(0);
            assertArrayEquals(new byte[] { 0 }, subcr);

            byte[] subscription = "subs".getBytes();

            // Append subscription
            byte[] expected = new byte[subscription.length + 1];
            expected[0] = 1;
            System.arraycopy(subscription, 0, expected, 1, subscription.length);

            sub.subscribe(subscription);
            subcr = pub.recv(0);
            assertArrayEquals(expected, subcr);

            // Verify xsub subscription
            xsub.send(expected, 0);
            subcr = pub.recv(1);
            assertNull(subcr);

            for (int i = 0; i < 10; i++) {
                byte[] data = ("subscrip" + i).getBytes();

                assertTrue(pub.send(data, 0));
                // Verify SUB
                byte[] tmp = sub.recv(0);
                assertArrayEquals(data, tmp);

                // Verify XSUB
                tmp = xsub.recv(0);
                assertArrayEquals(data, tmp);
            }
        }
    }

    @Test
    public void testSetXpubVerbose() {
        if (ZMQ.getFullVersion() < ZMQ.make_version(3, 2, 2)) {
            // Can only test ZMQ_XPUB_VERBOSE on ZMQ >= of 3.2.2
            return;
        }

        byte[] topic = "topic".getBytes();
        byte[] subscription = new byte[topic.length + 1];
        subscription[0] = 1;
        System.arraycopy(topic, 0, subscription, 1, topic.length);

        try (ZMQ.Socket xpubVerbose = context.socket(ZMQ.XPUB); ZMQ.Socket xpubDefault = context.socket(ZMQ.XPUB)) {

            xpubVerbose.setXpubVerbose(true);
            xpubVerbose.bind("inproc://xpub_verbose");

            xpubDefault.bind("inproc://xpub_default");

            ZMQ.Socket[] xsubs = new ZMQ.Socket[3];
            for (int i = 0; i < xsubs.length; i++) {
                xsubs[i] = context.socket(ZMQ.XSUB);
                xsubs[i].connect("inproc://xpub_verbose");
                xsubs[i].connect("inproc://xpub_default");
            }

            for (int i = 0; i < xsubs.length; i++) {
                xsubs[i].send(subscription, 0);
                assertArrayEquals(subscription, xpubVerbose.recv(0));
                if (i == 0) {
                    assertArrayEquals(subscription, xpubDefault.recv(0));
                }
                else {
                    assertNull(xpubDefault.recv(ZMQ.DONTWAIT));
                }
            }

            for (Socket xsub : xsubs) {
                xsub.close();
            }
        }
    }

    /**
     * Test method for various set/get options.
     */
    @Test
    public void testSetOption() {
        ZMQ.Socket sock = context.socket(ZMQ.REQ);

        if (ZMQ.getFullVersion() >= ZMQ.makeVersion(3, 2, 0)) {
            sock.setIPv4Only(false);
            assertFalse(sock.getIPv4Only());

            sock.setIPv4Only(true);
            assertTrue(sock.getIPv4Only());
        }
        sock.close();
    }

    static class Client extends Thread {

        private Socket s;

        Client(Context ctx, String name_) {
            s = ctx.socket(ZMQ.REQ);
            s.setIdentity(name_.getBytes());
        }

        @Override
        public void run() {
            s.connect("tcp://127.0.0.1:6660");
            s.send("hello", 0);
            String msg = s.recvStr(ZMQ.CHARSET);
            s.send("world", 0);
            msg = s.recvStr(ZMQ.CHARSET);

            s.close();
        }
    }

    static class Dealer extends Thread {

        private Socket s;

        Dealer(Context ctx, String name_) {
            s = ctx.socket(ZMQ.DEALER);
            s.setIdentity(name_.getBytes());
        }

        @Override
        public void run() {

            s.connect("tcp://127.0.0.1:6661");
            int count = 0;
            while (count < 2) {
                String msg = s.recvStr(0, ZMQ.CHARSET);
                if (msg == null) {
                    throw new RuntimeException();
                }
                String identity = msg;
                msg = s.recvStr(0, ZMQ.CHARSET);
                if (msg == null) {
                    throw new RuntimeException();
                }

                msg = s.recvStr(ZMQ.CHARSET);
                if (msg == null) {
                    throw new RuntimeException();
                }

                s.send(identity, ZMQ.SNDMORE);
                s.send("", ZMQ.SNDMORE);
                String response = "OK " + msg;

                s.send(response, 0);
                count++;
            }
            s.close();
        }
    }

    static class Main extends Thread {

        Context ctx;

        Main(Context ctx_) {
            ctx = ctx_;
        }

        @Override
        public void run() {
            Socket frontend = ctx.socket(ZMQ.ROUTER);

            assertNotNull(frontend);
            frontend.bind("tcp://127.0.0.1:6660");

            Socket backend = ctx.socket(ZMQ.DEALER);
            assertNotNull(backend);
            backend.bind("tcp://127.0.0.1:6661");

            ZMQ.proxy(frontend, backend, null);

            frontend.close();
            backend.close();
        }

    }

    @Test
    public void testProxy() throws Exception {

        if (ZMQ.getFullVersion() < ZMQ.make_version(3, 2, 2)) {
            // Can only test zmq_proxy on ZMQ >= of 3.2.2
            return;
        }

        Main mt = new Main(context);
        mt.start();
        new Dealer(context, "AA").start();
        new Dealer(context, "BB").start();

        Thread.sleep(1000);
        Thread c1 = new Client(context, "X");
        c1.start();

        Thread c2 = new Client(context, "Y");
        c2.start();

        c1.join();
        c2.join();
    }

    /**
     * Test method for Router Mandatory
     */
    @Test
    public void testRouterMandatory() {
        if (ZMQ.getFullVersion() < ZMQ.makeVersion(3, 2, 0))
            return;

        try (ZMQ.Socket sock = context.socket(ZMQ.ROUTER)) {
            boolean ret = sock.sendMore("UNREACHABLE");
            assertTrue(ret);
            sock.send("END");

            sock.setRouterMandatory(true);
            try {
                sock.sendMore("UNREACHABLE");
                fail();
            } catch (ZMQException e) {
                assertEquals(ZMQ.EHOSTUNREACH(), e.getErrorCode());
            }
        }
    }

    @Test
    public void testSendMoreRequestReplyOverTcp() {
        try (ZMQ.Socket reply = context.socket(ZMQ.REP); ZMQ.Socket socket = context.socket(ZMQ.REQ)) {
            reply.bind("tcp://*:12345");
            socket.connect("tcp://localhost:12345");
            socket.send("test1", ZMQ.SNDMORE);
            socket.send("test2");
            assertEquals("test1", reply.recvStr(ZMQ.CHARSET));
            assertTrue(reply.hasReceiveMore());
            assertEquals("test2", reply.recvStr(ZMQ.CHARSET));
        }
    }

    @Test
    public void testWritingToClosedSocket() {
        try (Socket sock = context.socket(ZMQ.REQ)) {
            sock.connect("ipc:///tmp/hai");
            sock.close();
            sock.send("PING".getBytes(), 0);
        } catch (ZMQException e) {
            assertEquals(ZMQ.ENOTSOCK(), e.getErrorCode());
        }
    }

    @Test
    public void testZeroCopyRecv() {
        if (ZMQ.version_full() >= ZMQ.make_version(3, 0, 0)) {
            ByteBuffer response = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder());
            try (Socket push = context.socket(ZMQ.PUSH); Socket pull = context.socket(ZMQ.PULL)) {
                pull.bind("tcp://*:45324");
                push.connect("tcp://localhost:45324");

                push.send("PING");
                int rc = pull.recvZeroCopy(response, 16, 0);
                response.flip();
                byte[] b = new byte[rc];
                response.get(b);
                assertEquals("PING", new String(b));
            }
        }
    }

    @Test
    public void testZeroCopySend() {
        if (ZMQ.version_full() >= ZMQ.make_version(3, 0, 0)) {
            ByteBuffer bb = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder());
            try (Socket push = context.socket(ZMQ.PUSH); Socket pull = context.socket(ZMQ.PULL)) {
                pull.bind("tcp://*:45324");
                push.connect("tcp://localhost:45324");
                bb.put("PING".getBytes());
                push.sendZeroCopy(bb, bb.position(), 0);
                assertEquals("PING", new String(pull.recv()));
            }
        }
    }

    @Test
    public void testByteBufferSend() {
        if (ZMQ.version_full() >= ZMQ.make_version(3, 0, 0)) {
            ByteBuffer bb = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            try (Socket push = context.socket(ZMQ.PUSH); Socket pull = context.socket(ZMQ.PULL)) {
                pull.bind("ipc:///tmp/sendbb");
                push.connect("ipc:///tmp/sendbb");
                bb.put("PING".getBytes());
                bb.flip();
                push.sendByteBuffer(bb, 0);
                String actual = new String(pull.recv());
                System.out.println(actual);
                assertEquals("PING", actual);
            }
        }
    }

    @Test
    public void testByteBufferRecv() {
        if (ZMQ.version_full() >= ZMQ.make_version(3, 0, 0)) {
            ByteBuffer bb = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            try (Socket push = context.socket(ZMQ.PUSH); Socket pull = context.socket(ZMQ.PULL)) {
                pull.bind("ipc:///tmp/recvbb");
                push.connect("ipc:///tmp/recvbb");
                push.send("PING".getBytes(), 0);
                pull.recvByteBuffer(bb, 0);
                bb.flip();
                byte[] b = new byte[4];
                bb.get(b);
                assertEquals("PING", new String(b));
            }
        }
    }

    @Test
    public void testByteBufferRecvTooLarge() {
        if (ZMQ.version_full() >= ZMQ.make_version(3, 0, 0)) {
            ByteBuffer bb = ByteBuffer.allocateDirect(5).order(ByteOrder.nativeOrder());
            try (Socket push = context.socket(ZMQ.PUSH); Socket pull = context.socket(ZMQ.PULL)) {
                pull.bind("tcp://*:6787");
                push.connect("tcp://127.0.0.1:6787");
                push.send("helloworld".getBytes(), 0);
                int size = pull.recvByteBuffer(bb, 0);
                bb.flip();
                byte[] b = new byte[size];
                bb.get(b);
                assertEquals("hello", new String(b));
            }
        }
    }
    @Test
    public void testPollerUnregister() {
        Socket socketOne = context.socket(ZMQ.SUB);
        Socket socketTwo = context.socket(ZMQ.REP);
        Poller poller = new ZMQ.Poller(2);
        poller.register(socketOne, ZMQ.Poller.POLLIN);
        poller.register(socketTwo, ZMQ.Poller.POLLIN);

        socketOne.setLinger(0);
        socketOne.close();
        socketTwo.setLinger(0);
        socketTwo.close();

        poller.unregister(socketOne);
        poller.unregister(socketTwo);
    }

    @Test(expected = ZMQException.class)
    public void testPollingInvalidSockets() {
        Poller poller = new ZMQ.Poller(1);
        Socket socketOne = context.socket(ZMQ.SUB);

        poller.register(socketOne, ZMQ.Poller.POLLIN);
        socketOne.close();
        poller.poll(100);
    }

    @Test
    public void testEventConnected() {
        if (ZMQ.version_full() < ZMQ.make_version(3, 2, 2)) // Monitor added in 3.2.2
            return;
        Event event;

        try (Socket helper = context.socket(ZMQ.REQ); Socket socket  = context.socket(ZMQ.REP);
          Socket monitor = context.socket(ZMQ.PAIR)) {
            int port = helper.bindToRandomPort("tcp://127.0.0.1");

            monitor.setReceiveTimeOut(100);

            assertTrue(socket.monitor("inproc://monitor.socket", ZMQ.EVENT_CONNECTED));
            monitor.connect("inproc://monitor.socket");

            socket.connect("tcp://127.0.0.1:" + port);
            event = Event.recv(monitor);
            assertNotNull("No event was received", event);
            assertEquals(ZMQ.EVENT_CONNECTED, event.getEvent());
        }
    }

    @Test
    public void testEventConnectDelayed() {
        if (ZMQ.version_full() < ZMQ.make_version(3, 2, 2)) // Monitor added in 3.2.2
            return;

        try (Socket socket = context.socket(ZMQ.REP); Socket monitor = context.socket(ZMQ.PAIR)) {
            monitor.setReceiveTimeOut(100);

            assertTrue(socket.monitor("inproc://monitor.socket", ZMQ.EVENT_CONNECT_DELAYED));
            monitor.connect("inproc://monitor.socket");

            socket.connect("tcp://127.0.0.1:6751");
            Event event = Event.recv(monitor);
            assertNotNull("No event was received", event);
            assertEquals(ZMQ.EVENT_CONNECT_DELAYED, event.getEvent());
        }
    }

    @Test
    public void testEventConnectRetried() {
        if (ZMQ.version_full() < ZMQ.make_version(3, 2, 2)) // Monitor added in 3.2.2
            return;

        try (Socket socket = context.socket(ZMQ.REP); Socket monitor = context.socket(ZMQ.PAIR)) {
            monitor.setReceiveTimeOut(100);

            assertTrue(socket.monitor("inproc://monitor.socket", ZMQ.EVENT_CONNECT_RETRIED));
            monitor.connect("inproc://monitor.socket");

            socket.connect("tcp://127.0.0.1:6752");
            Event event = Event.recv(monitor);
            assertNotNull("No event was received", event);
            assertEquals(ZMQ.EVENT_CONNECT_RETRIED, event.getEvent());
        }
    }

    @Test
    public void testEventListening() {
        if (ZMQ.version_full() < ZMQ.make_version(3, 2, 2)) // Monitor added in 3.2.2
            return;

        try (Socket socket = context.socket(ZMQ.REP); Socket monitor = context.socket(ZMQ.PAIR)) {
            monitor.setReceiveTimeOut(100);

            assertTrue(socket.monitor("inproc://monitor.socket", ZMQ.EVENT_LISTENING));
            monitor.connect("inproc://monitor.socket");

            socket.bindToRandomPort("tcp://127.0.0.1");
            Event event = Event.recv(monitor);
            assertNotNull("No event was received", event);
            assertEquals(ZMQ.EVENT_LISTENING, event.getEvent());
        }
    }

    @Test
    public void testEventBindFailed() {
        if (ZMQ.version_full() < ZMQ.make_version(3, 2, 2)) // Monitor added in 3.2.2
            return;

        try (Socket helper = context.socket(ZMQ.REP); Socket socket = context.socket(ZMQ.REP); Socket monitor = context.socket(ZMQ.PAIR)) {
            int port = helper.bindToRandomPort("tcp://127.0.0.1");
            monitor.setReceiveTimeOut(100);

            assertTrue(socket.monitor("inproc://monitor.socket", ZMQ.EVENT_BIND_FAILED));
            monitor.connect("inproc://monitor.socket");

            try {
                socket.bind("tcp://127.0.0.1:" + port);
            } catch (ZMQException ignored) {
            }
            Event event = Event.recv(monitor);
            assertNotNull("No event was received", event);
            assertEquals(ZMQ.EVENT_BIND_FAILED, event.getEvent());
        }
    }

    @Test
    public void testEventAccepted() {
        if (ZMQ.version_full() < ZMQ.make_version(3, 2, 2)) // Monitor added in 3.2.2
            return;

        try (Socket socket = context.socket(ZMQ.REP); Socket monitor = context.socket(ZMQ.PAIR); Socket helper = context.socket(ZMQ.REQ)) {
            monitor.setReceiveTimeOut(100);

            assertTrue(socket.monitor("inproc://monitor.socket", ZMQ.EVENT_ACCEPTED));
            monitor.connect("inproc://monitor.socket");

            int port = socket.bindToRandomPort("tcp://127.0.0.1");

            helper.connect("tcp://127.0.0.1:" + port);
            Event event = Event.recv(monitor);
            assertNotNull("No event was received", event);
            assertEquals(ZMQ.EVENT_ACCEPTED, event.getEvent());
        }
    }

    @Test
    public void testEventClosed() {
        if (ZMQ.version_full() < ZMQ.make_version(3, 2, 2)) // Monitor added in 3.2.2
            return;

        try (Socket socket = context.socket(ZMQ.REP); Socket monitor = context.socket(ZMQ.PAIR)) {
            monitor.setReceiveTimeOut(100);

            socket.bindToRandomPort("tcp://127.0.0.1");

            assertTrue(socket.monitor("inproc://monitor.socket", ZMQ.EVENT_CLOSED));
            monitor.connect("inproc://monitor.socket");

            socket.close();
            Event event = Event.recv(monitor);
            assertNotNull("No event was received", event);
            assertEquals(ZMQ.EVENT_CLOSED, event.getEvent());
        }
    }

    @Test
    public void testEventDisconnected() {
        if (ZMQ.version_full() < ZMQ.make_version(3, 2, 2)) // Monitor added in 3.2.2
            return;

        try (Socket socket = context.socket(ZMQ.REP); Socket monitor = context.socket(ZMQ.PAIR); Socket helper = context.socket(ZMQ.REQ)) {
            monitor.setReceiveTimeOut(100);

            int port = socket.bindToRandomPort("tcp://127.0.0.1");
            helper.connect("tcp://127.0.0.1:" + port);

            assertTrue(socket.monitor("inproc://monitor.socket", ZMQ.EVENT_DISCONNECTED));
            monitor.connect("inproc://monitor.socket");

            byte[] req = "request".getBytes();
            helper.send(req, 100);
            assertArrayEquals(socket.recv(100), req);

            helper.close();
            Event event = Event.recv(monitor);
            assertNotNull("No event was received", event);
            assertEquals(ZMQ.EVENT_DISCONNECTED, event.getEvent());
        }
    }

    @Test
    public void testEventMonitorStopped() {
        if (ZMQ.version_full() < ZMQ.make_version(4, 0, 0)) // EVENT_MONITOR_STOPPED added in 4.0.0
            return;

        try (Socket socket = context.socket(ZMQ.REP); Socket monitor = context.socket(ZMQ.PAIR);) {
            monitor.setReceiveTimeOut(100);

            assertTrue(socket.monitor("inproc://monitor.socket", ZMQ.EVENT_MONITOR_STOPPED));
            monitor.connect("inproc://monitor.socket");

            socket.monitor(null, 0);
            Event event = Event.recv(monitor);
            assertNotNull("No event was received", event);
            assertEquals(ZMQ.EVENT_MONITOR_STOPPED, event.getEvent());
        }
    }

    @Test
    public void testCurveZ85Keys() {
        if (ZMQ.getFullVersion() < ZMQ.makeVersion(4, 0, 0))
            return;

        final Charset utf8 = StandardCharsets.UTF_8;
        final String endpoint = "tcp://127.0.0.1:5000";

        final ZMQ.Curve.KeyPair req_key = ZMQ.Curve.generateKeyPair();
        final ZMQ.Curve.KeyPair rep_key = ZMQ.Curve.generateKeyPair();

        final byte[] req_pk = req_key.publicKey.getBytes(utf8);
        final byte[] req_sk = req_key.secretKey.getBytes(utf8);
        final byte[] rep_pk = rep_key.publicKey.getBytes(utf8);
        final byte[] rep_sk = rep_key.secretKey.getBytes(utf8);

        try (Socket rep = context.socket(ZMQ.REP); Socket req = context.socket(ZMQ.REQ)) {
            rep.setCurveServer(true);
            rep.setCurveSecretKey(rep_sk);
            rep.bind(endpoint);


            req.setCurvePublicKey(req_pk);
            req.setCurveSecretKey(req_sk);
            req.setCurveServerKey(rep_pk);
            req.connect(endpoint);

            final String sent = "Hello World";
            req.send(sent);
            final String received = rep.recvStr(utf8);
            assertEquals(sent, received);
        }
    }

    @Test
    public void testCurveBinaryKeys() {
        if (ZMQ.getFullVersion() < ZMQ.makeVersion(4, 0, 0))
            return;

        final Charset utf8 = StandardCharsets.UTF_8;
        final String endpoint = "tcp://127.0.0.1:5000";

        final byte[] req_pk = DatatypeConverter.parseHexBinary(
                "BB88471D65E2659B30C55A5321CEBB5AAB2B70A398645C26DCA2B2FCB43FC518");
        final byte[] req_sk = DatatypeConverter.parseHexBinary(
                "7BB864B489AFA3671FBE69101F94B38972F24816DFB01B51656B3FEC8DFD0888");
        final byte[] rep_pk = DatatypeConverter.parseHexBinary(
                "54FCBA24E93249969316FB617C872BB0C1D1FF14800427C594CBFACF1BC2D652");
        final byte[] rep_sk = DatatypeConverter.parseHexBinary(
                "8E0BDD697628B91D8F245587EE95C5B04D48963F79259877B49CD9063AEAD3B7");

        try (Socket rep = context.socket(ZMQ.REP); Socket req = context.socket(ZMQ.REQ)) {
            rep.setCurveServer(true);
            rep.setCurveSecretKey(rep_sk);
            rep.bind(endpoint);

            req.setCurvePublicKey(req_pk);
            req.setCurveSecretKey(req_sk);
            req.setCurveServerKey(rep_pk);
            req.connect(endpoint);

            final String sent = "Hello World";
            req.send(sent);
            final String received = rep.recvStr(utf8);
            assertEquals(sent, received);

            req.close();
        }
    }

    @Test
    public void testKeyEncode() {
        final String expected = "Yne@$w-vo<fVvi]a<NY6T1ed:M$fCG*[IaLV{hID";
        final String actual = ZMQ.Curve.z85Encode(DatatypeConverter.parseHexBinary(
                "BB88471D65E2659B30C55A5321CEBB5AAB2B70A398645C26DCA2B2FCB43FC518"));
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void testKeyDecode() {
        final byte[] expected = DatatypeConverter.parseHexBinary(
                "BB88471D65E2659B30C55A5321CEBB5AAB2B70A398645C26DCA2B2FCB43FC518");
        final byte[] actual = ZMQ.Curve.z85Decode("Yne@$w-vo<fVvi]a<NY6T1ed:M$fCG*[IaLV{hID");
        Assert.assertArrayEquals(expected, actual);
    }

    @Test
    public void testKeyEncodeDecode() {
        for (int i = 0; i < 100; i++) {
            final ZMQ.Curve.KeyPair pair = ZMQ.Curve.generateKeyPair();
            Assert.assertEquals(pair.publicKey, ZMQ.Curve.z85Encode(ZMQ.Curve.z85Decode(pair.publicKey)));
            Assert.assertEquals(pair.secretKey, ZMQ.Curve.z85Encode(ZMQ.Curve.z85Decode(pair.secretKey)));
        }
    }
}

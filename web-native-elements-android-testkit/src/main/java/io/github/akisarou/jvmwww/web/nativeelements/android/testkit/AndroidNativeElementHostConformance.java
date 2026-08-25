package io.github.akisarou.jvmwww.web.nativeelements.android.testkit;

import android.os.Looper;
import io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink;
import io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic Java 8 conformance for the Android committed native-element table. */
public final class AndroidNativeElementHostConformance {
    private int passed;

    public static void main(String[] args) throws Exception {
        Looper.prepare();
        new AndroidNativeElementHostConformance().run();
    }

    private void run() throws Exception {
        factoryAndOwnerIdentity();
        mountMetadataAndConnection();
        transformedAndUntransformedLayout();
        clientAndScrollMetrics();
        availabilityAndMetadataCommits();
        generationSafeReuseAndGrowth();
        foreignThreadRefusal();
        closeInvalidatesAndReleases();
        System.out.println("Android native element host conformance: " + passed + " tests passed");
    }

    private void factoryAndOwnerIdentity() throws Exception {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        same(Looper.myLooper(), host.getLooper(), "captured Looper");
        eq(0, host.getActiveElementCount(), "initial active count");

        AtomicReference<Throwable> missingLooper = new AtomicReference<Throwable>();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AndroidNativeElementHost.forCurrentLooper();
                } catch (Throwable error) {
                    missingLooper.set(error);
                }
            }
        });
        thread.start();
        thread.join();
        instanceOf(IllegalStateException.class, missingLooper.get(), "factory without Looper");
        host.close();
        pass();
    }

    private void mountMetadataAndConnection() {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        long first = host.mountElement("View", "hero");
        long second = host.mountElement(null, null);
        yes(first > 0L && second > 0L && first != second, "positive distinct identities");
        yes(host.isConnected(first), "first connected");
        yes(host.isConnected(second), "second connected");
        eq("View", host.getTagName(first), "tag name");
        eq("hero", host.getId(first), "id");
        eq("", host.getTagName(second), "null tag normalized");
        eq("", host.getId(second), "null id normalized");
        eq(2, host.getActiveElementCount(), "active count");
        no(host.isConnected(0L), "zero identity disconnected");
        isNull(host.getTagName(Long.MAX_VALUE), "unknown tag absent");
        host.close();
        pass();
    }

    private void transformedAndUntransformedLayout() {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        long identity = host.mountElement("Text", "label");
        yes(host.commitLayout(identity, 10, 20, 30, 40, 1, 2, 3, 4), "layout committed");

        RecordingSink sink = new RecordingSink();
        yes(host.measureBoundingClientRect(identity, true, sink), "transformed available");
        sink.assertRect(10, 20, 30, 40, "transformed");
        sink.reset();
        yes(host.measureBoundingClientRect(identity, false, sink), "untransformed available");
        sink.assertRect(1, 2, 3, 4, "untransformed");

        yes(host.commitLayout(
                identity,
                Double.NaN,
                Double.NEGATIVE_INFINITY,
                -0.0,
                Double.POSITIVE_INFINITY,
                -1.5,
                2.5,
                7.25,
                -9.75), "unrestricted layout committed");
        sink.reset();
        yes(host.measureBoundingClientRect(identity, true, sink), "unrestricted transformed");
        sink.assertRaw(
                Double.NaN,
                Double.NEGATIVE_INFINITY,
                -0.0,
                Double.POSITIVE_INFINITY,
                "unrestricted transformed");
        host.close();
        pass();
    }

    private void clientAndScrollMetrics() {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        long identity = host.mountElement("ScrollView", "list");
        assertZeroMetrics(host, identity, "initial");

        yes(host.commitClientAndScrollMetrics(
                identity,
                100.5,
                -20.25,
                -0.0,
                Double.NaN,
                -3.75,
                Double.NEGATIVE_INFINITY,
                1000.125,
                Double.POSITIVE_INFINITY),
                "metrics committed");
        raw(100.5, host.getClientWidth(identity), "client width");
        raw(-20.25, host.getClientHeight(identity), "client height");
        raw(-0.0, host.getClientTop(identity), "client top");
        yes(Double.isNaN(host.getClientLeft(identity)), "client left NaN");
        raw(-3.75, host.getScrollLeft(identity), "scroll left");
        raw(Double.NEGATIVE_INFINITY, host.getScrollTop(identity), "scroll top");
        raw(1000.125, host.getScrollWidth(identity), "scroll width");
        raw(Double.POSITIVE_INFINITY, host.getScrollHeight(identity), "scroll height");

        yes(host.commitClientAndScrollMetrics(
                identity, 1, 2, 3, 4, 5, 6, 7, 8), "metrics replaced");
        raw(1.0, host.getClientWidth(identity), "replaced client width");
        raw(8.0, host.getScrollHeight(identity), "replaced scroll height");
        yes(host.clearCommittedClientAndScrollMetrics(identity), "metrics cleared");
        assertZeroMetrics(host, identity, "cleared");
        yes(host.isConnected(identity), "metrics clear keeps connection");

        no(host.commitClientAndScrollMetrics(
                Long.MAX_VALUE, 0,0,0,0,0,0,0,0), "stale metrics rejected");
        no(host.clearCommittedClientAndScrollMetrics(Long.MAX_VALUE), "stale metric clear rejected");
        host.close();
        pass();
    }

    private void availabilityAndMetadataCommits() {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        long identity = host.mountElement("View", "before");
        RecordingSink sink = new RecordingSink();
        no(host.measureBoundingClientRect(identity, true, sink), "mount has no layout");
        eq(0, sink.writes, "unavailable layout writes nothing");

        yes(host.commitMetadata(identity, "Image", "after"), "metadata commit");
        eq("Image", host.getTagName(identity), "updated tag");
        eq("after", host.getId(identity), "updated id");
        yes(host.commitLayout(identity, 1, 2, 3, 4, 5, 6, 7, 8), "first layout");
        yes(host.commitClientAndScrollMetrics(
                identity, 9, 10, 11, 12, 13, 14, 15, 16), "first metrics");

        yes(host.clearCommittedLayout(identity), "layout cleared");
        no(host.measureBoundingClientRect(identity, false, sink), "cleared layout unavailable");
        eq(0, sink.writes, "cleared layout writes nothing");
        raw(9.0, host.getClientWidth(identity), "layout clear keeps metrics");

        yes(host.commitLayout(identity, 21,22,23,24,25,26,27,28), "layout restored");
        yes(host.clearCommittedClientAndScrollMetrics(identity), "metrics cleared separately");
        assertZeroMetrics(host, identity, "metrics unavailable");
        sink.reset();
        yes(host.measureBoundingClientRect(identity, true, sink), "metric clear keeps layout");
        sink.assertRect(21,22,23,24,"layout retained");
        yes(host.isConnected(identity), "availability clears keep connection");

        no(host.commitMetadata(Long.MAX_VALUE, "x", "y"), "stale metadata rejected");
        no(host.commitLayout(Long.MAX_VALUE, 0,0,0,0,0,0,0,0), "stale layout rejected");
        no(host.clearCommittedLayout(Long.MAX_VALUE), "stale layout clear rejected");
        host.close();
        pass();
    }

    private void generationSafeReuseAndGrowth() {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        long first = host.mountElement("View", "old");
        yes(host.unmountElement(first), "first unmounted");
        no(host.isConnected(first), "old identity disconnected");
        long reused = host.mountElement("View", "new");
        yes(first != reused, "slot reuse changes generation");
        no(host.isConnected(first), "old generation remains stale");
        yes(host.isConnected(reused), "reused slot connected");
        eq("new", host.getId(reused), "reused metadata isolated");
        no(host.unmountElement(first), "stale unmount rejected");
        assertZeroMetrics(host, reused, "reused metric isolation");

        long current = reused;
        for (int iteration = 0; iteration < 10000; iteration++) {
            yes(host.unmountElement(current), "generation cycle unmount " + iteration);
            long next = host.mountElement("View", "cycle");
            yes(next != current, "generation cycle identity " + iteration);
            no(host.isConnected(current), "generation cycle stale " + iteration);
            current = next;
        }

        long[] identities = new long[128];
        for (int index = 0; index < identities.length; index++) {
            identities[index] = host.mountElement("Cell", Integer.toString(index));
            yes(host.commitLayout(
                    identities[index],
                    index, index + 1, index + 2, index + 3,
                    index + 4, index + 5, index + 6, index + 7),
                    "growth layout " + index);
            yes(host.commitClientAndScrollMetrics(
                    identities[index],
                    index + 8, index + 9, index + 10, index + 11,
                    index + 12, index + 13, index + 14, index + 15),
                    "growth metrics " + index);
        }
        eq(129, host.getActiveElementCount(), "growth active count");
        RecordingSink sink = new RecordingSink();
        yes(host.measureBoundingClientRect(identities[127], false, sink), "grown table resolves tail");
        sink.assertRect(131, 132, 133, 134, "grown tail");
        raw(142.0, host.getScrollHeight(identities[127]), "grown metric tail");
        host.close();
        pass();
    }

    private void foreignThreadRefusal() throws Exception {
        final AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        final long identity = host.mountElement("View", "owner");
        host.commitClientAndScrollMetrics(identity, 1,2,3,4,5,6,7,8);
        final AtomicReference<Throwable> firstFailure = new AtomicReference<Throwable>();
        final AtomicReference<Throwable> secondFailure = new AtomicReference<Throwable>();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                try {
                    host.getScrollTop(identity);
                } catch (Throwable error) {
                    firstFailure.set(error);
                }
                try {
                    host.commitClientAndScrollMetrics(identity, 9,9,9,9,9,9,9,9);
                } catch (Throwable error) {
                    secondFailure.set(error);
                }
            }
        });
        thread.start();
        thread.join();
        instanceOf(IllegalStateException.class, firstFailure.get(), "foreign metric read refused");
        instanceOf(IllegalStateException.class, secondFailure.get(), "foreign metric write refused");
        raw(6.0, host.getScrollTop(identity), "foreign metric write made no change");
        eq("View", host.getTagName(identity), "foreign state intact");
        host.close();
        pass();
    }

    private void closeInvalidatesAndReleases() {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        long identity = host.mountElement("View", "closing");
        host.commitLayout(identity, 1,2,3,4,5,6,7,8);
        host.commitClientAndScrollMetrics(identity, 9,10,11,12,13,14,15,16);
        host.close();
        yes(host.isClosed(), "closed flag");
        eq(0, host.getActiveElementCount(), "closed count");
        no(host.isConnected(identity), "closed identity disconnected");
        isNull(host.getTagName(identity), "closed tag absent");
        isNull(host.getId(identity), "closed id absent");
        RecordingSink sink = new RecordingSink();
        no(host.measureBoundingClientRect(identity, true, sink), "closed measurement unavailable");
        eq(0, sink.writes, "closed measurement writes nothing");
        assertZeroMetrics(host, identity, "closed metrics");
        host.close();
        try {
            host.mountElement("View", "late");
            throw new AssertionError("closed mutation accepted");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        pass();
    }

    private static void assertZeroMetrics(
            AndroidNativeElementHost host, long identity, String label) {
        raw(0.0, host.getClientWidth(identity), label + " client width");
        raw(0.0, host.getClientHeight(identity), label + " client height");
        raw(0.0, host.getClientTop(identity), label + " client top");
        raw(0.0, host.getClientLeft(identity), label + " client left");
        raw(0.0, host.getScrollLeft(identity), label + " scroll left");
        raw(0.0, host.getScrollTop(identity), label + " scroll top");
        raw(0.0, host.getScrollWidth(identity), label + " scroll width");
        raw(0.0, host.getScrollHeight(identity), label + " scroll height");
    }

    private void pass() { passed++; }

    private static final class RecordingSink implements NativeElementRectSink {
        double x; double y; double width; double height; int writes;
        @Override public void setRect(double x, double y, double width, double height) {
            this.x=x; this.y=y; this.width=width; this.height=height; writes++;
        }
        void reset(){x=0;y=0;width=0;height=0;writes=0;}
        void assertRect(double ex,double ey,double ew,double eh,String label){
            eq(1,writes,label+" writes"); raw(ex,x,label+" x"); raw(ey,y,label+" y"); raw(ew,width,label+" width"); raw(eh,height,label+" height");
        }
        void assertRaw(double ex,double ey,double ew,double eh,String label){assertRect(ex,ey,ew,eh,label);}
    }

    private static void yes(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    private static void no(boolean condition, String label) { yes(!condition, label); }
    private static void same(Object expected,Object actual,String label){if(expected!=actual)throw new AssertionError(label);}
    private static void isNull(Object value,String label){if(value!=null)throw new AssertionError(label+": got "+value);}
    private static void instanceOf(Class<?> type,Throwable error,String label){if(!type.isInstance(error))throw new AssertionError(label+": got "+error);}
    private static void eq(int expected,int actual,String label){if(expected!=actual)throw new AssertionError(label+": expected "+expected+" got "+actual);}
    private static void eq(String expected,String actual,String label){if(!expected.equals(actual))throw new AssertionError(label+": expected "+expected+" got "+actual);}
    private static void raw(double expected,double actual,String label){
        if(Double.doubleToRawLongBits(expected)!=Double.doubleToRawLongBits(actual))throw new AssertionError(label+": expected "+expected+" got "+actual);
    }
}

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
        layoutAvailabilityAndMetadataCommits();
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

    private void layoutAvailabilityAndMetadataCommits() {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        long identity = host.mountElement("View", "before");
        RecordingSink sink = new RecordingSink();
        no(host.measureBoundingClientRect(identity, true, sink), "mount has no layout");
        eq(0, sink.writes, "unavailable layout writes nothing");

        yes(host.commitMetadata(identity, "Image", "after"), "metadata commit");
        eq("Image", host.getTagName(identity), "updated tag");
        eq("after", host.getId(identity), "updated id");
        yes(host.commitLayout(identity, 1, 2, 3, 4, 5, 6, 7, 8), "first layout");
        yes(host.clearCommittedLayout(identity), "layout cleared");
        no(host.measureBoundingClientRect(identity, false, sink), "cleared unavailable");
        eq(0, sink.writes, "cleared layout writes nothing");
        yes(host.isConnected(identity), "layout clear keeps connection");

        no(host.commitMetadata(Long.MAX_VALUE, "x", "y"), "stale metadata rejected");
        no(host.commitLayout(Long.MAX_VALUE, 0,0,0,0,0,0,0,0), "stale layout rejected");
        no(host.clearCommittedLayout(Long.MAX_VALUE), "stale clear rejected");
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
        }
        eq(129, host.getActiveElementCount(), "growth active count");
        RecordingSink sink = new RecordingSink();
        yes(host.measureBoundingClientRect(identities[127], false, sink), "grown table resolves tail");
        sink.assertRect(131, 132, 133, 134, "grown tail");
        host.close();
        pass();
    }

    private void foreignThreadRefusal() throws Exception {
        final AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        final long identity = host.mountElement("View", "owner");
        final AtomicReference<Throwable> firstFailure = new AtomicReference<Throwable>();
        final AtomicReference<Throwable> secondFailure = new AtomicReference<Throwable>();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                try {
                    host.isConnected(identity);
                } catch (Throwable error) {
                    firstFailure.set(error);
                }
                try {
                    host.commitMetadata(identity, "Bad", "foreign");
                } catch (Throwable error) {
                    secondFailure.set(error);
                }
            }
        });
        thread.start();
        thread.join();
        instanceOf(IllegalStateException.class, firstFailure.get(), "foreign read refused");
        instanceOf(IllegalStateException.class, secondFailure.get(), "foreign write refused");
        eq("View", host.getTagName(identity), "foreign write made no change");
        eq("owner", host.getId(identity), "foreign id made no change");
        host.close();
        pass();
    }

    private void closeInvalidatesAndReleases() {
        AndroidNativeElementHost host = AndroidNativeElementHost.forCurrentLooper();
        long identity = host.mountElement("View", "closing");
        host.commitLayout(identity, 1,2,3,4,5,6,7,8);
        host.close();
        yes(host.isClosed(), "closed flag");
        eq(0, host.getActiveElementCount(), "closed count");
        no(host.isConnected(identity), "closed identity disconnected");
        isNull(host.getTagName(identity), "closed tag absent");
        isNull(host.getId(identity), "closed id absent");
        RecordingSink sink = new RecordingSink();
        no(host.measureBoundingClientRect(identity, true, sink), "closed measurement unavailable");
        eq(0, sink.writes, "closed measurement writes nothing");
        host.close();
        try {
            host.mountElement("View", "late");
            throw new AssertionError("closed mutation accepted");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        pass();
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

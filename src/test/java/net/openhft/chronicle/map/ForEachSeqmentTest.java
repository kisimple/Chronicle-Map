package net.openhft.chronicle.map;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.hash.serialization.SizedReader;
import net.openhft.chronicle.hash.serialization.SizedWriter;
import net.openhft.chronicle.wire.AbstractBytesMarshallable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class ForEachSeqmentTest {
    static {
        ClassAliasPool.CLASS_ALIASES.addAlias(MyDto.class);
    }

    @Test
    public void forEachSegmentEntryWhileReleasesLock() throws IOException {
        ChronicleMapBuilder<Integer, MyDto> builder = ChronicleMapBuilder.of(Integer.class, MyDto.class)
                .entries(256)
                .averageValueSize(128)
                .actualSegments(1)
                .valueMarshaller(new MyDtoMarshaller());
        File tmp = new File(OS.TMP, "stressTest-" + System.nanoTime());
        tmp.deleteOnExit();
        try (ChronicleMap<Integer, MyDto> map = builder.createOrRecoverPersistedTo(tmp)) {
            map.put(1, new MyDto());
            try (MapSegmentContext<Integer, MyDto, ?> context = map.segmentContext(0)) {
                context.forEachSegmentEntryWhile(e -> {
                    System.out.println(e.key().get() + " = " + e.value().get());
                    return true;
                });
            }
            System.out.println("Done");
        }
    }

    @Test
    public void stressTest() throws IOException, InterruptedException {
        ChronicleMapBuilder<Integer, MyDto> builder = ChronicleMapBuilder.of(Integer.class, MyDto.class)
                .entries(256)
                .averageValueSize(128)
                .actualSegments(1)
                .valueMarshaller(new MyDtoMarshaller());
        File tmp = new File(OS.TMP, "stressTest-" + System.nanoTime());
        Thread t = null;
        try (ChronicleMap<Integer, MyDto> map = builder.createOrRecoverPersistedTo(tmp)) {
            try {
                t = new Thread(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            System.out.println("put " + i);
                            map.put(i, new MyDto());
                            Thread.sleep(10);
                        }
                    } catch (InterruptedException e) {
                        System.out.println("Interrupted");
                    }
                });
                t.setDaemon(true);
                t.start();
                Jvm.pause(100);
                try (MapSegmentContext<Integer, MyDto, ?> context = map.segmentContext(0)) {
                    context.forEachSegmentEntryWhile(e -> {
                        System.out.println(e.key().get() + " = " + e.value().get());
                        Jvm.pause(10);
                        return true;
                    });
                }
                System.out.println("Done");
                Jvm.pause(100);
            } catch (Throwable th) {
                th.printStackTrace();
            } finally {
                if (t != null) {
                    t.interrupt();
                    t.join();
                }
            }
        }
    }

    static class MyDto extends AbstractBytesMarshallable {
        String s1;
        String s2;

        public MyDto() {
        }

        public MyDto(final String s1, final String s2) {
            this.s1 = s1;
            this.s2 = s2;
        }
    }

    static class MyDtoMarshaller implements SizedReader<MyDto>, SizedWriter<MyDto> {
        transient Bytes bytes = Bytes.elasticByteBuffer();

        @NotNull
        @Override
        public MyDto read(final Bytes in, final long size, @Nullable final MyDto using) {
            MyDto u = using == null ? new MyDto() : using;
            u.readMarshallable(in);
            return u;
        }

        @Override
        public long size(@NotNull final MyDto toWrite) {
            bytes.clear();
            write(bytes, 0, toWrite);
            return bytes.readRemaining();
        }

        @Override
        public void write(final Bytes out, final long size, @NotNull final MyDto toWrite) {
            toWrite.writeMarshallable(out);
        }
    }

}

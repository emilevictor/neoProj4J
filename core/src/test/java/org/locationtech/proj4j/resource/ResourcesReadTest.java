/*******************************************************************************
 * Copyright 2026 Proj4J contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.locationtech.proj4j.resource;

import java.io.EOFException;
import java.io.IOException;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * {@link Resources#readFully} and {@link Resources#readAtMost}, which are now one loop.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The two methods were written out twice, character for character apart from what they do when
 * the resource ends early: {@code readFully} threw, {@code readAtMost} stopped. {@code readFully}
 * now calls {@code readAtMost} and throws on a short result, so the loop exists once.
 *
 * <p>That is only equivalent if three things hold, and only one of them was observable through the
 * grid readers that call these methods, which all read whole well-formed files. Each is asserted
 * below against a reader that hands back short reads on purpose:
 * <ul>
 *   <li>the same sequence of {@code read} calls is issued, so nothing that depends on read
 *       granularity - a reader over a socket-backed or decompressing source - sees a different
 *       pattern;</li>
 *   <li>the {@link EOFException} message is byte-for-byte what it was, including the byte offset,
 *       which is the count of bytes successfully read before the short read and is the one value
 *       the delegation could plausibly have got wrong;</li>
 *   <li>a reader returning {@code 0} rather than {@code -1} still terminates. Both methods treated
 *       {@code n <= 0} as the end, and a merged loop that tested only {@code n < 0} would spin
 *       forever rather than fail a test.</li>
 * </ul>
 */
public class ResourcesReadTest {

    private static byte[] ramp(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i + 1);
        }
        return b;
    }

    /** Serves at most {@code chunk} bytes per call, so every caller has to loop. */
    private static final class ChunkedReader implements SeekableByteReader {

        private final byte[] bytes;
        private final int chunk;
        int reads;

        ChunkedReader(byte[] bytes, int chunk) {
            this.bytes = bytes;
            this.chunk = chunk;
        }

        @Override
        public int read(long position, byte[] dst, int off, int len) {
            reads++;
            if (position >= bytes.length) {
                return -1;
            }
            int n = (int) Math.min(Math.min(len, chunk), bytes.length - position);
            System.arraycopy(bytes, (int) position, dst, off, n);
            return n;
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public void close() {
        }
    }

    /** Never makes progress and never reports the end. A loop testing {@code n < 0} hangs on it. */
    private static final class StalledReader implements SeekableByteReader {

        int reads;

        @Override
        public int read(long position, byte[] dst, int off, int len) {
            reads++;
            return 0;
        }

        @Override
        public long size() {
            return 64L;
        }

        @Override
        public void close() {
        }
    }

    @Test
    public void readFullyAssemblesShortReadsAndWritesOnlyTheRequestedWindow() throws IOException {
        ChunkedReader reader = new ChunkedReader(ramp(16), 3);
        byte[] dst = new byte[10];

        Resources.readFully(reader, 4L, dst, 2, 6);

        // Bytes 4..9 of the ramp are 5,6,7,8,9,10, landing at offset 2 and nowhere else.
        assertArrayEquals(new byte[] {0, 0, 5, 6, 7, 8, 9, 10, 0, 0}, dst);
        assertEquals("6 bytes in 3-byte chunks is two reads and no more", 2, reader.reads);
    }

    @Test
    public void readAtMostIssuesTheSameReadsAsReadFullyForASatisfiableRequest() throws IOException {
        ChunkedReader viaFully = new ChunkedReader(ramp(16), 3);
        byte[] a = new byte[7];
        Resources.readFully(viaFully, 1L, a, 0, 7);

        ChunkedReader viaAtMost = new ChunkedReader(ramp(16), 3);
        byte[] b = new byte[7];
        int got = Resources.readAtMost(viaAtMost, 1L, b, 0, 7);

        assertEquals(7, got);
        assertArrayEquals("the same bytes", a, b);
        assertEquals("and the same read pattern", viaFully.reads, viaAtMost.reads);
    }

    @Test
    public void readFullyToTheLastByteDoesNotThrow() throws IOException {
        ChunkedReader reader = new ChunkedReader(ramp(5), 2);
        byte[] dst = new byte[5];

        Resources.readFully(reader, 0L, dst, 0, 5);

        assertArrayEquals(ramp(5), dst);
    }

    @Test
    public void readFullyPastTheEndReportsTheOffsetItReachedNotTheOneItWanted() {
        ChunkedReader reader = new ChunkedReader(ramp(5), 2);
        try {
            Resources.readFully(reader, 2L, new byte[8], 0, 8);
            fail("reading 8 bytes from offset 2 of a 5-byte resource must throw");
        } catch (IOException e) {
            // Verbatim, because this message names the byte the read stopped at (2 + 3 available)
            // rather than the byte it was aiming for, and a caller diagnosing a truncated grid
            // reads it as a file offset.
            assertEquals("Unexpected end of resource at byte 5 (wanted 8 bytes from 2)",
                    e.getMessage());
            assertEquals("EOF is signalled as an EOFException, not a bare IOException",
                    EOFException.class, e.getClass());
        }
    }

    @Test
    public void readAtMostPastTheEndReturnsWhatThereWas() throws IOException {
        ChunkedReader reader = new ChunkedReader(ramp(5), 2);
        byte[] dst = new byte[8];

        int got = Resources.readAtMost(reader, 2L, dst, 0, 8);

        assertEquals(3, got);
        assertArrayEquals(new byte[] {3, 4, 5, 0, 0, 0, 0, 0}, dst);
    }

    @Test
    public void readFullyOfNothingReadsNothing() throws IOException {
        ChunkedReader reader = new ChunkedReader(ramp(5), 2);

        Resources.readFully(reader, 99L, new byte[4], 0, 0);

        assertEquals("a zero-length request must not touch the resource, even past its end",
                0, reader.reads);
    }

    @Test(timeout = 10000)
    public void aReaderThatReturnsZeroForeverIsTreatedAsTheEnd() {
        StalledReader stalled = new StalledReader();
        try {
            Resources.readFully(stalled, 0L, new byte[4], 0, 4);
            fail("a reader that never makes progress must not be reported as a complete read");
        } catch (IOException e) {
            assertEquals("Unexpected end of resource at byte 0 (wanted 4 bytes from 0)",
                    e.getMessage());
        }
        assertEquals("and it must be abandoned after one read, not retried", 1, stalled.reads);
    }

    @Test(timeout = 10000)
    public void readAtMostOfAStalledReaderReturnsZeroRatherThanSpinning() throws IOException {
        StalledReader stalled = new StalledReader();

        assertEquals(0, Resources.readAtMost(stalled, 0L, new byte[4], 0, 4));
        assertEquals(1, stalled.reads);
    }
}

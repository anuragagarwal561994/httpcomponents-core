/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.impl.nio.reactor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

import org.apache.http.Consts;
import org.apache.http.annotation.NotThreadSafe;
import org.apache.http.nio.reactor.SessionOutputBuffer;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.nio.util.ExpandableBuffer;
import org.apache.http.nio.util.HeapByteBufferAllocator;
import org.apache.http.util.Args;
import org.apache.http.util.CharArrayBuffer;

/**
 * Default implementation of {@link SessionOutputBuffer} based on
 * the {@link ExpandableBuffer} class.
 *
 * @since 4.0
 */
@NotThreadSafe
public class SessionOutputBufferImpl extends ExpandableBuffer implements SessionOutputBuffer {

    private static final byte[] CRLF = new byte[] {Consts.CR, Consts.LF};

    private final CharsetEncoder charencoder;
    private final int lineBuffersize;

    private CharBuffer charbuffer;

    /**
     *  Creates SessionOutputBufferImpl instance.
     *
     * @param buffersize input buffer size
     * @param lineBuffersize buffer size for line operations. Has effect only if
     *   {@code charencoder} is not {@code null}.
     * @param charencoder charencoder to be used for encoding HTTP protocol elements.
     *   If {@code null} simple type cast will be used for char to byte conversion.
     * @param allocator memory allocator.
     *   If {@code null} {@link HeapByteBufferAllocator#INSTANCE} will be used.
     *
     * @since 4.3
     */
    public SessionOutputBufferImpl(
            final int buffersize,
            final int lineBuffersize,
            final CharsetEncoder charencoder,
            final ByteBufferAllocator allocator) {
        super(buffersize, allocator != null ? allocator : HeapByteBufferAllocator.INSTANCE);
        this.lineBuffersize = Args.positive(lineBuffersize, "Line buffer size");
        this.charencoder = charencoder;
    }

    /**
     * @since 4.3
     */
    public SessionOutputBufferImpl(final int buffersize) {
        this(buffersize, 256, null, HeapByteBufferAllocator.INSTANCE);
    }

    /**
     * @since 4.3
     */
    public SessionOutputBufferImpl(
            final int buffersize,
            final int linebuffersize,
            final Charset charset) {
        this(buffersize, linebuffersize,
                charset != null ? charset.newEncoder() : null, HeapByteBufferAllocator.INSTANCE);
    }

    /**
     * @since 4.3
     */
    public SessionOutputBufferImpl(
            final int buffersize,
            final int linebuffersize) {
        this(buffersize, linebuffersize, null, HeapByteBufferAllocator.INSTANCE);
    }

    @Override
    public int flush(final WritableByteChannel channel) throws IOException {
        Args.notNull(channel, "Channel");
        setOutputMode();
        return channel.write(buffer());
    }

    @Override
    public void write(final ByteBuffer src) {
        if (src == null) {
            return;
        }
        setInputMode();
        final int requiredCapacity = buffer().position() + src.remaining();
        ensureCapacity(requiredCapacity);
        buffer().put(src);
    }

    @Override
    public void write(final ReadableByteChannel src) throws IOException {
        if (src == null) {
            return;
        }
        setInputMode();
        src.read(buffer());
    }

    private void write(final byte[] b) {
        if (b == null) {
            return;
        }
        setInputMode();
        final int off = 0;
        final int len = b.length;
        final int requiredCapacity = buffer().position() + len;
        ensureCapacity(requiredCapacity);
        buffer().put(b, off, len);
    }

    private void writeCRLF() {
        write(CRLF);
    }

    @Override
    public void writeLine(final CharArrayBuffer linebuffer) throws CharacterCodingException {
        if (linebuffer == null) {
            return;
        }
        setInputMode();
        // Do not bother if the buffer is empty
        if (linebuffer.length() > 0 ) {
            if (this.charencoder == null) {
                final int requiredCapacity = buffer().position() + linebuffer.length();
                ensureCapacity(requiredCapacity);
                if (buffer().hasArray()) {
                    final byte[] b = buffer().array();
                    final int len = linebuffer.length();
                    final int off = buffer().position();
                    for (int i = 0; i < len; i++) {
                        b[off + i]  = (byte) linebuffer.charAt(i);
                    }
                    buffer().position(off + len);
                } else {
                    for (int i = 0; i < linebuffer.length(); i++) {
                        buffer().put((byte) linebuffer.charAt(i));
                    }
                }
            } else {
                if (this.charbuffer == null) {
                    this.charbuffer = CharBuffer.allocate(this.lineBuffersize);
                }
                this.charencoder.reset();
                // transfer the string in small chunks
                int remaining = linebuffer.length();
                int offset = 0;
                while (remaining > 0) {
                    int l = this.charbuffer.remaining();
                    boolean eol = false;
                    if (remaining <= l) {
                        l = remaining;
                        // terminate the encoding process
                        eol = true;
                    }
                    this.charbuffer.put(linebuffer.buffer(), offset, l);
                    this.charbuffer.flip();

                    boolean retry = true;
                    while (retry) {
                        final CoderResult result = this.charencoder.encode(this.charbuffer, buffer(), eol);
                        if (result.isError()) {
                            result.throwException();
                        }
                        if (result.isOverflow()) {
                            expand();
                        }
                        retry = !result.isUnderflow();
                    }
                    this.charbuffer.compact();
                    offset += l;
                    remaining -= l;
                }
                // flush the encoder
                boolean retry = true;
                while (retry) {
                    final CoderResult result = this.charencoder.flush(buffer());
                    if (result.isError()) {
                        result.throwException();
                    }
                    if (result.isOverflow()) {
                        expand();
                    }
                    retry = !result.isUnderflow();
                }
            }
        }
        writeCRLF();
    }

}

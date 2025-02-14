/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /**
     * 一条消息的最大长度
     *
     * Maximum length of a frame we're willing to decode.
     */
    private final int maxLength;
    /**
     * 是否快速失败
     *
     * 当 true 时，未找到消息，但是超过最大长度，则马上触发 Exception 到下一个节点
     * 当 false 时，未找到消息，但是超过最大长度，需要匹配到一条消息后，再触发 Exception 到下一个节点
     *
     * Whether or not to throw an exception as soon as we exceed maxLength.
     */
    private final boolean failFast;
    /**
     * 是否过滤掉换行分隔符。
     *
     * 如果为 true ，解码的消息不包含换行符。
     */
    private final boolean stripDelimiter;

    /**
     * 是否处于废弃模式
     *
     * 如果为 true ，说明解析超过最大长度( maxLength )，结果还是找不到换行符
     *
     * True if we're discarding input because we're already over maxLength.
     */
    private boolean discarding;
    /**
     * 废弃的字节数
     */
    private int discardedBytes;

    /**
     * 最后扫描的位置
     *
     * Last scan position.
     */
    private int offset;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        // 获得换行符的位置
        final int eol = findEndOfLine(buffer);
        if (!discarding) { // 未处于废弃模式
            if (eol >= 0) { // 找到
                //计算分隔符和包长度
                final ByteBuf frame;
                final int length = eol - buffer.readerIndex(); // 读取长度
                final int delimLength = buffer.getByte(eol) == '\r' ? 2 : 1; // 分隔符的长度。2 为 `\r\n` ，1 为 `\n`

                // 丢弃异常数据
                if (length > maxLength) {
                    // 设置新的读取位置
                    buffer.readerIndex(eol + delimLength);
                    // 触发 Exception 到下一个节点
                    fail(ctx, length);
                    // 返回 null ，即未解码到消息
                    return null;
                }

                // 取包的时候是否包括分隔符
                if (stripDelimiter) {
                    frame = buffer.readRetainedSlice(length);
                    buffer.skipBytes(delimLength); // 忽略换行符
                } else {
                    frame = buffer.readRetainedSlice(length + delimLength);
                }

                // 返回解码的消息
                return frame;
            } else { // 未找到
                final int length = buffer.readableBytes();
                // 超过最大长度
                if (length > maxLength) {
                    // 记录 discardedBytes
                    discardedBytes = length;
                    // 跳到写入位置
                    buffer.readerIndex(buffer.writerIndex());
                    // 标记 discarding 为废弃模式
                    discarding = true;
                    // 重置 offset
                    offset = 0;
                    // 如果快速失败，则触发 Exception 到下一个节点
                    if (failFast) {
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null;
            }
        } else { // 处于废弃模式
            if (eol >= 0) { // 找到
                final int length = discardedBytes + eol - buffer.readerIndex(); // 读取长度
                final int delimLength = buffer.getByte(eol) == '\r' ? 2 : 1; // 分隔符的长度。2 为 `\r\n` ，1 为 `\n`
                // 设置新的读取位置
                buffer.readerIndex(eol + delimLength);
                // 重置 discardedBytes
                discardedBytes = 0;
                // 设置 discarding 不为废弃模式
                discarding = false;
                // 如果不为快速失败，则触发 Exception 到下一个节点
                if (!failFast) {
                    fail(ctx, length);
                }
            } else { // 未找到
                // 增加 discardedBytes
                discardedBytes += buffer.readableBytes();
                // 跳到写入位置
                buffer.readerIndex(buffer.writerIndex());
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(new TooLongFrameException("frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes();
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        // 找到
        if (i >= 0) {
            // 重置 offset
            offset = 0;
            // 如果前一个字节位 `\r` ，说明找到的是 `\n` ，所以需要 -1 ，因为寻找的是首个换行符的位置
            if (i > 0 && buffer.getByte(i - 1) == '\r') {
                i--;
            }
        // 未找到，记录 offset
        } else {
            offset = totalLength;
        }
        return i;
    }

}

/*
 *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
 *   Copyright (C) 2022 SonicCloudOrg
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published
 *   by the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.cloud.sonic.agent.tests.ios.mjpeg;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.ByteBuffer;

/**
 * ***************************************************************************
 * This code is built with reference to Swebcam-capture
 *
 * @see {https://github.com/sarxos/webcam-capture/blob/master/webcam-capture/src/main/java/com/github/sarxos/webcam/util/MjpegInputStream.java}
 * ***************************************************************************
 */
@Slf4j
public class MjpegInputStream extends DataInputStream {
    private final byte[] SOI_MARKER = {(byte) 0xFF, (byte) 0xD8};
    private final byte[] EOI_MARKER = {(byte) 0xFF, (byte) 0xD9};
    private final String CONTENT_LENGTH = "Content-Length".toLowerCase();
    private final static int HEADER_MAX_LENGTH = 100;
    //    private final static int FRAME_MAX_LENGTH = 1024 * 5 + HEADER_MAX_LENGTH;
    private final static int FRAME_MAX_LENGTH = 1024 * 100 + HEADER_MAX_LENGTH;  // 调整为100KB

    // 新增线程局部变量用于缓存缓冲区
    private static final ThreadLocal<byte[]> threadLocalBuffer = new ThreadLocal<>();

    public MjpegInputStream(final InputStream in) {
        super(new BufferedInputStream(in, FRAME_MAX_LENGTH));
    }

    private int getEndOfSequence(final DataInputStream in, final byte[] sequence) throws IOException {
        int s = 0;
        byte b;
        for (int i = 0; i < FRAME_MAX_LENGTH * 2; i++) {
            b = (byte) in.readUnsignedByte();
            if (b == sequence[s]) {
                if (++s == sequence.length) {
                    return i + 1;
                }
            } else {
                s = 0;
            }
        }
        log.warn("Failed to find sequence in {} bytes", FRAME_MAX_LENGTH * 2);
        return -1;
    }

    private int getStartOfSequence(final DataInputStream in, final byte[] sequence) throws IOException {
        int end = getEndOfSequence(in, sequence);
        return end < 0 ? -1 : end - sequence.length;
    }

    private int parseContentLength(final byte[] headerBytes) {
        // 使用 try-with-resources 自动关闭资源
        try (ByteArrayInputStream bais = new ByteArrayInputStream(headerBytes);
             InputStreamReader isr = new InputStreamReader(bais);
             BufferedReader br = new BufferedReader(isr)) {

            String line;
            while ((line = br.readLine()) != null) {
                if (line.toLowerCase().startsWith(CONTENT_LENGTH)) {
                    String[] parts = line.split(":", 2); // 限制分割次数
                    if (parts.length == 2) {
                        int value = Integer.parseInt(parts[1].trim());
                        if (value < 0) {
                            log.warn("Negative content-length: {}", value);
                            return 0;
                        }
                        return value;
                    }
                }
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid content-length format", e);
            throw e; // 保持原有异常抛出
        } catch (IOException e) {
            log.error("Header parsing error", e);
        }
        return 0;
    }

    public ByteBuffer readFrameForByteBuffer() throws IOException {
        mark(FRAME_MAX_LENGTH);
        int n = getStartOfSequence(this, SOI_MARKER);
        reset();
        final byte[] header = new byte[n];
        readFully(header);
        int length;
        try {
            length = parseContentLength(header);
        } catch (NumberFormatException e) {
            length = getEndOfSequence(this, EOI_MARKER);
        }
        if (length == 0) {
            log.error("EOI Marker 0xFF,0xD9 not found!");
        }
        reset();

        // 优化点：使用线程局部变量缓存缓冲区
        byte[] frame = threadLocalBuffer.get();
        if (frame == null || frame.length < length) {
//        frame = new byte[Math.min(length, FRAME_MAX_LENGTH)];
//        threadLocalBuffer.set(frame);
            // 修复点：确保新缓冲区长度不超过预设最大值，同时满足本次读取需求
            int bufferSize = Math.min(Math.max(length, FRAME_MAX_LENGTH), FRAME_MAX_LENGTH * 4);
            frame = new byte[bufferSize];
            threadLocalBuffer.set(frame);
        }

        skipBytes(n);
//    readFully(frame, 0, length);
        // 修复点：根据实际缓冲区长度调整读取量
        int bytesToRead = Math.min(length, frame.length);
        readFully(frame, 0, bytesToRead);
        // 修复点：添加异常数据检测
        if (bytesToRead < length) {
            log.warn("Frame truncated! Expected {} bytes but only read {} bytes", length, bytesToRead);
        }

        return ByteBuffer.wrap(frame, 0, length);
    }


    @Override
    public void close() throws IOException {
        super.close();
    }
}
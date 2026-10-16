package com.rea.directos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parser minimo de protobuf (wire format) — equivalente al de rb_live.py. */
public final class Pb {

    private Pb() {}

    public static final class Field {
        public final int no;
        public final int wire;
        public final long varint;   // valido si wire == 0
        public final byte[] bytes;  // valido si wire == 2

        Field(int no, int wire, long varint, byte[] bytes) {
            this.no = no;
            this.wire = wire;
            this.varint = varint;
            this.bytes = bytes;
        }
    }

    public static List<Field> parse(byte[] buf) throws IllegalArgumentException {
        List<Field> fields = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < buf.length) {
            long key = readVarint(buf, pos);
            int fno = (int) (key >>> 3);
            int wire = (int) (key & 7);
            switch (wire) {
                case 0: {
                    long v = readVarint(buf, pos);
                    fields.add(new Field(fno, wire, v, null));
                    break;
                }
                case 2: {
                    int len = (int) readVarint(buf, pos);
                    byte[] out = new byte[len];
                    System.arraycopy(buf, pos[0], out, 0, len);
                    pos[0] += len;
                    fields.add(new Field(fno, wire, 0, out));
                    break;
                }
                case 5: {
                    pos[0] += 4;
                    fields.add(new Field(fno, wire, 0, null));
                    break;
                }
                case 1: {
                    pos[0] += 8;
                    fields.add(new Field(fno, wire, 0, null));
                    break;
                }
                default:
                    throw new IllegalArgumentException("wire " + wire);
            }
        }
        return fields;
    }

    private static long readVarint(byte[] buf, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = buf[pos[0]++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
    }

    public static String utf8(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }
}

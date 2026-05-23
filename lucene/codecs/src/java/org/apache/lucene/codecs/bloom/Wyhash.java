/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.codecs.bloom;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Fast 64-bit non-cryptographic hash used by {@link SBBFuzzySet}. {@code hash16} is a 16-byte fast
 * path (single 64x64-&gt;128 multiply with XOR-fold), and {@code hashVar} is a variable-length
 * fasthash64 loop. The SBBF probe needs a single well-mixed 64-bit value (one half selects the
 * block, the other half drives the eight lane bits), so the 128-bit MurmurHash3 used by {@link
 * FuzzySet} is more than the split-block layout requires.
 *
 * @lucene.experimental
 */
public final class Wyhash {

  private static final long M = 0x880355f21e6d1965L;

  /**
   * byte[]-as-long view in little-endian order; lowers to a single load on little-endian hardware
   * (no byte-swap) and to load+bswap on big-endian.
   */
  private static final VarHandle BYTES_AS_LONG_LE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  private Wyhash() {}

  public static long hash(byte[] data, int offset, int length, long seed) {
    return length == 16 ? hash16(data, offset, seed) : hashVar(data, offset, length, seed);
  }

  public static long hash16(byte[] data, int offset, long seed) {
    return mix128(readLong(data, offset), readLong(data, offset + 8), seed);
  }

  @SuppressWarnings("fallthrough")
  public static long hashVar(byte[] data, int offset, int length, long seed) {
    long h = (long) length * M ^ seed;
    int nblocks = length >>> 3;
    for (int i = 0; i < nblocks; i++) {
      long k = readLong(data, offset + (i << 3));
      k *= M;
      k ^= k >>> 23;
      k *= M;
      h ^= k;
      h *= M;
    }
    int tailStart = offset + (nblocks << 3);
    long t = 0;
    switch (length & 7) {
      case 7:
        t ^= ((long) (data[tailStart + 6] & 0xff)) << 48;
      // fall through
      case 6:
        t ^= ((long) (data[tailStart + 5] & 0xff)) << 40;
      // fall through
      case 5:
        t ^= ((long) (data[tailStart + 4] & 0xff)) << 32;
      // fall through
      case 4:
        t ^= ((long) (data[tailStart + 3] & 0xff)) << 24;
      // fall through
      case 3:
        t ^= ((long) (data[tailStart + 2] & 0xff)) << 16;
      // fall through
      case 2:
        t ^= ((long) (data[tailStart + 1] & 0xff)) << 8;
      // fall through
      case 1:
        t ^= (long) (data[tailStart] & 0xff);
        t *= M;
        t ^= t >>> 23;
        t *= M;
        h ^= t;
        h *= M;
        break;
      default:
        break;
    }
    h ^= h >>> 23;
    h *= M;
    h ^= h >>> 23;
    return h;
  }

  /** 64x64-&gt;128 multiply, XOR-fold high and low, XOR seed. */
  private static long mix128(long a, long b, long seed) {
    long lo = a * b;
    long hi = Math.unsignedMultiplyHigh(a, b);
    return (lo ^ hi) ^ seed;
  }

  private static long readLong(byte[] data, int offset) {
    return (long) BYTES_AS_LONG_LE.get(data, offset);
  }
}

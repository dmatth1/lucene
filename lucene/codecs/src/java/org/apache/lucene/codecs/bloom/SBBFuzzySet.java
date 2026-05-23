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

import java.io.IOException;
import org.apache.lucene.codecs.bloom.FuzzySet.ContainsResult;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.RamUsageEstimator;

/**
 * A Split Block Bloom Filter (Apache Parquet layout) presenting the same probe contract as {@link
 * FuzzySet}: it can never say definitively YES (always MAYBE) but can definitively say NO.
 *
 * <p>Unlike {@link FuzzySet}, which scatters its K bits across the whole bitset and so touches up to
 * K cache lines per probe, an SBBF clusters all K=8 bits for a key into a single 256-bit block, so
 * every probe touches exactly one cache line. The block is selected by the high 32 bits of a single
 * {@link Wyhash} value; the low 32 bits drive the eight lane bits via the Parquet SALT constants
 * ({@code bit = (key * SALT[i]) >>> 27}). Reference: Putze, Sanders, Singler, "Cache-, Hash- and
 * Space-Efficient Bloom Filters" (JEA 2007). The bitset is bit-identical to other Parquet SBBF
 * implementations (arrow-cpp, arrow-rs, Velox, DuckDB, Impala) for the same 64-bit hash input.
 *
 * <p>An SBBF is sized once, up front, for the expected number of distinct values: it cannot be
 * downsized after the fact the way {@link FuzzySet} can, because the cheap re-projection trick that
 * relies on whole-bitset hashing does not apply to the split-block geometry. Callers therefore size
 * it from an up-front estimate (e.g. the segment's {@code maxDoc}).
 *
 * <p>This class is NOT threadsafe for concurrent writes; concurrent reads are safe.
 *
 * @lucene.experimental
 */
public class SBBFuzzySet implements Accountable {

  private static final long BASE_RAM_BYTES_USED =
      RamUsageEstimator.shallowSizeOfInstance(SBBFuzzySet.class);

  /** Parquet SBBF SALT constants, verbatim from the spec. */
  static final int[] SALT = {
    0x47b6137b, 0x44974d91, 0x8824ad5b, 0xa2b7289d,
    0x705495c7, 0x2df1424b, 0x9efc4947, 0x5c6bfb31,
  };

  /** Number of bits set (and tested) per key. */
  static final int K = 8;

  /** Each block is 256 bits == eight 32-bit lanes. */
  static final int INTS_PER_BLOCK = 8;

  /** Seed for the {@link Wyhash} probe path; insert and test must use the same seed. */
  static final long WYHASH_SEED = 0xCAFEBABEDEADBEEFL;

  /**
   * Upper bound on block count accepted from a (possibly corrupt) stream. {@code 1 << 27} blocks ==
   * 4 GiB of bitset, far past any sane filter; rejecting beyond it avoids an OOM from a bogus length.
   */
  static final int MAX_NUM_BLOCKS = 1 << 27;

  // numBlocks * INTS_PER_BLOCK lanes, little-endian per the Parquet layout.
  private final int[] data;
  private final int numBlocks;
  private final int blockMask; // numBlocks - 1; numBlocks is a power of two

  SBBFuzzySet(int[] data, int numBlocks) {
    if (numBlocks <= 0 || (numBlocks & (numBlocks - 1)) != 0) {
      throw new IllegalArgumentException(
          "numBlocks must be a positive power of two; got " + numBlocks);
    }
    if (data.length != numBlocks * INTS_PER_BLOCK) {
      throw new IllegalArgumentException(
          "data length " + data.length + " != numBlocks*8 (" + (numBlocks * INTS_PER_BLOCK) + ')');
    }
    this.data = data;
    this.numBlocks = numBlocks;
    this.blockMask = numBlocks - 1;
  }

  /** Block count this filter was sized to; package-visible for tests and benchmarks. */
  int numBlocks() {
    return numBlocks;
  }

  /**
   * Allocate an SBBF sized for {@code maxNumUniqueValues} at a target false-positive rate of {@code
   * targetFpp}. Uses the classical Bloom bits/element budget, then rounds the block count up to a
   * power of two. SBBF K=8 yields a lower observed FP rate than the requested target at the same
   * bits/element budget.
   */
  public static SBBFuzzySet createOptimalSet(int maxNumUniqueValues, float targetFpp) {
    if (maxNumUniqueValues <= 0) {
      throw new IllegalArgumentException("maxNumUniqueValues must be positive; got " + maxNumUniqueValues);
    }
    if (targetFpp <= 0f || targetFpp >= 1f) {
      throw new IllegalArgumentException("targetFpp must be in (0, 1); got " + targetFpp);
    }
    double bits = -((double) maxNumUniqueValues * Math.log(targetFpp)) / (Math.log(2) * Math.log(2));
    long blocks = nextPow2(Math.max(1L, (long) Math.ceil(bits / (INTS_PER_BLOCK * Integer.SIZE))));
    return createWithNumBlocks((int) Math.min(blocks, MAX_NUM_BLOCKS));
  }

  /** Allocate an empty SBBF with exactly {@code numBlocks} blocks (must be a power of two). */
  public static SBBFuzzySet createWithNumBlocks(int numBlocks) {
    return new SBBFuzzySet(new int[numBlocks * INTS_PER_BLOCK], numBlocks);
  }

  /** Records a value in the set. */
  public void addValue(BytesRef value) {
    addHash(Wyhash.hash(value.bytes, value.offset, value.length, WYHASH_SEED));
  }

  /**
   * Determines set membership. Like {@link FuzzySet}, returns NO or MAYBE rather than a boolean: a
   * MAYBE may be a false positive, but a NO is always definitive.
   */
  public ContainsResult contains(BytesRef value) {
    long hash = Wyhash.hash(value.bytes, value.offset, value.length, WYHASH_SEED);
    return testHash(hash) ? ContainsResult.MAYBE : ContainsResult.NO;
  }

  void addHash(long hash) {
    int base = ((int) (hash >>> 32) & blockMask) << 3;
    int key = (int) hash;
    int[] d = data;
    for (int i = 0; i < K; i++) {
      d[base + i] |= 1 << ((key * SALT[i]) >>> 27);
    }
  }

  boolean testHash(long hash) {
    int base = ((int) (hash >>> 32) & blockMask) << 3;
    int key = (int) hash;
    int[] d = data;
    for (int i = 0; i < K; i++) {
      if ((d[base + i] >>> ((key * SALT[i]) >>> 27) & 1) == 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Serializes the set:
   *
   * <ul>
   *   <li>NumBlocks --&gt; {@link DataOutput#writeInt Uint32} number of 256-bit blocks (a power of
   *       two)
   *   <li>Lane<sup>NumBlocks*8</sup> --&gt; {@link DataOutput#writeInt Uint32} the bitset lanes
   * </ul>
   */
  public void serialize(DataOutput out) throws IOException {
    out.writeInt(numBlocks);
    for (int lane : data) {
      out.writeInt(lane);
    }
  }

  public static SBBFuzzySet deserialize(DataInput in) throws IOException {
    int numBlocks = in.readInt();
    if (numBlocks <= 0 || (numBlocks & (numBlocks - 1)) != 0 || numBlocks > MAX_NUM_BLOCKS) {
      throw new IOException("Invalid SBBF numBlocks (not a power of two in (0, " + MAX_NUM_BLOCKS + "]): " + numBlocks);
    }
    int[] data = new int[numBlocks * INTS_PER_BLOCK];
    in.readInts(data, 0, data.length);
    return new SBBFuzzySet(data, numBlocks);
  }

  /** Fraction of lane bits set; a coarse fill gauge analogous to {@link FuzzySet#getSaturation()}. */
  public float getSaturation() {
    long set = 0;
    for (int lane : data) {
      set += Integer.bitCount(lane);
    }
    return (float) ((double) set / ((long) data.length * Integer.SIZE));
  }

  @Override
  public long ramBytesUsed() {
    return BASE_RAM_BYTES_USED + RamUsageEstimator.sizeOf(data);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(k=" + K + ", numBlocks=" + numBlocks + ')';
  }

  static long nextPow2(long x) {
    if (x <= 1) {
      return 1;
    }
    x--;
    x |= x >>> 1;
    x |= x >>> 2;
    x |= x >>> 4;
    x |= x >>> 8;
    x |= x >>> 16;
    x |= x >>> 32;
    return x + 1;
  }
}

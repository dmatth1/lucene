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
package org.apache.lucene.benchmark.jmh;

import java.util.concurrent.TimeUnit;
import org.apache.lucene.codecs.bloom.Wyhash;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.StringHelper;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Isolates the hash cost of a bloom probe: MurmurHash3-x64-128 (what {@link
 * org.apache.lucene.codecs.bloom.FuzzySet FuzzySet} uses) versus {@link Wyhash} (what {@link
 * org.apache.lucene.codecs.bloom.SBBFuzzySet SBBFuzzySet} uses). Subtracting these from the full
 * {@link BloomFilterProbeBenchmark} probe latencies separates how much of the SBBF speedup is the
 * cheaper hash versus the single-cache-line block geometry.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3)
@State(Scope.Benchmark)
public class BloomHashBenchmark {

  @Param({"murmur128", "wyhash"})
  public String hash;

  private static final long SEED = 0xCAFEBABEDEADBEEFL;

  private byte[][] keys;
  private int next;
  private boolean isWyhash;

  @Setup(Level.Trial)
  public void setup() {
    isWyhash = "wyhash".equals(hash);
    keys = new byte[1 << 14][];
    for (int i = 0; i < keys.length; i++) {
      byte[] k = new byte[16];
      long a = (long) i * 0x9E3779B97F4A7C15L;
      long b = (i ^ 0xD1B54A32D192ED03L) * 0xBF58476D1CE4E5B9L;
      for (int j = 0; j < 8; j++) {
        k[j] = (byte) (a >>> (8 * j));
        k[8 + j] = (byte) (b >>> (8 * j));
      }
      keys[i] = k;
    }
  }

  @Benchmark
  public void hash(Blackhole bh) {
    byte[] k = keys[(next = (next + 1) & (keys.length - 1))];
    if (isWyhash) {
      bh.consume(Wyhash.hash(k, 0, 16, SEED));
    } else {
      bh.consume(StringHelper.murmurhash3_x64_128(new BytesRef(k)));
    }
  }
}

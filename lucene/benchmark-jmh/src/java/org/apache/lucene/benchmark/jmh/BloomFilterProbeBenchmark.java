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

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.codecs.bloom.FuzzySet;
import org.apache.lucene.codecs.bloom.SBBFuzzySet;
import org.apache.lucene.util.BytesRef;
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
 * Per-probe latency of the Split Block Bloom Filter ({@link SBBFuzzySet}) against the classical
 * {@link FuzzySet}, both sized for the same target false-positive rate. Tiers are picked so the
 * filter footprint lands in L2 (S), L3 (M), around-L3 (L) or DRAM (XL); a primary-key field on a
 * large segment is closest to XL.
 *
 * <p>The {@code contains} path measured here includes the hash (MurmurHash3-x64-128 for classical,
 * {@link org.apache.lucene.codecs.bloom.Wyhash Wyhash} for SBBF) plus the bitset probes, which is
 * what {@code BloomFilteringPostingsFormat} and {@code SBBFBloomFilteringPostingsFormat} pay on
 * every {@code seekExact}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgsAppend = "-Xmx2g")
@State(Scope.Benchmark)
public class BloomFilterProbeBenchmark {

  @Param({"S", "M", "L", "XL"})
  public String tier;

  @Param({"miss", "hit"})
  public String workload;

  @Param({"sbbf", "classical"})
  public String impl;

  private static final float TARGET_FPP = 0.01f;
  private static final int MAX_INSERTED = 1 << 20;

  private SBBFuzzySet sbbf;
  private FuzzySet classical;
  private boolean isSbbf;
  private BytesRef[] probeKeys;
  private int next;

  @Setup(Level.Trial)
  public void setup() {
    int n =
        switch (tier) {
          case "S" -> 16_384;
          case "M" -> 262_144;
          case "L" -> 4_194_304;
          case "XL" -> 67_108_864;
          default -> throw new IllegalArgumentException("unknown tier: " + tier);
        };

    isSbbf = "sbbf".equals(impl);
    if (isSbbf) {
      sbbf = SBBFuzzySet.createOptimalSet(n, TARGET_FPP);
    } else {
      classical = FuzzySet.createOptimalSet(n, TARGET_FPP);
    }

    int inserted = Math.min(n, MAX_INSERTED);
    BytesRef[] insertedKeys = new BytesRef[inserted];
    Random r = new Random(0xCAFEBABEL);
    for (int i = 0; i < inserted; i++) {
      BytesRef key = randomKey(r);
      insertedKeys[i] = key;
      if (isSbbf) {
        sbbf.addValue(key);
      } else {
        classical.addValue(key);
      }
    }

    if ("hit".equals(workload)) {
      probeKeys = insertedKeys;
    } else {
      probeKeys = new BytesRef[1 << 14];
      Random r2 = new Random(0xDEADBEEFL);
      for (int i = 0; i < probeKeys.length; i++) {
        probeKeys[i] = randomKey(r2);
      }
    }
  }

  private static BytesRef randomKey(Random r) {
    byte[] k = new byte[16];
    r.nextBytes(k);
    return new BytesRef(k);
  }

  @Benchmark
  public void probe(Blackhole bh) {
    BytesRef key = probeKeys[(next = (next + 1) & (probeKeys.length - 1))];
    if (isSbbf) {
      bh.consume(sbbf.contains(key));
    } else {
      bh.consume(classical.contains(key));
    }
  }
}

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
@Fork(value = 3, jvmArgsAppend = "-Xmx4g")
@State(Scope.Benchmark)
public class BloomFilterProbeBenchmark {

  @Param({"S", "M", "L", "XL"})
  public String tier;

  /**
   * {@code miss} is the dominant pattern for a primary-key existence check (the term is in some
   * other segment); {@code hit} is the term-is-here case.
   */
  @Param({"miss", "hit"})
  public String workload;

  @Param({"sbbf", "classical"})
  public String impl;

  /**
   * {@code 0.1023} is Lucene's {@link
   * org.apache.lucene.codecs.bloom.DefaultBloomFilterFactory} default (classical K is then ~4-6);
   * {@code 0.01} is a stricter target that drives classical K up to ~7-12 and so flatters a
   * fixed-K=8 SBBF.
   */
  @Param({"0.1023", "0.01"})
  public float fpp;

  /** Cap on the hit probe-set size so XL stays within heap; the filter is still filled to {@code n}. */
  private static final int MAX_PROBE_KEYS = 1 << 20;

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
          case "XL" -> 16_777_216;
          default -> throw new IllegalArgumentException("unknown tier: " + tier);
        };

    isSbbf = "sbbf".equals(impl);
    if (isSbbf) {
      sbbf = SBBFuzzySet.createOptimalSet(n, fpp);
    } else {
      classical = FuzzySet.createOptimalSet(n, fpp);
    }

    // Fill the filter to its design point: insert all n distinct keys so saturation is realistic.
    // Keep a bounded sample of inserted keys for the hit probe set.
    int sampleEvery = Math.max(1, n / MAX_PROBE_KEYS);
    int sampleSize = (n + sampleEvery - 1) / sampleEvery;
    BytesRef[] hitSample = new BytesRef[sampleSize];
    int s = 0;
    for (int i = 0; i < n; i++) {
      BytesRef key = distinctKey(i);
      if (isSbbf) {
        sbbf.addValue(key);
      } else {
        classical.addValue(key);
      }
      if (i % sampleEvery == 0 && s < hitSample.length) {
        hitSample[s++] = key;
      }
    }

    if ("hit".equals(workload)) {
      probeKeys = hitSample;
    } else {
      probeKeys = new BytesRef[1 << 14];
      for (int i = 0; i < probeKeys.length; i++) {
        // Keys outside the inserted [0, n) domain: definite non-members (modulo the filter's FP).
        probeKeys[i] = distinctKey(n + 0x5000_0000L + i);
      }
    }
  }

  /** A deterministic, distinct 16-byte key for ordinal {@code i}. */
  private static BytesRef distinctKey(long i) {
    byte[] k = new byte[16];
    long a = i * 0x9E3779B97F4A7C15L;
    long b = (i ^ 0xD1B54A32D192ED03L) * 0xBF58476D1CE4E5B9L;
    for (int j = 0; j < 8; j++) {
      k[j] = (byte) (a >>> (8 * j));
      k[8 + j] = (byte) (b >>> (8 * j));
    }
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

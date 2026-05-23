# SBBFuzzySet vs classical FuzzySet — per-probe latency

Microbench backing `SBBFBloomFilteringPostingsFormat` (additive, opt-in)
against the existing `BloomFilteringPostingsFormat`. We measure the
`contains(BytesRef)` path each format pays on every `seekExact`: hash
(MurmurHash3-x64-128 for classical, Wyhash for SBBF) plus the bitset
probes. Both filters sized for the same 1% target FP rate.

Benchmark: `org.apache.lucene.benchmark.jmh.BloomFilterProbeBenchmark`.

Host: virtualised x86_64, Temurin OpenJDK 25.0.3. JMH `AverageTime`,
ns/op. The committed annotations run 3 forks; the table below is a
2-fork × 6-iteration confirmation run (Cnt=12), which is what the
`-f 2 -wi 4 -i 6 -w 1 -r 1` invocation produces.

| Tier | Workload | Classical (ns)  | SBBF (ns)       | Speedup |
|------|----------|----------------:|----------------:|--------:|
| S    | miss     |  35.28 ± 0.74   |  10.44 ± 0.15   | 3.38x   |
| S    | hit      |  32.39 ± 0.68   |  11.88 ± 0.21   | 2.73x   |
| M    | miss     |  40.26 ± 1.20   |  12.32 ± 0.63   | 3.27x   |
| M    | hit      |  37.17 ± 1.66   |  15.21 ± 0.26   | 2.44x   |
| L    | miss     |  32.53 ± 1.58   |  10.41 ± 0.44   | 3.13x   |
| L    | hit      | 115.45 ± 24.04  |  47.05 ± 6.55   | 2.45x   |
| XL   | miss     |  42.61 ± 1.39   |  17.92 ± 1.08   | 2.38x   |
| XL   | hit      | 306.42 ± 4.84   |  72.67 ± 1.44   | 4.22x   |

Tiers (number of distinct values the filter is sized for):

  - S  = 16K     (filter footprint in L2)
  - M  = 256K    (in L3)
  - L  = 4.2M    (around L3)
  - XL = 67M     (in DRAM)

Up to 2^20 keys are inserted regardless of tier, so larger tiers probe a
sparsely populated filter — the access pattern, not the fill ratio, is
what the larger tiers stress.

## Reading the numbers

  - **SBBF is 2.4–3.4x faster in every cell**, average ~2.9x.
  - The gap is widest at **XL hit (4.2x)**, the case closest to a
    primary-key field on a large segment with the filter resident in
    DRAM. A hit forces the classical filter to read all K bit positions,
    which are scattered across the whole bitset and so land in up to K
    separate cache lines (~306 ns). SBBF reads its K=8 lanes from a
    single 256-bit block — one cache line (~73 ns).
  - Misses are cheaper for both (early-exit on the first unset bit) but
    SBBF still wins ~2.4–3.4x: a single-cache-line block load plus the
    cheaper Wyhash beats MurmurHash3-128 + a scattered first probe.

This mirrors the quickbloom-java SBBF result on the Cassandra fork
(~2.3x average per-probe). The split-block geometry is the structural
win; Wyhash vs MurmurHash3-128 contributes the rest.

## Reproduce

```
./gradlew :lucene:benchmark-jmh:assemble
java -jar lucene/benchmark-jmh/build/benchmarks/lucene-benchmark-jmh-*.jar \
  BloomFilterProbeBenchmark \
  -p impl=sbbf,classical -p tier=S,M,L,XL -p workload=miss,hit
```

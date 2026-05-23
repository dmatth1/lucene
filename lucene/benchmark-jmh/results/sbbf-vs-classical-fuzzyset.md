# SBBFuzzySet vs classical FuzzySet — per-probe latency

Microbench backing `SBBFBloomFilteringPostingsFormat` (additive, opt-in)
against the existing `BloomFilteringPostingsFormat`. We measure the
`contains(BytesRef)` path each format pays on every `seekExact`: the hash
(MurmurHash3-x64-128 for classical, Wyhash for SBBF) plus the bitset
probes.

Benchmarks: `BloomFilterProbeBenchmark` (full probe) and
`BloomHashBenchmark` (hash only). Host: virtualised x86_64, Temurin
OpenJDK 25.0.3. JMH `AverageTime`, ns/op, 2 forks × 5 iterations (the
committed `@Fork` is 3 — re-run for publication-grade error bars).

## Read this first: the configuration matters more than the family

An earlier version of this bench sized both filters at a 1% target FP and
left them nearly empty. Both choices inflated SBBF's lead and the numbers
should not be trusted. The corrected setup:

  - **Target FP = 0.1023** — what `DefaultBloomFilterFactory` actually
    uses. At that target the classical `FuzzySet` picks **K ≈ 4–6**
    (not the K=7–8 that makes a strong SBBF target; the 2× over-size in
    `FuzzySet.createOptimalSet` keeps K from dropping to ~3). The 1% case
    drives classical to K ≈ 7–12 and is reported below only for contrast.
  - **Filled to design saturation** — all `n` keys inserted, so a
    classical miss reads ~2 bit positions (≈53% saturation) across ~2
    cache lines instead of early-exiting on a near-empty filter.

## Default config (FP 0.1023, classical K≈6) — the honest headline

| Tier | Workload | Classical (ns)  | SBBF (ns)      | Speedup |
|------|----------|----------------:|---------------:|--------:|
| S    | miss     |  34.50 ± 0.68   |  17.07 ± 0.39  | 2.02x   |
| M    | miss     |  38.38 ± 0.78   |  19.15 ± 0.69  | 2.00x   |
| L    | miss     |  54.44 ± 2.55   |  26.11 ± 0.93  | 2.09x   |
| XL   | miss     |  66.72 ± 1.71   |  30.36 ± 0.53  | 2.20x   |
| S    | hit      |  25.90 ± 0.26   |  11.51 ± 0.19  | 2.25x   |
| M    | hit      |  32.12 ± 1.02   |  15.78 ± 0.41  | 2.04x   |
| L    | hit      |  58.74 ± 5.44   |  36.49 ± 4.54  | 1.61x   |
| XL   | hit      | 199.05 ± 6.17   |  78.17 ± 10.6  | 2.55x   |

**~2x across the board, even on the miss-heavy path.** Not the 2.4–4.2x
the first (mis-configured) run reported, and not the ~1.0–1.3x one might
predict assuming K≈3 + early-exit — the real K is ~6 and the filter is
saturated, so a classical miss is ~2 scattered cache-line reads.

## Strict config (FP 0.01, classical K≈7–12) — for contrast only

| Tier | Workload | Classical (ns)  | SBBF (ns)      | Speedup |
|------|----------|----------------:|---------------:|--------:|
| S    | miss     |  36.13 ± 0.83   |  11.01 ± 0.30  | 3.28x   |
| M    | miss     |  40.76 ± 0.71   |  13.11 ± 0.32  | 3.11x   |
| L    | miss     |  59.80 ± 2.62   |  18.48 ± 0.44  | 3.24x   |
| XL   | miss     |  72.19 ± 2.00   |  22.13 ± 0.39  | 3.26x   |
| S    | hit      |  33.47 ± 1.17   |  11.85 ± 0.15  | 2.82x   |
| M    | hit      |  37.77 ± 1.64   |  16.47 ± 0.79  | 2.29x   |
| L    | hit      | 162.51 ± 52.0   |  52.49 ± 15.1  | 3.10x   |
| XL   | hit      | 276.99 ± 7.03   |  64.91 ± 0.83  | 4.27x   |

This is the K=7–8 "strong SBBF target" shape. It is not Lucene's default.

## Hash vs layout: what is the speedup actually made of?

`BloomHashBenchmark` (16-byte keys, cache-resident):

| Hash                 | ns/op        |
|----------------------|-------------:|
| MurmurHash3-x64-128  | 14.30 ± 0.23 |
| Wyhash               |  2.29 ± 0.04 |

A flat **~12 ns** hash delta, independent of tier. Subtracting it from the
full probe attributes the rest to bitset geometry (default-FP miss):

| Tier | Total speedup | Hash share | Layout-only (bitset) speedup |
|------|--------------:|-----------:|-----------------------------:|
| S    | 2.02x         | ~69%       | 1.37x                        |
| M    | 2.00x         | ~67%       | 1.43x                        |
| L    | 2.09x         | ~50%       | 1.69x                        |
| XL   | 2.20x         | ~33%       | 1.87x                        |

  - On **cache-resident** filters there are no cache misses to save, so the
    win is **mostly the cheaper hash** (~67%).
  - On **DRAM-resident** filters the **single-cache-line block geometry
    dominates** (~67% of the win; 1.87x on the bitset alone) — classical
    pays ~2 scattered cache-line misses, SBBF pays 1.

Implication: the hash half of the win is capturable in the *existing*
`FuzzySet` by swapping MurmurHash3-128 for a cheaper 64-bit hash (though
that is itself a bloom-format change for existing indexes). The part
genuinely unique to SBBF is the cache-line geometry — worth ~1.4–1.9x on
the bitset, growing with filter size and cache pressure.

## Big-picture impact on Lucene: CPU on a niche path, not latency

The bloom filter lives in `BloomFilteringPostingsFormat`, an **opt-in**
postings format used for primary-key / low-doc-frequency fields. Its job
is to avoid the *next* step on a NO: a terms-dictionary lookup (FST +
block decode) and possibly a disk/SSD seek — **hundreds of ns to ms**. The
probe itself is 30–300 ns.

  - **Miss (bloom says NO):** the value is the *avoided seek*, identical
    whether the probe is 67 ns or 30 ns. SBBF does not avoid more seeks
    than classical (comparable FP). **Read latency: unchanged.**
  - **Hit (bloom says MAYBE):** the probe is pure overhead before the full
    lookup runs anyway. Halving ~100 ns off a hundreds-of-ns-to-ms
    operation is **<1%, usually <0.1%.**

So these numbers do **not** move query or read latency in any user-visible
way. The only place the probe is a meaningful fraction is a
**high-throughput, fully-cached, miss-heavy primary-key existence check**
(bulk upsert/dedup ingest) — and even there it is **low-single-digit-%
CPU**, with the dominant ingest cost being analysis + indexing. The
defensible framing is "additive, lower-CPU, slightly-better-FP-at-iso-
memory bloom for PK-heavy ingest," not "faster queries."

## Reproduce

```
./gradlew :lucene:benchmark-jmh:assemble
java -jar lucene/benchmark-jmh/build/benchmarks/lucene-benchmark-jmh-*.jar \
  BloomFilterProbeBenchmark -p impl=sbbf,classical -p tier=S,M,L,XL \
  -p workload=miss,hit -p fpp=0.1023,0.01
java -jar lucene/benchmark-jmh/build/benchmarks/lucene-benchmark-jmh-*.jar \
  BloomHashBenchmark -p hash=murmur128,wyhash
```

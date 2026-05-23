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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.lucene.codecs.bloom.FuzzySet.ContainsResult;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BytesRef;

public class TestSBBFuzzySet extends LuceneTestCase {

  public void testNoFalseNegatives() throws IOException {
    int n = atLeast(2000);
    SBBFuzzySet set = SBBFuzzySet.createOptimalSet(n, 0.01f);
    List<BytesRef> added = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      BytesRef v = new BytesRef("key-" + i + "-" + random().nextLong());
      set.addValue(v);
      added.add(v);
    }
    // A Bloom filter must never report NO for a value it has seen.
    for (BytesRef v : added) {
      assertEquals(ContainsResult.MAYBE, set.contains(v));
    }
  }

  public void testFalsePositiveRateIsReasonable() throws IOException {
    int n = 50_000;
    SBBFuzzySet set = SBBFuzzySet.createOptimalSet(n, 0.01f);
    Set<String> inserted = new HashSet<>();
    for (int i = 0; i < n; i++) {
      String s = "present-" + i;
      inserted.add(s);
      set.addValue(new BytesRef(s));
    }
    int trials = 100_000;
    int falsePositives = 0;
    for (int i = 0; i < trials; i++) {
      String s = "absent-" + i;
      assertFalse(inserted.contains(s));
      if (set.contains(new BytesRef(s)) == ContainsResult.MAYBE) {
        falsePositives++;
      }
    }
    double fpRate = (double) falsePositives / trials;
    // Sized for 1%; SBBF K=8 typically beats the target. Allow generous slack against flakiness.
    assertTrue("FP rate too high: " + fpRate, fpRate < 0.03);
  }

  public void testSerializeRoundTrip() throws IOException {
    int n = atLeast(1000);
    SBBFuzzySet set = SBBFuzzySet.createOptimalSet(n, 0.05f);
    List<BytesRef> added = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      BytesRef v = new BytesRef("term" + random().nextInt(10 * n));
      set.addValue(v);
      added.add(v);
    }

    ByteBuffersDataOutput out = new ByteBuffersDataOutput();
    set.serialize(out);
    byte[] bytes = out.toArrayCopy();

    SBBFuzzySet restored = SBBFuzzySet.deserialize(new ByteArrayDataInput(bytes));
    assertEquals(set.numBlocks(), restored.numBlocks());
    for (BytesRef v : added) {
      assertEquals(ContainsResult.MAYBE, restored.contains(v));
    }
    // Same bitset bits => same saturation.
    assertEquals(set.getSaturation(), restored.getSaturation(), 0f);
  }

  public void testNumBlocksIsPowerOfTwo() {
    for (int values : new int[] {1, 10, 1000, 100_000, 5_000_000}) {
      SBBFuzzySet set = SBBFuzzySet.createOptimalSet(values, 0.01f);
      int nb = set.numBlocks();
      assertTrue("not a power of two: " + nb, nb > 0 && (nb & (nb - 1)) == 0);
    }
  }

  public void testRejectsBadBlockCount() {
    expectThrows(
        IllegalArgumentException.class, () -> new SBBFuzzySet(new int[8 * 3], 3)); // not pow2
    expectThrows(
        IllegalArgumentException.class,
        () -> new SBBFuzzySet(new int[7], 1)); // wrong data length
  }

  public void testDeserializeRejectsCorruptLength() {
    byte[] bad = new byte[4];
    // numBlocks = 3 (not a power of two)
    new org.apache.lucene.store.ByteArrayDataOutput(bad).writeInt(3);
    expectThrows(
        IOException.class, () -> SBBFuzzySet.deserialize(new ByteArrayDataInput(bad)));
  }
}

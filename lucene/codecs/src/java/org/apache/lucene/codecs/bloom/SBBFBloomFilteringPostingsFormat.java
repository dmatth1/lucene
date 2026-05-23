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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.NormsProducer;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.bloom.FuzzySet.ContainsResult;
import org.apache.lucene.index.BaseTermsEnum;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOBooleanSupplier;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.automaton.CompiledAutomaton;

/**
 * An <em>additive, opt-in</em> alternative to {@link BloomFilteringPostingsFormat} that backs its
 * per-field bloom filter with a Split Block Bloom Filter ({@link SBBFuzzySet}) instead of the
 * classical {@link FuzzySet}. The on-disk format, file extension ({@code .sblm}) and codec name are
 * all distinct, so this format coexists with the existing one rather than replacing it: indexes
 * already written with {@link BloomFilteringPostingsFormat} are unaffected, and there is no
 * format-version break to negotiate.
 *
 * <p>The motivation is probe latency. {@link FuzzySet} scatters K bits across the whole bitset, so a
 * negative probe can miss in up to K separate cache lines; an SBBF clusters all K bits for a key
 * into one 256-bit block, so every probe touches exactly one cache line, and the probe hash is
 * {@link Wyhash} rather than the 128-bit MurmurHash3 the split-block layout does not need. See
 * {@link SBBFuzzySet} for the algorithm.
 *
 * <p>Because an SBBF is sized once up front and cannot be downsized, the filter is allocated from
 * the segment's {@code maxDoc()} (an upper bound on the number of distinct terms) at a target
 * false-positive rate. This format is therefore best suited, like the classical one, to
 * low-doc-frequency fields such as primary keys.
 *
 * <p>The format of the {@code .sblm} file is:
 *
 * <ul>
 *   <li>SBBFBloomFilter (.sblm) --&gt; Header, DelegatePostingsFormatName, NumFilteredFields,
 *       Filter<sup>NumFilteredFields</sup>, Footer
 *   <li>Filter --&gt; FieldNumber, SBBFuzzySet
 *   <li>SBBFuzzySet --&gt; See {@link SBBFuzzySet#serialize(DataOutput)}
 *   <li>Header --&gt; {@link CodecUtil#writeIndexHeader IndexHeader}
 *   <li>DelegatePostingsFormatName --&gt; {@link DataOutput#writeString(String) String}
 *   <li>NumFilteredFields --&gt; {@link DataOutput#writeInt Uint32}
 *   <li>FieldNumber --&gt; {@link DataOutput#writeInt Uint32}
 *   <li>Footer --&gt; {@link CodecUtil#writeFooter CodecFooter}
 * </ul>
 *
 * @lucene.experimental
 */
public class SBBFBloomFilteringPostingsFormat extends PostingsFormat {

  public static final String BLOOM_CODEC_NAME = "SBBFBloomFilter";
  public static final int VERSION_START = 0;
  public static final int VERSION_CURRENT = VERSION_START;

  /** Extension of the SBBF bloom filter file. */
  static final String BLOOM_EXTENSION = "sblm";

  /** Default target false-positive rate, matching {@link DefaultBloomFilterFactory}. */
  static final float DEFAULT_TARGET_FPP = 0.1023f;

  private final PostingsFormat delegatePostingsFormat;
  private final float targetFpp;

  /**
   * @param delegatePostingsFormat the PostingsFormat that records all the non-bloom postings data
   * @param targetFpp target max false-positive rate used to size each field's filter
   */
  public SBBFBloomFilteringPostingsFormat(PostingsFormat delegatePostingsFormat, float targetFpp) {
    super(BLOOM_CODEC_NAME);
    this.delegatePostingsFormat = delegatePostingsFormat;
    this.targetFpp = targetFpp;
  }

  public SBBFBloomFilteringPostingsFormat(PostingsFormat delegatePostingsFormat) {
    this(delegatePostingsFormat, DEFAULT_TARGET_FPP);
  }

  // Used only by core Lucene at read-time via Service Provider instantiation - do not use at
  // Write-time in application code.
  public SBBFBloomFilteringPostingsFormat() {
    this(null, DEFAULT_TARGET_FPP);
  }

  /**
   * Allocates the (empty) filter for a field. Sizes from {@code maxDoc} since an SBBF cannot be
   * downsized after accumulation. Overridable so tests can cap memory.
   */
  protected SBBFuzzySet createFilter(SegmentWriteState state, FieldInfo info) {
    int maxNumValues = Math.max(1, state.segmentInfo.maxDoc());
    return SBBFuzzySet.createOptimalSet(maxNumValues, targetFpp);
  }

  @Override
  public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
    if (delegatePostingsFormat == null) {
      throw new UnsupportedOperationException(
          "Error - "
              + getClass().getName()
              + " has been constructed without a choice of PostingsFormat");
    }
    FieldsConsumer fieldsConsumer = delegatePostingsFormat.fieldsConsumer(state);
    return new SBBFBloomFilteredFieldsConsumer(fieldsConsumer, state);
  }

  @Override
  public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
    return new SBBFBloomFilteredFieldsProducer(state);
  }

  static class SBBFBloomFilteredFieldsProducer extends FieldsProducer {
    private FieldsProducer delegateFieldsProducer;
    private final HashMap<String, SBBFuzzySet> bloomsByFieldName = new HashMap<>();

    public SBBFBloomFilteredFieldsProducer(SegmentReadState state) throws IOException {
      String bloomFileName =
          IndexFileNames.segmentFileName(
              state.segmentInfo.name, state.segmentSuffix, BLOOM_EXTENSION);
      try (ChecksumIndexInput bloomIn = state.directory.openChecksumInput(bloomFileName)) {
        CodecUtil.checkIndexHeader(
            bloomIn,
            BLOOM_CODEC_NAME,
            VERSION_START,
            VERSION_CURRENT,
            state.segmentInfo.getId(),
            state.segmentSuffix);
        PostingsFormat delegatePostingsFormat = PostingsFormat.forName(bloomIn.readString());
        this.delegateFieldsProducer = delegatePostingsFormat.fieldsProducer(state);
        int numBlooms = bloomIn.readInt();
        for (int i = 0; i < numBlooms; i++) {
          int fieldNum = bloomIn.readInt();
          SBBFuzzySet bloom = SBBFuzzySet.deserialize(bloomIn);
          FieldInfo fieldInfo = state.fieldInfos.fieldInfo(fieldNum);
          bloomsByFieldName.put(fieldInfo.name, bloom);
        }
        CodecUtil.checkFooter(bloomIn);
      } catch (Throwable t) {
        IOUtils.closeWhileSuppressingExceptions(t, delegateFieldsProducer);
        throw t;
      }
    }

    @Override
    public Iterator<String> iterator() {
      return delegateFieldsProducer.iterator();
    }

    @Override
    public void close() throws IOException {
      delegateFieldsProducer.close();
    }

    @Override
    public Terms terms(String field) {
      SBBFuzzySet filter = bloomsByFieldName.get(field);
      if (filter == null) {
        return delegateFieldsProducer.terms(field);
      } else {
        Terms result = delegateFieldsProducer.terms(field);
        if (result == null) {
          return null;
        }
        return new SBBFBloomFilteredTerms(result, filter);
      }
    }

    @Override
    public int size() {
      return delegateFieldsProducer.size();
    }

    static class SBBFBloomFilteredTerms extends Terms {
      private final Terms delegateTerms;
      private final SBBFuzzySet filter;

      public SBBFBloomFilteredTerms(Terms terms, SBBFuzzySet filter) {
        this.delegateTerms = terms;
        this.filter = filter;
      }

      @Override
      public TermsEnum intersect(CompiledAutomaton compiled, final BytesRef startTerm)
          throws IOException {
        return delegateTerms.intersect(compiled, startTerm);
      }

      @Override
      public TermsEnum iterator() throws IOException {
        return new SBBFBloomFilteredTermsEnum(delegateTerms, filter);
      }

      @Override
      public long size() throws IOException {
        return delegateTerms.size();
      }

      @Override
      public long getSumTotalTermFreq() throws IOException {
        return delegateTerms.getSumTotalTermFreq();
      }

      @Override
      public long getSumDocFreq() throws IOException {
        return delegateTerms.getSumDocFreq();
      }

      @Override
      public int getDocCount() {
        return delegateTerms.getDocCount();
      }

      @Override
      public boolean hasFreqs() {
        return delegateTerms.hasFreqs();
      }

      @Override
      public boolean hasOffsets() {
        return delegateTerms.hasOffsets();
      }

      @Override
      public boolean hasPositions() {
        return delegateTerms.hasPositions();
      }

      @Override
      public boolean hasPayloads() {
        return delegateTerms.hasPayloads();
      }

      @Override
      public BytesRef getMin() throws IOException {
        return delegateTerms.getMin();
      }

      @Override
      public BytesRef getMax() throws IOException {
        return delegateTerms.getMax();
      }
    }

    static final class SBBFBloomFilteredTermsEnum extends BaseTermsEnum {
      private Terms delegateTerms;
      private TermsEnum delegateTermsEnum;
      private final SBBFuzzySet filter;

      public SBBFBloomFilteredTermsEnum(Terms delegateTerms, SBBFuzzySet filter) {
        this.delegateTerms = delegateTerms;
        this.filter = filter;
      }

      void reset(Terms delegateTerms) {
        this.delegateTerms = delegateTerms;
        this.delegateTermsEnum = null;
      }

      private TermsEnum delegate() throws IOException {
        if (delegateTermsEnum == null) {
          delegateTermsEnum = delegateTerms.iterator();
        }
        return delegateTermsEnum;
      }

      @Override
      public BytesRef next() throws IOException {
        return delegate().next();
      }

      @Override
      public IOBooleanSupplier prepareSeekExact(BytesRef text) throws IOException {
        if (filter.contains(text) == ContainsResult.NO) {
          return null;
        }
        return delegate().prepareSeekExact(text);
      }

      @Override
      public boolean seekExact(BytesRef text) throws IOException {
        if (filter.contains(text) == ContainsResult.NO) {
          return false;
        }
        return delegate().seekExact(text);
      }

      @Override
      public SeekStatus seekCeil(BytesRef text) throws IOException {
        return delegate().seekCeil(text);
      }

      @Override
      public void seekExact(long ord) throws IOException {
        delegate().seekExact(ord);
      }

      @Override
      public BytesRef term() throws IOException {
        return delegate().term();
      }

      @Override
      public long ord() throws IOException {
        return delegate().ord();
      }

      @Override
      public int docFreq() throws IOException {
        return delegate().docFreq();
      }

      @Override
      public long totalTermFreq() throws IOException {
        return delegate().totalTermFreq();
      }

      @Override
      public PostingsEnum postings(PostingsEnum reuse, int flags) throws IOException {
        return delegate().postings(reuse, flags);
      }

      @Override
      public ImpactsEnum impacts(int flags) throws IOException {
        return delegate().impacts(flags);
      }

      @Override
      public boolean preferSeekExact() {
        return true;
      }

      @Override
      public String toString() {
        return getClass().getSimpleName() + "(filter=" + filter.toString() + ")";
      }
    }

    @Override
    public void checkIntegrity() throws IOException {
      delegateFieldsProducer.checkIntegrity();
    }

    @Override
    public String toString() {
      return getClass().getSimpleName()
          + "(fields="
          + bloomsByFieldName.size()
          + ",delegate="
          + delegateFieldsProducer
          + ")";
    }
  }

  class SBBFBloomFilteredFieldsConsumer extends FieldsConsumer {
    private final FieldsConsumer delegateFieldsConsumer;
    private final Map<FieldInfo, SBBFuzzySet> bloomFilters = new HashMap<>();
    private final SegmentWriteState state;

    public SBBFBloomFilteredFieldsConsumer(FieldsConsumer fieldsConsumer, SegmentWriteState state) {
      this.delegateFieldsConsumer = fieldsConsumer;
      this.state = state;
    }

    @Override
    public void write(Fields fields, NormsProducer norms) throws IOException {
      // Delegate must write first: it may have opened files on creating the class, and write() will
      // close them.
      delegateFieldsConsumer.write(fields, norms);

      for (String field : fields) {
        Terms terms = fields.terms(field);
        if (terms == null) {
          continue;
        }
        FieldInfo fieldInfo = state.fieldInfos.fieldInfo(field);
        TermsEnum termsEnum = terms.iterator();

        SBBFuzzySet bloomFilter = null;
        PostingsEnum postingsEnum = null;
        while (true) {
          BytesRef term = termsEnum.next();
          if (term == null) {
            break;
          }
          if (bloomFilter == null) {
            bloomFilter = createFilter(state, fieldInfo);
            assert bloomFilters.containsKey(fieldInfo) == false;
            bloomFilters.put(fieldInfo, bloomFilter);
          }
          // Make sure there's at least one doc for this term:
          postingsEnum = termsEnum.postings(postingsEnum, 0);
          if (postingsEnum.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
            bloomFilter.addValue(term);
          }
        }
      }
    }

    private boolean closed;

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      delegateFieldsConsumer.close();

      String bloomFileName =
          IndexFileNames.segmentFileName(
              state.segmentInfo.name, state.segmentSuffix, BLOOM_EXTENSION);
      try (IndexOutput bloomOutput = state.directory.createOutput(bloomFileName, state.context)) {
        CodecUtil.writeIndexHeader(
            bloomOutput,
            BLOOM_CODEC_NAME,
            VERSION_CURRENT,
            state.segmentInfo.getId(),
            state.segmentSuffix);
        bloomOutput.writeString(delegatePostingsFormat.getName());
        bloomOutput.writeInt(bloomFilters.size());
        for (Map.Entry<FieldInfo, SBBFuzzySet> entry : bloomFilters.entrySet()) {
          bloomOutput.writeInt(entry.getKey().number);
          entry.getValue().serialize(bloomOutput);
        }
        CodecUtil.writeFooter(bloomOutput);
      }
      bloomFilters.clear();
    }
  }

  @Override
  public String toString() {
    return "SBBFBloomFilteringPostingsFormat(" + delegatePostingsFormat + ")";
  }
}

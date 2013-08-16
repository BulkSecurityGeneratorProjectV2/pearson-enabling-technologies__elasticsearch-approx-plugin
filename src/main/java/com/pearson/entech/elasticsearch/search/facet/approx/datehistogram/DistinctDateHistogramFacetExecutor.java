package com.pearson.entech.elasticsearch.search.facet.approx.datehistogram;

import java.io.IOException;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.joda.TimeZoneRounding;
import org.elasticsearch.common.trove.ExtTHashMap;
import org.elasticsearch.common.trove.ExtTLongObjectHashMap;
import org.elasticsearch.common.trove.map.TLongObjectMap;
import org.elasticsearch.common.trove.map.hash.TLongIntHashMap;
import org.elasticsearch.common.trove.map.hash.TObjectIntHashMap;
import org.elasticsearch.common.trove.procedure.TObjectProcedure;
import org.elasticsearch.index.fielddata.BytesValues;
import org.elasticsearch.index.fielddata.LongValues;
import org.elasticsearch.index.fielddata.LongValues.Iter;
import org.elasticsearch.index.fielddata.plain.LongArrayIndexFieldData;
import org.elasticsearch.search.facet.FacetExecutor;
import org.elasticsearch.search.facet.InternalFacet;

import com.pearson.entech.elasticsearch.search.facet.approx.datehistogram.DistinctDateHistogramFacet.ComparatorType;

public class DistinctDateHistogramFacetExecutor extends FacetExecutor {

    private final TypedFieldData _keyFieldData;
    private final TypedFieldData _distinctFieldData;
    private final TypedFieldData _sliceFieldData;

    private final BuildableCollector _collector;

    private final TimeZoneRounding _tzRounding;
    private final ComparatorType _comparatorType;
    private final int _maxExactPerShard;

    public DistinctDateHistogramFacetExecutor(final TypedFieldData keyFieldData, final TypedFieldData distinctFieldData, final TypedFieldData sliceFieldData,
            final TimeZoneRounding tzRounding, final ComparatorType comparatorType, final int maxExactPerShard) {
        _keyFieldData = keyFieldData;
        _distinctFieldData = distinctFieldData;
        _sliceFieldData = sliceFieldData;
        _tzRounding = tzRounding;
        _comparatorType = comparatorType;
        _maxExactPerShard = maxExactPerShard;
        if(_distinctFieldData == null && _sliceFieldData == null)
            _collector = new CountingCollector();
        else if(_distinctFieldData == null)
            _collector = new SlicedCollector();
        else if(_sliceFieldData == null)
            _collector = new DistinctCollector();
        else
            _collector = new SlicedDistinctCollector();
    }

    @Override
    public InternalFacet buildFacet(final String facetName) {
        return _collector.build();
    }

    @Override
    public Collector collector() {
        return _collector;
    }

    // TODO keep track of totals and missing values
    // TODO replace "new DistinctCountPayload()" with an object cache
    // TODO rename max_exact_per_shard to exact_threshold

    private class CountingCollector extends BuildableCollector {

        private LongValues _keyFieldValues;

        private TLongIntHashMap _counts;

        CountingCollector() {
            _counts = CacheRecycler.popLongIntMap();
        }

        @Override
        public void setNextReader(final AtomicReaderContext context) throws IOException {
            _keyFieldValues = ((LongArrayIndexFieldData) _keyFieldData.data)
                    .load(context).getLongValues();
        }

        @Override
        public void collect(final int doc) throws IOException {
            final Iter keyIter = _keyFieldValues.getIter(doc);
            while(keyIter.hasNext()) {
                final long time = _tzRounding.calc(keyIter.next());
                _counts.adjustOrPutValue(time, 1, 1);
            }
        }

        @Override
        public void postCollection() {}

        @Override
        public InternalFacet build() {
            final InternalFacet facet = new InternalCountingFacet(_counts, _comparatorType);
            _keyFieldValues = null;
            _counts = null;
            return facet;
        }

    }

    private class SlicedCollector extends BuildableCollector {

        private LongValues _keyFieldValues;
        private BytesValues _sliceFieldValues;

        private ExtTLongObjectHashMap<TObjectIntHashMap<BytesRef>> _counts;

        SlicedCollector() {
            _counts = CacheRecycler.popLongObjectMap();
        }

        @Override
        public void setNextReader(final AtomicReaderContext context) throws IOException {
            _keyFieldValues = ((LongArrayIndexFieldData) _keyFieldData.data)
                    .load(context).getLongValues();
            // TODO if these aren't strings, this isn't the most efficient way:
            _sliceFieldValues = _sliceFieldData.data.load(context).getBytesValues();
        }

        @Override
        public void collect(final int doc) throws IOException {
            final Iter keyIter = _keyFieldValues.getIter(doc);
            final org.elasticsearch.index.fielddata.BytesValues.Iter sliceIter =
                    _sliceFieldValues.getIter(doc);
            while(keyIter.hasNext()) {
                final long time = _tzRounding.calc(keyIter.next());
                while(sliceIter.hasNext()) {
                    // TODO we can reduce hash lookups by getting the outer map in the outer loop
                    incrementSafely(_counts, time, sliceIter.next());
                }
            }
        }

        @Override
        public void postCollection() {}

        @Override
        public InternalFacet build() {
            final InternalFacet facet = new InternalSlicedFacet(_counts, _comparatorType);
            _keyFieldValues = null;
            _sliceFieldValues = null;
            _counts = null;
            return facet;
        }

        private void incrementSafely(final TLongObjectMap<TObjectIntHashMap<BytesRef>> counts,
                final long key1, final BytesRef key2) {
            TObjectIntHashMap<BytesRef> subMap = counts.get(key1);
            if(subMap == null) {
                subMap = CacheRecycler.popObjectIntMap();
                counts.put(key1, subMap);
            }
            subMap.adjustOrPutValue(key2, 1, 1);
        }

    }

    private class DistinctCollector extends BuildableCollector {

        private LongValues _keyFieldValues;
        private BytesValues _distinctFieldValues;

        private final ExtTLongObjectHashMap<DistinctCountPayload> _counts;

        DistinctCollector() {
            _counts = CacheRecycler.popLongObjectMap();
        }

        @Override
        public void setNextReader(final AtomicReaderContext context) throws IOException {
            _keyFieldValues = ((LongArrayIndexFieldData) _keyFieldData.data)
                    .load(context).getLongValues();
            // TODO if these aren't strings, this isn't the most efficient way:
            _distinctFieldValues = _distinctFieldData.data.load(context).getBytesValues();
        }

        @Override
        public void collect(final int doc) throws IOException {
            final Iter keyIter = _keyFieldValues.getIter(doc);
            final org.elasticsearch.index.fielddata.BytesValues.Iter distinctIter =
                    _distinctFieldValues.getIter(doc);
            while(keyIter.hasNext()) {
                final long time = _tzRounding.calc(keyIter.next());
                while(distinctIter.hasNext()) {
                    // TODO we can reduce hash lookups by getting the outer map in the outer loop
                    final DistinctCountPayload count = getSafely(_counts, time);
                    while(distinctIter.hasNext()) {
                        count.update(distinctIter.next());
                    }
                }
            }
        }

        private DistinctCountPayload getSafely(final TLongObjectMap<DistinctCountPayload> counts, final long key) {
            DistinctCountPayload payload = counts.get(key);
            if(payload == null)
                payload = counts.put(key, new DistinctCountPayload(_maxExactPerShard));
            return payload;
        }

        @Override
        public void postCollection() {}

        @Override
        public InternalFacet build() {
            final InternalFacet facet = new InternalDistinctFacet(_counts, _comparatorType);
            _keyFieldValues = null;
            _distinctFieldValues = null;

            // TODO move this logic to internal facet
            CacheRecycler.pushLongObjectMap(_counts);
            return facet;
        }

    }

    private class SlicedDistinctCollector extends BuildableCollector {

        private LongValues _keyFieldValues;
        private BytesValues _distinctFieldValues;
        private BytesValues _sliceFieldValues;

        private final ExtTLongObjectHashMap<ExtTHashMap<BytesRef, DistinctCountPayload>> _counts;

        SlicedDistinctCollector() {
            _counts = CacheRecycler.popLongObjectMap();
        }

        @Override
        public void setNextReader(final AtomicReaderContext context) throws IOException {
            _keyFieldValues = ((LongArrayIndexFieldData) _keyFieldData.data)
                    .load(context).getLongValues();
            // TODO if these aren't strings, this isn't the most efficient way:
            _distinctFieldValues = _distinctFieldData.data.load(context).getBytesValues();
            _sliceFieldValues = _sliceFieldData.data.load(context).getBytesValues();
        }

        @Override
        public void collect(final int doc) throws IOException {
            final Iter keyIter = _keyFieldValues.getIter(doc);
            final org.elasticsearch.index.fielddata.BytesValues.Iter distinctIter =
                    _distinctFieldValues.getIter(doc);
            final org.elasticsearch.index.fielddata.BytesValues.Iter sliceIter =
                    _sliceFieldValues.getIter(doc);
            while(keyIter.hasNext()) {
                final long time = _tzRounding.calc(keyIter.next());
                while(sliceIter.hasNext()) {
                    // TODO we can reduce hash lookups by getting the outer map in the outer loop
                    final DistinctCountPayload count = getSafely(_counts, time, sliceIter.next());
                    while(distinctIter.hasNext()) {
                        count.update(distinctIter.next());
                    }
                }
            }
        }

        @Override
        public void postCollection() {}

        private DistinctCountPayload getSafely(
                final TLongObjectMap<ExtTHashMap<BytesRef, DistinctCountPayload>> counts,
                final long key1, final BytesRef key2) {
            ExtTHashMap<BytesRef, DistinctCountPayload> subMap = counts.get(key1);
            if(subMap == null) {
                subMap = CacheRecycler.popHashMap();
                counts.put(key1, subMap);
            }
            DistinctCountPayload payload = subMap.get(key2);
            if(payload == null) {
                payload = counts.get(key1).put(key2, new DistinctCountPayload(_maxExactPerShard));
            }
            return payload;
        }

        @Override
        public InternalFacet build() {
            final InternalFacet facet = new InternalSlicedDistinctFacet(_counts, _comparatorType);
            _keyFieldValues = null;
            _distinctFieldValues = null;
            _sliceFieldValues = null;

            // TODO move this logic to internal facet
            _counts.forEachValue(new TObjectProcedure<ExtTHashMap<BytesRef, DistinctCountPayload>>() {
                @Override
                public boolean execute(final ExtTHashMap<BytesRef, DistinctCountPayload> subMap) {
                    CacheRecycler.pushHashMap(subMap);
                    return true;
                }
            });
            CacheRecycler.pushLongObjectMap(_counts);
            return facet;
        }

    }

    private abstract class BuildableCollector extends Collector {

        abstract InternalFacet build();

    }

}

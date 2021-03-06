/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package htsjdk.samtools;

import htsjdk.samtools.seekablestream.SeekableStream;

import java.io.File;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Class for reading BAM file indices, caching each contig as it's loaded and
 * dropping values when the next contig is loaded.
 */
class CachingBAMFileIndex extends AbstractBAMFileIndex implements BrowseableBAMIndex
{
    // Since null is a valid return value for this index, it's possible to have lastReferenceIndex != null and
    // lastReference == null, this is effectively caching the return value null
    private Integer lastReferenceIndex = null;
    private BAMIndexContent lastReference = null;

    private long cacheHits = 0;
    private long cacheMisses = 0;

    public CachingBAMFileIndex(final File file, final SAMSequenceDictionary dictionary) {
        super(file, dictionary);
    }

    public CachingBAMFileIndex(final SeekableStream stream, final SAMSequenceDictionary dictionary) {
        super(stream, dictionary);
    }

    public CachingBAMFileIndex(final File file, final SAMSequenceDictionary dictionary, final boolean useMemoryMapping) {
        super(file, dictionary, useMemoryMapping);
    }

    /**
     * Get list of regions of BAM file that may contain SAMRecords for the given range
     * @param referenceIndex sequence of desired SAMRecords
     * @param startPos 1-based start of the desired interval, inclusive
     * @param endPos 1-based end of the desired interval, inclusive
     * @return the virtual file position.  Each pair is the first and last virtual file position
     *         in a range that can be scanned to find SAMRecords that overlap the given positions.
     *         May return null if there is no content overlapping the region.
     */
    @Override
    public BAMFileSpan getSpanOverlapping(final int referenceIndex, final int startPos, final int endPos) {
        final BAMIndexContent queryResults = getQueryResults(referenceIndex);

        if(queryResults == null)
            return null;

        final List<Chunk> chunkList = queryResults.getChunksOverlapping(startPos, endPos);
        if (chunkList == null) return null;

        return new BAMFileSpan(chunkList);
    }

    /**
     * Get a list of bins in the BAM file that may contain SAMRecords for the given range.
     * @param referenceIndex sequence of desired SAMRecords
     * @param startPos 1-based start of the desired interval, inclusive
     * @param endPos 1-based end of the desired interval, inclusive
     * @return a list of bins that contain relevant data.
     */
    @Override
    public BinList getBinsOverlapping(final int referenceIndex, final int startPos, final int endPos) {
        final BitSet regionBins = GenomicIndexUtil.regionToBins(startPos, endPos);
        if (regionBins == null) {
            return null;
        }
        return new BinList(referenceIndex,regionBins);        
    }

    /**
     * Perform an overlapping query of all bins bounding the given location.
     * @param bin The bin over which to perform an overlapping query.
     * @return The file pointers
     */
    @Override
    public BAMFileSpan getSpanOverlapping(final Bin bin) {
        if(bin == null)
            return null;

        final int referenceSequence = bin.getReferenceSequence();
        final BAMIndexContent indexQuery = getQueryResults(referenceSequence);

        if(indexQuery == null)
            return null;

        final int binLevel = getLevelForBin(bin);
        final int firstLocusInBin = getFirstLocusInBin(bin);

        // Add the specified bin to the tree if it exists.
        final List<Bin> binTree = new ArrayList<>();
        if(indexQuery.containsBin(bin))
            binTree.add(indexQuery.getBins().getBin(bin.getBinNumber()));

        int currentBinLevel = binLevel;
        while(--currentBinLevel >= 0) {
            final int binStart = getFirstBinInLevel(currentBinLevel);
            final int binWidth = getMaxAddressibleGenomicLocation()/getLevelSize(currentBinLevel);
            final int binNumber = firstLocusInBin/binWidth + binStart;
            final Bin parentBin = indexQuery.getBins().getBin(binNumber);
            if(parentBin != null && indexQuery.containsBin(parentBin))
                binTree.add(parentBin);
        }

        List<Chunk> chunkList = new ArrayList<Chunk>();
        for(final Bin coveringBin: binTree) {
            for(final Chunk chunk: coveringBin.getChunkList())
                chunkList.add(chunk.clone());
        }

        final int start = getFirstLocusInBin(bin);
        chunkList = Chunk.optimizeChunkList(chunkList,indexQuery.getLinearIndex().getMinimumOffset(start));
        return new BAMFileSpan(chunkList);
    }

    /**
     * Looks up the cached BAM query results if they're still in the cache and not expired.  Otherwise,
     * retrieves the cache results from disk.
     * @param referenceIndex The reference to load.  CachingBAMFileIndex only stores index data for entire references. 
     * @return The index information for this reference or null if no index information is available for the given index.
     */
    @Override
    protected BAMIndexContent getQueryResults(final int referenceIndex) {

        // If this query is for the same reference index as the last query, return it.
        // This compares a boxed Integer to an int with == which is ok because the Integer will be unboxed to the primitive value
        if(lastReferenceIndex!=null && lastReferenceIndex == referenceIndex){
            cacheHits++;
            return lastReference;
        }

        // If not attempt to load it from disk.
        final BAMIndexContent queryResults = query(referenceIndex,1,-1);
        cacheMisses++;
        lastReferenceIndex = referenceIndex;
        lastReference = queryResults;
        return lastReference;
    }

    public long getCacheHits() {
        return cacheHits;
    }

    public long getCacheMisses() {
        return cacheMisses;
    }
}

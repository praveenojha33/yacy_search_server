// plasmaDHTChunk.java 
// ------------------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@anomic.de
// first published on http://www.anomic.de
// Frankfurt, Germany, 2006
// created: 18.02.2006
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notive above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.

package de.anomic.plasma;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import de.anomic.index.indexContainer;
import de.anomic.index.indexTreeMapContainer;
import de.anomic.index.indexURLEntry;
import de.anomic.kelondro.kelondroBase64Order;
import de.anomic.kelondro.kelondroException;
import de.anomic.server.serverCodings;
import de.anomic.server.logging.serverLog;
import de.anomic.yacy.yacyCore;
import de.anomic.yacy.yacyDHTAction;
import de.anomic.yacy.yacySeedDB;

public class plasmaDHTChunk {

    public static final int chunkStatus_UNDEFINED = -1;
    public static final int chunkStatus_FAILED = 0;
    public static final int chunkStatus_FILLED = 1;
    public static final int chunkStatus_RUNNING = 2;
    public static final int chunkStatus_INTERRUPTED = 3;
    public static final int chunkStatus_COMPLETE = 4;
    
    public static final int peerRedundancy = 3;
    
    private plasmaWordIndex wordIndex;
    private serverLog log;
    private plasmaCrawlLURL lurls;
    
    private int status = chunkStatus_UNDEFINED;
    private String startPointHash;
    private indexTreeMapContainer[] indexContainers = null;
    private HashMap urlCache; // String (url-hash) / plasmaCrawlLURL.Entry
    private int idxCount;
    
    private long selectionStartTime = 0;
    private long selectionEndTime = 0;
    
    public indexTreeMapContainer firstContainer() {
        return indexContainers[0];
    }
    
    public indexTreeMapContainer lastContainer() {
        return indexContainers[indexContainers.length - 1];
    }
    
    public indexTreeMapContainer[] containers() {
        return indexContainers;
    }
    
    public int containerSize() {
        return indexContainers.length;
    }
    
    public int indexCount() {
        return this.idxCount;
    }
    
    private int indexCounter() {
        int c = 0;
        for (int i = 0; i < indexContainers.length; i++) {
            c += indexContainers[i].size();
        }
        return c;
    }
    
    public HashMap urlCacheMap() {
        return urlCache;
    }
    
    public void setStatus(int newStatus) {
        this.status = newStatus;
    }
    
    public int getStatus() {
        return this.status;
    }
    
    public plasmaDHTChunk(serverLog log, plasmaWordIndex wordIndex, plasmaCrawlLURL lurls, int minCount, int maxCount) {
        this.log = log;
        this.wordIndex = wordIndex;
        this.lurls = lurls;
        startPointHash = selectTransferStart();
        log.logFine("Selected hash " + startPointHash + " as start point for index distribution, distance = " + yacyDHTAction.dhtDistance(yacyCore.seedDB.mySeed.hash, startPointHash));
        selectTransferContainers(startPointHash, minCount, maxCount);

        // count the indexes, can be smaller as expected
        this.idxCount = indexCounter();
        if (idxCount < minCount) {
            log.logFine("Too few (" + idxCount + ") indexes selected for transfer.");
            this.status = chunkStatus_FAILED;
        }
    }

    public plasmaDHTChunk(serverLog log, plasmaWordIndex wordIndex, plasmaCrawlLURL lurls, int minCount, int maxCount, String startHash) {
        this.log = log;
        this.wordIndex = wordIndex;
        this.lurls = lurls;
        log.logFine("Demanded hash " + startHash + " as start point for index distribution, distance = " + yacyDHTAction.dhtDistance(yacyCore.seedDB.mySeed.hash, startPointHash));
        selectTransferContainers(startHash, minCount, maxCount);

        // count the indexes, can be smaller as expected
        this.idxCount = indexCounter();
        if (idxCount < minCount) {
            log.logFine("Too few (" + idxCount + ") indexes selected for transfer.");
            this.status = chunkStatus_FAILED;
        }
    }

    private String selectTransferStart() {
        String startPointHash;
        // first try to select with increasing probality a good start point
        double minimumDistance = ((double) peerRedundancy) / ((double) yacyCore.seedDB.sizeConnected());
        if (Math.round(Math.random() * 6) != 4)
            for (int i = 9; i > 0; i--) {
                startPointHash = kelondroBase64Order.enhancedCoder.encode(serverCodings.encodeMD5Raw(Long.toString(i + System.currentTimeMillis()))).substring(2, 2 + yacySeedDB.commonHashLength);
                if (yacyDHTAction.dhtDistance(yacyCore.seedDB.mySeed.hash, startPointHash) > (minimumDistance + ((double) i / (double) 10)))
                    return startPointHash;
            }
        // if that fails, take simply the best start point (this is usually avoided, since that leads to always the same target peers)
        startPointHash = yacyCore.seedDB.mySeed.hash.substring(0, 11) + "z";
        return startPointHash;
    }

    private void selectTransferContainers(String hash, int mincount, int maxcount) {        
        try {
            this.selectionStartTime = System.currentTimeMillis();
            int refcountRAM = selectTransferContainersResource(hash, plasmaWordIndex.RL_RAMCACHE, maxcount);
            if (refcountRAM >= mincount) {
                log.logFine("DHT selection from RAM: " + refcountRAM + " entries");
                return;
            }
            int refcountFile = selectTransferContainersResource(hash, plasmaWordIndex.RL_WORDFILES, maxcount);
            log.logFine("DHT selection from FILE: " + refcountFile + " entries, RAM provided only " + refcountRAM + " entries");
            return;
        } finally {
            this.selectionEndTime = System.currentTimeMillis();
        }
    }

    private int selectTransferContainersResource(String hash, int resourceLevel, int maxcount) {
        // the hash is a start hash from where the indexes are picked
        ArrayList tmpContainers = new ArrayList(maxcount);
        try {
            Iterator indexContainerIterator = wordIndex.indexContainerSet(hash, resourceLevel, true, maxcount).iterator();
            indexContainer container;
            Iterator urlIter;
            indexURLEntry indexEntry;
            plasmaCrawlLURL.Entry lurl;
            int refcount = 0;
            int wholesize;
            
            urlCache = new HashMap();
            double maximumDistance = ((double) peerRedundancy * 2) / ((double) yacyCore.seedDB.sizeConnected());
            
            while ((maxcount > refcount) && (indexContainerIterator.hasNext()) && ((container = (indexContainer) indexContainerIterator.next()) != null) && (container.size() > 0)
                            && ((tmpContainers.size() == 0) || (yacyDHTAction.dhtDistance(container.getWordHash(), ((indexTreeMapContainer) tmpContainers.get(0)).getWordHash()) < maximumDistance))) {
                // make an on-the-fly entity and insert values
                int notBoundCounter = 0;
                try {
                    wholesize = container.size();
                    urlIter = container.entries();
                    // iterate over indexes to fetch url entries and store them in the urlCache
                    while ((urlIter.hasNext()) && (maxcount > refcount)) {
                        indexEntry = (indexURLEntry) urlIter.next();
                        try {
                            lurl = lurls.getEntry(indexEntry.urlHash(), indexEntry);
                            if ((lurl == null) || (lurl.url() == null)) {
                                notBoundCounter++;
                                urlIter.remove();
                                wordIndex.removeEntries(container.getWordHash(), new String[] { indexEntry.urlHash() }, true);
                            } else {
                                urlCache.put(indexEntry.urlHash(), lurl);
                                refcount++;
                            }
                        } catch (IOException e) {
                            notBoundCounter++;
                            urlIter.remove();
                            wordIndex.removeEntries(container.getWordHash(), new String[] { indexEntry.urlHash() }, true);
                        }
                    }

                    // remove all remaining; we have enough
                    while (urlIter.hasNext()) {
                        indexEntry = (indexURLEntry) urlIter.next();
                        urlIter.remove();
                    }

                    // use whats left
                    log.logFine("Selected partial index (" + container.size() + " from " + wholesize + " URLs, " + notBoundCounter + " not bound) for word " + container.getWordHash());
                    tmpContainers.add(container);
                } catch (kelondroException e) {
                    log.logSevere("plasmaWordIndexDistribution/2: deleted DB for word " + container.getWordHash(), e);
                    wordIndex.deleteContainer(container.getWordHash());
                }
            }
            // create result
            indexContainers = (indexTreeMapContainer[]) tmpContainers.toArray(new indexTreeMapContainer[tmpContainers.size()]);

            if ((indexContainers == null) || (indexContainers.length == 0)) {
                log.logFine("No index available for index transfer, hash start-point " + startPointHash);
                this.status = chunkStatus_FAILED;
                return 0;
            }

            this.status = chunkStatus_FILLED;
            
            return refcount;
        } catch (kelondroException e) {
            log.logSevere("selectTransferIndexes database corrupted: " + e.getMessage(), e);
            indexContainers = new indexTreeMapContainer[0];
            urlCache = new HashMap();
            this.status = chunkStatus_FAILED;
            return 0;
        } catch (IOException e) {
            log.logSevere("selectTransferIndexes database corrupted: " + e.getMessage(), e);
            indexContainers = new indexTreeMapContainer[0];
            urlCache = new HashMap();
            this.status = chunkStatus_FAILED;
            return 0;
        }
    }
    
    
    public int deleteTransferIndexes() {
        Iterator urlIter;
        indexURLEntry indexEntry;
        String[] urlHashes;
        int count = 0;
        for (int i = 0; i < this.indexContainers.length; i++) {
            // delete entries separately
            int c = 0;
            urlHashes = new String[this.indexContainers[i].size()];
            urlIter = this.indexContainers[i].entries();
            while (urlIter.hasNext()) {
                indexEntry = (indexURLEntry) urlIter.next();
                urlHashes[c++] = indexEntry.urlHash();
            }
            count += wordIndex.removeEntries(this.indexContainers[i].getWordHash(), urlHashes, true);
            log.logFine("Deleted partial index (" + c + " URLs) for word " + this.indexContainers[i].getWordHash() + "; " + this.wordIndex.indexSize(indexContainers[i].getWordHash()) + " entries left");
            this.indexContainers[i] = null;
        }
        return count;
    }
    
    public long getSelectionTime() {
        if (this.selectionStartTime == 0 || this.selectionEndTime == 0) return -1;
        return this.selectionEndTime-this.selectionStartTime;
    }
}

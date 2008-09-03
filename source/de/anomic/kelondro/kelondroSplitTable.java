// kelondroSplitTable.java
// (C) 2006 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 12.10.2006 on http://www.anomic.de
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2006-04-02 22:40:07 +0200 (So, 02 Apr 2006) $
// $LastChangedRevision: 1986 $
// $LastChangedBy: orbiter $
//
// LICENSE
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

package de.anomic.kelondro;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import de.anomic.server.NamePrefixThreadFactory;
import de.anomic.server.serverMemory;
import de.anomic.server.serverProcessor;
import de.anomic.server.logging.serverLog;

public class kelondroSplitTable implements kelondroIndex {

    // this is a set of kelondro tables
    // the set is divided into tables with different entry date
    // the table type can be either kelondroFlex or kelondroEco

    private static final long minimumRAM4Eco = 80 * 1024 * 1024;
    private static final int EcoFSBufferSize = 20;
    static final kelondroIndex dummyIndex = new kelondroRAMIndex(new kelondroRow(new kelondroColumn[]{new kelondroColumn("key", kelondroColumn.celltype_binary, kelondroColumn.encoder_bytes, 2, "key")}, kelondroNaturalOrder.naturalOrder, 0), 0);

    // the thread pool for the keeperOf executor service
    private ExecutorService executor;
    
    private HashMap<String, kelondroIndex> tables; // a map from a date string to a kelondroIndex object
    private final kelondroRow rowdef;
    private final File path;
    private final String tablename;
    private final kelondroOrder<kelondroRow.Entry> entryOrder;
    
    public kelondroSplitTable(final File path, final String tablename, final kelondroRow rowdef, final boolean resetOnFail) {
        this.path = path;
        this.tablename = tablename;
        this.rowdef = rowdef;
        this.entryOrder = new kelondroRow.EntryComparator(rowdef.objectOrder);
        init(resetOnFail);
    }
    
    public void init(final boolean resetOnFail) {

        // init the thread pool for the keeperOf executor service
        this.executor = new ThreadPoolExecutor(serverProcessor.useCPU + 1, serverProcessor.useCPU + 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new NamePrefixThreadFactory(tablename));
        
        // initialized tables map
        this.tables = new HashMap<String, kelondroIndex>();
        if (!(path.exists())) path.mkdirs();
        final String[] tablefile = path.list();
        String date;
        
        // first pass: find tables
        final HashMap<String, Long> t = new HashMap<String, Long>();
        long ram, sum = 0;
        File f;
        for (int i = 0; i < tablefile.length; i++) {
            if ((tablefile[i].startsWith(tablename)) &&
                (tablefile[i].charAt(tablename.length()) == '.') &&
                (tablefile[i].length() == tablename.length() + 7)) {
                f = new File(path, tablefile[i]);
                if (f.isDirectory()) {
                    ram = kelondroFlexTable.staticRAMIndexNeed(path, tablefile[i], rowdef);
                } else {
                    ram = kelondroEcoTable.staticRAMIndexNeed(f, rowdef);
                }
                if (ram > 0) {
                    t.put(tablefile[i], Long.valueOf(ram));
                    sum += ram;
                }
            }
        }
        
        // second pass: open tables
        Iterator<Map.Entry<String, Long>> i;
        Map.Entry<String, Long> entry;
        String maxf;
        long maxram;
        kelondroIndex table;
        while (t.size() > 0) {
            // find maximum table
            maxram = 0;
            maxf = null;
            i = t.entrySet().iterator();
            while (i.hasNext()) {
                entry = i.next();
                ram = entry.getValue().longValue();
                if (ram > maxram) {
                    maxf = entry.getKey();
                    maxram = ram;
                }
            }
            
            // open next biggest table
            t.remove(maxf);
            if(maxf != null) {
                date = maxf.substring(tablename.length() + 1);
                f = new File(path, maxf);
                if (f.isDirectory()) {
                    // this is a kelonodroFlex table
                    serverLog.logInfo("kelondroSplitTable", "opening partial flex table " + f);
                    table = new kelondroFlexTable(path, maxf, rowdef, 0, resetOnFail);
                } else {
                    serverLog.logInfo("kelondroSplitTable", "opening partial eco table " + f);
                    table = new kelondroEcoTable(f, rowdef, kelondroEcoTable.tailCacheUsageAuto, EcoFSBufferSize, 0);
                }
                tables.put(date, table);
            }
        }
    }
    
    public void clear() throws IOException {
    	this.close();
    	final String[] l = path.list();
    	for (int i = 0; i < l.length; i++) {
    		if (l[i].startsWith(tablename)) {
    		    final File f = new File(path, l[i]);
    		    if (f.isDirectory()) kelondroFlexWidthArray.delete(path, l[i]); else f.delete();
    		}
    	}
    	init(true);
    }
    
    public String filename() {
        return new File(path, tablename).toString();
    }
    
    private static final Calendar thisCalendar = Calendar.getInstance();
    public static final String dateSuffix(final Date date) {
        int month, year;
        final StringBuffer suffix = new StringBuffer(6);
        synchronized (thisCalendar) {
            thisCalendar.setTime(date);
            month = thisCalendar.get(Calendar.MONTH) + 1;
            year = thisCalendar.get(Calendar.YEAR);
        }
        if ((year < 1970) && (year >= 70)) suffix.append("19").append(Integer.toString(year));
        else if (year < 1970) suffix.append("20").append(Integer.toString(year));
        else if (year > 3000) return null;
        else suffix.append(Integer.toString(year));
        if (month < 10) suffix.append("0").append(Integer.toString(month)); else suffix.append(Integer.toString(month));
        return new String(suffix);    
    }
    
    public int size() {
        final Iterator<kelondroIndex> i = tables.values().iterator();
        int s = 0;
        while (i.hasNext()) s += i.next().size();
        return s;
    }
    
    public synchronized kelondroProfile profile() {
        final kelondroProfile[] profiles = new kelondroProfile[tables.size()];
        final Iterator<kelondroIndex> i = tables.values().iterator();
        int c = 0;
        while (i.hasNext()) profiles[c++] = (i.next()).profile();
        return kelondroProfile.consolidate(profiles);
    }
    
    public int writeBufferSize() {
        int s = 0;
        for (final kelondroIndex index : tables.values()) {
            if (index instanceof kelondroCache) s += ((kelondroCache) index).writeBufferSize();
        }
        return s;
    }
    
    public kelondroRow row() {
        return this.rowdef;
    }
    
    public boolean has(final byte[] key) {
        return keeperOf(key) != null;
    }
    
    public synchronized kelondroRow.Entry get(final byte[] key) throws IOException {
        final kelondroIndex keeper = keeperOf(key);
        if (keeper == null) return null;
        return keeper.get(key);
    }
    
    public synchronized void putMultiple(final List<kelondroRow.Entry> rows) throws IOException {
        throw new UnsupportedOperationException("not yet implemented");
    }
    
    public synchronized kelondroRow.Entry put(final kelondroRow.Entry row) throws IOException {
        return put(row, null); // entry for current date
    }
    
    public synchronized kelondroRow.Entry put(final kelondroRow.Entry row, Date entryDate) throws IOException {
        assert row.objectsize() <= this.rowdef.objectsize;
        final kelondroIndex keeper = keeperOf(row.getColBytes(0));
        if (keeper != null) return keeper.put(row);
        if ((entryDate == null) || (entryDate.after(new Date()))) entryDate = new Date(); // fix date
        final String suffix = dateSuffix(entryDate);
        if (suffix == null) return null;
        kelondroIndex table = tables.get(suffix);
        if (table == null) {
            // open table
            final File f = new File(path, tablename + "." + suffix);
            if (f.exists()) {
                if (f.isDirectory()) {
                    // open a flex table
                    table = new kelondroFlexTable(path, tablename + "." + suffix, rowdef, 0, true);
                } else {
                    // open a eco table
                    table = new kelondroEcoTable(f, rowdef, kelondroEcoTable.tailCacheDenyUsage, EcoFSBufferSize, 0);
                }
            } else {
                // make new table
                if (serverMemory.request(minimumRAM4Eco, true)) {
                    // enough memory for a ecoTable
                    table = new kelondroEcoTable(f, rowdef, kelondroEcoTable.tailCacheDenyUsage, EcoFSBufferSize, 0);
                } else {
                    // use the flex table
                    table = new kelondroFlexTable(path, tablename + "." + suffix, rowdef, 0, true);
                }
            }
            tables.put(suffix, table);
        }
        table.put(row);
        return null;
    }
    
    public synchronized kelondroIndex keeperOf(final byte[] key) {
        // because the index is stored only in one table,
        // and the index is completely in RAM, a concurrency will create
        // not concurrent File accesses
        //long start = System.currentTimeMillis();
        
        // start a concurrent query to database tables
        final CompletionService<kelondroIndex> cs = new ExecutorCompletionService<kelondroIndex>(executor);
        final int s = tables.size();
        int rejected = 0;
        for (final kelondroIndex table : tables.values()) {
            try {
                cs.submit(new Callable<kelondroIndex>() {
                    public kelondroIndex call() {
                        if (table.has(key)) return table;
                        return dummyIndex;
                    }
                });
            } catch (final RejectedExecutionException e) {
                // the executor is either shutting down or the blocking queue is full
                // execute the search direct here without concurrency
                if (table.has(key)) return table;
                rejected++;
            }
        }

        // read the result
        try {
            for (int i = 0, n = s - rejected; i < n; i++) {
                final Future<kelondroIndex> f = cs.take();
                final kelondroIndex index = f.get();
                if (index != dummyIndex) {
                    //System.out.println("*DEBUG SplitTable success.time = " + (System.currentTimeMillis() - start) + " ms");
                    return index;
                }
            }
            //System.out.println("*DEBUG SplitTable fail.time = " + (System.currentTimeMillis() - start) + " ms");
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
        //System.out.println("*DEBUG SplitTable fail.time = " + (System.currentTimeMillis() - start) + " ms");
        return null;
    }
    
    public synchronized boolean addUnique(final kelondroRow.Entry row) throws IOException {
        return addUnique(row, null);
    }
    
    public synchronized boolean addUnique(final kelondroRow.Entry row, Date entryDate) throws IOException {
        assert row.objectsize() <= this.rowdef.objectsize;
        if ((entryDate == null) || (entryDate.after(new Date()))) entryDate = new Date(); // fix date
        final String suffix = dateSuffix(entryDate);
        if (suffix == null) return false;
        kelondroIndex table = tables.get(suffix);
        if (table == null) {
            // make new table
            if (serverMemory.request(minimumRAM4Eco, true)) {
                // enough memory for a ecoTable
                table = new kelondroEcoTable(new File(path, tablename + "." + suffix), rowdef, kelondroEcoTable.tailCacheDenyUsage, EcoFSBufferSize, 0);
            } else {
                // use the flex table
                table = new kelondroFlexTable(path, tablename + "." + suffix, rowdef, 0, true);
            }
            tables.put(suffix, table);
        }
        return table.addUnique(row);
    }
    
    public synchronized int addUniqueMultiple(final List<kelondroRow.Entry> rows) throws IOException {
        final Iterator<kelondroRow.Entry> i = rows.iterator();
        int c = 0;
        while (i.hasNext()) {
            if (addUnique(i.next())) c++;
        }
        return c;
    }
    
    public synchronized void addUniqueMultiple(final List<kelondroRow.Entry> rows, final Date entryDate) throws IOException {
        final Iterator<kelondroRow.Entry> i = rows.iterator();
        while (i.hasNext()) addUnique(i.next(), entryDate);
    }
    
    public ArrayList<kelondroRowCollection> removeDoubles() throws IOException {
        final Iterator<kelondroIndex> i = tables.values().iterator();
        final ArrayList<kelondroRowCollection> report = new ArrayList<kelondroRowCollection>();
        while (i.hasNext()) {
            report.addAll(i.next().removeDoubles());
        }
        return report;
    }
    
    public synchronized kelondroRow.Entry remove(final byte[] key) throws IOException {
        final kelondroIndex table = keeperOf(key);
        if (table == null) return null;
        return table.remove(key);
    }
    
    public synchronized kelondroRow.Entry removeOne() throws IOException {
        final Iterator<kelondroIndex> i = tables.values().iterator();
        kelondroIndex table, maxtable = null;
        int maxcount = -1;
        while (i.hasNext()) {
            table = i.next();
            if (table.size() > maxcount) {
                maxtable = table;
                maxcount = table.size();
            }
        }
        if (maxtable == null) {
            return null;
        }
        return maxtable.removeOne();
    }
    
    public synchronized kelondroCloneableIterator<byte[]> keys(final boolean up, final byte[] firstKey) throws IOException {
        final List<kelondroCloneableIterator<byte[]>> c = new ArrayList<kelondroCloneableIterator<byte[]>>(tables.size());
        final Iterator<kelondroIndex> i = tables.values().iterator();
        while (i.hasNext()) {
            c.add(i.next().keys(up, firstKey));
        }
        return kelondroMergeIterator.cascade(c, rowdef.objectOrder, kelondroMergeIterator.simpleMerge, up);
    }
    
    public synchronized kelondroCloneableIterator<kelondroRow.Entry> rows(final boolean up, final byte[] firstKey) throws IOException {
        final List<kelondroCloneableIterator<kelondroRow.Entry>> c = new ArrayList<kelondroCloneableIterator<kelondroRow.Entry>>(tables.size());
        final Iterator<kelondroIndex> i = tables.values().iterator();
        while (i.hasNext()) {
            c.add(i.next().rows(up, firstKey));
        }
        return kelondroMergeIterator.cascade(c, entryOrder, kelondroMergeIterator.simpleMerge, up);
    }

    public final int cacheObjectChunkSize() {
        // dummy method
        return -1;
    }
    
    public long[] cacheObjectStatus() {
        // dummy method
        return null;
    }
    
    public final int cacheNodeChunkSize() {
        // returns the size that the node cache uses for a single entry
        return -1;
    }
    
    public final int[] cacheNodeStatus() {
        // a collection of different node cache status values
        return new int[]{0,0,0,0,0,0,0,0,0,0};
    }
    
    public synchronized void close() {
        if (tables == null) return;
        this.executor.shutdown();
        try {
            this.executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
        }
        this.executor = null;
        final Iterator<kelondroIndex> i = tables.values().iterator();
        while (i.hasNext()) {
            i.next().close();
        }
        this.tables = null;
    }
    
    public static void main(final String[] args) {
        System.out.println(dateSuffix(new Date()));
    }
    
}

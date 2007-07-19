// plasmaCrawlBalancerEntry.java
// (C) 2007 by Michael Peter Christen; mc@anomic.de, Frankfurt a. M., Germany
// first published 14.03.2007 on http://www.anomic.de
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

package de.anomic.plasma;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Date;

import de.anomic.kelondro.kelondroBase64Order;
import de.anomic.kelondro.kelondroBitfield;
import de.anomic.kelondro.kelondroNaturalOrder;
import de.anomic.kelondro.kelondroRow;
import de.anomic.net.URL;
import de.anomic.yacy.yacyCore;
import de.anomic.yacy.yacySeedDB;

public class plasmaCrawlEntry {
    
    // row definition for balancer-related NURL-entries
    public final static kelondroRow rowdef = new kelondroRow(
        "String urlhash-" + yacySeedDB.commonHashLength + ", " +    // the url's hash
        "String initiator-" + yacySeedDB.commonHashLength + ", " +  // the crawling initiator
        "String urlstring-256, " +                                  // the url as string
        "String refhash-" + yacySeedDB.commonHashLength + ", " +    // the url's referrer hash
        "String urlname-80, " +                                     // the name of the url, from anchor tag <a>name</a>
        "Cardinal appdate-8 {b256}, " +                             // the time when the url was first time appeared
        "String profile-4, " +                                      // the name of the prefetch profile handle
        "Cardinal depth-2 {b256}, " +                               // the prefetch depth so far, starts at 0
        "Cardinal parentbr-3 {b256}, " +                            // number of anchors of the parent
        "Cardinal forkfactor-4 {b256}, " +                          // sum of anchors of all ancestors
        "byte[] flags-4, " +                                        // flags
        "String handle-4, " +                                       // extra handle
        "Cardinal loaddate-8 {b256}," +                             // time when the file was loaded
        "Cardinal serverdate-8 {b256}," +                           // time when that the server returned as document date
        "Cardinal modifiedSince-8 {b256}",                           // time that was given to server as ifModifiedSince
        kelondroBase64Order.enhancedCoder,
        0
        );
    
    private String   initiator;     // the initiator hash, is NULL or "" if it is the own proxy;
                                    // if this is generated by a crawl, the own peer hash in entered
    private String   urlhash;       // the url's hash
    private String   referrer;      // the url's referrer hash
    private URL      url;           // the url as string
    private String   name;          // the name of the url, from anchor tag <a>name</a>     
    private long     appdate;       // the time when the url was first time appeared
    private long     loaddate;      // the time when the url was loaded
    private long     serverdate;    // the document date from the target server
    private long     imsdate;       // the time of a ifModifiedSince request
    private String   profileHandle; // the name of the prefetch profile
    private int      depth;         // the prefetch depth so far, starts at 0
    private int      anchors;       // number of anchors of the parent
    private int      forkfactor;    // sum of anchors of all ancestors
    private kelondroBitfield flags;
    private int      handle;

    public plasmaCrawlEntry(URL url) {
        this(yacyCore.seedDB.mySeed.hash, url, null, null, new Date(), null, 0, 0, 0);
    }
    
    /**
     * @param initiator the hash of the initiator peer
     * @param url the {@link URL} to crawl
     * @param referrer the hash of the referrer URL
     * @param name the name of the document to crawl
     * @param appdate the time when the url was first time appeared
     * @param profileHandle the name of the prefetch profile. This must not be null!
     * @param depth the crawling depth of the entry 
     * @param anchors number of anchors of the parent
     * @param forkfactor sum of anchors of all ancestors
     */
    public plasmaCrawlEntry(
                 String initiator, 
                 URL url, 
                 String referrer, 
                 String name, 
                 Date appdate,
                 String profileHandle,
                 int depth, 
                 int anchors, 
                 int forkfactor
    ) {
        // create new entry and store it into database
        this.urlhash       = plasmaURL.urlHash(url);
        this.initiator     = initiator;
        this.url           = url;
        this.referrer      = (referrer == null) ? plasmaURL.dummyHash : referrer;
        this.name          = (name == null) ? "" : name;
        this.appdate       = (appdate == null) ? 0 : appdate.getTime();
        this.profileHandle = profileHandle; // must not be null
        this.depth         = depth;
        this.anchors       = anchors;
        this.forkfactor    = forkfactor;
        this.flags         = new kelondroBitfield(rowdef.width(10));
        this.handle        = 0;
        this.loaddate      = 0;
        this.serverdate    = 0;
        this.imsdate       = 0;
    }
    
    public plasmaCrawlEntry(kelondroRow.Entry entry) throws IOException {
        assert (entry != null);
        insertEntry(entry);
    }

    private void insertEntry(kelondroRow.Entry entry) throws IOException {
        String urlstring = entry.getColString(2, null);
        if (urlstring == null) throw new IOException ("url string is null");
        this.urlhash = entry.getColString(0, null);
        this.initiator = entry.getColString(1, null);
        this.url = new URL(urlstring);
        this.referrer = (entry.empty(3)) ? plasmaURL.dummyHash : entry.getColString(3, null);
        this.name = (entry.empty(4)) ? "" : entry.getColString(4, "UTF-8").trim();
        this.appdate = entry.getColLong(5);
        this.profileHandle = (entry.empty(6)) ? null : entry.getColString(6, null).trim();
        this.depth = (int) entry.getColLong(7);
        this.anchors = (int) entry.getColLong(8);
        this.forkfactor = (int) entry.getColLong(9);
        this.flags = new kelondroBitfield(entry.getColBytes(10));
        this.handle = Integer.parseInt(entry.getColString(11, null), 16);
        this.loaddate = entry.getColLong(12);
        this.serverdate = entry.getColLong(13);
        this.imsdate = entry.getColLong(14);
        return;
    }
    
    private static String normalizeHandle(int h) {
        String d = Integer.toHexString(h);
        while (d.length() < rowdef.width(11)) d = "0" + d;
        return d;
    }
    
    public kelondroRow.Entry toRow() {
        byte[] appdatestr = kelondroNaturalOrder.encodeLong(appdate, rowdef.width(5));
        byte[] loaddatestr = kelondroNaturalOrder.encodeLong(loaddate, rowdef.width(12));
        byte[] serverdatestr = kelondroNaturalOrder.encodeLong(serverdate, rowdef.width(13));
        byte[] imsdatestr = kelondroNaturalOrder.encodeLong(imsdate, rowdef.width(14));
        // store the hash in the hash cache
        byte[] namebytes;
        try {
            namebytes = this.name.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            namebytes = this.name.getBytes();
        }
        byte[][] entry = new byte[][] {
                this.urlhash.getBytes(),
                (initiator == null) ? "".getBytes() : this.initiator.getBytes(),
                this.url.toString().getBytes(),
                this.referrer.getBytes(),
                namebytes,
                appdatestr,
                (this.profileHandle == null) ? null : this.profileHandle.getBytes(),
                kelondroNaturalOrder.encodeLong(this.depth, rowdef.width(7)),
                kelondroNaturalOrder.encodeLong(this.anchors, rowdef.width(8)),
                kelondroNaturalOrder.encodeLong(this.forkfactor, rowdef.width(9)),
                this.flags.bytes(),
                normalizeHandle(this.handle).getBytes(),
                loaddatestr,
                serverdatestr,
                imsdatestr};
        return rowdef.newEntry(entry);
    }
    
    public URL url() {
        // the url
        return url;
    }

    public String urlhash() {
        // the hash of this url
        return this.urlhash;
    }

    public String referrerhash() {
        // the urlhash of a referer url
        return this.referrer;
    }

    public String initiator() {
        // returns the hash of the initiating peer
        if (initiator == null) return null;
        if (initiator.length() == 0) return null; 
        return initiator;
    }

    public boolean proxy() {
        // true when the url was retrieved using the proxy
        return (initiator() == null);
    }

    public Date appdate() {
        // the date when the url appeared first
        return new Date(appdate);
    }
    
    public Date loaddate() {
        // the date when the url was loaded
        return new Date(loaddate);
    }
    
    public Date serverdate() {
        // the date that the server returned as document date
        return new Date(serverdate);
    }
    
    public Date imsdate() {
        // the date that the client (browser) send as ifModifiedSince in proxy mode
        return new Date(imsdate);
    }

    public String name() {
        // return the anchor name (text inside <a> tag)
        return name;
    }

    public int depth() {
        // crawl depth where the url appeared
        return depth;
    }

    public String profileHandle() {
        // the handle of the crawl profile
        return profileHandle;
    }
}
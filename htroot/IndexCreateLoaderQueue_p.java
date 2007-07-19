// IndexCreateLoaderQueue_p.java
// -----------------------------
// part of the AnomicHTTPD caching proxy
// (C) by Michael Peter Christen; mc@anomic.de
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004, 2005
// last major change: 04.07.2005
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

// You must compile this file with
// javac -classpath .:../classes IndexCreate_p.java
// if the shell's current path is HTROOT

import de.anomic.data.htmlTools;
import de.anomic.http.httpHeader;
import de.anomic.plasma.plasmaCrawlLoaderMessage;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.plasma.crawler.plasmaCrawlWorker;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;
import de.anomic.yacy.yacyCore;
import de.anomic.yacy.yacySeed;

public class IndexCreateLoaderQueue_p {
    
    public static serverObjects respond(httpHeader header, serverObjects post, serverSwitch env) {
        // return variable that accumulates replacements
        plasmaSwitchboard switchboard = (plasmaSwitchboard) env;
        serverObjects prop = new serverObjects();
        

        if (switchboard.cacheLoader.size() == 0) {
            prop.put("loader-set", 0);
        } else {
            prop.put("loader-set", 1);
            boolean dark = true;
            
            ThreadGroup loaderThreads = switchboard.cacheLoader.threadStatus();            
            int threadCount  = loaderThreads.activeCount();
            Thread[] threadList = new Thread[threadCount*2];
            threadCount = loaderThreads.enumerate(threadList);
            yacySeed initiator;
            int i, count = 0;
            for (i = 0; i < threadCount; i++)  {
                plasmaCrawlWorker theWorker = (plasmaCrawlWorker)threadList[i];
                plasmaCrawlLoaderMessage theMsg = theWorker.getMessage();
                if (theMsg == null) continue;
                
                initiator = yacyCore.seedDB.getConnected(theMsg.initiator);
                prop.put("loader-set_list_"+count+"_dark", ((dark) ? 1 : 0) );
                prop.put("loader-set_list_"+count+"_initiator", ((initiator == null) ? "proxy" : htmlTools.encodeUnicode2html(initiator.getName(), true)) );
                prop.put("loader-set_list_"+count+"_depth", theMsg.depth );
                prop.put("loader-set_list_"+count+"_url", htmlTools.encodeUnicode2html(theMsg.url.toNormalform(false, true), false)); // null pointer exception here !!! maybe url = null; check reason.
                dark = !dark;
                count++;
            }
            prop.put("loader-set_list", count );
            prop.put("loader-set_num", count);
        }
                
        // return rewrite properties
        return prop;
    }
    
}



